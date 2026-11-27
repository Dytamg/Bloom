package com.bloom.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.bloom.storage.SecureStorageManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

actual class DriverFactory(private val context: Context) {
    private fun loadSqlCipher() {
        System.loadLibrary("sqlcipher")
    }

    private fun buildPassphrase(key: ByteArray): ByteArray {
        val hexChars = "0123456789abcdef".toByteArray()
        val passphrase = ByteArray(key.size * 2 + 3)
        passphrase[0] = 'x'.code.toByte()
        passphrase[1] = '\''.code.toByte()
        passphrase[passphrase.size - 1] = '\''.code.toByte()
        for (i in key.indices) {
            val v = key[i].toInt() and 0xFF
            passphrase[2 + i * 2] = hexChars[v ushr 4]
            passphrase[2 + i * 2 + 1] = hexChars[v and 0x0F]
        }
        return passphrase
    }

    private fun resolveOrCreateKey(storage: SecureStorageManager, dbFile: File): ByteArray {
        val masterKey = storage.getMasterKey()
        if (masterKey != null) return masterKey

        if (dbFile.exists()) {
            for (i in 0 until 3) {
                Thread.sleep(100)
                storage.getMasterKey()?.let { return it }
            }
            archiveCorruptedDatabase(dbFile)
        }
        val newKey = ByteArray(32)
        java.security.SecureRandom().nextBytes(newKey)
        try { storage.saveMasterKey(newKey) } catch (_: Exception) {}
        return newKey
    }

    private fun archiveCorruptedDatabase(dbFile: File) {
        try {
            val bakFile = File(dbFile.absolutePath + ".bak_" + System.currentTimeMillis())
            dbFile.renameTo(bakFile)
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
            File(dbFile.path + "-journal").delete()
        } catch (_: Exception) {}
    }

    private fun openDriverInstance(context: Context, passphrase: ByteArray): SqlDriver {
        val factory = SupportOpenHelperFactory(passphrase)
        val migrationCallback = AndroidSqliteDriver.Callback(SafeMigrationSchema)
        return AndroidSqliteDriver(
            schema = SafeMigrationSchema,
            context = context,
            name = "bloom.db",
            factory = factory,
            callback = migrationCallback
        )
    }

    actual fun createDriver(storage: SecureStorageManager): SqlDriver {
        loadSqlCipher()
        val dbFile = context.getDatabasePath("bloom.db")
        val effectiveKey = resolveOrCreateKey(storage, dbFile)
        val passphrase = buildPassphrase(effectiveKey)

        try {
            if (dbFile.exists()) {
                try {
                    val driver = openDriverInstance(context, passphrase)
                    driver.executeQuery(null, "SELECT count(*) FROM sqlite_master;", { it.next(); app.cash.sqldelight.db.QueryResult.Unit }, 0)
                    return driver
                } catch (_: Exception) {}
            }
            if (dbFile.exists()) {
                archiveCorruptedDatabase(dbFile)
            }
            return openDriverInstance(context, passphrase)
        } finally {
            effectiveKey.fill(0)
        }
    }
}
