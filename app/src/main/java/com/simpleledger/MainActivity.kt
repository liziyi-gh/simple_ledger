package com.simpleledger

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(vm: LedgerViewModel = viewModel()) {
    val expenses by vm.expenses.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Expense?>(null) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("极简记账") }) },
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
                        headlineContent = { Text("${expense.amount} ${expense.currency}") },
                        supportingContent = { Text(listOfNotNull(expense.date, expense.time, expense.category).joinToString(" · ")) },
                        trailingContent = { IconButton(onClick = { deleting = expense }) { Icon(Icons.Default.Delete, contentDescription = "删除支出") } }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    if (adding) AddExpenseDialog(onDismiss = { adding = false }, onSave = { vm.add(it); adding = false })
    deleting?.let { expense ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这笔支出？") },
            text = { Text("${expense.amount} ${expense.currency} · ${expense.date}") },
            confirmButton = { TextButton(onClick = { vm.delete(expense); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseDialog(onDismiss: () -> Unit, onSave: (Expense) -> Unit) {
    val context = LocalContext.current
    var amount by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf("人民币") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var time by rememberSaveable { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf("") }
    var details by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val valid = amount.matches(Regex("[0-9]+(\\.[0-9]+)?")) && (amount.toBigDecimalOrNull()?.compareTo(BigDecimal.ZERO) ?: 0) > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增支出") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("金额") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                    isError = attempted && !valid, supportingText = { if (attempted && !valid) Text("请输入大于零的金额") }, modifier = Modifier.fillMaxWidth())
                Text("币种", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("人民币", "USDT", "USDC").forEachIndexed { index, item ->
                        SegmentedButton(selected = currency == item, onClick = { currency = item }, shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(item) }
                    }
                }
                OutlinedButton(onClick = {
                    val current = LocalDate.parse(date)
                    DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y, m + 1, d).toString() }, current.year, current.monthValue - 1, current.dayOfMonth).show()
                }, modifier = Modifier.fillMaxWidth()) { Text("日期：$date") }
                TextButton(onClick = { details = !details }) { Text(if (details) "收起更多选项" else "更多选项（时间）") }
                if (details) {
                    OutlinedButton(onClick = {
                        val current = time?.let { LocalTime.parse(it) } ?: LocalTime.now()
                        TimePickerDialog(context, { _, h, m -> time = LocalTime.of(h, m).format(DateTimeFormatter.ofPattern("HH:mm")) }, current.hour, current.minute, true).show()
                    }, modifier = Modifier.fillMaxWidth()) { Text(time?.let { "时间：$it" } ?: "选择时间（可选）") }
                    if (time != null) TextButton(onClick = { time = null }) { Text("清除时间") }
                }
                OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("类别（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { attempted = true; if (valid) onSave(Expense(amount = amount.toBigDecimal().stripTrailingZeros().toPlainString(), currency = currency, date = date, time = time, category = category.trim().ifEmpty { null })) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
