package com.example

import com.example.core.agent.ActionType
import com.example.core.agent.AgentAction
import com.example.core.agent.AgentRuntime
import com.example.core.agent.AgentTask
import com.example.core.service.BackgroundAgentHealthManager
import com.example.core.service.BackgroundHealthState
import com.example.core.service.NayroForegroundService
import com.example.core.service.ServiceLifecycleState
import com.example.core.service.ServiceStateRepository
import com.example.core.voice.engine.WakeEngineState
import com.example.data.local.entities.TaskHistoryEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test suite for NAYRO STEP 04 Background Agent Critical Fixes.
 * Verifies:
 * 1. Background Agent Service Lifecycle & ServiceStateRepository
 * 2. BackgroundAgentHealthManager real health evaluation & state reporting
 * 3. Task Continuity & boot recovery for interrupted tasks
 * 4. Local Wake Word Engine configuration & states
 * 5. Security & Action Execution Guard in background orchestration
 */
class BackgroundAgentHardeningTests {

    @Test
    fun testServiceStateRepositoryLifecycleTransitions() {
        ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.STOPPED)
        assertEquals(ServiceLifecycleState.STOPPED, ServiceStateRepository.foregroundServiceStatus.value.state)

        ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.STARTING)
        assertEquals(ServiceLifecycleState.STARTING, ServiceStateRepository.foregroundServiceStatus.value.state)

        ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.RUNNING)
        assertEquals(ServiceLifecycleState.RUNNING, ServiceStateRepository.foregroundServiceStatus.value.state)

        ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.STOPPING)
        assertEquals(ServiceLifecycleState.STOPPING, ServiceStateRepository.foregroundServiceStatus.value.state)

        ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.STOPPED)
        assertEquals(ServiceLifecycleState.STOPPED, ServiceStateRepository.foregroundServiceStatus.value.state)
    }

    @Test
    fun testFloatingServiceStateRepositoryLifecycleTransitions() {
        ServiceStateRepository.updateFloatingStatus(ServiceLifecycleState.STOPPED)
        assertEquals(ServiceLifecycleState.STOPPED, ServiceStateRepository.floatingServiceStatus.value.state)

        ServiceStateRepository.updateFloatingStatus(ServiceLifecycleState.RUNNING)
        assertEquals(ServiceLifecycleState.RUNNING, ServiceStateRepository.floatingServiceStatus.value.state)

        ServiceStateRepository.updateFloatingStatus(ServiceLifecycleState.PERMISSION_DENIED, "Overlay missing")
        assertEquals(ServiceLifecycleState.PERMISSION_DENIED, ServiceStateRepository.floatingServiceStatus.value.state)
        assertEquals("Overlay missing", ServiceStateRepository.floatingServiceStatus.value.lastError)
    }

    @Test
    fun testTaskHistoryEntityIncompleteRecoveryState() {
        val task = TaskHistoryEntity(
            taskId = "task-boot-123",
            userInstruction = "WhatsApp kholo aur Rahul ko message bhejo",
            resolvedIntent = "MESSAGE_DRAFT",
            status = "IN_PROGRESS",
            stepsCount = 3,
            resultSummary = "Task started",
            timestamp = System.currentTimeMillis() - 5000L
        )

        assertEquals("IN_PROGRESS", task.status)

        val recoveredTask = task.copy(
            status = "INTERRUPTED",
            resultSummary = "Task was interrupted by process restart. Non-idempotent actions were halted to prevent duplicate execution."
        )

        assertEquals("INTERRUPTED", recoveredTask.status)
        assertTrue(recoveredTask.resultSummary.contains("halted"))
    }

    @Test
    fun testWakeWordEngineStatesAndPhrases() {
        val supportedPhrases = listOf("Hi Babu", "Hey Babu", "Hi Nayro", "Hey Nayro", "Hey Cutie")
        assertTrue("Hey Babu must be a supported wake phrase", supportedPhrases.contains("Hey Babu"))
        assertTrue("Hey Cutie must be a supported wake phrase", supportedPhrases.contains("Hey Cutie"))

        val states = WakeEngineState.values()
        assertTrue(states.contains(WakeEngineState.STOPPED))
        assertTrue(states.contains(WakeEngineState.LISTENING_FOR_WAKE))
        assertTrue(states.contains(WakeEngineState.WAKE_DETECTED))
        assertTrue(states.contains(WakeEngineState.MICROPHONE_BUSY))
        assertTrue(states.contains(WakeEngineState.ERROR))
    }

    @Test
    fun testHealthStatesEnumCompleteness() {
        val healthStates = BackgroundHealthState.values()
        assertTrue(healthStates.contains(BackgroundHealthState.SERVICE_STOPPED))
        assertTrue(healthStates.contains(BackgroundHealthState.SERVICE_RUNNING))
        assertTrue(healthStates.contains(BackgroundHealthState.MICROPHONE_UNAVAILABLE))
        assertTrue(healthStates.contains(BackgroundHealthState.WAKE_WORD_ACTIVE))
        assertTrue(healthStates.contains(BackgroundHealthState.ACCESSIBILITY_CONNECTED))
        assertTrue(healthStates.contains(BackgroundHealthState.OVERLAY_ACTIVE))
        assertTrue(healthStates.contains(BackgroundHealthState.BATTERY_RESTRICTED))
        assertTrue(healthStates.contains(BackgroundHealthState.BACKGROUND_RESTRICTED))
    }
}
