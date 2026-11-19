package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.core.agent.AgentRuntime
import com.example.core.agent.AgentState
import com.example.core.capability.CapabilityId
import com.example.core.capability.CapabilityManager
import com.example.core.floating.NayroFloatingService
import com.example.core.permission.PermissionManager
import com.example.core.permission.PermissionRegistry
import com.example.core.permission.PermissionSetupManager
import com.example.core.permission.PermissionType
import com.example.core.provider.AIProviderManager
import com.example.core.provider.ProviderConfig
import com.example.core.provider.ProviderType
import com.example.core.provider.model.AIResult
import com.example.core.security.EncryptedStorageManager
import com.example.core.service.NayroForegroundService
import com.example.core.service.ServiceLifecycleState
import com.example.core.service.ServiceStateRepository
import com.example.core.voice.VoiceManager
import com.example.core.voice.VoiceState
import com.example.data.local.NayroDatabase
import com.example.data.local.entities.ConversationEntity
import com.example.data.repository.NayroRepository
import com.example.ui.activity.ActivityScreen
import com.example.ui.background.BackgroundControlScreen
import com.example.ui.capabilities.CapabilitiesScreen
import com.example.ui.components.NayroBottomBar
import com.example.ui.components.NayroTopBar
import com.example.ui.conversation.ConversationScreen
import com.example.ui.home.HomeScreen
import com.example.ui.memory.MemoryScreen
import com.example.ui.navigation.Screen
import com.example.ui.onboarding.OnboardingScreen
import com.example.ui.permissions.PermissionAccessScreen
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.lifecycle.lifecycleScope
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.NayroTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var database: NayroDatabase
    private lateinit var repository: NayroRepository
    private lateinit var permissionManager: PermissionManager
    private lateinit var permissionSetupManager: PermissionSetupManager
    private lateinit var capabilityManager: CapabilityManager
    private lateinit var encryptedStorageManager: EncryptedStorageManager
    private lateinit var agentRuntime: AgentRuntime
    private lateinit var voiceManager: VoiceManager
    private lateinit var providerManager: AIProviderManager

    // Holds pending prompt delivered from floating assistant
    private var pendingFloatingPrompt: String? = null

    // Tracks the specific Android permission string requested so the callback only performs targeted handling
    private var lastRequestedPermissionString: String? = null
    private var pendingSpecialAccessPermissionType: PermissionType? = null

    private var isUnlockReceiverRegistered = false
    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT && ::agentRuntime.isInitialized) {
                lifecycleScope.launch {
                    agentRuntime.onDeviceUnlocked()
                }
            }
        }
    }

    private var pendingStartListeningAfterMicGrant: Boolean = false

    // Phase 4 — Targeted Single Permission Request Launcher:
    // Directly notifies AgentRuntime.onPermissionDecision(permission, isGranted) and persists the decision
    // across process recreation. Strictly avoids calling refreshAllPermissions(), refreshReport(), or evaluateAll().
    @Suppress("InvalidFragmentVersionForActivityResult")
    private val requestSinglePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val permStr = lastRequestedPermissionString
            ?: agentRuntime.pendingRequiredPermission
            ?: "ANDROID_PERMISSION"
        if (::permissionManager.isInitialized) {
            permissionManager.evaluateSinglePermissionTargeted(permStr, this)
        }
        if (permStr == android.Manifest.permission.RECORD_AUDIO && isGranted && pendingStartListeningAfterMicGrant) {
            pendingStartListeningAfterMicGrant = false
            if (::voiceManager.isInitialized) {
                voiceManager.startListening()
            }
        } else if (permStr == android.Manifest.permission.RECORD_AUDIO) {
            pendingStartListeningAfterMicGrant = false
        }
        if (::agentRuntime.isInitialized) {
            agentRuntime.persistPendingPermissionDecision(permStr, isGranted)
            lifecycleScope.launch {
                agentRuntime.onPermissionDecision(permStr, isGranted)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Extract floating assistant intent prompt if present
        handleIncomingIntent(intent)

        // Initialize Core Engines from NayroApplication Container
        val app = (application as? NayroApplication) ?: NayroApplication.instance
        database = app.database
        repository = app.repository
        permissionManager = app.permissionManager
        permissionSetupManager = app.permissionSetupManager
        capabilityManager = app.capabilityManager
        encryptedStorageManager = app.encryptedStorageManager
        providerManager = app.providerManager
        agentRuntime = app.agentRuntime
        voiceManager = app.voiceManager
        val notebookManager = app.notebookManager

        val savedName = permissionSetupManager.getSavedAssistantName()
        agentRuntime.setAssistantIdentity(savedName)
        voiceManager.updateAssistantIdentity(savedName)

        try {
            registerReceiver(userPresentReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
            isUnlockReceiverRegistered = true
        } catch (_: Exception) {}

        lifecycleScope.launch {
            agentRuntime.consumePersistedPermissionDecision()
        }

        setContent {
            NayroTheme {
                NayroAppContent(
                    repository = repository,
                    permissionManager = permissionManager,
                    permissionSetupManager = permissionSetupManager,
                    capabilityManager = capabilityManager,
                    encryptedStorageManager = encryptedStorageManager,
                    agentRuntime = agentRuntime,
                    voiceManager = voiceManager,
                    providerManager = providerManager,
                    notebookManager = notebookManager,
                    initialPendingPrompt = pendingFloatingPrompt,
                    onClearPendingPrompt = { pendingFloatingPrompt = null },
                    onRequestRuntimePermission = { type ->
                        val item = PermissionRegistry.findByType(type)
                        item?.androidPermissionString?.let { permStr ->
                            if (permissionManager.isPermissionGranted(permStr)) {
                                // Reuse already-granted permission without re-prompting
                                permissionManager.evaluateSinglePermissionTargeted(permStr, this)
                                lifecycleScope.launch {
                                    agentRuntime.onPermissionDecision(permStr, true)
                                }
                            } else {
                                lastRequestedPermissionString = permStr
                                permissionManager.markRuntimePermissionRequested(permStr)
                                requestSinglePermissionLauncher.launch(permStr)
                            }
                        }
                    },
                    onRequestMicAndListen = {
                        val micPerm = android.Manifest.permission.RECORD_AUDIO
                        if (permissionManager.isPermissionGranted(micPerm)) {
                            voiceManager.startListening()
                        } else {
                            pendingStartListeningAfterMicGrant = true
                            lastRequestedPermissionString = micPerm
                            permissionManager.markRuntimePermissionRequested(micPerm)
                            requestSinglePermissionLauncher.launch(micPerm)
                        }
                    },
                    onMicrophonePermissionResult = { granted ->
                        val micPerm = android.Manifest.permission.RECORD_AUDIO
                        permissionManager.evaluateSinglePermissionTargeted(micPerm, this)
                        agentRuntime.persistPendingPermissionDecision(micPerm, granted)
                        lifecycleScope.launch {
                            agentRuntime.onPermissionDecision(micPerm, granted)
                        }
                    },
                    onOpenPermissionSettings = { type ->
                        pendingSpecialAccessPermissionType = type
                        permissionSetupManager.triggerSpecialAccessSettings(type, this)
                    },
                    onRefreshPermissions = {
                        permissionManager.refreshAllPermissions(this)
                        permissionSetupManager.refreshReport()
                        capabilityManager.evaluateAll()
                    },
                    onStartForegroundService = {
                        NayroForegroundService.startService(this)
                    },
                    onStopForegroundService = {
                        NayroForegroundService.stopService(this)
                    },
                    onStartFloatingService = {
                        NayroFloatingService.startService(this)
                    },
                    onStopFloatingService = {
                        NayroFloatingService.stopService(this)
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val floatingPrompt = intent?.getStringExtra(NayroFloatingService.EXTRA_FLOATING_PROMPT)
        if (!floatingPrompt.isNullOrBlank()) {
            pendingFloatingPrompt = floatingPrompt
            intent.removeExtra(NayroFloatingService.EXTRA_FLOATING_PROMPT)
        }
    }

    override fun onResume() {
        super.onResume()
        // Section 3: Strictly avoid global refreshAllPermissions(), refreshReport(), or evaluateAll() in onResume().
        // Only perform a targeted single-permission check if returning from a special-access settings flow.
        val specialType = pendingSpecialAccessPermissionType
        if (specialType != null && ::permissionManager.isInitialized) {
            pendingSpecialAccessPermissionType = null
            val granted = permissionManager.evaluateSinglePermissionTargeted(specialType.name, this)
            if (::agentRuntime.isInitialized) {
                lifecycleScope.launch {
                    agentRuntime.onPermissionDecision(specialType.name, granted)
                }
            }
        }
    }

    override fun onDestroy() {
        if (isUnlockReceiverRegistered) {
            try {
                unregisterReceiver(userPresentReceiver)
            } catch (_: Exception) {}
            isUnlockReceiverRegistered = false
        }
        super.onDestroy()
        voiceManager.onActivityDestroyed()
    }
}

@Composable
fun NayroAppContent(
    repository: NayroRepository,
    permissionManager: PermissionManager,
    permissionSetupManager: PermissionSetupManager,
    capabilityManager: CapabilityManager,
    encryptedStorageManager: EncryptedStorageManager,
    agentRuntime: AgentRuntime,
    voiceManager: VoiceManager,
    providerManager: AIProviderManager,
    notebookManager: com.example.core.memory.NotebookManager,
    initialPendingPrompt: String?,
    onClearPendingPrompt: () -> Unit,
    onRequestRuntimePermission: (PermissionType) -> Unit,
    onRequestMicAndListen: () -> Unit,
    onMicrophonePermissionResult: (Boolean) -> Unit,
    onOpenPermissionSettings: (PermissionType) -> Unit,
    onRefreshPermissions: () -> Unit,
    onStartForegroundService: () -> Unit,
    onStopForegroundService: () -> Unit,
    onStartFloatingService: () -> Unit,
    onStopFloatingService: () -> Unit
) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
    var assistantName by remember { mutableStateOf(permissionSetupManager.getSavedAssistantName()) }
    var showOnboarding by remember { mutableStateOf(!permissionSetupManager.isSetupCompleted()) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val conversations by repository.conversations.collectAsState(initial = emptyList())
    val memories by repository.memories.collectAsState(initial = emptyList())
    val notes by repository.notes.collectAsState(initial = emptyList())
    val auditLogs by repository.auditLogs.collectAsState(initial = emptyList())

    val evaluations by permissionManager.evaluations.collectAsState()
    val capabilities by capabilityManager.capabilities.collectAsState()

    val agentState by agentRuntime.currentState.collectAsState()
    val currentTask by agentRuntime.currentTask.collectAsState()
    val pendingConfirmation by agentRuntime.confirmationManager.pendingConfirmation.collectAsState()

    val configuredProviders by providerManager.configuredProviders.collectAsState()
    val activeProvider by providerManager.activeProvider.collectAsState()
    val voiceConfig by voiceManager.configuration.collectAsState()
    val voiceState by voiceManager.voiceState.collectAsState()
    val transcribedText by voiceManager.transcribedText.collectAsState()
    val partialTranscript by voiceManager.partialTranscript.collectAsState()
    val voiceError by voiceManager.errorMessage.collectAsState()
    val audioLevel by voiceManager.normalizedAudioLevel.collectAsState()
    val isSpeechRecognitionAvailable = remember(voiceManager) {
        voiceManager.speechRecognitionProvider.isRecognitionAvailable()
    }
    var micPermissionGranted by remember(evaluations) {
        mutableStateOf(permissionManager.isMicrophoneGranted())
    }

    val foregroundStatus by ServiceStateRepository.foregroundServiceStatus.collectAsState()
    val floatingStatus by ServiceStateRepository.floatingServiceStatus.collectAsState()

    // Unified dispatch function: executes user request through AgentRuntime orchestration
    fun processUserPrompt(promptText: String) {
        val text = voiceManager.normalizeVoiceInvocationCommand(promptText)
        if (text.isEmpty()) return

        coroutineScope.launch {
            repository.addConversationMessage(
                ConversationEntity(sender = "user", content = text)
            )

            val reply = agentRuntime.executeUserInstruction(text)

            repository.addConversationMessage(
                ConversationEntity(sender = "babu", content = reply)
            )
            agentRuntime.setState(AgentState.Speaking(reply))

            if (voiceConfig.isWakeWordEnabled || voiceState == VoiceState.SPEAKING || voiceConfig.isTtsEnabled) {
                voiceManager.speak(reply)
            }
        }
    }

    fun handleVoiceToggle() {
        if (voiceState == VoiceState.LISTENING || voiceState == VoiceState.FOLLOW_UP_LISTENING) {
            voiceManager.stopListening()
        } else {
            if (!permissionManager.isMicrophoneGranted()) {
                onRequestMicAndListen()
            } else {
                voiceManager.startListening()
            }
        }
    }

    LaunchedEffect(voiceState) {
        when (voiceState) {
            VoiceState.LISTENING, VoiceState.FOLLOW_UP_LISTENING -> {
                agentRuntime.setState(AgentState.UserListening)
            }
            VoiceState.TRANSCRIBING -> {
                agentRuntime.setState(AgentState.Transcribing)
            }
            else -> {}
        }
    }

    // Handle Speech Recognition callback
    LaunchedEffect(Unit) {
        voiceManager.attachUiListener(
            onRecognized = { recognizedText ->
                processUserPrompt(recognizedText)
            },
            onError = { errorMsg ->
                agentRuntime.setState(AgentState.Error(errorMsg))
            }
        )
    }

    // Process Pending prompt from Floating Assistant
    LaunchedEffect(initialPendingPrompt) {
        if (!initialPendingPrompt.isNullOrBlank()) {
            currentScreen = Screen.Chat
            processUserPrompt(initialPendingPrompt)
            onClearPendingPrompt()
        }
    }

    if (currentScreen != Screen.Home) {
        BackHandler {
            currentScreen = Screen.Home
        }
    }

    if (showOnboarding) {
        OnboardingScreen(
            evaluations = evaluations,
            initialAssistantName = assistantName,
            isForegroundRunning = foregroundStatus.state == ServiceLifecycleState.RUNNING,
            isFloatingRunning = floatingStatus.state == ServiceLifecycleState.RUNNING,
            onRequestRuntimePermission = onRequestRuntimePermission,
            onOpenPermissionSettings = onOpenPermissionSettings,
            onRefreshPermissions = onRefreshPermissions,
            onStartForegroundService = onStartForegroundService,
            onStartFloatingService = onStartFloatingService,
            onCompleteOnboarding = { name ->
                val cleanName = name.trim().ifEmpty { "Babu" }
                assistantName = cleanName
                permissionSetupManager.markSetupCompleted(cleanName)
                agentRuntime.setAssistantIdentity(cleanName)
                voiceManager.updateAssistantIdentity(cleanName)
                showOnboarding = false
                currentScreen = Screen.Home
            }
        )
    } else {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                NayroTopBar(
                    currentScreen = currentScreen,
                    onOpenPermissions = { currentScreen = Screen.PermissionAccess },
                    onOpenControlCenter = { currentScreen = Screen.AgentDashboard }
                )
            },
            bottomBar = {
                NayroBottomBar(
                    currentRoute = currentScreen.route,
                    onNavigate = { screen -> currentScreen = screen }
                )
            }
        ) { innerPadding ->
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (currentScreen) {
                    Screen.Home -> HomeScreen(
                        assistantName = assistantName,
                        agentState = agentState,
                        voiceState = voiceState,
                        liveTranscript = transcribedText,
                        partialTranscript = partialTranscript,
                        audioLevel = audioLevel,
                        isMicrophoneGranted = micPermissionGranted,
                        isSpeechRecognitionAvailable = isSpeechRecognitionAvailable,
                        selectedLanguageTag = voiceConfig.languageTag,
                        voiceError = voiceError,
                        currentTask = currentTask,
                        recentConversations = conversations,
                        isForegroundActive = foregroundStatus.state == ServiceLifecycleState.RUNNING,
                        isFloatingActive = floatingStatus.state == ServiceLifecycleState.RUNNING,
                        isAiReady = activeProvider != null,
                        onVoiceTap = {
                            handleVoiceToggle()
                        },
                        onStartListening = {
                            voiceManager.startListening()
                        },
                        onStopListening = {
                            voiceManager.stopListening()
                        },
                        onStopSpeaking = {
                            voiceManager.stopSpeaking()
                        },
                        onMicrophonePermissionResult = { granted ->
                            micPermissionGranted = granted
                            onMicrophonePermissionResult(granted)
                        },
                        onLanguageSelected = { langTag ->
                            voiceManager.setRecognitionLanguage(langTag)
                        },
                        onClearTranscript = {
                            voiceManager.clearTranscriptAndError()
                        },
                        onSendText = { text ->
                            processUserPrompt(text)
                        },
                        onCancelTask = {
                            agentRuntime.cancelCurrentTask()
                        },
                        onPauseTask = {
                            agentRuntime.pauseCurrentTask()
                        },
                        onResumeTask = {
                            agentRuntime.resumeCurrentTask()
                        },
                        onQuickActionClick = { tag ->
                            when (tag) {
                                "action_dashboard" -> currentScreen = Screen.AgentDashboard
                                "action_background" -> currentScreen = Screen.BackgroundControl
                                "action_perm" -> currentScreen = Screen.PermissionAccess
                                "action_caps" -> currentScreen = Screen.Capabilities
                                "action_api" -> currentScreen = Screen.Settings
                                "action_memory" -> currentScreen = Screen.Memory
                                "action_chat" -> currentScreen = Screen.Chat
                                "action_wizard" -> showOnboarding = true
                            }
                        }
                    )

                    Screen.BackgroundControl -> BackgroundControlScreen(
                        isForegroundRunning = foregroundStatus.state == ServiceLifecycleState.RUNNING,
                        isFloatingRunning = floatingStatus.state == ServiceLifecycleState.RUNNING,
                        hasOverlayPermission = permissionManager.canDrawOverlays(),
                        onStartForegroundService = onStartForegroundService,
                        onStopForegroundService = onStopForegroundService,
                        onStartFloatingService = onStartFloatingService,
                        onStopFloatingService = onStopFloatingService,
                        onRequestOverlayPermission = {
                            onOpenPermissionSettings(PermissionType.OVERLAY)
                        },
                        isWakeWordEnabled = voiceConfig.isWakeWordEnabled,
                        onToggleWakeWord = { enabled ->
                            voiceManager.updateConfiguration(voiceConfig.copy(isWakeWordEnabled = enabled))
                            capabilityManager.evaluateAll()
                        },
                        currentAgentStateLabel = agentState.javaClass.simpleName,
                        onPauseBackgroundAgent = { _ ->
                            agentRuntime.pauseCurrentTask()
                        },
                        onResumeBackgroundAgent = {
                            agentRuntime.resumeCurrentTask()
                        },
                        onOpenPermissionSettings = onOpenPermissionSettings
                    )

                    Screen.PermissionAccess -> PermissionAccessScreen(
                        evaluations = evaluations,
                        onRequestRuntimePermission = onRequestRuntimePermission,
                        onOpenSystemSettings = onOpenPermissionSettings,
                        onRefreshPermissions = onRefreshPermissions,
                        onLaunchSetupWizard = { showOnboarding = true }
                    )

                    Screen.Capabilities -> CapabilitiesScreen(
                        capabilities = capabilities,
                        onActionClick = { capId, _ ->
                            when (capId) {
                                CapabilityId.FLOATING_ASSISTANT -> {
                                    if (!permissionManager.canDrawOverlays()) {
                                        onOpenPermissionSettings(PermissionType.OVERLAY)
                                    } else if (floatingStatus.state == ServiceLifecycleState.RUNNING) {
                                        onStopFloatingService()
                                    } else {
                                        onStartFloatingService()
                                    }
                                }
                                CapabilityId.BACKGROUND_AGENT -> {
                                    if (foregroundStatus.state == ServiceLifecycleState.RUNNING) {
                                        onStopForegroundService()
                                    } else {
                                        onStartForegroundService()
                                    }
                                }
                                CapabilityId.MICROPHONE_ACCESS, CapabilityId.WAKE_WORD_READY, CapabilityId.VOICE_INTERACTION_READY -> {
                                    onRequestRuntimePermission(PermissionType.MICROPHONE)
                                }
                                CapabilityId.NOTIFICATION_ACCESS -> {
                                    onRequestRuntimePermission(PermissionType.NOTIFICATIONS)
                                }
                                CapabilityId.ACCESSIBILITY -> {
                                    context.startActivity(com.example.core.accessibility.AccessibilityConnectionManager.createSettingsIntent())
                                }
                                else -> {}
                            }
                        }
                    )

                    Screen.Chat -> ConversationScreen(
                        assistantName = assistantName,
                        conversations = conversations,
                        voiceState = voiceState,
                        liveTranscript = transcribedText,
                        audioLevel = audioLevel,
                        onVoiceTap = {
                            handleVoiceToggle()
                        },
                        onSendMessage = { text ->
                            processUserPrompt(text)
                        },
                        onClearHistory = {
                            coroutineScope.launch {
                                repository.clearHistory()
                            }
                        }
                    )

                    Screen.Activity -> ActivityScreen(
                        auditLogs = auditLogs
                    )

                    Screen.Memory -> MemoryScreen(
                        memories = memories,
                        notes = notes,
                        onDeleteMemory = { id ->
                            coroutineScope.launch {
                                repository.deleteMemory(id)
                            }
                        },
                        onCreateNote = { title, content, cat ->
                            coroutineScope.launch {
                                notebookManager.createNote(title, content, cat)
                            }
                        },
                        onDeleteNote = { id ->
                            coroutineScope.launch {
                                notebookManager.deleteNote(id)
                            }
                        },
                        onTogglePinNote = { note ->
                            coroutineScope.launch {
                                notebookManager.togglePin(note)
                            }
                        }
                    )

                    Screen.Settings -> {
                        SettingsScreen(
                            assistantName = assistantName,
                            isWakeEnabled = voiceConfig.isWakeWordEnabled,
                            providers = configuredProviders,
                            activeProviderId = activeProvider?.id,
                            onSaveApiKey = { providerId, apiKey ->
                                encryptedStorageManager.saveApiKey(providerId, apiKey)
                                providerManager.refreshActiveProvider()
                                capabilityManager.evaluateAll()
                                coroutineScope.launch {
                                    repository.logAuditAction(
                                        actionType = "SECURITY_KEY_UPDATE",
                                        target = providerId,
                                        riskLevel = "R0",
                                        outcome = "SUCCESS",
                                        details = "API key securely stored in Android KeyStore."
                                    )
                                }
                            },
                            onSaveCustomProvider = { id, name, url, model ->
                                val customConfig = ProviderConfig(
                                    id = id,
                                    type = ProviderType.CUSTOM_OPENAI,
                                    name = name,
                                    baseUrl = url,
                                    modelId = model,
                                    priority = 5,
                                    isEnabled = true
                                )
                                providerManager.updateProvider(customConfig)
                                capabilityManager.evaluateAll()
                            },
                            onToggleWake = { enabled ->
                                voiceManager.updateConfiguration(voiceConfig.copy(isWakeWordEnabled = enabled))
                                capabilityManager.evaluateAll()
                            },
                            onTestProvider = { providerId ->
                                coroutineScope.launch {
                                    val provider = configuredProviders.firstOrNull { it.id == providerId }
                                    if (provider != null) {
                                        agentRuntime.setState(AgentState.Thinking)
                                        val result = providerManager.sendRequest("Test connection from NAYRO")
                                        if (result is AIResult.Success) {
                                            agentRuntime.setState(AgentState.Completed("Connected successfully to ${provider.name}!"))
                                        } else if (result is AIResult.Error) {
                                            agentRuntime.setState(AgentState.Error(result.message))
                                        }
                                    }
                                }
                            },
                            wakeSensitivity = voiceManager.wakeWordEngine.getConfidenceThreshold(),
                            onWakeSensitivityChange = { threshold ->
                                voiceManager.wakeWordEngine.setSensitivity(1.0f - (threshold - 0.50f))
                            },
                            onEnrollWakePhrase = { phrase ->
                                voiceManager.wakeWordEngine.recordOrEnrollPhraseFromUi(phrase)
                            },
                            onSaveAssistantName = { newName ->
                                val cleanName = newName.trim().ifEmpty { "Babu" }
                                assistantName = cleanName
                                permissionSetupManager.saveAssistantName(cleanName)
                                agentRuntime.setAssistantIdentity(cleanName)
                                voiceManager.updateAssistantIdentity(cleanName)
                            },
                            onAddCustomWakePhrase = { customPhrase ->
                                val cleanPhrase = customPhrase.trim()
                                if (cleanPhrase.isNotBlank()) {
                                    val updatedPhrases = (voiceConfig.wakePhrases + cleanPhrase).distinct()
                                    voiceManager.updateConfiguration(voiceConfig.copy(wakePhrases = updatedPhrases))
                                }
                            }
                        )
                    }

                    Screen.ToolsCatalog -> com.example.ui.tools.ToolsCatalogScreen()

                    Screen.SkillsHub -> com.example.ui.skills.SkillsHubScreen(
                        skillManager = (context.applicationContext as NayroApplication).skillManager,
                        onExecuteSkill = { skillId ->
                            processUserPrompt("Execute procedural skill: $skillId")
                        }
                    )

                    Screen.SubAgents -> com.example.ui.subagent.SubAgentManagerScreen(
                        subAgentManager = (context.applicationContext as NayroApplication).subAgentManager,
                        onDelegate = { agentType, subGoal ->
                            val app = (context.applicationContext as NayroApplication)
                            app.subAgentManager.delegateTask(agentType, subGoal)
                            processUserPrompt("Delegate sub-task to ${agentType.displayName}: $subGoal")
                        }
                    )

                    Screen.Workspace -> com.example.ui.workspace.WorkspaceScreen(
                        workspaceManager = (context.applicationContext as NayroApplication).workspaceManager
                    )

                    Screen.ResearchAgent -> com.example.ui.research.ResearchAgentScreen(
                        providerManager = providerManager,
                        workspaceManager = (context.applicationContext as NayroApplication).workspaceManager
                    )

                    Screen.AgentDashboard -> com.example.ui.dashboard.AgentDashboardScreen(
                        agentRuntime = agentRuntime,
                        onExecuteInstruction = { cmd ->
                            processUserPrompt(cmd)
                        }
                    )

                    Screen.Onboarding -> OnboardingScreen(
                        evaluations = evaluations,
                        initialAssistantName = assistantName,
                        isForegroundRunning = foregroundStatus.state == ServiceLifecycleState.RUNNING,
                        isFloatingRunning = floatingStatus.state == ServiceLifecycleState.RUNNING,
                        onRequestRuntimePermission = onRequestRuntimePermission,
                        onOpenPermissionSettings = onOpenPermissionSettings,
                        onRefreshPermissions = onRefreshPermissions,
                        onStartForegroundService = onStartForegroundService,
                        onStartFloatingService = onStartFloatingService,
                        onCompleteOnboarding = { name ->
                            val cleanName = name.trim().ifEmpty { "Babu" }
                            assistantName = cleanName
                            permissionSetupManager.markSetupCompleted(cleanName)
                            agentRuntime.setAssistantIdentity(cleanName)
                            voiceManager.updateAssistantIdentity(cleanName)
                            currentScreen = Screen.Home
                        }
                    )
                }

                pendingConfirmation?.let { conf ->
                    com.example.ui.components.ConfirmationDialog(
                        confirmation = conf,
                        onConfirm = { agentRuntime.confirmationManager.approve(conf.actionId, conf.parameters) },
                        onReject = { agentRuntime.confirmationManager.reject(conf.actionId) }
                    )
                }
            }
        }
    }
}
