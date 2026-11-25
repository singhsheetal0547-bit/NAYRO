package com.example

import android.app.Application
import com.example.core.agent.AgentBrain
import com.example.core.agent.AgentRuntime
import com.example.core.agent.SelfImprovementEngine
import com.example.core.automation.AutomationEngine
import com.example.core.capability.CapabilityManager
import com.example.core.memory.ConversationContinuityManager
import com.example.core.memory.MemoryManager
import com.example.core.memory.NotebookManager
import com.example.core.network.NetworkConnectivityMonitor
import com.example.core.permission.PermissionManager
import com.example.core.permission.PermissionSetupManager
import com.example.core.proactive.ProactiveIntelligenceEngine
import com.example.core.provider.AIProviderManager
import com.example.core.screen.ScreenIntelligenceManager
import com.example.core.security.AutonomyPolicyManager
import com.example.core.security.EncryptedStorageManager
import com.example.core.service.BackgroundAgentHealthManager
import com.example.core.service.DeviceOperatingStateManager
import com.example.core.service.VoiceAgentServiceWrapper
import com.example.core.skills.SkillManager
import com.example.core.subagent.SubAgentManager
import com.example.core.telephony.CallIntelligenceManager
import com.example.core.voice.VoiceManager
import com.example.core.workflow.CrossAppWorkflowEngine
import com.example.core.workspace.WorkspaceManager
import com.example.data.local.NayroDatabase
import com.example.data.repository.NayroRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Global NAYRO Application Container.
 * Holds Application-scoped singletons for background services, agent runtime,
 * health manager, and voice intelligence independent of Activity lifecycle.
 */
class NayroApplication : com.advanceagent.core.AgentApplication() {

    companion object {
        lateinit var instance: NayroApplication
            private set
    }

    lateinit var database: NayroDatabase
        private set
    lateinit var repository: NayroRepository
        private set
    lateinit var permissionManager: PermissionManager
        private set
    lateinit var permissionSetupManager: PermissionSetupManager
        private set
    lateinit var capabilityManager: CapabilityManager
        private set
    lateinit var encryptedStorageManager: EncryptedStorageManager
        private set
    lateinit var networkConnectivityMonitor: NetworkConnectivityMonitor
        private set
    lateinit var providerManager: AIProviderManager
        private set
    lateinit var memoryManager: MemoryManager
        private set
    lateinit var notebookManager: NotebookManager
        private set
    lateinit var workspaceManager: WorkspaceManager
        private set
    lateinit var skillManager: SkillManager
        private set
    lateinit var subAgentManager: SubAgentManager
        private set
    lateinit var callIntelligenceManager: CallIntelligenceManager
        private set
    lateinit var autonomyPolicyManager: AutonomyPolicyManager
        private set
    lateinit var continuityManager: ConversationContinuityManager
        private set
    lateinit var workflowEngine: CrossAppWorkflowEngine
        private set
    lateinit var proactiveEngine: ProactiveIntelligenceEngine
        private set
    lateinit var automationEngine: AutomationEngine
        private set
    lateinit var operatingStateManager: DeviceOperatingStateManager
        private set
    lateinit var selfImprovementEngine: SelfImprovementEngine
        private set
    lateinit var agentRuntime: AgentRuntime
        private set
    lateinit var agentBrain: AgentBrain
        private set
    lateinit var assistantIdentityStore: com.example.core.identity.AssistantIdentityStore
        private set
    lateinit var voiceManager: VoiceManager
        private set
    lateinit var backgroundVoiceController: com.example.core.voice.BackgroundVoiceController
        private set
    lateinit var voiceAgentServiceWrapper: VoiceAgentServiceWrapper
        private set
    lateinit var healthManager: BackgroundAgentHealthManager
        private set

    private val applicationScope = CoroutineScope(Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = NayroDatabase.getDatabase(this)
        repository = NayroRepository(database)
        assistantIdentityStore = com.example.core.identity.AssistantIdentityStore(this)
        permissionManager = PermissionManager(this)
        permissionSetupManager = PermissionSetupManager(this, permissionManager)
        encryptedStorageManager = EncryptedStorageManager(this)
        networkConnectivityMonitor = NetworkConnectivityMonitor(this).also { it.startMonitoring() }
        providerManager = AIProviderManager(encryptedStorageManager, networkConnectivityMonitor)
        capabilityManager = CapabilityManager(this, permissionManager, providerManager)

        val screenIntelligenceManager = ScreenIntelligenceManager(this)
        memoryManager = MemoryManager(repository, encryptedStorageManager)
        selfImprovementEngine = SelfImprovementEngine(encryptedStorageManager)
        notebookManager = NotebookManager(repository)
        workspaceManager = WorkspaceManager(this)
        skillManager = SkillManager()
        subAgentManager = SubAgentManager()
        callIntelligenceManager = CallIntelligenceManager(this)
        autonomyPolicyManager = AutonomyPolicyManager()
        continuityManager = ConversationContinuityManager()
        workflowEngine = CrossAppWorkflowEngine()
        proactiveEngine = ProactiveIntelligenceEngine()
        automationEngine = AutomationEngine(this)
        operatingStateManager = DeviceOperatingStateManager(this)

        agentRuntime = AgentRuntime(
            context = this,
            repository = repository,
            permissionManager = permissionManager,
            capabilityManager = capabilityManager,
            screenIntelligenceManager = screenIntelligenceManager,
            memoryManager = memoryManager,
            notebookManager = notebookManager,
            providerManager = providerManager,
            workspaceManager = workspaceManager,
            skillManager = skillManager,
            subAgentManager = subAgentManager,
            callIntelligenceManager = callIntelligenceManager,
            autonomyPolicyManager = autonomyPolicyManager,
            continuityManager = continuityManager,
            workflowEngine = workflowEngine,
            proactiveEngine = proactiveEngine,
            automationEngine = automationEngine,
            operatingStateManager = operatingStateManager,
            selfImprovementEngine = selfImprovementEngine
        )

        agentBrain = agentRuntime.agentBrain
        agentRuntime.setAssistantIdentity(assistantIdentityStore.getDisplayName())

        voiceManager = VoiceManager(this, encryptedStorageManager)
        voiceManager.bindIdentityStore(assistantIdentityStore)
        backgroundVoiceController = com.example.core.voice.BackgroundVoiceController(
            context = this,
            identityStore = assistantIdentityStore,
            permissionManager = permissionManager,
            voiceManager = voiceManager
        )
        voiceAgentServiceWrapper = VoiceAgentServiceWrapper(
            appContext = this,
            permissionManager = permissionManager,
            voiceManager = voiceManager,
            backgroundVoiceController = backgroundVoiceController,
            agentRuntime = agentRuntime,
            repository = repository,
            identityStore = assistantIdentityStore,
            providerManager = providerManager
        )
        healthManager = BackgroundAgentHealthManager(this, permissionManager, capabilityManager, voiceManager)

        // Section 10 — Connect real incoming notifications to AutomationEngine notification watch rules
        com.example.core.notification.NotificationIntelligenceRepository.onNotificationCapturedTrigger = { item ->
            if (!com.example.core.service.ServiceStateRepository.isBackgroundAgentPaused.value) {
                applicationScope.launch {
                    val eventPayload = "${item.packageName} ${item.title} ${item.text}"
                    automationEngine.onEventTriggered(
                        triggerType = com.example.core.automation.AutomationTriggerType.NOTIFICATION_ARRIVED,
                        eventValue = eventPayload
                    ) { instruction ->
                        val out = agentRuntime.executeUserInstruction(instruction)
                        val failed = out.startsWith("Failed", ignoreCase = true) ||
                            out.startsWith("Cannot", ignoreCase = true) ||
                            out.contains("error", ignoreCase = true)
                        !failed to out
                    }
                }
            }
        }

        // Hydrate persistent Room conversation/memory history and recover interrupted tasks on boot
        applicationScope.launch {
            agentRuntime.hydrateMemoryAndConversationHistoryFromStorage()
            agentRuntime.recoverInterruptedTasksOnBoot()
        }
    }
}
