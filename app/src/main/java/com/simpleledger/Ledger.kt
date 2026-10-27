package com.simpleledger

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: String,
    val currency: String,
    val date: String,
    val time: String?,
    val category: String?
)

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses ORDER BY date DESC, COALESCE(time, '') DESC, id DESC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<Expense>>
    @Insert suspend fun insert(expense: Expense)
    @Delete suspend fun delete(expense: Expense)
}

@Database(entities = [Expense::class], version = 1, exportSchema = false)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun expenses(): ExpenseDao
}

class LedgerViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = Room.databaseBuilder(application, LedgerDatabase::class.java, "ledger.db").build().expenses()
    val expenses = dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun add(expense: Expense) = viewModelScope.launch(Dispatchers.IO) { dao.insert(expense) }
    fun delete(expense: Expense) = viewModelScope.launch(Dispatchers.IO) { dao.delete(expense) }
}
