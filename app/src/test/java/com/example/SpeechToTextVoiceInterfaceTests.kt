package com.example

import com.example.core.identity.AssistantIdentityStore
import com.example.core.voice.VoiceManager
import com.example.core.voice.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests verifying the Speech-to-Text voice command pipeline, Android microphone
 * permission guard, "Babu NAYRO cute baby" invocation normalization, language switching,
 * and deduplicated UI/Service command dispatch.
 */
class SpeechToTextVoiceInterfaceTests {

    @Test
    fun testBabuNayroCuteBabyInvocationPrefixNormalization() {
        val store = AssistantIdentityStore(context = null)
        val voiceManager = VoiceManager(context = null)
        voiceManager.bindIdentityStore(store)

        assertEquals(
            "time kya hua hai?",
            voiceManager.normalizeVoiceInvocationCommand("Babu NAYRO cute baby, time kya hua hai?")
        )
        assertEquals(
            "open YouTube",
            voiceManager.normalizeVoiceInvocationCommand("Hey Babu NAYRO cute baby open YouTube")
        )
        assertEquals(
            "set a reminder",
            voiceManager.normalizeVoiceInvocationCommand("Hi Babu NAYRO cute baby: set a reminder")
        )
        // Standalone greeting without trailing command should be preserved
        assertEquals(
            "Babu NAYRO cute baby",
            voiceManager.normalizeVoiceInvocationCommand("Babu NAYRO cute baby")
        )
    }

    @Test
    fun testMicrophonePermissionMissingFailsClosedWithoutEnteringListeningState() {
        val voiceManager = VoiceManager(context = null)
        var capturedError: String? = null
        voiceManager.attachUiListener(
            onRecognized = {},
            onError = { capturedError = it }
        )

        voiceManager.startListening()

        assertFalse(voiceManager.isMicrophonePermissionGranted())
        assertEquals(VoiceState.ERROR, voiceManager.voiceState.value)
        assertTrue(capturedError?.contains("Microphone permission", ignoreCase = true) == true)
        assertEquals(capturedError, voiceManager.errorMessage.value)
    }

    @Test
    fun testSingleDispatchPreventsDuplicateCommandExecutionAcrossUiAndServiceListeners() {
        val voiceManager = VoiceManager(context = null)
        val uiCommands = mutableListOf<String>()
        val serviceCommands = mutableListOf<String>()

        voiceManager.attachUiListener(onRecognized = { uiCommands.add(it) })
        voiceManager.attachServiceListener { serviceCommands.add(it) }

        voiceManager.dispatchRecognizedSpeech("Open Settings")
        // Immediate duplicate within deduplication window must be ignored
        voiceManager.dispatchRecognizedSpeech("Open Settings")

        assertEquals(listOf("Open Settings"), uiCommands)
        assertTrue(
            "UI listener owns active foreground dispatch without double-firing service listener",
            serviceCommands.isEmpty()
        )
    }

    @Test
    fun testSpeechToTextStreamingLanguageSwitchAndClearTranscript() {
        val voiceManager = VoiceManager(context = null)
        voiceManager.setRecognitionLanguage("hi-IN")
        assertEquals("hi-IN", voiceManager.configuration.value.languageTag)

        val partials = mutableListOf<String>()
        val result = voiceManager.speechRecognitionProvider.simulateSpeechRecognitionLifecycleForTest(
            partialHypotheses = listOf("Babu NAYRO", "Babu NAYRO cute baby time"),
            finalTranscript = "Babu NAYRO cute baby time kya hua hai",
            confidence = 0.96f,
            simulatedRmsDb = 7.2f,
            languageTag = "hi-IN",
            onPartial = { partials.add(it) }
        )

        assertEquals(2, partials.size)
        assertEquals("Babu NAYRO cute baby time kya hua hai", result.primaryText)
        assertEquals("hi-IN", result.languageTag)
        assertTrue(result.confidenceScore >= 0.9f)

        val normalizedCommand = voiceManager.normalizeVoiceInvocationCommand(result.primaryText)
        assertEquals("time kya hua hai", normalizedCommand)

        voiceManager.dispatchRecognizedSpeech(normalizedCommand)
        assertEquals("time kya hua hai", voiceManager.transcribedText.value)

        voiceManager.clearTranscriptAndError()
        assertEquals("", voiceManager.transcribedText.value)
        assertNull(voiceManager.errorMessage.value)
    }
}
