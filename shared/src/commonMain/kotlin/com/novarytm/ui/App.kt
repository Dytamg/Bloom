package com.novarytm.ui

import kotlinx.datetime.*
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.*
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
import com.novarytm.db.NovarytmDatabase
import com.novarytm.ffi.RustBridge
import com.novarytm.tracker.CycleTracker
import com.novarytm.db.CycleEntry
import com.novarytm.storage.SecureStorageManager
import com.novarytm.auth.SessionManager
import com.novarytm.auth.SessionState
import com.novarytm.auth.BiometricPromptEffect
import com.novarytm.auth.GoogleAuthManager
import com.novarytm.sync.SyncManager
import com.novarytm.sync.GoogleDriveManager
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import com.novarytm.utils.AppJson
import kotlinx.coroutines.withContext

import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.novarytm.auth.rememberGoogleAuthManager

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(database: NovarytmDatabase, driver: SqlDriver, rustBridge: RustBridge, storage: SecureStorageManager, notificationScheduler: com.novarytm.notifications.NotificationScheduler) {
    var isInitializing by remember { mutableStateOf(true) }
    var criticalError by remember { mutableStateOf<String?>(null) }
    
    val sessionManager = remember { SessionManager(driver, rustBridge, storage) }
    val sessionState by sessionManager.state.collectAsState()
    
    // SECURITY: Auto-Lock on Background
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                sessionManager.lock()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    val httpClient = remember {
        HttpClient {
            install(ContentNegotiation) {
                json(json = AppJson)
            }
        }
    }
    
    val googleAuthManager = rememberGoogleAuthManager(storage)
    val googleDriveManager = remember(googleAuthManager) { GoogleDriveManager(googleAuthManager, httpClient) }
    val syncManager = remember(database, rustBridge, storage, googleDriveManager, isInitializing) { 
        if (isInitializing) null else SyncManager(database, rustBridge, storage, googleDriveManager) 
    }
    
    var onboardingStage by remember { mutableStateOf<OnboardingStage>(OnboardingStage.Welcome) }
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
    var showLogScreen by remember { mutableStateOf(false) }

    val cycleQueries = database.cycleQueries
    val latestEntries by remember(isInitializing) {
        if (isInitializing) kotlinx.coroutines.flow.flowOf(emptyList())
        else cycleQueries.selectAllEntries().asFlow().mapToList(Dispatchers.IO)
    }.collectAsState(initial = emptyList())
    
    val birthControl by remember(isInitializing) {
        if (isInitializing) kotlinx.coroutines.flow.flowOf(null)
        else cycleQueries.getBirthControl().asFlow().mapToOneOrNull(Dispatchers.IO)
    }.collectAsState(initial = null)
    
    val coroutineScope = rememberCoroutineScope { GlobalExceptionHandler }
    val cycleTracker = remember(rustBridge, cycleQueries, coroutineScope) { 
        CycleTracker(rustBridge, cycleQueries, coroutineScope) 
    }
    var triggerStepUpAuth by remember { mutableStateOf(0) }
    var retryTrigger by remember { mutableStateOf(0) }

    // Initialization Logic Audit: Move to background thread
    LaunchedEffect(retryTrigger) {
        try {
            println("App initialization started")
            withContext(Dispatchers.IO) {
                println("Inside IO context, executing getBirthControl")
                // Perform any necessary disk/native migrations or initial checks here
                // Force load queries to check DB health
                database.cycleQueries.getBirthControl().executeAsOneOrNull()
                
                val savedPermsJson = storage.getPartnerPermissionsJson()
                if (savedPermsJson != null) {
                    try {
                        val perms = com.novarytm.utils.AppJson.decodeFromString<com.novarytm.sync.PartnerPermissions>(savedPermsJson)
                        rustBridge.savePartnerPermissions(perms)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                println("getBirthControl finished, delaying")
                delay(500) // Aesthetic delay for splash
                println("delay finished")
            }
            println("Setting isInitializing to false")
            isInitializing = false
            cycleTracker.refreshCycleState()
        } catch (e: Throwable) {
            println("App initialization caught exception: ${e.message}")
            e.printStackTrace()
            criticalError = e.message ?: "Critical initialization failure"
        }
    }

    BiometricPromptEffect(
        trigger = triggerStepUpAuth,
        title = "Confirm Identity",
        subtitle = "Secure authentication required to access Partner settings.",
        onSuccess = {
            currentScreen = Screen.PartnerPermissions
        },
        onError = {
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            syncManager?.scope?.launch {
                if (sessionState is SessionState.Unlocked && !(sessionState as SessionState.Unlocked).isPartner) {
                    syncManager?.prepareAndUploadPayload()
                }
            }
            sessionManager.lock()
        }
    }

    var currentThemeName by remember { mutableStateOf(storage.getThemePreference() ?: "Default") }
    val appColors = if (currentThemeName == "Baby Blue") BabyBlueThemeColors else DefaultThemeColors

    val bloomColorScheme = lightColorScheme(
        primary = appColors.primary,
        onPrimary = Color.White,
        primaryContainer = appColors.primary.copy(alpha = 0.2f),
        onPrimaryContainer = appColors.textPrimary,
        secondary = appColors.secondary,
        onSecondary = Color.White,
        secondaryContainer = appColors.primary.copy(alpha = 0.2f),
        onSecondaryContainer = appColors.textPrimary,
        tertiary = appColors.secondary,
        onTertiary = Color.White,
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
                    onScreenChange = { currentScreen = it },
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
                    onTriggerStepUpAuth = { triggerStepUpAuth++ },
                    currentThemeName = currentThemeName,
                    onThemeChange = { newTheme ->
                        currentThemeName = newTheme
                        storage.saveThemePreference(newTheme)
                    }
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
    cycleQueries: com.novarytm.db.CycleQueries,
    latestEntries: List<CycleEntry>,
    birthControl: com.novarytm.db.BirthControl?,
    cycleTracker: CycleTracker,
    userId: String,
    storage: SecureStorageManager,
    rustBridge: RustBridge,
    notificationScheduler: com.novarytm.notifications.NotificationScheduler,
    googleAuthManager: GoogleAuthManager,
    onTriggerStepUpAuth: () -> Unit,
    currentThemeName: String,
    onThemeChange: (String) -> Unit
) {
    val appColors = LocalAppColors.current

    var editingEntry by remember { mutableStateOf<CycleEntry?>(null) }
    var showStepUpPinScreen by remember { mutableStateOf(false) }
    when (val state = sessionState) {
        is SessionState.Onboarding -> {
            when (val stage = onboardingStage) {
                is OnboardingStage.Welcome -> LoginScreen(
                    onLogin = { id -> onOnboardingStageChange(OnboardingStage.RecoveryWarning(id)) },
                    onRestore = { onOnboardingStageChange(OnboardingStage.Restore) },
                    onPartnerConnect = { onOnboardingStageChange(OnboardingStage.ConnectPartner) }
                )
                is OnboardingStage.RecoveryWarning -> RecoveryKeyWarningScreen(
                    userId = stage.userId,
                    onConfirmed = { onOnboardingStageChange(OnboardingStage.CreatePin(stage.userId, isPartner = false)) }
                )
                is OnboardingStage.Restore -> InputIdentityScreen(
                    onIdEntered = { id -> onOnboardingStageChange(OnboardingStage.CreatePin(id, isPartner = false, isRestore = true)) },
                    onBack = { onOnboardingStageChange(OnboardingStage.Welcome) }
                )
                is OnboardingStage.ConnectPartner -> ConnectPartnerScreen(
                    rustBridge = rustBridge,
                    googleAuthManager = googleAuthManager,
                    onIdEntered = { id, key -> onOnboardingStageChange(OnboardingStage.CreatePin(id, isPartner = true, secretKey = key)) },
                    onBack = { onOnboardingStageChange(OnboardingStage.Welcome) }
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
                            stage.clearCredentials()
                        },
                        onSkip = {
                            storage.setBiometricsEnabled(false)
                            if (stage.isPartner) {
                                sessionManager.completePartnerOnboarding(stage.userId, stage.hash, stage.masterKey, stage.secretKey, stage.salt)
                            } else {
                                sessionManager.completeOnboarding(stage.userId, stage.hash, stage.masterKey, stage.salt, isRestore = stage.isRestore)
                            }
                            stage.clearCredentials()
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
                    cycleQueries.deleteAllEntries()
                    cycleQueries.deleteBirthControl()
                    sessionManager.reset() 
                }
            )
        }
        is SessionState.Unlocked -> {
            // Auto-Refresh on Unlock (for Partners or freshly restored accounts)
            LaunchedEffect(Unit) {
                if (state.isPartner || state.justRestored) {
                    try {
                        withContext(Dispatchers.IO) {
                            syncManager?.manualRefresh()
                        }
                        cycleTracker.refreshCycleState()
                    } catch (e: Exception) {
                        println("SYNC_FATAL: Crash during post-login sync: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
            
            if (showStepUpPinScreen) {
                LockScreen(
                    rustBridge = rustBridge,
                    storage = storage,
                    pinHash = storage.getPinHash(),
                    onUnlocked = {
                        showStepUpPinScreen = false
                        onScreenChange(Screen.PartnerPermissions)
                    },
                    onReset = { showStepUpPinScreen = false }
                )
            } else if (showLogScreen || editingEntry != null) {
                Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        LogEntryScreen(
                            initialEntry = editingEntry,
                            isReadOnly = state.isPartner,
                            onSave = { startDate, endDate, intensity, notes, sexualRelations, painLevel, symptoms ->
                                cycleTracker.logNewPeriodRange(
                                    kotlinx.datetime.LocalDate.parse(startDate), 
                                    kotlinx.datetime.LocalDate.parse(endDate), 
                                    intensity, notes, sexualRelations, painLevel, symptoms
                                )
                                onShowLogScreenChange(false)
                                editingEntry = null
                            },
                            onCancel = { 
                                onShowLogScreenChange(false)
                                editingEntry = null
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
                                val partnerPerms = remember(currentScreen, latestEntries) { rustBridge.getPartnerPermissions() }
                                if (partnerPerms.shareCyclePhase) {
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
                    val partnerPerms = remember(currentScreen, latestEntries) { rustBridge.getPartnerPermissions() }
                    LaunchedEffect(state.isPartner, currentScreen, partnerPerms.shareBirthControl, partnerPerms.sharePregnancyTests) {
                        if (state.isPartner && currentScreen is Screen.Hub) {
                            if (!partnerPerms.shareBirthControl && !partnerPerms.sharePregnancyTests) {
                                onScreenChange(Screen.Home)
                            }
                        }
                    }
                    
                    Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                        when (currentScreen) {
                            is Screen.Home -> MainDashboard(
                                rustBridge = rustBridge,
                                cycleTracker = cycleTracker,
                                entries = latestEntries,
                                birthControl = birthControl,
                                userId = userId,
                                isPartnerView = state.isPartner,
                                syncManager = syncManager,
                                queries = cycleQueries,
                                onLogClick = { onShowLogScreenChange(true) },
                                onSettingsClick = { 
                                    if (state.isPartner) {
                                        onScreenChange(Screen.PartnerSettings)
                                    } else {
                                        onScreenChange(Screen.PartnerPermissions)
                                    }
                                },
                                onPairClick = { onScreenChange(Screen.ShareSyncCode) },
                                currentThemeName = currentThemeName,
                                onThemeChange = onThemeChange
                            )
                            is Screen.Calendar -> {
                                val perms = rustBridge.getPartnerPermissions()
                                val hasCalPerms = perms.shareCyclePhase || perms.shareFlowIntensity || perms.shareSymptomsMoods || perms.shareSexualActivity
                                if (state.isPartner && !hasCalPerms) {
                                    LaunchedEffect(Unit) { onScreenChange(Screen.Home) }
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Access revoked by partner") }
                                } else {
                                    CalendarScreen(
                                        cycleTracker = cycleTracker, 
                                        entries = latestEntries,
                                        isPartner = state.isPartner,
                                        partnerPerms = perms,
                                        onEditEntry = { editingEntry = it },
                                        onDeleteEntry = if (state.isPartner) {{ /* read-only */ }} else {{ cycleTracker.deleteEntry(it) }}
                                    )
                                }
                            }
                            is Screen.Hub -> {
                                val perms = rustBridge.getPartnerPermissions()
                                val hasHubPerms = perms.shareBirthControl || perms.sharePregnancyTests
                                if (state.isPartner && !hasHubPerms) {
                                    LaunchedEffect(Unit) { onScreenChange(Screen.Home) }
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Access revoked by partner") }
                                } else {
                                    HealthHubScreen(
                                        currentMethod = birthControl?.type,
                                        onMethodSelected = if (state.isPartner) { {} } else { { type ->
                                            cycleQueries.setBirthControl(type)
                                            cycleQueries.selectAllReminders().executeAsList()
                                                .filter { it.type != "Pregnancy Test" }
                                                .forEach { cycleQueries.deleteReminder(it.id) }
                                        } },
                                        isPartner = state.isPartner,
                                        partnerPerms = perms,
                                        queries = cycleQueries,
                                        notificationScheduler = notificationScheduler
                                    )
                                }
                            }
                            is Screen.PartnerPermissions -> if (state.isPartner) Box(Modifier.fillMaxSize()) else PartnerPermissionsScreen(
                                rustBridge = rustBridge,
                                isBiometricsEnabled = storage.isBiometricsEnabled(),
                                onToggleBiometrics = { enabled ->
                                    storage.setBiometricsEnabled(enabled)
                                },
                                onBack = { onScreenChange(Screen.ShareSyncCode) },
                                onSave = { perms ->
                                    storage.savePartnerPermissionsJson(com.novarytm.utils.AppJson.encodeToString(perms))
                                    syncManager?.scope?.launch {
                                        syncManager?.prepareAndUploadPayload()
                                    }
                                }
                            )
                            is Screen.PartnerSettings -> if (!state.isPartner) Box(Modifier.fillMaxSize()) else PartnerSettingsScreen(
                                onBack = { onScreenChange(Screen.Home) },
                                onDisconnect = {
                                    storage.saveTargetSyncId("")
                                    sessionManager.lock()
                                }
                            )
                            is Screen.ShareSyncCode -> if (state.isPartner) Box(Modifier.fillMaxSize()) else ShareSyncCodeScreen(
                                ownerId = userId,
                                storage = storage,
                                rustBridge = rustBridge,
                                syncManager = syncManager,
                                googleAuthManager = googleAuthManager,
                                birthControl = birthControl,
                                entries = latestEntries,
                                onBack = { onScreenChange(Screen.Home) },
                                onOpenPermissions = { onScreenChange(Screen.PartnerPermissions) }
                            )
                        }
                    }
                }
            }
        }
    }
}
