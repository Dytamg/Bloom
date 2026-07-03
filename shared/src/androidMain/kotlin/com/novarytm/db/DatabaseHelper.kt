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
        val masterKey = storage.getMasterKey()
        
        val dbFile = context.getDatabasePath("novarytm.db")
        android.util.Log.i("DriverFactory", "createDriver: getMasterKey()=${if (masterKey != null) "present(${masterKey.size}bytes)" else "NULL"}, dbFile.exists=${dbFile.exists()}, dbFile.length=${if (dbFile.exists()) dbFile.length() else -1}")
        
        val effectiveKey: ByteArray = if (masterKey != null) {
            masterKey
        } else if (dbFile.exists()) {
            android.util.Log.w("DriverFactory", "KEY IS NULL but database file exists! Retrying...")
            var retried: ByteArray? = null
            for (i in 0 until 3) {
                Thread.sleep(100)
                retried = storage.getMasterKey()
                if (retried != null) break
            }
            if (retried != null) {
                retried
            } else {
                // Key is truly unrecoverable — archive existing db, generate fresh key
                android.util.Log.e("DriverFactory", "CRITICAL: Encryption key unreadable after retries. Archiving database.")
                if (dbFile.length() > 0) {
                    try {
                        val bakFile = File(dbFile.absolutePath + ".bak_" + System.currentTimeMillis())
                        dbFile.renameTo(bakFile)
                    } catch (_: Exception) {}
                }
                val newKey = ByteArray(32)
                java.security.SecureRandom().nextBytes(newKey)
                try { storage.saveMasterKey(newKey) } catch (e: Exception) {
                    android.util.Log.e("DriverFactory", "Failed to save master key", e)
                }
                newKey
            }
        } else {
            // Fresh install — no database file, no key
            android.util.Log.i("DriverFactory", "Fresh install: generating new master key")
            val newKey = ByteArray(32)
            java.security.SecureRandom().nextBytes(newKey)
            try { storage.saveMasterKey(newKey) } catch (e: Exception) {
                android.util.Log.e("DriverFactory", "Failed to save master key", e)
            }
            newKey
        }

        // Build the raw-key passphrase: x'<hex>'
        // CRITICAL: Do NOT zero this array — SupportOpenHelperFactory holds a REFERENCE
        // to it and Android's SQLite connection pool reuses it to reopen connections.
        fun buildPassphrase(key: ByteArray): ByteArray {
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

        val passphrase = buildPassphrase(effectiveKey)
        // NOTE: In sqlcipher-android 4.6.1, SupportOpenHelperFactory stores the passphrase
        // as a final reference and does NOT auto-clear it. The passphrase bytes must stay
        // alive for the lifetime of the connection pool.
        val factory = SupportOpenHelperFactory(passphrase)

        // H1 FIX: Use a custom Callback that runs CREATE TABLE IF NOT EXISTS instead of
        // the schema's default CREATE TABLE. This prevents SupportSQLiteOpenHelper's version
        // checking from either throwing (table already exists) or recreating empty tables.
        // The schema is still passed for the version number, but the actual table creation
        // is handled by our safe callback.
        val safeCallback = object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                android.util.Log.w("BLOOM_DIAG", "SupportSQLiteOpenHelper.onCreate CALLED — creating tables with IF NOT EXISTS")
                db.execSQL("CREATE TABLE IF NOT EXISTS CycleEntry (id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL UNIQUE, intensity INTEGER, notes TEXT, sexualRelations TEXT, painLevel INTEGER, symptoms TEXT);")
                db.execSQL("CREATE TABLE IF NOT EXISTS BirthControl (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, reminderTime TEXT, isActive INTEGER DEFAULT 1);")
                db.execSQL("CREATE TABLE IF NOT EXISTS PregnancyTest (id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL UNIQUE, result TEXT NOT NULL);")
                db.execSQL("CREATE TABLE IF NOT EXISTS Reminder (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, frequency TEXT NOT NULL, timeOfDay TEXT, nextDate TEXT, isActive INTEGER DEFAULT 1);")
            }
            override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                android.util.Log.w("BLOOM_DIAG", "SupportSQLiteOpenHelper.onUpgrade: $oldVersion -> $newVersion")
                // No migrations needed — just ensure tables exist
                onCreate(db)
            }
            override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                android.util.Log.w("BLOOM_DIAG", "SupportSQLiteOpenHelper.onOpen: version=${db.version}")
            }
        }

        fun openDriver(f: SupportOpenHelperFactory): SqlDriver {
            return AndroidSqliteDriver(
                schema = NovarytmDatabase.Schema,
                context = context,
                name = "novarytm.db",
                factory = f,
                callback = safeCallback
            )
        }

        // Try opening with the primary factory
        if (dbFile.exists()) {
            try {
                val driver = openDriver(factory)
                // Verify we can read/write from the database using a valid DDL command (SELECT via driver.execute throws code 0)
                driver.execute(null, "CREATE TABLE IF NOT EXISTS CycleEntry (id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL UNIQUE, intensity INTEGER, notes TEXT, sexualRelations TEXT, painLevel INTEGER, symptoms TEXT);", 0)
                val walFile = File(dbFile.absolutePath + "-wal")
                android.util.Log.i("DriverFactory", "Database opened successfully (main=${dbFile.length()}b, wal=${if (walFile.exists()) walFile.length() else -1}b)")
                return driver
            } catch (e: Exception) {
                android.util.Log.e("DriverFactory", "Primary key failed to open database: ${e.message}", e)
            }
        }

        // If primary failed and db exists, archive it
        if (dbFile.exists()) {
            android.util.Log.e("DriverFactory", "Database unreadable. Archiving and creating fresh database.")
            try {
                val bakFile = File(dbFile.absolutePath + ".bak_" + System.currentTimeMillis())
                dbFile.renameTo(bakFile)
            } catch (_: Exception) {}
            try { File(dbFile.absolutePath + "-journal").delete() } catch (_: Exception) {}
            try { File(dbFile.absolutePath + "-shm").delete() } catch (_: Exception) {}
            try { File(dbFile.absolutePath + "-wal").delete() } catch (_: Exception) {}
        }

        // Create fresh database
        android.util.Log.w("BLOOM_DIAG", "FRESH DB CREATED — this is a new empty database!")
        val freshPassphrase = buildPassphrase(effectiveKey)
        val freshFactory = SupportOpenHelperFactory(freshPassphrase)
        return openDriver(freshFactory)
        // NOTE: Do NOT call effectiveKey.fill(0) — the factory holds a reference
        // to the passphrase derived from it, and the connection pool needs it alive.
    }
}
