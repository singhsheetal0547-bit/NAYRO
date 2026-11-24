package com.example

import com.example.core.memory.CloudMemorySyncManager
import com.example.core.security.EncryptedStorageManager
import com.example.core.voice.SpeechSynthesisEngineType
import com.example.core.voice.VoiceProviderRouter
import com.example.data.local.entities.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudMemoryAndTtsProviderTests {

    @Test
    fun cloudMemoryMergePrefersNewestApprovedRecordWithoutDuplicatingKeys() {
        val local = listOf(
            MemoryEntity(category = "USER_PREFERENCE", keyName = "language", memoryValue = "English", createdAt = 100),
            MemoryEntity(category = "PROJECT_CONTEXT", keyName = "NAYRO", memoryValue = "Keep Kotlin architecture", createdAt = 200)
        )
        val remote = listOf(
            MemoryEntity(category = "user_preference", keyName = "LANGUAGE", memoryValue = "Hindi", createdAt = 300),
            MemoryEntity(category = "PROJECT_CONTEXT", keyName = "NAYRO", memoryValue = "Old remote value", createdAt = 150)
        )

        val merged = CloudMemorySyncManager.mergeSnapshots(local, remote)

        assertEquals(2, merged.size)
        assertEquals("Hindi", merged.first { it.keyName.equals("language", true) }.memoryValue)
        assertEquals(
            "Keep Kotlin architecture",
            merged.first { it.keyName.equals("NAYRO", true) }.memoryValue
        )
    }

    @Test
    fun cloudMemoryMergeRejectsUnconfirmedAndSensitiveRemoteRecords() {
        val remote = listOf(
            MemoryEntity(category = "NOTE", keyName = "safe", memoryValue = "I prefer concise answers", userConfirmed = true, createdAt = 10),
            MemoryEntity(category = "NOTE", keyName = "unconfirmed", memoryValue = "temporary detail", userConfirmed = false, createdAt = 20),
            MemoryEntity(category = "NOTE", keyName = "secret", memoryValue = "my API key is sk-abcdefghijklmnopqrstuvwxyz123456", userConfirmed = true, createdAt = 30)
        )

        val merged = CloudMemorySyncManager.mergeSnapshots(emptyList(), remote)

        assertEquals(1, merged.size)
        assertEquals("safe", merged.single().keyName)
    }

    @Test
    fun selectingCloudTtsWithoutCredentialsKeepsNativeTtsAsEffectiveFallback() {
        val router = VoiceProviderRouter(
            context = null,
            encryptedStorageManager = EncryptedStorageManager(null)
        )

        val status = router.selectTtsEngine(SpeechSynthesisEngineType.OPENAI_COMPATIBLE_CLOUD)

        assertEquals(SpeechSynthesisEngineType.OPENAI_COMPATIBLE_CLOUD, status.selectedTtsEngine)
        assertEquals(SpeechSynthesisEngineType.ANDROID_TTS, status.effectiveTtsEngine)
        assertFalse(status.hasCloudTtsKey)
        assertTrue(status.ttsFallbackActiveReason.orEmpty().contains("not configured", true))
    }

    @Test
    fun cloudTtsConfigurationRejectsPlainHttpEndpoints() {
        val router = VoiceProviderRouter(
            context = null,
            encryptedStorageManager = EncryptedStorageManager(null)
        )

        val error = runCatching {
            router.saveCloudTtsConfiguration(
                endpoint = "http://example.com/v1",
                model = "tts-1",
                voice = "alloy",
                apiKey = "not-a-real-key"
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertFalse(router.status.value.hasCloudTtsKey)
    }
}
