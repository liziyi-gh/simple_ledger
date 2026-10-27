package com.simpleledger

import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter

class LedgerCsvTest {
    private fun expense(description: String = "餐费") = Expense(amount = "2", currency = "人民币",
        date = "2026-10-27", time = null, category = "餐饮", description = description, source = "微信")

    @Test fun roundTripPreservesUnicodeQuotesCommasAndNewlines() {
        val entries = listOf(expense("咖啡, \"大杯\"\n第二行"), expense().copy(currency = "USDT", amount = "0.000001"))
        val output = StringWriter().also { LedgerCsv.write(it, entries) }.toString()
        assertTrue(output.startsWith("\uFEFF"))
        assertEquals(entries, LedgerCsv.read(output.reader()))
    }

    @Test fun aliasesAndOptionalColumns() {
        val parsed = LedgerCsv.read("date,amount,currency\r\n2024-02-29,02.00,r\r\n".reader()).single()
        assertEquals("人民币", parsed.currency)
        assertEquals("2", parsed.amount)
        assertNull(parsed.category)
        assertEquals("", parsed.description)
    }

    @Test fun repeatedPaymentsRemainButRepeatImportAddsNone() {
        val incoming = List(6) { expense() }
        assertEquals(6, expensesToInsert(incoming, emptyList()).size)
        assertEquals(4, expensesToInsert(incoming, incoming.take(2)).size)
        assertEquals(0, expensesToInsert(incoming, incoming).size)
        assertEquals(0, expensesToInsert(incoming, incoming.mapIndexed { i, item -> item.copy(id = i.toLong(), amount = "2.00") }).size)
    }

    @Test fun identityIncludesDescriptionSourceAndCategory() {
        val original = expense()
        assertEquals(3, expensesToInsert(listOf(original.copy(description = "其他"),
            original.copy(source = "支付宝"), original.copy(category = "其他")), listOf(original)).size)
    }

    @Test fun sameTimeWithOptionalSecondsMatches() {
        assertEquals(0, expensesToInsert(listOf(expense().copy(time = "12:30:00")), listOf(expense().copy(time = "12:30"))).size)
    }

    @Test fun rejectsInvalidDatesTimesMoneyCurrenciesAndMalformedCsv() {
        val badRows = listOf("2026-02-29,,2,CNY", "2026-10-27,25:00,2,CNY", "2026-10-27,,0,CNY",
            "2026-10-27,,-2,CNY", "2026-10-27,,1e3,CNY", "2026-10-27,,2,USD",
            "2026-10-27,,2", "\"2026-10-27,,2,CNY", "\"2026-10-27\"x,,2,CNY")
        badRows.forEach { row ->
            assertThrows("Should reject: $row", IllegalArgumentException::class.java) {
                LedgerCsv.read("日期,时间,金额,币种\n$row".reader())
            }
        }
    }

    @Test fun invalidLaterRowDoesNotProducePartialResult() {
        assertThrows(IllegalArgumentException::class.java) {
            LedgerCsv.read("日期,金额,币种\n2026-10-27,2,CNY\n2026-02-30,3,CNY".reader())
        }
    }

    @Test fun rejectsUnknownOrDuplicateHeadersAndOversizedFields() {
        listOf("日期,金额,币种,备注\n2026-10-27,2,CNY,x", "日期,金额,金额,币种\n2026-10-27,2,2,CNY",
            "日期,金额,币种,说明\n2026-10-27,2,CNY,${"x".repeat(2001)}").forEach {
            assertThrows(IllegalArgumentException::class.java) { LedgerCsv.read(it.reader()) }
        }
    }
}
