package com.simpleledger

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException

@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: String,
    val currency: String,
    val date: String,
    val time: String?,
    val category: String?,
    @ColumnInfo(defaultValue = "''") val description: String = "",
    @ColumnInfo(defaultValue = "''") val source: String = ""
)

@Entity(tableName = "categories")
data class Category(@PrimaryKey val name: String)

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses ORDER BY date DESC, COALESCE(time, '') DESC, id DESC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<Expense>>
    @Insert suspend fun insert(expense: Expense)
    @Delete suspend fun delete(expense: Expense)
    @Query("SELECT name FROM categories ORDER BY name COLLATE NOCASE")
    fun observeCategories(): kotlinx.coroutines.flow.Flow<List<String>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertCategory(category: Category)
    @Query("SELECT * FROM expenses ORDER BY date DESC, COALESCE(time, '') DESC, id DESC")
    suspend fun snapshot(): List<Expense>
    @Insert suspend fun insertAll(expenses: List<Expense>)
    @Transaction
    suspend fun importExpenses(incoming: List<Expense>): Int {
        val additions = expensesToInsert(incoming, snapshot())
        insertAll(additions)
        incoming.mapNotNull { it.category }.distinct().forEach { insertCategory(Category(it)) }
        return additions.size
    }
}

val LEDGER_MIGRATIONS = arrayOf(
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS categories (name TEXT NOT NULL PRIMARY KEY)")
            db.execSQL("INSERT OR IGNORE INTO categories (name) SELECT DISTINCT TRIM(category) FROM expenses WHERE category IS NOT NULL AND TRIM(category) != ''")
        }
    },
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE expenses ADD COLUMN description TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE expenses ADD COLUMN source TEXT NOT NULL DEFAULT ''")
        }
    }
)

@Database(entities = [Expense::class, Category::class], version = 3, exportSchema = false)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun expenses(): ExpenseDao
}

class LedgerViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = Room.databaseBuilder(application, LedgerDatabase::class.java, "ledger.db")
        .addMigrations(*LEDGER_MIGRATIONS).build().expenses()
    val expenses = dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val categories = dao.observeCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _transfer = MutableStateFlow(TransferState())
    val transfer = _transfer.asStateFlow()

    fun dismissTransfer() { _transfer.value = TransferState() }

    fun previewImport(uri: Uri) = transferTask {
        val expenses = withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                // Bound bytes before decoding; reject invalid UTF-8 rather than corrupting descriptions.
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count == -1) break
                    require(bytes.size() + count <= LedgerCsv.MAX_BYTES) { "CSV 文件不能超过 10 MB" }
                    bytes.write(buffer, 0, count)
                }
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                try {
                    InputStreamReader(bytes.toByteArray().inputStream(), decoder).use(LedgerCsv::read)
                } catch (e: CharacterCodingException) {
                    throw IllegalArgumentException("文件编码不是 UTF-8，请另存为 UTF-8 CSV 后导入", e)
                }
            } ?: error("无法打开所选文件")
        }
        val count = withContext(Dispatchers.IO) { expensesToInsert(expenses, dao.snapshot()).size }
        _transfer.value = TransferState(preview = ImportPreview(expenses, count))
    }

    fun confirmImport() {
        val preview = _transfer.value.preview ?: return
        transferTask {
            val count = withContext(Dispatchers.IO) { dao.importExpenses(preview.expenses) }
            _transfer.value = TransferState(message = "已导入 $count 笔，跳过 ${preview.expenses.size - count} 笔已有记录")
        }
    }

    fun exportCsv(uri: Uri) = transferTask {
        val count = withContext(Dispatchers.IO) {
            val expenses = dao.snapshot()
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.writer(Charsets.UTF_8).use { LedgerCsv.write(it, expenses) }
            } ?: error("无法写入所选位置")
            expenses.size
        }
        _transfer.value = TransferState(message = "已导出 $count 笔账单")
    }

    private fun transferTask(block: suspend () -> Unit) {
        if (_transfer.value.busy) return
        _transfer.value = TransferState(busy = true)
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _transfer.value = TransferState(message = "操作失败：${e.localizedMessage ?: "请检查文件格式和文件访问权限"}")
            }
        }
    }
    fun addCategory(name: String) = viewModelScope.launch(Dispatchers.IO) { dao.insertCategory(Category(name)) }
    fun add(expense: Expense) = viewModelScope.launch(Dispatchers.IO) { dao.insert(expense) }
    fun delete(expense: Expense) = viewModelScope.launch(Dispatchers.IO) { dao.delete(expense) }
}

data class TransferState(val busy: Boolean = false, val preview: ImportPreview? = null, val message: String? = null)
