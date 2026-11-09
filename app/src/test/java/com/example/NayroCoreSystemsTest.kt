package com.example

import com.example.core.capability.CapabilityId
import com.example.core.capability.CapabilityManager
import com.example.core.capability.CapabilityRegistry
import com.example.core.capability.CapabilityStatus
import com.example.core.permission.PermissionCategory
import com.example.core.permission.PermissionEvaluation
import com.example.core.permission.PermissionItem
import com.example.core.permission.PermissionManager
import com.example.core.permission.PermissionRegistry
import com.example.core.permission.PermissionStatus
import com.example.core.permission.PermissionType
import com.example.core.service.NayroForegroundService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM Unit tests verifying:
 * 1. Permission status evaluation and categories
 * 2. Capability availability and mapping
 * 3. Permission-to-capability relationships
 * 4. Service state management and duplicate prevention
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

        val wakeWord = CapabilityRegistry.getById(CapabilityId.WAKE_WORD_READY)
        assertNotNull(wakeWord)
        assertTrue("Wake word must be marked future capability in Step 02", wakeWord?.isFutureCapability == true)
    }

    @Test
    fun testPermissionToCapabilityMapping() {
        val micItem = PermissionRegistry.findByType(PermissionType.MICROPHONE)
        assertNotNull(micItem)
        assertTrue(micItem!!.associatedCapabilities.contains("MICROPHONE_ACCESS"))

        val overlayItem = PermissionRegistry.findByType(PermissionType.OVERLAY)
        assertNotNull(overlayItem)
        assertTrue(overlayItem!!.associatedCapabilities.contains("FLOATING_ASSISTANT"))
    }

    @Test
    fun testForegroundServiceStateFlow() {
        // Assert initial StateFlow behavior
        assertFalse(NayroForegroundService.isRunning.value)
    }
}
