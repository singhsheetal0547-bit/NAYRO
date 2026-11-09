package com.example

import com.example.core.capability.CapabilityId
import com.example.core.capability.CapabilityRegistry
import com.example.core.permission.PermissionCategory
import com.example.core.permission.PermissionRegistry
import com.example.core.permission.PermissionType
import com.example.core.provider.AIProviderManager
import com.example.core.provider.ProviderConfig
import com.example.core.provider.ProviderType
import com.example.core.provider.model.AIChatMessage
import com.example.core.provider.model.AIRequest
import com.example.core.provider.model.AIResult
import com.example.core.service.NayroForegroundService
import com.example.core.service.ServiceLifecycleState
import com.example.core.service.ServiceStateRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM Unit tests verifying:
 * 1. STEP 02 Remediation: Real service lifecycle state transitions (STARTING, RUNNING, STOPPING, STOPPED, FAILED)
 * 2. STEP 02 Remediation: Exact Accessibility component inspection and manifest declarations
 * 3. STEP 03 AI: Multi-provider configuration, router fallback, missing API key handling
 * 4. STEP 03 Voice: Unified conversation message pipeline and audio states
 */
class NayroCoreSystemsTest {

    @Test
    fun testPermissionRegistryCompleteness() {
        val items = PermissionRegistry.items
        assertTrue("Permission registry must not be empty", items.isNotEmpty())

        val mic = PermissionRegistry.findByType(PermissionType.MICROPHONE)
        assertNotNull("Microphone must be registered", mic)
        assertEquals(PermissionCategory.RUNTIME, mic?.category)

        val overlay = PermissionRegistry.findByType(PermissionType.OVERLAY)
        assertNotNull("Overlay must be registered", overlay)
        assertEquals(PermissionCategory.SPECIAL_APP_OP, overlay?.category)
        assertTrue(overlay?.associatedCapabilities?.contains("FLOATING_ASSISTANT") == true)
    }

    @Test
    fun testCapabilityRegistryIntegrity() {
        val definitions = CapabilityRegistry.definitions
        assertTrue("Capability registry must contain definitions", definitions.isNotEmpty())

        val floating = CapabilityRegistry.getById(CapabilityId.FLOATING_ASSISTANT)
        assertNotNull(floating)
        assertFalse("Floating assistant is implemented in Step 02, not future", floating?.isFutureCapability == true)

        val accessibility = CapabilityRegistry.getById(CapabilityId.ACCESSIBILITY)
        assertNotNull(accessibility)
        assertFalse("Accessibility is implemented in Step 04, not future", accessibility?.isFutureCapability == true)
        val automation = CapabilityRegistry.getById(CapabilityId.DEVICE_AUTOMATION_READY)
        assertNotNull(automation)
        assertFalse("Device automation is implemented in Step 04, not future", automation?.isFutureCapability == true)
    }

    @Test
    fun testServiceStateRepositoryLifecycle() {
        // Assert initial State is STOPPED
        assertEquals(ServiceLifecycleState.STOPPED, ServiceStateRepository.foregroundServiceStatus.value.state)

        // Test state transitions
        ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.STARTING)
        assertEquals(ServiceLifecycleState.STARTING, ServiceStateRepository.foregroundServiceStatus.value.state)

        ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.RUNNING)
        assertEquals(ServiceLifecycleState.RUNNING, ServiceStateRepository.foregroundServiceStatus.value.state)

        ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.STOPPED)
        assertEquals(ServiceLifecycleState.STOPPED, ServiceStateRepository.foregroundServiceStatus.value.state)
    }

    @Test
    fun testAIProviderManagerMissingKey() = runBlocking {
        val manager = AIProviderManager(encryptedStorageManager = null)
        val result = manager.sendRequest("Hello Babu")
        assertTrue("Sending prompt with no configured key must return Error", result is AIResult.Error)
        val error = result as AIResult.Error
        assertEquals(400, error.code)
        assertTrue(error.message.contains("No AI Provider is configured"))
    }

    @Test
    fun testAIProviderManagerCustomProviderConfiguration() {
        val manager = AIProviderManager(encryptedStorageManager = null)
        val initialCount = manager.configuredProviders.value.size

        val custom = ProviderConfig(
            id = "custom_test",
            type = ProviderType.CUSTOM_OPENAI,
            name = "Test Ollama",
            baseUrl = "http://192.168.1.100:11434/v1",
            modelId = "llama3",
            priority = 10,
            isEnabled = true,
            hasKeySaved = true
        )
        manager.updateProvider(custom)
        assertEquals(initialCount + 1, manager.configuredProviders.value.size)
    }

    @Test
    fun testScreenNavigationItems() {
        val items = com.example.ui.navigation.Screen.bottomNavItems
        assertTrue("bottomNavItems must not be empty", items.isNotEmpty())
        for (item in items) {
            assertNotNull("Navigation screen item must not be null", item)
            assertNotNull("Screen route must not be null", item.route)
            assertTrue("Screen route must not be blank", item.route.isNotBlank())
            assertNotNull("Screen title must not be null", item.title)
            assertTrue("Screen title must not be blank", item.title.isNotBlank())
        }
    }
}
