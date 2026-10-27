package com.simpleledger

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun migratesBothExistingDatabaseVersionsWithoutLosingExpenses() = runBlocking {
        for (oldVersion in listOf(1, 2)) {
            val name = "migration-test-$oldVersion.db"
            context.deleteDatabase(name)
            context.getDatabasePath(name).parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { old ->
                old.execSQL("CREATE TABLE expenses (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, amount TEXT NOT NULL, currency TEXT NOT NULL, date TEXT NOT NULL, time TEXT, category TEXT)")
                old.execSQL("INSERT INTO expenses VALUES (1, '12.50', '人民币', '2026-10-26', NULL, '餐饮')")
                if (oldVersion == 2) {
                    old.execSQL("CREATE TABLE categories (name TEXT NOT NULL PRIMARY KEY)")
                    old.execSQL("INSERT INTO categories VALUES ('餐饮')")
                }
                old.version = oldVersion
            }
            val db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).addMigrations(*LEDGER_MIGRATIONS).build()
            try {
                val expense = db.expenses().snapshot().single()
                assertEquals("12.50", expense.amount)
                assertEquals("", expense.description)
                assertEquals("", expense.source)
                assertEquals(listOf("餐饮"), db.expenses().observeCategories().first())
            } finally { db.close() }
            context.deleteDatabase(name)
        }
    }

    @Test fun importsAtomicallyPreservesRepeatedPaymentsAndSavesCategories() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val dao = db.expenses()
            val entry = Expense(amount = "0.01", currency = "人民币", date = "2026-05-13", time = null,
                category = "服务", description = "一分购", source = "微信")
            val incoming = List(6) { entry }
            assertEquals(6, dao.importExpenses(incoming))
            assertEquals(0, dao.importExpenses(incoming))
            assertEquals(6, dao.snapshot().size)
            assertEquals(listOf("服务"), dao.observeCategories().first())
            // A primary-key conflict after an earlier insertion must roll back the entire batch.
            val id = dao.snapshot().first().id
            try {
                dao.importExpenses(listOf(entry.copy(description = "应回滚"), entry.copy(id = id, description = "冲突")))
                fail("Expected a primary-key conflict")
            } catch (_: android.database.sqlite.SQLiteConstraintException) { }
            assertEquals(6, dao.snapshot().size)
        } finally { db.close() }
    }
}
