package com.novarytm.db

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.novarytm.storage.SecureStorageManager

expect class DriverFactory {
    fun createDriver(storage: SecureStorageManager): SqlDriver
}

private fun tableExists(driver: SqlDriver, tableName: String): Boolean {
    return driver.executeQuery(
        identifier = null,
        sql = "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1;",
        mapper = { cursor -> QueryResult.Value(cursor.next().value) },
        parameters = 1,
        binders = { bindString(0, tableName) }
    ).value
}

private fun getTableColumns(driver: SqlDriver, tableName: String): Set<String> {
    return driver.executeQuery(
        identifier = null,
        sql = "PRAGMA table_info($tableName);",
        mapper = { cursor ->
            val columns = mutableSetOf<String>()
            while (cursor.next().value) {
                cursor.getString(1)?.let { columns.add(it) }
            }
            QueryResult.Value(columns)
        },
        parameters = 0
    ).value
}

private fun migrateV1ToV2Idempotently(driver: SqlDriver) {
    if (!tableExists(driver, "CycleEntry")) {
        NovarytmDatabase.Schema.create(driver)
        return
    }
    val columns = getTableColumns(driver, "CycleEntry")
    if ("painLevel" !in columns) {
        driver.execute(null, "ALTER TABLE CycleEntry ADD COLUMN painLevel INTEGER;", 0)
    }
    if ("symptoms" !in columns) {
        driver.execute(null, "ALTER TABLE CycleEntry ADD COLUMN symptoms TEXT;", 0)
    }
    driver.execute(
        null,
        "CREATE TABLE IF NOT EXISTS BirthControl (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, reminderTime TEXT, isActive INTEGER DEFAULT 1);",
        0
    )
    driver.execute(
        null,
        "CREATE TABLE IF NOT EXISTS PregnancyTest (id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL UNIQUE, result TEXT NOT NULL);",
        0
    )
    driver.execute(
        null,
        "CREATE TABLE IF NOT EXISTS Reminder (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, frequency TEXT NOT NULL, timeOfDay TEXT, nextDate TEXT, isActive INTEGER DEFAULT 1);",
        0
    )
}

internal object SafeMigrationSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long
        get() = NovarytmDatabase.Schema.version

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        if (tableExists(driver, "CycleEntry")) {
            migrateV1ToV2Idempotently(driver)
        } else {
            NovarytmDatabase.Schema.create(driver)
        }
        return QueryResult.Unit
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion
    ): QueryResult.Value<Unit> {
        var current = oldVersion
        if (current < 2L && newVersion >= 2L) {
            migrateV1ToV2Idempotently(driver)
            callbacks.filter { it.afterVersion == 1L }.forEach { it.block(driver) }
            current = 2L
        }
        if (current < newVersion) {
            NovarytmDatabase.Schema.migrate(driver, current, newVersion, *callbacks)
        }
        return QueryResult.Unit
    }
}

fun createDatabase(driverFactory: DriverFactory, storage: SecureStorageManager): Pair<SqlDriver, NovarytmDatabase> {
    val driver = driverFactory.createDriver(storage)
    return Pair(driver, NovarytmDatabase(
        driver = driver,
        CycleEntryAdapter = CycleEntry.Adapter(
            intensityAdapter = object : ColumnAdapter<Int, Long> {
                override fun decode(databaseValue: Long) = databaseValue.toInt()
                override fun encode(value: Int) = value.toLong()
            },
            painLevelAdapter = object : ColumnAdapter<Int, Long> {
                override fun decode(databaseValue: Long) = databaseValue.toInt()
                override fun encode(value: Int) = value.toLong()
            }
        )
    ))
}
