package com.novarytm.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.novarytm.storage.SecureStorageManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

actual class DriverFactory(private val context: Context) {
    actual fun createDriver(storage: SecureStorageManager): SqlDriver {
        System.loadLibrary("sqlcipher")
        var masterKey = storage.getMasterKey()
        
        val dbFile = context.getDatabasePath("novarytm.db")
        if (masterKey == null && dbFile.exists()) {
            // Unreadable database (we lost the key or user cleared app data but not the file)
            dbFile.delete()
            File(dbFile.absolutePath + "-journal").delete()
            File(dbFile.absolutePath + "-shm").delete()
            File(dbFile.absolutePath + "-wal").delete()
        }

        if (masterKey == null) {
            // VULN-10 FIX: Generate a secure 32-byte key instead of using a hardcoded fallback
            val newKey = ByteArray(32)
            java.security.SecureRandom().nextBytes(newKey)
            try {
                storage.saveMasterKey(newKey)
            } catch (e: Exception) {
                android.util.Log.e("DriverFactory", "Failed to save master key to storage", e)
                throw IllegalStateException("Cannot initialize encrypted database without securely persisting master key", e)
            }
            masterKey = newKey
        }

        // M-4: Secure memory handling without intermediate String allocation
        val hexChars = "0123456789abcdef".toByteArray()
        val passphrase = ByteArray(masterKey.size * 2 + 3)
        passphrase[0] = 'x'.code.toByte()
        passphrase[1] = '\''.code.toByte()
        passphrase[passphrase.size - 1] = '\''.code.toByte()
        for (i in masterKey.indices) {
            val v = masterKey[i].toInt() and 0xFF
            passphrase[2 + i * 2] = hexChars[v ushr 4]
            passphrase[2 + i * 2 + 1] = hexChars[v and 0x0F]
        }
        hexChars.fill(0)
        
        val factory = SupportOpenHelperFactory(passphrase)
        
        return try {
            val driver = AndroidSqliteDriver(
                schema = NovarytmDatabase.Schema,
                context = context,
                name = "novarytm.db",
                factory = factory
            )
            // Force database open to verify encryption key and prevent code 26 errors from escaping
            driver.execute(null, "SELECT count(*) FROM sqlite_schema;", 0)
            driver
        } catch (e: Exception) {
            android.util.Log.e("DriverFactory", "Failed to open database (code 26 / unreadable). Resetting database files.", e)
            try { android.database.sqlite.SQLiteDatabase.deleteDatabase(dbFile) } catch (_: Exception) {}
            dbFile.delete()
            File(dbFile.absolutePath + "-journal").delete()
            File(dbFile.absolutePath + "-shm").delete()
            File(dbFile.absolutePath + "-wal").delete()
            AndroidSqliteDriver(
                schema = NovarytmDatabase.Schema,
                context = context,
                name = "novarytm.db",
                factory = factory
            )
        } finally {
            masterKey.fill(0)
        }
    }
}
