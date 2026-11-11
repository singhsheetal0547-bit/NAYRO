package com.example

import android.app.Application
import com.example.core.agent.AgentBrain
import com.example.core.agent.AgentRuntime
import com.example.core.automation.AutomationEngine
import com.example.core.capability.CapabilityManager
import com.example.core.memory.ConversationContinuityManager
import com.example.core.memory.MemoryManager
import com.example.core.memory.NotebookManager
import com.example.core.permission.PermissionManager
import com.example.core.permission.PermissionSetupManager
import com.example.core.proactive.ProactiveIntelligenceEngine
import com.example.core.provider.AIProviderManager
import com.example.core.screen.ScreenIntelligenceManager
import com.example.core.security.AutonomyPolicyManager
import com.example.core.security.EncryptedStorageManager
import com.example.core.service.BackgroundAgentHealthManager
import com.example.core.service.DeviceOperatingStateManager
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
class NayroApplication : Application() {

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
    lateinit var agentRuntime: AgentRuntime
        private set
    lateinit var agentBrain: AgentBrain
        private set
    lateinit var voiceManager: VoiceManager
        private set
    lateinit var healthManager: BackgroundAgentHealthManager
        private set

    private val applicationScope = CoroutineScope(Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = NayroDatabase.getDatabase(this)
        repository = NayroRepository(database)
        permissionManager = PermissionManager(this)
        permissionSetupManager = PermissionSetupManager(this, permissionManager)
        encryptedStorageManager = EncryptedStorageManager(this)
        providerManager = AIProviderManager(encryptedStorageManager)
        capabilityManager = CapabilityManager(this, permissionManager, providerManager)

        val screenIntelligenceManager = ScreenIntelligenceManager(this)
        memoryManager = MemoryManager(repository)
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
            operatingStateManager = operatingStateManager
        )

        agentBrain = agentRuntime.agentBrain

        voiceManager = VoiceManager(this)
        healthManager = BackgroundAgentHealthManager(this, permissionManager, capabilityManager, voiceManager)

        // Attempt Task Continuity recovery on application boot
        applicationScope.launch {
            agentRuntime.recoverInterruptedTasksOnBoot()
        }
    }
}
