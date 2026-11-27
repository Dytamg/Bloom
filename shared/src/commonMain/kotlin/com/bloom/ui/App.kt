@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.bloom.ui

import kotlinx.datetime.*
import kotlin.time.Clock
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.*
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bloom.db.BloomDatabase
import com.bloom.ffi.RustBridge
import com.bloom.tracker.CycleTracker
import com.bloom.db.CycleEntry
import com.bloom.storage.SecureStorageManager
import com.bloom.auth.SessionManager
import com.bloom.auth.SessionState
import com.bloom.auth.BiometricPromptEffect
import com.bloom.auth.GoogleAuthManager
import com.bloom.sync.SyncManager
import com.bloom.sync.SyncProviderMode
import com.bloom.sync.SyncRole
import com.bloom.sync.SafDocumentActions
import com.bloom.sync.rememberSafDocumentLauncher
import com.bloom.sync.rememberSyncBlobClient
import com.bloom.utils.AppJson
import com.bloom.utils.AppStrings
import kotlinx.coroutines.withContext

import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bloom.auth.rememberGoogleAuthManager

sealed class Screen {
    object Home : Screen()
    object Calendar : Screen()
    object Hub : Screen()
    object PartnerPermissions : Screen()
    object ShareSyncCode : Screen()
    object PartnerSettings : Screen()
}

sealed class OnboardingStage {
    open fun clearCredentials() {}
    object Welcome : OnboardingStage()
    data class RecoveryWarning(val userId: String) : OnboardingStage()
    object Restore : OnboardingStage()
    object ConnectPartner : OnboardingStage()
    data class CreatePin(val userId: String, val isPartner: Boolean = false, val isRestore: Boolean = false, val secretKey: ByteArray? = null) : OnboardingStage() {
        override fun clearCredentials() {
            secretKey?.fill(0)
        }
    }
    data class BiometricsSetup(val userId: String, val hash: ByteArray, val masterKey: ByteArray, val salt: ByteArray, val isPartner: Boolean, val isRestore: Boolean, val secretKey: ByteArray?) : OnboardingStage() {
        override fun clearCredentials() {
            hash.fill(0)
            masterKey.fill(0)
            salt.fill(0)
            secretKey?.fill(0)
        }
    }
}

internal fun resetOnboardingStage(current: OnboardingStage): OnboardingStage {
    current.clearCredentials()
    return OnboardingStage.Welcome
}

private fun performFullSessionReset(
    backgroundSyncScheduler: com.bloom.sync.SyncUploadScheduler?,
    storage: SecureStorageManager,
    onProviderModeChanged: (SyncProviderMode) -> Unit,
    cycleQueries: com.bloom.db.CycleQueries,
    onOnboardingStageChange: (OnboardingStage) -> Unit,
    onScreenChange: (Screen) -> Unit,
    onShowLogScreenChange: (Boolean) -> Unit,
    sessionManager: SessionManager
) {
    backgroundSyncScheduler?.cancelPartnerPull()
    backgroundSyncScheduler?.cancelPeriodicOwnerUpload()
    backgroundSyncScheduler?.cancelPendingUpload()
    storage.saveSyncRole(SyncRole.NONE)
    storage.saveSafFileUri(null)
    onProviderModeChanged(SyncProviderMode.DRIVE_API)
    cycleQueries.deleteAllEntries()
    cycleQueries.deleteAllPregnancyTests()
    cycleQueries.deleteAllReminders()
    cycleQueries.deleteBirthControl()
    onShowLogScreenChange(false)
    onScreenChange(Screen.Home)
    onOnboardingStageChange(OnboardingStage.Welcome)
    sessionManager.reset()
}

private fun isOwnerSyncMode(storage: SecureStorageManager): Boolean {
    return when (storage.getSyncRole()) {
        SyncRole.OWNER -> true
        SyncRole.PARTNER -> false
        SyncRole.NONE -> storage.getUserId() != null && storage.getTargetSyncId().isNullOrEmpty()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(
    database: BloomDatabase,
    driver: SqlDriver,
    rustBridge: RustBridge,
    storage: SecureStorageManager,
    notificationScheduler: com.bloom.notifications.NotificationScheduler,
    backgroundSyncScheduler: com.bloom.sync.BackgroundSyncScheduler? = null
) {
    var isInitializing by remember { mutableStateOf(true) }
    var criticalError by remember { mutableStateOf<String?>(null) }
    
    val sessionManager = remember { SessionManager(driver, rustBridge, storage) }
    val sessionState by sessionManager.state.collectAsState()

    val cycleQueries = database.cycleQueries
    val coroutineScope = rememberCoroutineScope { GlobalExceptionHandler }
    val cycleTracker = remember(rustBridge, cycleQueries, coroutineScope, storage) { 
        CycleTracker(rustBridge, cycleQueries, coroutineScope).apply {
            isReadOnlyMode = { storage.getTargetSyncId() != null && storage.getUserId() == null }
        }
    }

    var syncProviderMode by remember { mutableStateOf(storage.getSyncProviderMode()) }
    val googleAuthManager = rememberGoogleAuthManager(storage)
    val syncBlobClient = rememberSyncBlobClient(storage, googleAuthManager, syncProviderMode)
    val syncManager = remember(database, rustBridge, storage, syncBlobClient, cycleTracker, backgroundSyncScheduler, isInitializing) { 
        if (isInitializing) null else SyncManager(
            database = database,
            rustBridge = rustBridge,
            storage = storage,
            googleDriveManager = syncBlobClient,
            cycleTracker = cycleTracker,
            backgroundSyncScheduler = backgroundSyncScheduler,
            changeDebounceMs = 30_000L
        ) 
    }

    DisposableEffect(syncManager) {
        onDispose {
            syncManager?.scope?.cancel()
        }
    }

    val isOwnerSession = sessionState is SessionState.Unlocked &&
        !(sessionState as SessionState.Unlocked).isPartner &&
        isOwnerSyncMode(storage)

    LaunchedEffect(syncManager, isOwnerSession, syncProviderMode) {
        if (syncManager != null && isOwnerSession) {
            backgroundSyncScheduler?.schedulePeriodicOwnerUpload()
        }
    }

    // SECURITY: Flush pending outbound changes & Auto-Lock on Background
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentSyncManager by rememberUpdatedState(syncManager)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                val currentState = sessionManager.state.value
                if (!storage.isAuthFlowActive() && currentState is SessionState.Unlocked && !currentState.isPartner) {
                    currentSyncManager?.let { manager ->
                        manager.scope.launch {
                            manager.flushPendingChanges()
                        }
                    }
                }
                sessionManager.lock()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    var onboardingStage by remember { mutableStateOf<OnboardingStage>(OnboardingStage.Welcome) }
    LaunchedEffect(sessionState) {
        if (sessionState is SessionState.Onboarding && onboardingStage !is OnboardingStage.Welcome) {
            onboardingStage = resetOnboardingStage(onboardingStage)
        }
    }
    val screenStack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val currentScreen = screenStack.lastOrNull() ?: Screen.Home
    val onScreenChange: (Screen) -> Unit = { newScreen ->
        if (newScreen == Screen.Home) {
            screenStack.clear()
            screenStack.add(Screen.Home)
        } else {
            screenStack.remove(newScreen)
            screenStack.add(newScreen)
        }
    }
    val popBackStack: () -> Unit = {
        if (screenStack.size > 1) {
            screenStack.removeAt(screenStack.lastIndex)
        } else {
            onScreenChange(Screen.Home)
        }
    }
    var showLogScreen by remember { mutableStateOf(false) }

    val latestEntries by remember(isInitializing) {
        if (isInitializing) kotlinx.coroutines.flow.flowOf(emptyList())
        else cycleQueries.selectAllEntries().asFlow().mapToList(Dispatchers.IO)
    }.collectAsState(initial = emptyList())

    val birthControl by remember(isInitializing) {
        if (isInitializing) kotlinx.coroutines.flow.flowOf(null)
        else cycleQueries.getBirthControl().asFlow().mapToOneOrNull(Dispatchers.IO)
    }.collectAsState(initial = null)
    var triggerStepUpAuth by remember { mutableStateOf(0) }
    var stepUpFallbackTrigger by remember { mutableStateOf(0) }
    var retryTrigger by remember { mutableStateOf(0) }

    // Initialization Logic Audit: Move to background thread
    LaunchedEffect(retryTrigger) {
        try {
            withContext(Dispatchers.IO) {
                // Perform any necessary disk/native migrations or initial checks here
                // Force load queries to check DB health
                database.cycleQueries.getBirthControl().executeAsOneOrNull()
                
                val savedPermsJson = storage.getPartnerPermissionsJson()
                if (savedPermsJson != null) {
                    try {
                        val perms = com.bloom.utils.AppJson.decodeFromString<com.bloom.sync.PartnerPermissions>(savedPermsJson)
                        rustBridge.savePartnerPermissions(perms)
                    } catch (e: Exception) {
    
                    }
                }
                
                delay(500) // Aesthetic delay for splash
            }

            isInitializing = false
            cycleTracker.refreshCycleState()
        } catch (e: Throwable) {

            criticalError = e.message ?: "Critical initialization failure"
        }
    }

    BiometricPromptEffect(
        trigger = triggerStepUpAuth,
        title = "Confirm Identity",
        subtitle = "Secure authentication required to access Partner settings.",
        onSuccess = {
            onScreenChange(Screen.PartnerPermissions)
        },
        onError = {
            stepUpFallbackTrigger++
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            val currentState = sessionManager.state.value
            if (currentState is SessionState.Unlocked && !currentState.isPartner) {
                currentSyncManager?.let { manager ->
                    manager.scope.launch {
                        manager.flushPendingChanges()
                    }
                }
            }
            sessionManager.lock()
        }
    }

    var currentThemeName by remember { mutableStateOf(storage.getThemePreference() ?: "Default") }
    val appColors = if (currentThemeName == "Baby Blue") BabyBlueThemeColors else DefaultThemeColors

    val bloomColorScheme = lightColorScheme(
        primary = appColors.primary,
        onPrimary = appColors.onPrimary,
        primaryContainer = appColors.primary.copy(alpha = 0.2f),
        onPrimaryContainer = appColors.textPrimary,
        secondary = appColors.secondary,
        onSecondary = appColors.onSecondary,
        secondaryContainer = appColors.primary.copy(alpha = 0.2f),
        onSecondaryContainer = appColors.textPrimary,
        tertiary = appColors.secondary,
        onTertiary = appColors.onSecondary,
        tertiaryContainer = appColors.secondaryVariant,
        onTertiaryContainer = appColors.textPrimary,
        background = appColors.background,
        onBackground = appColors.textPrimary,
        surface = appColors.surface,
        onSurface = appColors.textPrimary,
        surfaceVariant = appColors.background,
        onSurfaceVariant = appColors.textSecondary,
        surfaceTint = appColors.primary,
        surfaceContainer = appColors.surface,
        surfaceContainerHigh = appColors.surface,
        surfaceContainerHighest = appColors.surface,
        surfaceContainerLow = appColors.surface,
        surfaceContainerLowest = appColors.surface
    )

    CompositionLocalProvider(LocalAppColors provides appColors) {
        MaterialTheme(colorScheme = bloomColorScheme) {
        Surface(color = MaterialTheme.colorScheme.background) {
            if (criticalError != null) {
                FallbackErrorScreen(message = criticalError ?: "An unexpected error occurred") {
                    criticalError = null
                    isInitializing = true
                    retryTrigger++
                }
            } else if (isInitializing) {
                SplashScreen()
            } else {
                AppContent(
                    sessionState = sessionState,
                    onboardingStage = onboardingStage,
                    onOnboardingStageChange = { newStage ->
                        onboardingStage.clearCredentials()
                        onboardingStage = newStage
                    },
                    currentScreen = currentScreen,
                    onScreenChange = onScreenChange,
                    showLogScreen = showLogScreen,
                    onShowLogScreenChange = { showLogScreen = it },
                    sessionManager = sessionManager,
                    syncManager = syncManager,
                    cycleQueries = cycleQueries,
                    latestEntries = latestEntries,
                    birthControl = birthControl,
                    cycleTracker = cycleTracker,
                    userId = if (sessionState is SessionState.Unlocked) {
                        val unlocked = sessionState as SessionState.Unlocked
                        if (unlocked.isPartner) storage.getTargetSyncId() ?: "" else storage.getUserId() ?: ""
                    } else "",
                    storage = storage,
                    rustBridge = rustBridge,
                    notificationScheduler = notificationScheduler,
                    googleAuthManager = googleAuthManager,
                    backgroundSyncScheduler = backgroundSyncScheduler,
                    onTriggerStepUpAuth = { triggerStepUpAuth++ },
                    stepUpFallbackTrigger = stepUpFallbackTrigger,
                    currentThemeName = currentThemeName,
                    onThemeChange = { newTheme ->
                        currentThemeName = newTheme
                        storage.saveThemePreference(newTheme)
                    },
                    syncProviderMode = syncProviderMode,
                    onProviderModeChanged = { newMode ->
                        syncProviderMode = newMode
                    },
                    canGoBack = screenStack.size > 1,
                    onBack = popBackStack
                )
            }
        }
    }
    }
}

@Composable
fun SplashScreen() {
    val colors = LocalAppColors.current
    Box(modifier = Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Favorite, contentDescription = null, tint = colors.primary, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(color = colors.primary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent(
    sessionState: SessionState,
    onboardingStage: OnboardingStage,
    onOnboardingStageChange: (OnboardingStage) -> Unit,
    currentScreen: Screen,
    onScreenChange: (Screen) -> Unit,
    showLogScreen: Boolean,
    onShowLogScreenChange: (Boolean) -> Unit,
    sessionManager: SessionManager,
    syncManager: SyncManager?,
    cycleQueries: com.bloom.db.CycleQueries,
    latestEntries: List<CycleEntry>,
    birthControl: com.bloom.db.BirthControl?,
    cycleTracker: CycleTracker,
    userId: String,
    storage: SecureStorageManager,
    rustBridge: RustBridge,
    notificationScheduler: com.bloom.notifications.NotificationScheduler,
    googleAuthManager: GoogleAuthManager,
    backgroundSyncScheduler: com.bloom.sync.SyncUploadScheduler? = null,
    onTriggerStepUpAuth: () -> Unit,
    stepUpFallbackTrigger: Int = 0,
    currentThemeName: String,
    onThemeChange: (String) -> Unit,
    syncProviderMode: SyncProviderMode,
    onProviderModeChanged: (SyncProviderMode) -> Unit,
    canGoBack: Boolean,
    onBack: () -> Unit
) {
    val appColors = LocalAppColors.current
    var editingEntry by remember { mutableStateOf<CycleEntry?>(null) }
    var editingEndDate by remember { mutableStateOf<String?>(null) }
    var showStepUpPinScreen by remember { mutableStateOf(false) }
    var stepUpFailedAttempts by remember { mutableStateOf(storage.getFailedPinAttempts()) }
    var stepUpLockedUntil by remember { mutableStateOf(storage.getPinLockoutUntil()) }
    LaunchedEffect(stepUpFallbackTrigger) {
        if (stepUpFallbackTrigger > 0) {
            showStepUpPinScreen = true
        }
    }
    when (val state = sessionState) {
        is SessionState.Onboarding -> {
            AppBackHandler(enabled = onboardingStage !is OnboardingStage.Welcome) {
                onOnboardingStageChange(OnboardingStage.Welcome)
            }
            when (val stage = onboardingStage) {
                is OnboardingStage.Welcome -> LoginScreen(
                    onLogin = { id -> onOnboardingStageChange(OnboardingStage.RecoveryWarning(id)) },
                    onRestore = { onOnboardingStageChange(OnboardingStage.Restore) },
                    onPartnerConnect = { onOnboardingStageChange(OnboardingStage.ConnectPartner) }
                )
                is OnboardingStage.RecoveryWarning -> RecoveryKeyWarningScreen(
                    userId = stage.userId,
                    storage = storage,
                    onConfirmed = { onOnboardingStageChange(OnboardingStage.CreatePin(stage.userId, isPartner = false)) }
                )
                is OnboardingStage.Restore -> InputIdentityScreen(
                    onIdEntered = { id -> onOnboardingStageChange(OnboardingStage.CreatePin(id, isPartner = false, isRestore = true)) },
                    onBack = { onOnboardingStageChange(OnboardingStage.Welcome) }
                )
                is OnboardingStage.ConnectPartner -> ConnectPartnerScreen(
                    rustBridge = rustBridge,
                    storage = storage,
                    googleAuthManager = googleAuthManager,
                    onIdEntered = { id, key -> onOnboardingStageChange(OnboardingStage.CreatePin(id, isPartner = true, secretKey = key)) },
                    onBack = { onOnboardingStageChange(OnboardingStage.Welcome) },
                    onProviderModeChanged = onProviderModeChanged
                )
                is OnboardingStage.CreatePin -> CreatePinScreen(
                    rustBridge = rustBridge,
                    onPinCreated = { hash, masterKey, salt ->
                        onOnboardingStageChange(OnboardingStage.BiometricsSetup(
                            userId = stage.userId,
                            hash = hash,
                            masterKey = masterKey,
                            salt = salt,
                            isPartner = stage.isPartner,
                            isRestore = stage.isRestore,
                            secretKey = stage.secretKey?.copyOf()
                        ))
                    }
                )
                is OnboardingStage.BiometricsSetup -> {
                    BiometricsSetupScreen(
                        onEnable = {
                            storage.setBiometricsEnabled(true)
                            if (stage.isPartner) {
                                sessionManager.completePartnerOnboarding(stage.userId, stage.hash, stage.masterKey, stage.secretKey, stage.salt)
                            } else {
                                sessionManager.completeOnboarding(stage.userId, stage.hash, stage.masterKey, stage.salt, isRestore = stage.isRestore)
                            }
                            onOnboardingStageChange(OnboardingStage.Welcome)
                        },
                        onSkip = {
                            storage.setBiometricsEnabled(false)
                            if (stage.isPartner) {
                                sessionManager.completePartnerOnboarding(stage.userId, stage.hash, stage.masterKey, stage.secretKey, stage.salt)
                            } else {
                                sessionManager.completeOnboarding(stage.userId, stage.hash, stage.masterKey, stage.salt, isRestore = stage.isRestore)
                            }
                            onOnboardingStageChange(OnboardingStage.Welcome)
                        }
                    )
                }
            }
        }
        is SessionState.Locked -> {
            LockScreen(
                rustBridge = rustBridge,
                storage = storage,
                pinHash = storage.getPinHash(),
                onUnlocked = { sessionManager.unlock() },
                onReset = {
                    performFullSessionReset(
                        backgroundSyncScheduler = backgroundSyncScheduler,
                        storage = storage,
                        onProviderModeChanged = onProviderModeChanged,
                        cycleQueries = cycleQueries,
                        onOnboardingStageChange = onOnboardingStageChange,
                        onScreenChange = onScreenChange,
                        onShowLogScreenChange = onShowLogScreenChange,
                        sessionManager = sessionManager
                    )
                }
            )
        }
        is SessionState.Unlocked -> {
            var permsVersion by remember { mutableStateOf(0) }
            LaunchedEffect(cycleQueries) {
                cycleQueries.selectAllEntries().asFlow().collect {
                    kotlinx.coroutines.yield()
                    permsVersion++
                }
            }
            val partnerPerms = remember(currentScreen, latestEntries, permsVersion) {
                rustBridge.getPartnerPermissions()
            }

            // Auto-Refresh on Unlock (for all accounts and partners)
            var hasRunUnlockRefresh by remember(state) { mutableStateOf(false) }
            LaunchedEffect(syncManager, syncProviderMode, state) {
                if (hasRunUnlockRefresh || syncManager == null || syncProviderMode != storage.getSyncProviderMode()) {
                    return@LaunchedEffect
                }
                hasRunUnlockRefresh = true
                try {
                    cycleTracker.refreshCycleState()
                    if (state.isPartner || state.justRestored) {
                        withContext(Dispatchers.IO) {
                            val forceInitialSaf = state.isPartner &&
                                syncProviderMode == SyncProviderMode.SAF_FILE &&
                                storage.getLastSyncTimestamp() == 0L
                            syncManager.manualRefresh(forceResync = forceInitialSaf)
                        }
                        cycleTracker.refreshCycleState()
                        permsVersion++
                    }
                } catch (e: Exception) {
                    println("BloomSync: Startup partner refresh failed: ${e::class.simpleName}: ${e.message}")
                }
            }
            
            val canAppGoBack = showStepUpPinScreen || showLogScreen || editingEntry != null || canGoBack
            AppBackHandler(enabled = canAppGoBack) {
                if (showStepUpPinScreen) {
                    showStepUpPinScreen = false
                } else if (showLogScreen || editingEntry != null) {
                    onShowLogScreenChange(false)
                    editingEntry = null
                    editingEndDate = null
                } else if (canGoBack) {
                    onBack()
                }
            }

            if (showStepUpPinScreen) {
                stepUpLockedUntil = storage.getPinLockoutUntil()
                stepUpFailedAttempts = storage.getFailedPinAttempts()
                val stepUpNow = Clock.System.now().toEpochMilliseconds()
                if (stepUpFailedAttempts >= 5 && stepUpNow < stepUpLockedUntil) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Too many failed attempts. Please wait 30 seconds.",
                            color = Color.Red,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(32.dp))
                    }
                } else {
                LockScreen(
                    rustBridge = rustBridge,
                    storage = storage,
                    pinHash = storage.getPinHash(),
                    onUnlocked = {
                        stepUpFailedAttempts = 0
                        storage.saveFailedPinAttempts(0)
                        storage.savePinLockoutUntil(0L)
                        showStepUpPinScreen = false
                        onScreenChange(Screen.PartnerPermissions)
                    },
                    onReset = {
                        stepUpFailedAttempts++
                        storage.saveFailedPinAttempts(stepUpFailedAttempts)
                        if (stepUpFailedAttempts >= 5) {
                            stepUpLockedUntil = Clock.System.now().toEpochMilliseconds() + 30_000L
                            storage.savePinLockoutUntil(stepUpLockedUntil)
                        }
                        showStepUpPinScreen = false
                    }
                )
                }
            } else if (showLogScreen || editingEntry != null) {
                Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        LogEntryScreen(
                            initialEntry = editingEntry,
                            initialEndDate = editingEndDate,
                            isReadOnly = state.isPartner,
                            onSave = { startDate, endDate, intensity, notes, sexualRelations, painLevel, symptoms ->
                                if (!state.isPartner) {
                                    cycleTracker.logNewPeriodRange(
                                        kotlinx.datetime.LocalDate.parse(startDate), 
                                        kotlinx.datetime.LocalDate.parse(endDate), 
                                        intensity, notes, sexualRelations, painLevel, symptoms
                                    )
                                }
                                onShowLogScreenChange(false)
                                editingEntry = null
                                editingEndDate = null
                            },
                            onCancel = { 
                                onShowLogScreenChange(false)
                                editingEntry = null
                                editingEndDate = null
                            }
                        )
                    }
                }
            } else {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        NavigationBar(
                            containerColor = Color.White.copy(alpha = 0.95f),
                            tonalElevation = 8.dp
                        ) {
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Home, contentDescription = null) },
                                label = { Text("Home", style = MaterialTheme.typography.labelSmall) },
                                selected = currentScreen is Screen.Home,
                                onClick = { onScreenChange(Screen.Home) },
                                colors = NavigationBarItemDefaults.colors(selectedIconColor = appColors.secondary, indicatorColor = appColors.secondary.copy(alpha=0.1f))
                            )
                            if (!state.isPartner) {
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                                    label = { Text("Calendar", style = MaterialTheme.typography.labelSmall) },
                                    selected = currentScreen is Screen.Calendar,
                                    onClick = { onScreenChange(Screen.Calendar) },
                                    colors = NavigationBarItemDefaults.colors(selectedIconColor = appColors.secondary, indicatorColor = appColors.secondary.copy(alpha=0.1f))
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Favorite, contentDescription = null) },
                                    label = { Text("Hub", style = MaterialTheme.typography.labelSmall) },
                                    selected = currentScreen is Screen.Hub,
                                    onClick = { onScreenChange(Screen.Hub) },
                                    colors = NavigationBarItemDefaults.colors(selectedIconColor = appColors.secondary, indicatorColor = appColors.secondary.copy(alpha=0.1f))
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Group, contentDescription = null) },
                                    label = { Text("Partner", style = MaterialTheme.typography.labelSmall) },
                                    selected = currentScreen is Screen.ShareSyncCode || currentScreen is Screen.PartnerPermissions,
                                    onClick = { 
                                        onScreenChange(Screen.ShareSyncCode)
                                    },
                                    colors = NavigationBarItemDefaults.colors(selectedIconColor = appColors.secondary, indicatorColor = appColors.secondary.copy(alpha=0.1f))
                                )
                            } else {
                                // Partner: show tabs based on owner-granted permissions
                                if (partnerPerms.shareCyclePhase || partnerPerms.shareFlowIntensity || partnerPerms.shareSymptomsMoods || partnerPerms.shareSexualActivity) {
                                    NavigationBarItem(
                                        icon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                                        label = { Text("Calendar", style = MaterialTheme.typography.labelSmall) },
                                        selected = currentScreen is Screen.Calendar,
                                        onClick = { onScreenChange(Screen.Calendar) },
                                        colors = NavigationBarItemDefaults.colors(selectedIconColor = appColors.secondary, indicatorColor = appColors.secondary.copy(alpha=0.1f))
                                    )
                                }
                                if (partnerPerms.shareBirthControl || partnerPerms.sharePregnancyTests) {
                                    NavigationBarItem(
                                        icon = { Icon(Icons.Default.Favorite, contentDescription = null) },
                                        label = { Text("Hub", style = MaterialTheme.typography.labelSmall) },
                                        selected = currentScreen is Screen.Hub,
                                        onClick = { onScreenChange(Screen.Hub) },
                                        colors = NavigationBarItemDefaults.colors(selectedIconColor = appColors.secondary, indicatorColor = appColors.secondary.copy(alpha=0.1f))
                                    )
                                }
                            }
                        }
                    }
                ) { padding ->
                    // Auto-redirect partner to Home if they are on the Hub but both permissions are revoked
                    LaunchedEffect(state.isPartner, currentScreen, partnerPerms.shareBirthControl, partnerPerms.sharePregnancyTests) {
                        if (state.isPartner && currentScreen is Screen.Hub) {
                            if (!partnerPerms.shareBirthControl && !partnerPerms.sharePregnancyTests) {
                                onScreenChange(Screen.Home)
                            }
                        }
                    }
                    
                    val syncWarning by (syncManager?.syncWarning ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()
                    val syncAuthRequired by (syncManager?.syncAuthRequired ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
                    val safAccessLostFromManager by (syncManager?.safAccessLost ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
                    val lastSuccessfulSyncWallclock by (
                        syncManager?.lastSuccessfulSyncWallclock
                            ?: kotlinx.coroutines.flow.MutableStateFlow(storage.getLastSuccessfulSyncWallclock())
                        ).collectAsState()
                    val syncStatusMsg = syncManager?.syncStatusMessage?.collectAsState()?.value
                    val activeWarning = syncWarning ?: syncStatusMsg
                    val bannerScope = rememberCoroutineScope()
                    var isSyncBannerBusy by remember { mutableStateOf(false) }
                    var safRevision by remember { mutableStateOf(0) }
                    var safResyncRequestId by remember { mutableStateOf(0) }
                    val handledSafResyncId = remember { intArrayOf(0) }
                    var safBannerDismissed by remember { mutableStateOf(false) }
                    var safOwnerWriteError by remember { mutableStateOf<String?>(null) }
                    val isOwner = isOwnerSyncMode(storage)
                    val safActionsHolder = remember { arrayOfNulls<SafDocumentActions>(1) }

                    val onSafAccessRestored: (String) -> Unit = { uri ->
                        val isOwnerWritable = state.isPartner || safActionsHolder[0]?.hasValidPermission(uri, true) == true
                        if (!isOwnerWritable) {
                            safOwnerWriteError = AppStrings.get("saf_owner_write_required")
                            safBannerDismissed = false
                        } else {
                            storage.saveSafFileUri(uri)
                            storage.saveSyncProviderMode(SyncProviderMode.SAF_FILE)
                            storage.saveSyncRole(if (state.isPartner) SyncRole.PARTNER else SyncRole.OWNER)
                            if (!state.isPartner) {
                                storage.saveLastUploadedContentHash(null)
                            }
                            onProviderModeChanged(SyncProviderMode.SAF_FILE)
                            safOwnerWriteError = null
                            safBannerDismissed = false
                            safRevision++
                            syncManager?.clearSyncStatus()
                            safResyncRequestId++
                        }
                    }

                    val safActions = rememberSafDocumentLauncher(
                        onFileCreated = onSafAccessRestored,
                        onFileOpened = onSafAccessRestored,
                        storage = storage
                    )
                    safActionsHolder[0] = safActions

                    LaunchedEffect(syncManager, syncProviderMode, safResyncRequestId) {
                        if (safResyncRequestId <= handledSafResyncId[0]) return@LaunchedEffect
                        val manager = syncManager ?: return@LaunchedEffect
                        if (syncProviderMode != SyncProviderMode.SAF_FILE) return@LaunchedEffect
                        handledSafResyncId[0] = safResyncRequestId
                        withContext(Dispatchers.IO) {
                            if (state.isPartner) {
                                manager.manualRefresh(forceResync = true)
                            } else {
                                manager.prepareAndUploadPayload()
                            }
                        }
                        cycleTracker.refreshCycleState()
                        permsVersion++
                    }

                    val configuredSafUri = storage.getSafFileUri()
                    val hasConfiguredSafSync = !configuredSafUri.isNullOrBlank()
                    LaunchedEffect(safAccessLostFromManager) {
                        if (safAccessLostFromManager) {
                            safBannerDismissed = false
                        }
                    }
                    LaunchedEffect(configuredSafUri) {
                        safBannerDismissed = false
                    }

                    val safPermissionMissing = remember(syncProviderMode, configuredSafUri, isOwner, safRevision) {
                        syncProviderMode == SyncProviderMode.SAF_FILE &&
                            hasConfiguredSafSync &&
                            !safActions.hasValidPermission(configuredSafUri, isOwner)
                    }
                    val showSafAccessLostBanner = (safOwnerWriteError != null) || (
                        syncProviderMode == SyncProviderMode.SAF_FILE &&
                            (safAccessLostFromManager || safPermissionMissing) &&
                            !safBannerDismissed
                        )
                    val isSyncConfigured = state.isPartner ||
                        (syncProviderMode == SyncProviderMode.SAF_FILE && hasConfiguredSafSync) ||
                        !storage.getPrimarySyncFileId().isNullOrBlank() ||
                        !storage.getPartnerEmail().isNullOrBlank()

                    Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                        if (showSafAccessLostBanner) {
                            SafAccessLostBanner(
                                message = safOwnerWriteError ?: activeWarning ?: AppStrings.get("saf_access_lost_banner"),
                                isError = safOwnerWriteError != null,
                                isOwner = isOwner,
                                onReconnect = { safActions.openSyncFile() },
                                onCreateNew = { safActions.createSyncFile("Bloom-sync.bloom") },
                                onDismiss = {
                                    safOwnerWriteError = null
                                    safBannerDismissed = true
                                    syncManager?.clearSyncStatus()
                                }
                            )
                        }

                        // Unified Drive sync warning banner
                        if (activeWarning != null && !showSafAccessLostBanner) {
                            val isClockSkewOrRollback = activeWarning.contains("force resync", ignoreCase = true) ||
                                activeWarning.contains("clock", ignoreCase = true) ||
                                activeWarning.contains("older than last sync", ignoreCase = true)

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Warning,
                                            contentDescription = AppStrings.get("sync_warning_cd"),
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = activeWarning,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                                        )
                                        IconButton(
                                            onClick = { syncManager?.clearSyncStatus() },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = AppStrings.get("sync_banner_dismiss"),
                                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    if (syncManager != null) {
                                        Spacer(Modifier.height(8.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.End,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            when {
                                                syncAuthRequired -> {
                                                    Button(
                                                        enabled = !isSyncBannerBusy,
                                                        onClick = {
                                                            isSyncBannerBusy = true
                                                            bannerScope.launch {
                                                                try {
                                                                    val signedIn = googleAuthManager.signIn()
                                                                    if (signedIn) {
                                                                        withContext(Dispatchers.IO) {
                                                                            if (state.isPartner) {
                                                                                syncManager.manualRefresh()
                                                                            } else {
                                                                                syncManager.prepareAndUploadPayload() ?: syncManager.manualRefresh()
                                                                            }
                                                                        }
                                                                        cycleTracker.refreshCycleState()
                                                                        permsVersion++
                                                                    }
                                                                } finally {
                                                                    isSyncBannerBusy = false
                                                                }
                                                            }
                                                        },
                                                        colors = ButtonDefaults.buttonColors(
                                                            containerColor = MaterialTheme.colorScheme.primary,
                                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                                        ),
                                                        shape = RoundedCornerShape(8.dp),
                                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                                        modifier = Modifier.heightIn(min = 48.dp)
                                                    ) {
                                                        Text(
                                                            AppStrings.get("sync_banner_reconnect_drive"),
                                                            style = MaterialTheme.typography.labelMedium
                                                        )
                                                    }
                                                }
                                                isClockSkewOrRollback -> {
                                                    Button(
                                                        enabled = !isSyncBannerBusy,
                                                        onClick = {
                                                            isSyncBannerBusy = true
                                                            bannerScope.launch {
                                                                try {
                                                                    withContext(Dispatchers.IO) {
                                                                        syncManager.forceResync()
                                                                    }
                                                                    cycleTracker.refreshCycleState()
                                                                    permsVersion++
                                                                } finally {
                                                                    isSyncBannerBusy = false
                                                                }
                                                            }
                                                        },
                                                        colors = ButtonDefaults.buttonColors(
                                                            containerColor = MaterialTheme.colorScheme.primary,
                                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                                        ),
                                                        shape = RoundedCornerShape(8.dp),
                                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                                        modifier = Modifier.heightIn(min = 48.dp)
                                                    ) {
                                                        Text(
                                                            AppStrings.get("sync_banner_force_resync"),
                                                            style = MaterialTheme.typography.labelMedium
                                                        )
                                                    }
                                                }
                                                else -> {
                                                    Button(
                                                        enabled = !isSyncBannerBusy,
                                                        onClick = {
                                                            isSyncBannerBusy = true
                                                            bannerScope.launch {
                                                                try {
                                                                    withContext(Dispatchers.IO) {
                                                                        if (state.isPartner) {
                                                                            syncManager.manualRefresh()
                                                                        } else {
                                                                            syncManager.prepareAndUploadPayload() ?: syncManager.manualRefresh()
                                                                        }
                                                                    }
                                                                    cycleTracker.refreshCycleState()
                                                                    permsVersion++
                                                                } finally {
                                                                    isSyncBannerBusy = false
                                                                }
                                                            }
                                                        },
                                                        colors = ButtonDefaults.buttonColors(
                                                            containerColor = MaterialTheme.colorScheme.primary,
                                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                                        ),
                                                        shape = RoundedCornerShape(8.dp),
                                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                                        modifier = Modifier.heightIn(min = 48.dp)
                                                    ) {
                                                        Text(
                                                            AppStrings.get("sync_banner_retry"),
                                                            style = MaterialTheme.typography.labelMedium
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        AnimatedContent(
                            targetState = currentScreen,
                            transitionSpec = {
                                val targetIdx = when (targetState) {
                                    is Screen.Home -> 0
                                    is Screen.Calendar -> 1
                                    is Screen.Hub -> 2
                                    is Screen.ShareSyncCode, is Screen.PartnerPermissions, is Screen.PartnerSettings -> 3
                                }
                                val initialIdx = when (initialState) {
                                    is Screen.Home -> 0
                                    is Screen.Calendar -> 1
                                    is Screen.Hub -> 2
                                    is Screen.ShareSyncCode, is Screen.PartnerPermissions, is Screen.PartnerSettings -> 3
                                }
                                val animSpec = tween<androidx.compose.ui.unit.IntOffset>(durationMillis = 220, easing = FastOutSlowInEasing)
                                val fadeSpec = tween<Float>(durationMillis = 220, easing = FastOutSlowInEasing)

                                if (targetIdx > initialIdx) {
                                    (slideInHorizontally(animationSpec = animSpec) { it } + fadeIn(animationSpec = fadeSpec))
                                        .togetherWith(slideOutHorizontally(animationSpec = animSpec) { -it / 3 } + fadeOut(animationSpec = fadeSpec))
                                } else if (targetIdx < initialIdx) {
                                    (slideInHorizontally(animationSpec = animSpec) { -it } + fadeIn(animationSpec = fadeSpec))
                                        .togetherWith(slideOutHorizontally(animationSpec = animSpec) { it / 3 } + fadeOut(animationSpec = fadeSpec))
                                } else {
                                    fadeIn(animationSpec = fadeSpec).togetherWith(fadeOut(animationSpec = fadeSpec))
                                }
                            },
                            label = "TabMenuSlideTransition",
                            modifier = Modifier.fillMaxSize()
                        ) { targetScreen ->
                            when (targetScreen) {
                                is Screen.Home -> MainDashboard(
                                    rustBridge = rustBridge,
                                    cycleTracker = cycleTracker,
                                    entries = latestEntries,
                                    birthControl = birthControl,
                                    userId = userId,
                                    isPartnerView = state.isPartner,
                                    syncManager = syncManager,
                                    googleAuthManager = googleAuthManager,
                                    partnerPerms = partnerPerms,
                                    onSyncComplete = { permsVersion++ },
                                    queries = cycleQueries,
                                    onLogClick = {
                                        if (latestEntries.none { (it.intensity ?: 0) > 0 }) {
                                            val todayStr = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
                                            editingEntry = CycleEntry(
                                                id = 0L,
                                                date = todayStr,
                                                intensity = 3,
                                                notes = "",
                                                sexualRelations = null,
                                                painLevel = null,
                                                symptoms = ""
                                            )
                                            editingEndDate = todayStr
                                        }
                                        onShowLogScreenChange(true)
                                    },
                                    onSettingsClick = { 
                                        if (state.isPartner) {
                                            onScreenChange(Screen.PartnerSettings)
                                        } else {
                                            onTriggerStepUpAuth()
                                        }
                                    },
                                    onPairClick = { onScreenChange(Screen.ShareSyncCode) },
                                    currentThemeName = currentThemeName,
                                    onThemeChange = onThemeChange,
                                    notificationScheduler = notificationScheduler,
                                    isSyncConfigured = isSyncConfigured,
                                    syncProviderMode = syncProviderMode
                                )
                                is Screen.Calendar -> {
                                    val hasCalPerms = partnerPerms.shareCyclePhase || partnerPerms.shareFlowIntensity || partnerPerms.shareSymptomsMoods || partnerPerms.shareSexualActivity
                                    if (state.isPartner && !hasCalPerms) {
                                        LaunchedEffect(Unit) { onScreenChange(Screen.Home) }
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Access revoked by partner") }
                                    } else {
                                        CalendarScreen(
                                            cycleTracker = cycleTracker, 
                                            entries = latestEntries,
                                            isPartner = state.isPartner,
                                            partnerPerms = partnerPerms,
                                            onEditEntry = { entry, end -> 
                                                editingEntry = entry
                                                editingEndDate = end
                                            },
                                            onDeleteEntry = if (state.isPartner) {{ /* read-only */ }} else {{ cycleTracker.deleteEntry(it) }},
                                            onDeleteEntries = if (state.isPartner) {{ /* read-only */ }} else {{ cycleTracker.deleteEntries(it) }}
                                        )
                                    }
                                }
                                is Screen.Hub -> {
                                    val hasHubPerms = partnerPerms.shareBirthControl || partnerPerms.sharePregnancyTests
                                    if (state.isPartner && !hasHubPerms) {
                                        LaunchedEffect(Unit) { onScreenChange(Screen.Home) }
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Access revoked by partner") }
                                    } else {
                                        HealthHubScreen(
                                            currentMethod = birthControl?.takeIf { it.isActive != false && !it.type.equals("None", ignoreCase = true) }?.type,
                                            onMethodSelected = if (state.isPartner) { {} } else { { type ->
                                                if (type.equals("None", ignoreCase = true)) {
                                                    cycleQueries.setBirthControlWithReminder("None", null, false)
                                                } else {
                                                    cycleQueries.setBirthControl(type, true)
                                                }
                                                cycleQueries.selectAllReminders().executeAsList()
                                                    .filter { it.type != "Pregnancy Test" }
                                                    .forEach { reminder ->
                                                        notificationScheduler.cancelNotification(
                                                            com.bloom.notifications.reminderNotificationId(reminder.type, reminder.frequency)
                                                        )
                                                        cycleQueries.deleteReminder(reminder.id)
                                                    }
                                            } },
                                            isPartner = state.isPartner,
                                            partnerPerms = partnerPerms,
                                            queries = cycleQueries,
                                            notificationScheduler = notificationScheduler
                                        )
                                    }
                                }
                                is Screen.PartnerPermissions -> if (state.isPartner) { LaunchedEffect(Unit) { onScreenChange(Screen.Home) } } else PartnerPermissionsScreen(
                                    rustBridge = rustBridge,
                                    isBiometricsEnabled = storage.isBiometricsEnabled(),
                                    onToggleBiometrics = { enabled ->
                                        storage.setBiometricsEnabled(enabled)
                                    },
                                    onBack = onBack,
                                    onSave = { perms ->
                                        rustBridge.savePartnerPermissions(perms)
                                        storage.savePartnerPermissionsJson(com.bloom.utils.AppJson.encodeToString(perms))
                                        permsVersion++
                                        syncManager?.scope?.launch {
                                            syncManager?.prepareAndUploadPayload()
                                        }
                                    }
                                )
                                is Screen.PartnerSettings -> if (!state.isPartner) { LaunchedEffect(Unit) { onScreenChange(Screen.Home) } } else PartnerSettingsScreen(
                                    partnerPerms = partnerPerms,
                                    syncProviderMode = syncProviderMode,
                                    hasValidSafPermission = !safPermissionMissing,
                                    lastSuccessfulSyncWallclock = lastSuccessfulSyncWallclock,
                                    onReconnectSafFile = { safActions.openSyncFile() },
                                    onBack = onBack,
                                    onDisconnect = {
                                        performFullSessionReset(
                                            backgroundSyncScheduler = backgroundSyncScheduler,
                                            storage = storage,
                                            onProviderModeChanged = onProviderModeChanged,
                                            cycleQueries = cycleQueries,
                                            onOnboardingStageChange = onOnboardingStageChange,
                                            onScreenChange = onScreenChange,
                                            onShowLogScreenChange = onShowLogScreenChange,
                                            sessionManager = sessionManager
                                        )
                                    }
                                )
                                is Screen.ShareSyncCode -> if (state.isPartner) { LaunchedEffect(Unit) { onScreenChange(Screen.Home) } } else ShareSyncCodeScreen(
                                    ownerId = userId,
                                    storage = storage,
                                    rustBridge = rustBridge,
                                    syncManager = syncManager,
                                    googleAuthManager = googleAuthManager,
                                    birthControl = birthControl,
                                    entries = latestEntries,
                                    onBack = onBack,
                                    onOpenPermissions = { onTriggerStepUpAuth() },
                                    activeProviderMode = syncProviderMode,
                                    onProviderModeChanged = onProviderModeChanged
                                )
                            }
                        }
                    } // Column
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SafAccessLostBanner(
    message: String,
    isError: Boolean,
    isOwner: Boolean,
    onReconnect: () -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit
) {
    val containerColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    val actionContainerColor = if (isError) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val actionContentColor = if (isError) {
        MaterialTheme.colorScheme.onError
    } else {
        MaterialTheme.colorScheme.onPrimary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = AppStrings.get("sync_warning_cd"),
                    tint = contentColor,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = message,
                    color = contentColor,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = AppStrings.get("sync_banner_dismiss"),
                        tint = contentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isOwner) {
                    OutlinedButton(
                        onClick = onCreateNew,
                        border = BorderStroke(1.dp, contentColor),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(
                            AppStrings.get("sync_banner_create_new"),
                            color = contentColor,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                Button(
                    onClick = onReconnect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = actionContainerColor,
                        contentColor = actionContentColor
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(
                        AppStrings.get("sync_banner_reconnect"),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}
