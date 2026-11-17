package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.auth.AuthSession
import com.example.auth.DirectAuthResult
import com.example.auth.UserSessionManager
import com.example.firebase.configureAppCheck
import com.example.ui.MainViewModel
import com.example.ui.SyncUiState
import com.example.ui.components.E2eeShieldBadge
import com.example.ui.components.NewEraLogoBadge
import com.example.ui.screens.AiToolsScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.OwnerMonitorSheet
import com.example.ui.screens.PhoneAppsScreen
import com.example.ui.screens.VaultSyncSheet
import com.example.ui.screens.VoiceAiScreen
import com.example.ui.screens.WebsitePortalScreen
import com.example.ui.theme.NewEraTheme
import kotlinx.coroutines.launch

private sealed class NewEraRoute(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    data object Dashboard : NewEraRoute("dashboard", "Dashboard", Icons.Filled.Dashboard, Icons.Outlined.Dashboard, "nav_dashboard")
    data object VoiceAi : NewEraRoute("voice_ai", "Voice AI", Icons.Filled.Mic, Icons.Outlined.Mic, "nav_voice_ai")
    data object AiTools : NewEraRoute("ai_tools", "AI Tools", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome, "nav_ai_tools")
    data object PhoneApps : NewEraRoute("phone_apps", "Apps", Icons.Filled.Apps, Icons.Outlined.Apps, "nav_phone_apps")
    data object Website : NewEraRoute("website", "Website", Icons.Filled.Language, Icons.Outlined.Language, "nav_website")
}

private val NAV_ITEMS = listOf(
    NewEraRoute.Dashboard,
    NewEraRoute.VoiceAi,
    NewEraRoute.AiTools,
    NewEraRoute.PhoneApps,
    NewEraRoute.Website
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure App Check with launch intent before any UI or Firebase operations
        configureAppCheck(this, intent)

        // STEP 2: Enable Edge-to-Edge and render UI
        enableEdgeToEdge()
        setContent {
            NewEraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionManager = remember(context) { UserSessionManager(context) }
    val activeSession by sessionManager.sessionFlow.collectAsStateWithLifecycle(initialValue = AuthSession())

    if (activeSession.isSignedIn && activeSession.uid.isNotBlank()) {
        key(activeSession.uid, activeSession.isOwner) {
            NewEraMainShell(
                currentUserId = activeSession.uid,
                currentUsername = activeSession.username.ifBlank { "New Era User" },
                currentUserEmail = activeSession.email,
                isOwnerSession = activeSession.isOwner,
                authMethod = activeSession.authMethod,
                onUpdateOwnerCode = { newCode ->
                    sessionManager.updateOwnerCode(newCode)
                },
                onSignOutComplete = {
                    scope.launch {
                        sessionManager.signOutSession()
                    }
                }
            )
        }
    } else {
        AuthScreen(
            onDirectLoginSubmit = { optionalCode ->
                when (val result = sessionManager.signInDirect(optionalCode)) {
                    is DirectAuthResult.Success -> null
                    is DirectAuthResult.Error -> result.message
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewEraMainShell(
    currentUserId: String,
    currentUsername: String = "Sanskar",
    currentUserEmail: String = "",
    isOwnerSession: Boolean = false,
    authMethod: String = "direct",
    onUpdateOwnerCode: suspend (String) -> Result<Unit> = { Result.success(Unit) },
    onSignOutComplete: () -> Unit = {}
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()
    val viewModel: MainViewModel = viewModel(
        key = "${authMethod}_${currentUserId}",
        factory = MainViewModel.provideFactory(
            currentUserId = currentUserId,
            currentUsername = currentUsername,
            isOwnerSession = isOwnerSession,
            authMethod = authMethod,
            appContext = appContext
        )
    )

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val snackbarHostState = remember { SnackbarHostState() }

    val profileState by viewModel.userProfileState.collectAsStateWithLifecycle()
    val recordsState by viewModel.decryptedRecordsState.collectAsStateWithLifecycle()
    val websiteState by viewModel.websiteState.collectAsStateWithLifecycle()
    val vaultBundle by viewModel.vaultBundleState.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val flashlightOn by viewModel.flashlightOn.collectAsStateWithLifecycle()
    val keyFingerprint by viewModel.keyFingerprint.collectAsStateWithLifecycle()
    val needsPassphraseUnlock by viewModel.needsPassphraseUnlock.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()

    val chatMessages by viewModel.chatMessages.collectAsStateWithLifecycle()
    val isChatGenerating by viewModel.isChatGenerating.collectAsStateWithLifecycle()
    val isListening by viewModel.voiceEngine.isListening.collectAsStateWithLifecycle()
    val isSpeaking by viewModel.voiceEngine.isSpeaking.collectAsStateWithLifecycle()
    val isTranscribing by viewModel.voiceEngine.isTranscribing.collectAsStateWithLifecycle()
    val usingAudioFallback by viewModel.voiceEngine.usingAudioFallback.collectAsStateWithLifecycle()
    val rmsLevel by viewModel.voiceEngine.rmsLevel.collectAsStateWithLifecycle()
    val captureMode by viewModel.voiceEngine.captureMode.collectAsStateWithLifecycle()
    val partialTranscript by viewModel.voiceEngine.partialTranscript.collectAsStateWithLifecycle()
    val voiceError by viewModel.voiceEngine.voiceError.collectAsStateWithLifecycle()
    val prosodyProfile by viewModel.voiceEngine.prosodyProfile.collectAsStateWithLifecycle()
    val handsFreeLoopEnabled by viewModel.handsFreeLoop.collectAsStateWithLifecycle()
    val computeMode by viewModel.computeMode.collectAsStateWithLifecycle()
    val edgePrivacyMode by viewModel.edgePrivacyMode.collectAsStateWithLifecycle()
    val toolResultState by viewModel.toolResultState.collectAsStateWithLifecycle()
    val isServerRunning by viewModel.websiteServer.isRunning.collectAsStateWithLifecycle()
    val dailyLearningState by viewModel.dailyLearningState.collectAsStateWithLifecycle()
    val isLearningNow by viewModel.isLearningNow.collectAsStateWithLifecycle()
    val permittedGoogleApps by viewModel.permittedGoogleApps.collectAsStateWithLifecycle()
    val googleOAuthSession by viewModel.googleOAuthSession.collectAsStateWithLifecycle()
    val pendingAuthIntentSender by viewModel.pendingAuthIntentSender.collectAsStateWithLifecycle()
    val googleAppPreviewResult by viewModel.googleAppPreviewResult.collectAsStateWithLifecycle()
    val graphMemoryState by viewModel.graphMemoryState.collectAsStateWithLifecycle()
    val gatewayTokensState by viewModel.gatewayTokensState.collectAsStateWithLifecycle()
    val userDirectoryState by viewModel.userDirectoryState.collectAsStateWithLifecycle()

    var showVaultSheet by remember { mutableStateOf(false) }
    var showOwnerMonitor by remember { mutableStateOf(false) }
    var requestedAiTab by remember { mutableStateOf<Int?>(null) }

    val googleAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        viewModel.onGoogleAuthIntentCompleted(activityResult.data)
    }

    LaunchedEffect(pendingAuthIntentSender) {
        val sender = pendingAuthIntentSender
        if (sender != null) {
            runCatching {
                googleAuthLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }.onFailure {
                viewModel.clearPendingAuthIntent()
            }
        }
    }

    LaunchedEffect(statusBanner) {
        val msg = statusBanner
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    val navigateToRoute: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val voiceReplyEnabled = (profileState as? SyncUiState.Success)?.data?.voiceReplyEnabled ?: true
    val pinnedItems = (recordsState as? SyncUiState.Success)?.data?.filter { it.pinned && it.isDecrypted } ?: emptyList()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NewEraLogoBadge(size = 34.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "New Era",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (isOwnerSession) {
                                        val label = currentUserEmail.ifBlank { currentUsername }
                                        "$label • Owner"
                                    } else {
                                        currentUserEmail.ifBlank { currentUsername }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isOwnerSession) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    },
                    actions = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            if (isOwnerSession) {
                                FilledTonalButton(
                                    onClick = {
                                        viewModel.refreshOwnerDirectory()
                                        showOwnerMonitor = true
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("owner_monitor_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.AdminPanelSettings,
                                        contentDescription = "Owner User Monitor",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Owner (${userDirectoryState.totalUsers})",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                            E2eeShieldBadge(
                                fingerprint = keyFingerprint,
                                onClick = { showVaultSheet = true }
                            )
                        }
                    }
                )
            },
            bottomBar = {
                if (!useNavRail) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        NAV_ITEMS.forEach { item ->
                            val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateToRoute(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label
                                    )
                                },
                                label = { Text(item.label, maxLines = 1) },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        NAV_ITEMS.forEach { item ->
                            val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateToRoute(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label
                                    )
                                },
                                label = { Text(item.label) },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    NavHost(
                        navController = navController,
                        startDestination = NewEraRoute.Dashboard.route
                    ) {
                        composable(NewEraRoute.Dashboard.route) {
                            DashboardScreen(
                                profileState = profileState,
                                recordsState = recordsState,
                                telemetry = telemetry,
                                dailyLearningState = dailyLearningState,
                                isLearningNow = isLearningNow,
                                keyFingerprint = keyFingerprint,
                                needsPassphraseUnlock = needsPassphraseUnlock,
                                isListening = isListening,
                                isSpeaking = isSpeaking,
                                onNavigateToVoice = { navigateToRoute(NewEraRoute.VoiceAi.route) },
                                onNavigateToTools = { navigateToRoute(NewEraRoute.AiTools.route) },
                                onNavigateToImageStudio = {
                                    requestedAiTab = 1
                                    navigateToRoute(NewEraRoute.AiTools.route)
                                },
                                onNavigateToVideoStudio = {
                                    requestedAiTab = 2
                                    navigateToRoute(NewEraRoute.AiTools.route)
                                },
                                onNavigateToArchitect = {
                                    requestedAiTab = 4
                                    navigateToRoute(NewEraRoute.AiTools.route)
                                },
                                onNavigateToApps = { navigateToRoute(NewEraRoute.PhoneApps.route) },
                                onNavigateToWebsite = { navigateToRoute(NewEraRoute.Website.route) },
                                onOpenVaultSheet = { showVaultSheet = true },
                                onLearnNow = { topic -> viewModel.learnNewThingToday(topic) },
                                onToggleAutoLearn = { enabled -> viewModel.toggleAutoLearnDaily(enabled) },
                                onRunSuggestion = { prompt ->
                                    navigateToRoute(NewEraRoute.VoiceAi.route)
                                    viewModel.sendVoiceOrTextCommand(prompt)
                                },
                                onSaveEncryptedNote = { title, content ->
                                    viewModel.saveEncryptedVaultNote("note", title, content, pinned = true)
                                },
                                onTogglePin = { id, pin -> viewModel.toggleRecordPin(id, pin) },
                                onDeleteRecord = { id -> viewModel.deleteRecord(id) }
                            )
                        }
                        composable(NewEraRoute.VoiceAi.route) {
                            VoiceAiScreen(
                                messages = chatMessages,
                                isGenerating = isChatGenerating,
                                isListening = isListening,
                                isSpeaking = isSpeaking,
                                isTranscribing = isTranscribing,
                                usingAudioFallback = usingAudioFallback,
                                rmsLevel = rmsLevel,
                                captureMode = captureMode,
                                partialTranscript = partialTranscript,
                                voiceError = voiceError,
                                voiceReplyEnabled = voiceReplyEnabled,
                                handsFreeLoopEnabled = handsFreeLoopEnabled,
                                computeMode = computeMode,
                                edgePrivacyMode = edgePrivacyMode,
                                prosodyProfile = prosodyProfile,
                                onSelectComputeMode = { viewModel.setComputeMode(it) },
                                onToggleEdgePrivacy = { viewModel.setEdgePrivacyMode(it) },
                                onSelectProsody = { viewModel.setVoiceProsody(it) },
                                onStartVoice = { viewModel.startVoiceListening() },
                                onStartDirectAudioVoice = { viewModel.startVoiceListening(preferDirectAudioCapture = true) },
                                onStartDictation = { onUpdate -> viewModel.startVoiceDictation(onUpdate) },
                                onStopVoice = { viewModel.stopVoiceListening() },
                                onSendPartialNow = { viewModel.sendPartialVoiceNow() },
                                onCancelVoice = { viewModel.cancelVoiceListening() },
                                onStopSpeaking = { viewModel.voiceEngine.stopSpeaking() },
                                onToggleVoiceReply = { viewModel.toggleVoiceReply(it) },
                                onToggleHandsFreeLoop = { viewModel.toggleHandsFreeLoop(it) },
                                onSendCommand = { text, img -> viewModel.sendVoiceOrTextCommand(text, img) },
                                onSaveMessageToVault = { title, content ->
                                    viewModel.saveEncryptedVaultNote("chat", title, content, pinned = true)
                                },
                                onClearVoiceError = { viewModel.voiceEngine.clearError() },
                                smartSuggestions = dailyLearningState.activeSuggestions
                            )
                        }
                        composable(NewEraRoute.AiTools.route) {
                            AiToolsScreen(
                                toolResultState = toolResultState,
                                requestedTabIndex = requestedAiTab,
                                onRequestedTabConsumed = { requestedAiTab = null },
                                graphMemoryState = graphMemoryState,
                                gatewayTokens = gatewayTokensState,
                                onRunAiTool = { kind, input, img -> viewModel.runAiTool(kind, input, img) },
                                onGenerateImage = { prompt, ratio, size, engineProfile, editAction, enhance, ref ->
                                    viewModel.generateAiImage(
                                        prompt = prompt,
                                        aspectRatio = ratio,
                                        imageSize = size,
                                        engineProfile = engineProfile,
                                        editAction = editAction,
                                        enhancePrompt = enhance,
                                        referenceImage = ref
                                    )
                                },
                                onGenerateVideo = { prompt, ratio, quality, style, videoEngine, ref, secPerScene, motion, grade, captions ->
                                    viewModel.generateEnhancedVideo(
                                        prompt = prompt,
                                        aspectRatio = ratio,
                                        qualityPreset = quality,
                                        stylePreset = style,
                                        videoEngine = videoEngine,
                                        referenceImage = ref,
                                        secondsPerScene = secPerScene,
                                        forcedMotion = motion,
                                        colorGrade = grade,
                                        showCaptions = captions
                                    )
                                },
                                onSynthesizeBlueprint = { goal, overrideInstruction ->
                                    viewModel.synthesizeAutonomousArchitecture(goal, overrideInstruction)
                                },
                                onAddMemoryNode = { type, label, summary ->
                                    viewModel.addGraphMemoryNode(type, label, summary)
                                },
                                onDeleteMemoryNode = { nodeId ->
                                    viewModel.deleteGraphMemoryNode(nodeId)
                                },
                                onRotateGatewayToken = { channel ->
                                    viewModel.rotateGatewayToken(channel)
                                },
                                onRevokeGatewayToken = { channel ->
                                    viewModel.revokeGatewayToken(channel)
                                },
                                onAnalyzeVision = { bmp, prompt -> viewModel.analyzeVision(bmp, prompt) },
                                onSaveResultToVault = { title, content ->
                                    viewModel.saveEncryptedVaultNote("tool_output", title, content, pinned = true)
                                }
                            )
                        }
                        composable(NewEraRoute.PhoneApps.route) {
                            PhoneAppsScreen(
                                telemetry = telemetry,
                                installedApps = installedApps,
                                flashlightOn = flashlightOn,
                                permittedGoogleApps = permittedGoogleApps,
                                googleOAuthSession = googleOAuthSession,
                                googleAppPreviewResult = googleAppPreviewResult,
                                onToggleGoogleApp = { app, allowed -> viewModel.toggleGoogleAppPermission(app, allowed) },
                                onToggleAllGoogleApps = { allowed -> viewModel.toggleAllGoogleAppPermissions(allowed) },
                                onAuthorizeGoogleApps = { viewModel.authorizeSelectedGoogleApps() },
                                onPreviewGoogleApp = { app -> viewModel.fetchGoogleAppPreview(app) },
                                onClearGooglePreview = { viewModel.clearGoogleAppPreview() },
                                onRefresh = { viewModel.refreshDeviceAndApps() },
                                onToggleFlashlight = { viewModel.toggleFlashlight() },
                                onOpenSettings = { section -> viewModel.openSettings(section) },
                                onStartTimer = { viewModel.startQuickTimer(60, "New Era 1m Timer") },
                                onOpenDialer = { viewModel.openDialer() },
                                onOpenCalendar = { viewModel.openCalendarEvent() },
                                onLaunchApp = { pkg -> viewModel.launchApp(pkg) }
                            )
                        }
                        composable(NewEraRoute.Website.route) {
                            WebsitePortalScreen(
                                websiteState = websiteState,
                                telemetry = telemetry,
                                pinnedItems = pinnedItems,
                                keyFingerprint = keyFingerprint,
                                isServerRunning = isServerRunning,
                                localServerUrl = viewModel.websiteServer.localUrl,
                                onToggleServer = { enable -> viewModel.toggleLocalServer(enable) },
                                onSaveWebsite = { title, headline, bio, accent, showTel, showAi ->
                                    viewModel.saveWebsiteConfig(title, headline, bio, accent, showTel, showAi)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showOwnerMonitor && isOwnerSession) {
        OwnerMonitorSheet(
            directory = userDirectoryState,
            onRefresh = { viewModel.refreshOwnerDirectory() },
            onUpdateOwnerCode = { code ->
                scope.launch {
                    onUpdateOwnerCode(code)
                }
            },
            onDismiss = { showOwnerMonitor = false }
        )
    }

    if (showVaultSheet) {
        VaultSyncSheet(
            keyFingerprint = keyFingerprint,
            hasCloudBundle = vaultBundle != null,
            needsPassphraseUnlock = needsPassphraseUnlock,
            recoveryKeyProvider = { viewModel.exportCurrentRecoveryKey() },
            onSetPassphrase = { viewModel.setSyncPassphrase(it) },
            onUnlockPassphrase = { viewModel.unlockWithPassphrase(it) },
            onImportRecoveryKey = { viewModel.importRecoveryKey(it) },
            onSignOut = {
                showVaultSheet = false
                onSignOutComplete()
            },
            onDismiss = { showVaultSheet = false }
        )
    }
}
