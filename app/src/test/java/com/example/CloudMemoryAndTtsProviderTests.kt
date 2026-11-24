package com.example

import com.example.core.agent.tools.DevProjectToolkit
import com.example.core.agent.tools.RemoteDevelopmentExecutionGateway
import com.example.core.memory.CloudMemorySyncManager
import com.example.core.security.EncryptedStorageManager
import com.example.core.workspace.WorkspaceManager
import com.example.core.voice.SpeechSynthesisEngineType
import com.example.core.voice.VoiceProviderRouter
import com.example.data.local.entities.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking

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
    @Test
    fun unconfiguredRemotePythonExecutionFailsHonestlyWithoutFakeOutput() = runBlocking {
        val gateway = RemoteDevelopmentExecutionGateway(EncryptedStorageManager(null))

        val result = gateway.execute("print('hello')", "python")

        assertFalse(result.isSuccess)
        assertEquals(null, result.exitCode)
        assertTrue(result.errorMessage.orEmpty().contains("not configured", true))
    }

    @Test
    fun remoteDevelopmentGatewayRejectsNonHttpsEndpoint() {
        val gateway = RemoteDevelopmentExecutionGateway(EncryptedStorageManager(null))
        val error = runCatching {
            gateway.configure("http://localhost:8080", "test-token")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertFalse(gateway.isConfigured())
    }

    @Test
    fun reactNativeScaffoldContainsProjectConfigurationAndRealSourceFiles() {
        val root = Files.createTempDirectory("nayro-rn-scaffold").toFile()
        try {
            val workspace = WorkspaceManager(root)
            val result = DevProjectToolkit.scaffoldProject(workspace, "NayroMobile", "react-native")

            assertTrue(result.isSuccess)
            assertTrue(File(root, "NayroMobile/App.tsx").isFile)
            assertTrue(File(root, "NayroMobile/babel.config.js").isFile)
            assertTrue(File(root, "NayroMobile/metro.config.js").isFile)
            assertTrue(File(root, "NayroMobile/tsconfig.json").isFile)
            val packageJson = File(root, "NayroMobile/package.json").readText()
            assertTrue(packageJson.contains("react-native"))
            assertTrue(packageJson.contains("run-android"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun pythonScaffoldDoesNotClaimEmbeddedInterpreterIsCpython() {
        val root = Files.createTempDirectory("nayro-python-scaffold").toFile()
        try {
            val workspace = WorkspaceManager(root)
            val result = DevProjectToolkit.scaffoldProject(workspace, "NayroScript", "python")

            assertTrue(result.isSuccess)
            assertTrue(File(root, "NayroScript/main.py").isFile)
            assertTrue(File(root, "NayroScript/pyproject.toml").isFile)
            assertTrue(File(root, "NayroScript/requirements.txt").isFile)
            assertTrue(File(root, "NayroScript/README.md").readText().contains("real CPython"))
        } finally {
            root.deleteRecursively()
        }
    }

}
