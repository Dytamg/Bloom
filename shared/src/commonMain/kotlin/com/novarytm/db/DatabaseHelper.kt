package com.novarytm.db

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import com.novarytm.storage.SecureStorageManager

expect class DriverFactory {
    fun createDriver(storage: SecureStorageManager): SqlDriver
}

fun createDatabase(driverFactory: DriverFactory, storage: SecureStorageManager): Pair<SqlDriver, NovarytmDatabase> {
    val driver = driverFactory.createDriver(storage)
    try {
        driver.execute(null, "ALTER TABLE CycleEntry ADD COLUMN painLevel INTEGER;", 0)
        driver.execute(null, "ALTER TABLE CycleEntry ADD COLUMN symptoms TEXT;", 0)
    } catch (e: Exception) {
        // Columns already exist or fresh schema creation
    }
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
