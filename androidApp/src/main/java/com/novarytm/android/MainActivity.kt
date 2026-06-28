package com.novarytm.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import com.novarytm.db.DriverFactory
import com.novarytm.db.createDatabase
import com.novarytm.ffi.RustBridge
import com.novarytm.storage.SecureStorageManager
import com.novarytm.ui.App

class MainActivity : FragmentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // Permission result handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // SECURITY: Prevent background apps/viruses from screen recording or taking screenshots
        // of sensitive health data, PINs, or Sync Codes.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            val storage = remember { SecureStorageManager(applicationContext) }
            val (driver, database) = remember {
                createDatabase(DriverFactory(applicationContext), storage)
            }
            val rustBridge = remember { RustBridge() }
            val notificationScheduler = remember { com.novarytm.notifications.NotificationScheduler(applicationContext) }
            
            App(database, driver, rustBridge, storage, notificationScheduler)
        }
    }
}
