package com.simpleledger

import java.io.Reader
import java.io.Writer
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** The same lossless CSV format is used for importing, exporting and cleaned bills. */
object LedgerCsv {
    const val MAX_BYTES = 10 * 1024 * 1024
    private const val MAX_ROWS = 50_000
    private val headers = listOf("日期", "时间", "金额", "币种", "类别", "说明", "来源")
    private val aliases = mapOf(
        "date" to "日期", "time" to "时间", "amount" to "金额", "currency" to "币种",
        "category" to "类别", "description" to "说明", "source" to "来源"
    )

    fun read(reader: Reader): List<Expense> {
        val text = buildString {
            val buffer = CharArray(8192)
            while (true) {
                val count = reader.read(buffer)
                if (count == -1) break
                require(length + count <= MAX_BYTES) { "CSV 文件不能超过 10 MB" }
                append(buffer, 0, count)
            }
        }.removePrefix("\uFEFF")
        val rows = parse(text)
        require(rows.isNotEmpty()) { "CSV 文件为空" }
        val columns = rows.first().map { aliases[it.trim().lowercase()] ?: it.trim() }
        require(columns.distinct().size == columns.size && columns.all { it in headers }) {
            "表头应为：日期、时间、金额、币种、类别、说明、来源（也支持对应英文表头）"
        }
        require(columns.containsAll(listOf("日期", "金额", "币种"))) { "CSV 缺少日期、金额或币种列" }
        val expenses = rows.drop(1).mapIndexed { index, row ->
            try {
                require(row.size == columns.size) { "列数与表头不一致" }
                val values = columns.zip(row.map { it.trim() }).toMap()
                val date = values.getValue("日期")
                require(date.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) { "日期必须为 YYYY-MM-DD" }
                require(LocalDate.parse(date).year in 1..9999) { "年份必须在 1–9999 之间" }
                val amount = values.getValue("金额")
                require(amount.length <= 100 && amount.matches(Regex("[0-9]+(\\.[0-9]+)?")) && amount.toBigDecimal().signum() > 0) { "金额必须为大于零的十进制数" }
                val currency = when (values.getValue("币种").uppercase()) {
                    "R", "RMB", "CNY", "人民币", "¥", "￥" -> "人民币"
                    "USDT" -> "USDT"
                    "USDC" -> "USDC"
                    else -> error("币种仅支持人民币、USDT、USDC")
                }
                val time = values["时间"]?.ifBlank { null }?.let {
                    require(it.matches(Regex("[0-9]{2}:[0-9]{2}(:[0-9]{2})?"))) { "时间必须为 HH:mm 或 HH:mm:ss" }
                    LocalTime.parse(it).format(DateTimeFormatter.ISO_LOCAL_TIME)
                }
                Expense(amount = amount.toBigDecimal().stripTrailingZeros().toPlainString(), currency = currency,
                    date = date, time = time, category = values["类别"]?.ifBlank { null },
                    description = values["说明"].orEmpty(), source = values["来源"].orEmpty())
            } catch (e: Exception) {
                val reason = if (e is DateTimeParseException) "日期或时间无效，请检查日期是否存在及时间范围" else e.message ?: "记录无效"
                throw IllegalArgumentException("第 ${index + 2} 条记录：$reason", e)
            }
        }
        require(expenses.isNotEmpty()) { "CSV 中没有账单记录" }
        return expenses
    }

    fun write(writer: Writer, expenses: List<Expense>) {
        writer.write("\uFEFF")
        fun row(values: List<String>) {
            writer.write(values.joinToString(",") { value -> "\"${value.replace("\"", "\"\"")}\"" })
            writer.write("\r\n")
        }
        row(headers)
        expenses.forEach { row(listOf(it.date, it.time.orEmpty(), it.amount, it.currency, it.category.orEmpty(), it.description, it.source)) }
        writer.flush()
    }

    private fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val cells = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var closedQuote = false
        var index = 0
        fun cell() { cells.add(field.toString()); field.setLength(0); closedQuote = false }
        fun row() {
            cell()
            if (cells.any { it.isNotBlank() }) rows.add(cells.toList())
            cells.clear()
            require(rows.size <= MAX_ROWS + 1) { "一次最多导入 50000 笔" }
        }
        while (index < text.length) {
            val char = text[index]
            if (inQuotes) {
                if (char == '"') {
                    if (index + 1 < text.length && text[index + 1] == '"') { field.append('"'); index++ }
                    else { inQuotes = false; closedQuote = true }
                } else field.append(char)
            } else when (char) {
                '"' -> {
                    require(field.isEmpty() && !closedQuote) { "第 ${rows.size + 1} 条记录引号位置错误" }
                    inQuotes = true
                }
                ',' -> cell()
                '\r', '\n' -> {
                    row()
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                }
                else -> {
                    require(!closedQuote) { "第 ${rows.size + 1} 条记录的引号后有多余字符" }
                    field.append(char)
                }
            }
            require(field.length <= 2000) { "第 ${rows.size + 1} 条记录字段超过 2000 字符" }
            index++
        }
        require(!inQuotes) { "CSV 引号未闭合" }
        if (field.isNotEmpty() || cells.isNotEmpty() || closedQuote) row()
        return rows
    }
}

data class ImportPreview(val expenses: List<Expense>, val newCount: Int) {
    val skippedCount: Int get() = expenses.size - newCount
}

/** Match multiplicities: repeated real payments in a CSV remain, reimporting it adds none. */
fun expensesToInsert(incoming: List<Expense>, existing: List<Expense>): List<Expense> {
    fun key(expense: Expense) = expense.copy(id = 0,
        amount = expense.amount.toBigDecimal().stripTrailingZeros().toPlainString(),
        time = expense.time?.let { LocalTime.parse(it).format(DateTimeFormatter.ISO_LOCAL_TIME) })
    val remaining = existing.groupingBy(::key).eachCount().toMutableMap()
    return incoming.filter { expense ->
        val identity = key(expense)
        val count = remaining[identity] ?: 0
        if (count > 0) { remaining[identity] = count - 1; false } else true
    }
}
