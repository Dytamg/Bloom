package com.novarytm.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.novarytm.storage.SecureStorageManager

actual class DriverFactory {
    actual fun createDriver(storage: SecureStorageManager): SqlDriver {
        return NativeSqliteDriver(NovarytmDatabase.Schema, "novarytm.db")
    }
}
