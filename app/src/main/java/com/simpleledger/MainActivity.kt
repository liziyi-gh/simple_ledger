package com.simpleledger

import android.app.TimePickerDialog
import android.os.Build
import android.os.Bundle
import android.widget.NumberPicker
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            val context = LocalContext.current
            val colors = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) { LedgerScreen() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LedgerScreen(vm: LedgerViewModel = viewModel()) {
    val expenses by vm.expenses.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val transfer by vm.transfer.collectAsStateWithLifecycle()
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.previewImport(uri)
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportCsv(uri)
    }
    var adding by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by remember { mutableStateOf<Expense?>(null) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("极简记账") }, actions = {
            TextButton(enabled = !transfer.busy && transfer.preview == null, onClick = { importFile.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) }) { Text("导入") }
            TextButton(enabled = !transfer.busy && transfer.preview == null && expenses.isNotEmpty(), onClick = { exportFile.launch("账单-${LocalDate.now()}.csv") }) { Text("导出") }
        }) },
        floatingActionButton = { FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Default.Add, contentDescription = "新增支出") } }
    ) { padding ->
        if (expenses.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("还没有支出，点击 + 记录一笔", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(expenses, key = { it.id }) { expense ->
                    ListItem(
                        modifier = Modifier.combinedClickable(onClick = {}, onLongClickLabel = "编辑支出", onLongClick = { editingId = expense.id }),
                        headlineContent = { Text("${expense.amount} ${expense.currency}") },
                        supportingContent = { Column {
                            if (expense.description.isNotEmpty()) Text(expense.description)
                            Text(listOfNotNull(expense.date, expense.time, expense.category).joinToString(" · "))
                        } },
                        trailingContent = { IconButton(onClick = { deleting = expense }) { Icon(Icons.Default.Delete, contentDescription = "删除支出") } }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    if (transfer.busy) AlertDialog(onDismissRequest = {}, title = { Text("正在处理账单") },
        text = { Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp)); Text("请稍候…")
        } }, confirmButton = {})
    transfer.message?.let { message ->
        AlertDialog(onDismissRequest = vm::dismissTransfer, title = { Text("账单文件") },
            text = { Text(message) }, confirmButton = { TextButton(onClick = vm::dismissTransfer) { Text("确定") } })
    }
    transfer.preview?.let { preview ->
        AlertDialog(onDismissRequest = vm::dismissTransfer, title = { Text("确认导入") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("共 ${preview.expenses.size} 笔，将新增 ${preview.newCount} 笔，跳过 ${preview.skippedCount} 笔已有记录。")
                Text("${preview.expenses.minOf { it.date }} 至 ${preview.expenses.maxOf { it.date }}")
                Text("文件合计（各币种分别统计）")
                preview.expenses.groupBy { it.currency }.forEach { (currency, items) ->
                    Text("$currency：${items.sumOf { it.amount.toBigDecimal() }.stripTrailingZeros().toPlainString()}")
                }
                Text("导入会保留文件中重复支付的笔数，已有记录按相同内容和笔数匹配。不会覆盖现有账单。")
            }
        }, confirmButton = { TextButton(onClick = vm::confirmImport) { Text("确认导入") } },
            dismissButton = { TextButton(onClick = vm::dismissTransfer) { Text("取消") } })
    }
    if (adding) ExpenseDialog(categories = categories, onAddCategory = vm::addCategory, onDismiss = { adding = false }, onSave = { vm.add(it); adding = false })
    expenses.firstOrNull { it.id == editingId }?.let { expense ->
        ExpenseDialog(initialExpense = expense, categories = categories, onAddCategory = vm::addCategory,
            onDismiss = { editingId = null }, onSave = { vm.update(it); editingId = null })
    }
    deleting?.let { expense ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这笔支出？") },
            text = { Text("${expense.amount} ${expense.currency} · ${expense.date}") },
            confirmButton = { TextButton(onClick = { vm.delete(expense); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseDialog(categories: List<String>, onAddCategory: (String) -> Unit, onDismiss: () -> Unit,
    onSave: (Expense) -> Unit, initialExpense: Expense? = null) {
    val context = LocalContext.current
    var amount by rememberSaveable(initialExpense?.id) { mutableStateOf(initialExpense?.amount.orEmpty()) }
    var currency by rememberSaveable(initialExpense?.id) { mutableStateOf(initialExpense?.currency ?: "人民币") }
    var date by rememberSaveable(initialExpense?.id) { mutableStateOf(initialExpense?.date ?: LocalDate.now().toString()) }
    var time by rememberSaveable(initialExpense?.id) { mutableStateOf(initialExpense?.time) }
    var category by rememberSaveable(initialExpense?.id) { mutableStateOf(initialExpense?.category.orEmpty()) }
    var description by rememberSaveable(initialExpense?.id) { mutableStateOf(initialExpense?.description.orEmpty()) }
    var newCategory by rememberSaveable { mutableStateOf("") }
    var categoryExpanded by remember { mutableStateOf(false) }
    var choosingDate by remember { mutableStateOf(false) }
    var details by rememberSaveable(initialExpense?.id) { mutableStateOf(initialExpense?.time != null) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val valid = amount.matches(Regex("[0-9]+(\\.[0-9]+)?")) && (amount.toBigDecimalOrNull()?.compareTo(BigDecimal.ZERO) ?: 0) > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialExpense == null) "新增支出" else "编辑支出") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = amount, onValueChange = { amount = it.take(100) }, label = { Text("金额") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                    isError = attempted && !valid, supportingText = { if (attempted && !valid) Text("请输入大于零的金额") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description, onValueChange = { description = it.take(2000) }, label = { Text("说明（可选）") }, modifier = Modifier.fillMaxWidth())
                Text("币种", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("人民币", "USDT", "USDC").forEachIndexed { index, item ->
                        SegmentedButton(selected = currency == item, onClick = { currency = item }, shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(item) }
                    }
                }
                OutlinedButton(onClick = { choosingDate = true }, modifier = Modifier.fillMaxWidth()) { Text("日期：$date") }
                TextButton(onClick = { details = !details }) { Text(if (details) "收起更多选项" else "更多选项（时间）") }
                if (details) {
                    OutlinedButton(onClick = {
                        val current = time?.let { LocalTime.parse(it) } ?: LocalTime.now()
                        TimePickerDialog(context, { _, h, m -> time = LocalTime.of(h, m).format(DateTimeFormatter.ofPattern("HH:mm")) }, current.hour, current.minute, true).show()
                    }, modifier = Modifier.fillMaxWidth()) { Text(time?.let { "时间：$it" } ?: "选择时间（可选）") }
                    if (time != null) TextButton(onClick = { time = null }) { Text("清除时间") }
                }
                ExposedDropdownMenuBox(expanded = categoryExpanded, onExpandedChange = { categoryExpanded = it }) {
                    OutlinedTextField(value = category.ifEmpty { "不选择" }, onValueChange = {}, readOnly = true,
                        label = { Text("类别（可选）") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(categoryExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                        DropdownMenuItem(text = { Text("不选择") }, onClick = { category = ""; categoryExpanded = false })
                        categories.forEach { item ->
                            DropdownMenuItem(text = { Text(item) }, onClick = { category = item; categoryExpanded = false })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newCategory, onValueChange = { newCategory = it.take(2000) }, label = { Text("新类别") },
                        singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val name = newCategory.trim()
                        if (name.isNotEmpty()) { onAddCategory(name); category = name; newCategory = "" }
                    }, enabled = newCategory.isNotBlank()) { Text("添加") }
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            attempted = true
            if (valid) {
                val draft = Expense(id = initialExpense?.id ?: 0,
                    amount = amount.toBigDecimal().stripTrailingZeros().toPlainString(), currency = currency,
                    date = date, time = time, category = category.trim().ifEmpty { null },
                    description = description.trim(), source = initialExpense?.source.orEmpty())
                onSave(draft)
            }
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
    if (choosingDate) WheelDateDialog(initialDate = LocalDate.parse(date), onDismiss = { choosingDate = false },
        onConfirm = { date = it.toString(); choosingDate = false })
}

@Composable
private fun WheelDateDialog(initialDate: LocalDate, onDismiss: () -> Unit, onConfirm: (LocalDate) -> Unit) {
    var selected by remember { mutableStateOf(initialDate) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择日期") }, text = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1.2f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("年")
                DateWheel(1, 9999, selected.year) { selected = selected.withYear(it) }
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("月")
                DateWheel(1, 12, selected.monthValue) { selected = selected.withMonth(it) }
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("日")
                DateWheel(1, selected.lengthOfMonth(), selected.dayOfMonth) { selected = selected.withDayOfMonth(it) }
            }
        }
    }, confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun DateWheel(min: Int, max: Int, value: Int, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    val currentOnChange by rememberUpdatedState(onChange)
    AndroidView(modifier = modifier, factory = { context ->
        NumberPicker(context).apply {
            minValue = min
            maxValue = max
            wrapSelectorWheel = true
            setOnValueChangedListener { _, _, newValue -> currentOnChange(newValue) }
        }
    }, update = { picker ->
        picker.minValue = min
        picker.maxValue = max
        picker.wrapSelectorWheel = true
        picker.value = value
    })
}
