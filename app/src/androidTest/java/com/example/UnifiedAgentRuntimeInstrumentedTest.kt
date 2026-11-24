package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.voice.SpeechSynthesisEngineType
import com.example.data.local.entities.MemoryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android-runtime smoke coverage. This verifies Application wiring and durable Room memory
 * operations on an emulator/device; it intentionally does not grant mic/accessibility permissions
 * or trigger real phone, SMS, WhatsApp, or external-app side effects.
 */
@RunWith(AndroidJUnit4::class)
class UnifiedAgentRuntimeInstrumentedTest {

    @Test
    fun applicationWiresOneRuntimeAndPersistsMemoryWithoutImplicitCloudSetup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as NayroApplication

        assertNotNull(app.agentRuntime)
        assertSame("AgentBrain must be owned by the single AgentRuntime", app.agentRuntime.agentBrain, app.agentBrain)
        assertNotNull(app.agentRuntime.actionExecutor)
        assertNotNull(app.memoryManager)
        assertNotNull(app.memorySyncManager)
        assertNotNull(app.voiceManager)
        assertNotNull(app.backgroundVoiceController)

        val uniqueKey = "instrumented_test_${System.currentTimeMillis()}"
        val memory = MemoryEntity(
            category = "TEST",
            keyName = uniqueKey,
            memoryValue = "Room persistence integration check",
            userConfirmed = true
        )
        app.repository.upsertMemory(memory)
        val stored = app.repository.memories.first().firstOrNull { it.keyName == uniqueKey }

        assertNotNull("Memory should be persisted via the Room repository", stored)
        assertEquals("Room persistence integration check", stored?.memoryValue)
        stored?.let { app.repository.deleteMemory(it.id) }

        assertFalse("Cloud sync must remain disabled until the user configures an endpoint/token", app.memorySyncManager.isConfigured())
        assertEquals(
            "Native TTS is the safe default before cloud credentials are configured",
            SpeechSynthesisEngineType.ANDROID_TTS,
            app.voiceManager.voiceProviderRouter.status.value.selectedTtsEngine
        )
        assertFalse("Wake-word capture must not start automatically without user opt-in", app.voiceManager.wakeWordEngine.isAudioCaptureActive.value)
    }
}
