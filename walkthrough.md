
## 4. Fixed Final Edge-Case Splash Screen Deadlock
**Issue**: The user reported that putting in the wrong email, closing the app, and relaunching it still caused the app to freeze on the splash screen.
**Cause**: While the previous patch deferred `SyncManager` and UI-level Flow listeners, I discovered that the `CycleTracker` was still executing a synchronous `selectAllEntries().executeAsList()` database query inside its `init` block upon class instantiation. Since `CycleTracker` was instantiated right before the splash screen's database decryption logic began, they raced for the exact same SQLCipher initialization lock, causing a permanent deadlock.
**Fix**: I removed the automatic database fetch from `CycleTracker`'s `init` block. The `refreshCycleState()` function is now safely deferred and explicitly invoked by `App.kt` *only after* the database has completed its startup routine (`isInitializing = false`).

## 5. Fixed Critical iOS Login Error and App Lockout
**Issue**: When the user tried to share their sync code on iOS, it threw an error and then permanently locked the user out on relaunch due to the `ASWebAuthenticationSession`.
**Cause**: The iOS `GoogleAuthManager.kt` requested the `drive.appdata` scope, but strictly checked for the `drive.file` scope in its completion handler, always throwing an "Auth Warning". Furthermore, unlike Android, it did not set `storage.setAuthFlowActive(true)`, causing `SessionManager.lock()` to instantly kick the user out of the app when the Google Login web view appeared.
**Fix**: Patched iOS `GoogleAuthManager.kt` to check for `drive.appdata` to match the request, and properly set the `storage.setAuthFlowActive` boolean before launching the web view.

## 6. Fixed SQLCipher Rekey / PBKDF2 Master Key Mismatch (Android)
**Issue**: After partnering, the app completely crashed upon restart with a `SQLiteDatabaseCorruptException`. It only worked during the *first* session.
**Cause**: When the Partner flow concludes, `rekeyDatabase` explicitly uses `PRAGMA rekey = "x'...hex...'"` which uses the SQLCipher RAW KEY bypass format (disabling PBKDF2). However, on the very next app launch, `DatabaseHelper.kt` passed the new master key to `SupportOpenHelperFactory(masterKey)`, which uses the default PBKDF2 hash flow. The app tried to open a raw-key-encrypted DB using a PBKDF2-hashed key, instantly crashing the app.
**Fix**: Updated `DatabaseHelper.kt` to explicitly detect the existing `masterKey` and inject it in the `"x'...'"` raw hex string format so that `SupportOpenHelperFactory` continues to use the exact same raw encryption bypass.

## 7. Fixed FallbackErrorScreen Retry Button Deadlock
**Issue**: When the app hit a critical error on launch (due to the DB corruption crash above), the `FallbackErrorScreen` appeared. Clicking the "Attempt Restart" button did nothing, hanging the app forever.
**Cause**: `App.kt` used `LaunchedEffect(Unit)` for its initialization block. The "Attempt Restart" button set `isInitializing = true`, but Compose did not re-execute the effect because its key was `Unit`.
**Fix**: Swapped the key to a `retryTrigger` integer that increments whenever the user hits "Attempt Restart", guaranteeing the app re-runs the secure launch sequence.
