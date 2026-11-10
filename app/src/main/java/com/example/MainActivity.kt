package com.example

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
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
import com.example.core.permission.PermissionType
import com.example.core.provider.AIProviderManager
import com.example.core.provider.ProviderConfig
import com.example.core.provider.ProviderType
import com.example.core.provider.model.AIChatMessage
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
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.NayroTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var database: NayroDatabase
    private lateinit var repository: NayroRepository
    private lateinit var permissionManager: PermissionManager
    private lateinit var capabilityManager: CapabilityManager
    private lateinit var encryptedStorageManager: EncryptedStorageManager
    private lateinit var agentRuntime: AgentRuntime
    private lateinit var voiceManager: VoiceManager
    private lateinit var providerManager: AIProviderManager

    // Holds pending prompt delivered from floating assistant
    private var pendingFloatingPrompt: String? = null

    // Single permission request launcher
    @Suppress("InvalidFragmentVersionForActivityResult")
    private val requestSinglePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        permissionManager.refreshAllPermissions(this)
        capabilityManager.evaluateAll()
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
        capabilityManager = app.capabilityManager
        encryptedStorageManager = app.encryptedStorageManager
        providerManager = app.providerManager
        agentRuntime = app.agentRuntime
        voiceManager = app.voiceManager
        val notebookManager = app.notebookManager

        setContent {
            NayroTheme {
                NayroAppContent(
                    repository = repository,
                    permissionManager = permissionManager,
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
                            permissionManager.markRuntimePermissionRequested(permStr)
                            requestSinglePermissionLauncher.launch(permStr)
                        }
                    },
                    onOpenPermissionSettings = { type ->
                        permissionManager.createSettingsIntent(type)?.let { intent ->
                            startActivity(intent)
                        }
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
        permissionManager.refreshAllPermissions(this)
        capabilityManager.evaluateAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceManager.onActivityDestroyed()
    }
}

@Composable
fun NayroAppContent(
    repository: NayroRepository,
    permissionManager: PermissionManager,
    capabilityManager: CapabilityManager,
    encryptedStorageManager: EncryptedStorageManager,
    agentRuntime: AgentRuntime,
    voiceManager: VoiceManager,
    providerManager: AIProviderManager,
    notebookManager: com.example.core.memory.NotebookManager,
    initialPendingPrompt: String?,
    onClearPendingPrompt: () -> Unit,
    onRequestRuntimePermission: (PermissionType) -> Unit,
    onOpenPermissionSettings: (PermissionType) -> Unit,
    onStartForegroundService: () -> Unit,
    onStopForegroundService: () -> Unit,
    onStartFloatingService: () -> Unit,
    onStopFloatingService: () -> Unit
) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
    var assistantName by remember { mutableStateOf("Babu") }
    var showOnboarding by remember { mutableStateOf(false) }

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

    val foregroundStatus by ServiceStateRepository.foregroundServiceStatus.collectAsState()
    val floatingStatus by ServiceStateRepository.floatingServiceStatus.collectAsState()

    // Unified dispatch function: executes user request through AgentRuntime orchestration
    fun processUserPrompt(promptText: String) {
        val text = promptText.trim()
        if (text.isEmpty()) return

        coroutineScope.launch {
            // Save User message
            repository.addConversationMessage(
                ConversationEntity(sender = "user", content = text)
            )

            // Execute full Agentic Workflow: Planning, Permissions, Execution, Verification
            val reply = agentRuntime.executeUserInstruction(text)

            // Save Babu verified response
            repository.addConversationMessage(
                ConversationEntity(sender = "babu", content = reply)
            )
            agentRuntime.setState(AgentState.Speaking(reply))

            // Speak response via TTS if configured
            if (voiceConfig.isWakeWordEnabled || voiceState == VoiceState.SPEAKING) {
                voiceManager.speak(reply)
            }
        }
    }

    // Handle Speech Recognition callback
    LaunchedEffect(Unit) {
        voiceManager.onSpeechRecognized = { recognizedText ->
            processUserPrompt(recognizedText)
        }
        voiceManager.onSpeechError = { errorMsg ->
            agentRuntime.setState(AgentState.Error(errorMsg))
        }
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
            onCompleteOnboarding = { name ->
                assistantName = name
                showOnboarding = false
                currentScreen = Screen.Home
            }
        )
    } else {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                NayroTopBar(currentScreen = currentScreen)
            },
            bottomBar = {
                NayroBottomBar(
                    currentRoute = currentScreen.route,
                    onNavigate = { screen -> currentScreen = screen }
                )
            }
        ) { innerPadding ->
            val modifier = Modifier.padding(innerPadding)

            when (currentScreen) {
                Screen.Home -> HomeScreen(
                    assistantName = assistantName,
                    agentState = agentState,
                    currentTask = currentTask,
                    isForegroundActive = foregroundStatus.state == ServiceLifecycleState.RUNNING,
                    isFloatingActive = floatingStatus.state == ServiceLifecycleState.RUNNING,
                    isAiReady = activeProvider != null,
                    onVoiceTap = {
                        if (voiceState == VoiceState.LISTENING) {
                            voiceManager.stopListening()
                        } else {
                            if (!permissionManager.isMicrophoneGranted()) {
                                onRequestRuntimePermission(PermissionType.MICROPHONE)
                            } else {
                                voiceManager.startListening()
                            }
                        }
                    },
                    onSendText = { text ->
                        processUserPrompt(text)
                    },
                    onCancelTask = {
                        agentRuntime.cancelCurrentTask()
                    },
                    onQuickActionClick = { tag ->
                        when (tag) {
                            "action_background" -> currentScreen = Screen.BackgroundControl
                            "action_perm" -> currentScreen = Screen.PermissionAccess
                            "action_caps" -> currentScreen = Screen.Capabilities
                            "action_api" -> currentScreen = Screen.Settings
                            "action_memory" -> currentScreen = Screen.Memory
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
                    }
                )

                Screen.PermissionAccess -> PermissionAccessScreen(
                    evaluations = evaluations,
                    onRequestRuntimePermission = onRequestRuntimePermission,
                    onOpenSystemSettings = onOpenPermissionSettings
                )

                Screen.Capabilities -> CapabilitiesScreen(
                    capabilities = capabilities,
                    onActionClick = { capId, label ->
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
                    conversations = conversations,
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

                Screen.Settings -> SettingsScreen(
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
                            val key = encryptedStorageManager.getApiKey(providerId)
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
                    }
                )

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

                Screen.Onboarding -> OnboardingScreen(
                    onCompleteOnboarding = { name ->
                        assistantName = name
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
