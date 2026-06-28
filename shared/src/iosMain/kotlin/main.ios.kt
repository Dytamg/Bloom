import androidx.compose.ui.window.ComposeUIViewController
import com.novarytm.ui.App
import com.novarytm.db.DriverFactory
import com.novarytm.db.createDatabase
import com.novarytm.ffi.RustBridge
import com.novarytm.storage.SecureStorageManager
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController {
    val storage = SecureStorageManager()
    val (driver, database) = createDatabase(DriverFactory(), storage)
    val rustBridge = RustBridge()
    val notificationScheduler = com.novarytm.notifications.NotificationScheduler()
    App(database, driver, rustBridge, storage, notificationScheduler)
}
