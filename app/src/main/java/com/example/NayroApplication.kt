package com.example

import android.app.Application
import com.example.core.screen.ScreenIntelligenceManager
import com.example.core.agent.AgentRuntime
import com.example.core.capability.CapabilityManager
import com.example.core.memory.MemoryManager
import com.example.core.memory.NotebookManager
import com.example.core.permission.PermissionManager
import com.example.core.provider.AIProviderManager
import com.example.core.security.EncryptedStorageManager
import com.example.core.service.BackgroundAgentHealthManager
import com.example.core.voice.VoiceManager
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
    lateinit var agentRuntime: AgentRuntime
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
        encryptedStorageManager = EncryptedStorageManager(this)
        providerManager = AIProviderManager(encryptedStorageManager)
        capabilityManager = CapabilityManager(this, permissionManager, providerManager)

        val screenIntelligenceManager = ScreenIntelligenceManager(this)
        memoryManager = MemoryManager(repository)
        notebookManager = NotebookManager(repository)

        agentRuntime = AgentRuntime(
            context = this,
            repository = repository,
            permissionManager = permissionManager,
            capabilityManager = capabilityManager,
            screenIntelligenceManager = screenIntelligenceManager,
            memoryManager = memoryManager,
            notebookManager = notebookManager,
            providerManager = providerManager
        )

        voiceManager = VoiceManager(this)
        healthManager = BackgroundAgentHealthManager(this, permissionManager, capabilityManager, voiceManager)

        // Attempt Task Continuity recovery on application boot
        applicationScope.launch {
            agentRuntime.recoverInterruptedTasksOnBoot()
        }
    }
}
