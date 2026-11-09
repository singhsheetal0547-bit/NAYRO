package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
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
import com.example.core.security.EncryptedStorageManager
import com.example.core.service.NayroForegroundService
import com.example.core.voice.VoiceManager
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

    // Single permission request launcher
    private val requestSinglePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        permissionManager.refreshAllPermissions(this)
        capabilityManager.evaluateAll()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Core Engines
        database = NayroDatabase.getDatabase(this)
        repository = NayroRepository(database)
        permissionManager = PermissionManager(this)
        capabilityManager = CapabilityManager(this, permissionManager)
        encryptedStorageManager = EncryptedStorageManager(this)
        agentRuntime = AgentRuntime()
        voiceManager = VoiceManager(this)
        providerManager = AIProviderManager()

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
                        capabilityManager.updateServiceStates(
                            foregroundRunning = true,
                            floatingRunning = NayroFloatingService.isRunning.value
                        )
                    },
                    onStopForegroundService = {
                        NayroForegroundService.stopService(this)
                        capabilityManager.updateServiceStates(
                            foregroundRunning = false,
                            floatingRunning = NayroFloatingService.isRunning.value
                        )
                    },
                    onStartFloatingService = {
                        NayroFloatingService.startService(this)
                        capabilityManager.updateServiceStates(
                            foregroundRunning = NayroForegroundService.isRunning.value,
                            floatingRunning = true
                        )
                    },
                    onStopFloatingService = {
                        NayroFloatingService.stopService(this)
                        capabilityManager.updateServiceStates(
                            foregroundRunning = NayroForegroundService.isRunning.value,
                            floatingRunning = false
                        )
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionManager.refreshAllPermissions(this)
        capabilityManager.updateServiceStates(
            foregroundRunning = NayroForegroundService.isRunning.value,
            floatingRunning = NayroFloatingService.isRunning.value
        )
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

    val coroutineScope = rememberCoroutineScope()
    val conversations by repository.conversations.collectAsState(initial = emptyList())
    val memories by repository.memories.collectAsState(initial = emptyList())
    val auditLogs by repository.auditLogs.collectAsState(initial = emptyList())

    val evaluations by permissionManager.evaluations.collectAsState()
    val capabilities by capabilityManager.capabilities.collectAsState()

    val agentState by agentRuntime.currentState.collectAsState()
    val activeProvider by providerManager.activeProvider.collectAsState()
    val voiceConfig by voiceManager.configuration.collectAsState()

    val isForegroundRunning by NayroForegroundService.isRunning.collectAsState()
    val isFloatingRunning by NayroFloatingService.isRunning.collectAsState()

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
                    isForegroundActive = isForegroundRunning,
                    isFloatingActive = isFloatingRunning,
                    isAiReady = activeProvider != null,
                    onVoiceTap = {
                        if (agentState is AgentState.UserListening) {
                            agentRuntime.setState(AgentState.Idle)
                        } else {
                            agentRuntime.setState(AgentState.UserListening)
                        }
                    },
                    onSendText = { text ->
                        coroutineScope.launch {
                            repository.addConversationMessage(
                                ConversationEntity(sender = "user", content = text)
                            )
                            agentRuntime.submitUserRequest(text)
                            repository.addConversationMessage(
                                ConversationEntity(
                                    sender = "babu",
                                    content = "Namaste! I am Babu. I received your message: \"$text\". AI provider setup can be configured in Settings."
                                )
                            )
                            agentRuntime.setState(AgentState.Idle)
                        }
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
                    isForegroundRunning = isForegroundRunning,
                    isFloatingRunning = isFloatingRunning,
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
                                } else if (isFloatingRunning) {
                                    onStopFloatingService()
                                } else {
                                    onStartFloatingService()
                                }
                            }
                            CapabilityId.BACKGROUND_AGENT -> {
                                if (isForegroundRunning) {
                                    onStopForegroundService()
                                } else {
                                    onStartForegroundService()
                                }
                            }
                            CapabilityId.MICROPHONE_ACCESS -> {
                                onRequestRuntimePermission(PermissionType.MICROPHONE)
                            }
                            CapabilityId.NOTIFICATION_ACCESS -> {
                                onRequestRuntimePermission(PermissionType.NOTIFICATIONS)
                            }
                            CapabilityId.ACCESSIBILITY -> {
                                onOpenPermissionSettings(PermissionType.ACCESSIBILITY)
                            }
                            else -> {}
                        }
                    }
                )

                Screen.Chat -> ConversationScreen(
                    conversations = conversations,
                    onSendMessage = { text ->
                        coroutineScope.launch {
                            repository.addConversationMessage(
                                ConversationEntity(sender = "user", content = text)
                            )
                            repository.addConversationMessage(
                                ConversationEntity(
                                    sender = "babu",
                                    content = "Babu received: \"$text\"."
                                )
                            )
                        }
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
                    onDeleteMemory = { id ->
                        coroutineScope.launch {
                            repository.deleteMemory(id)
                        }
                    }
                )

                Screen.Settings -> SettingsScreen(
                    assistantName = assistantName,
                    isWakeEnabled = voiceConfig.isWakeWordEnabled,
                    onSaveApiKey = { providerId, apiKey ->
                        encryptedStorageManager.saveApiKey(providerId, apiKey)
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
                    onToggleWake = { enabled ->
                        voiceManager.updateConfiguration(voiceConfig.copy(isWakeWordEnabled = enabled))
                    }
                )

                Screen.Onboarding -> OnboardingScreen(
                    onCompleteOnboarding = { name ->
                        assistantName = name
                        currentScreen = Screen.Home
                    }
                )
            }
        }
    }
}
