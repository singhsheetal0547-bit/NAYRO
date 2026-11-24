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
        assertEquals("", voiceManager.partialTranscript.value)
        assertNull(voiceManager.errorMessage.value)
    }

    @Test
    fun testClearTranscriptAndErrorClearsProviderPartialTranscriptAndResetsOnNewSession() {
        val voiceManager = VoiceManager(context = null)
        voiceManager.speechRecognitionProvider.simulateSpeechRecognitionLifecycleForTest(
            partialHypotheses = listOf("Open", "Open WhatsApp"),
            finalTranscript = "Open WhatsApp"
        )
        voiceManager.dispatchRecognizedSpeech("Open WhatsApp")

        assertEquals("Open WhatsApp", voiceManager.transcribedText.value)
        assertEquals("Open WhatsApp", voiceManager.partialTranscript.value)

        // Starting a new capture session must immediately clear stale transcripts before recording starts
        voiceManager.startListening()
        assertEquals("", voiceManager.transcribedText.value)
        assertEquals("", voiceManager.partialTranscript.value)

        // Re-populate and verify explicit clearTranscriptAndError clears both flows and error state
        voiceManager.speechRecognitionProvider.simulateSpeechRecognitionLifecycleForTest(
            partialHypotheses = listOf("Check battery"),
            finalTranscript = "Check battery"
        )
        voiceManager.dispatchRecognizedSpeech("Check battery")
        voiceManager.clearTranscriptAndError()

        assertEquals("", voiceManager.transcribedText.value)
        assertEquals("", voiceManager.partialTranscript.value)
        assertNull(voiceManager.errorMessage.value)
    }

    @Test
    fun testSpeechRecognizerErrorMappingAndRmsNormalization() {
        val voiceManager = VoiceManager(context = null)
        val provider = voiceManager.speechRecognitionProvider

        assertTrue(provider.mapSpeechRecognizerError(android.speech.SpeechRecognizer.ERROR_NO_MATCH).contains("try speaking", ignoreCase = true))
        assertTrue(provider.mapSpeechRecognizerError(android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS).contains("Microphone", ignoreCase = true))
        assertTrue(provider.mapSpeechRecognizerError(android.speech.SpeechRecognizer.ERROR_NETWORK).contains("Network", ignoreCase = true))
        assertTrue(provider.mapSpeechRecognizerError(12).contains("language", ignoreCase = true))
        assertTrue(provider.mapSpeechRecognizerError(13).contains("language pack", ignoreCase = true))

        assertEquals(0f, provider.normalizeRmsDb(Float.NaN), 0.001f)
        assertEquals(0f, provider.normalizeRmsDb(-5f), 0.001f)
        assertEquals(1f, provider.normalizeRmsDb(10f), 0.001f)
        assertEquals(1f, provider.normalizeRmsDb(25f), 0.001f)
        assertTrue(provider.normalizeRmsDb(4f) in 0.4f..0.6f)
    }

    @Test
    fun testEndToEndSpeechRecognizerVoiceInputReachesAiAgentHandler() {
        val voiceManager = VoiceManager(context = null)
        val receivedAgentPrompts = mutableListOf<String>()

        voiceManager.attachUiListener(
            onRecognized = { prompt -> receivedAgentPrompts.add(prompt) }
        )

        val result = voiceManager.speechRecognitionProvider.simulateSpeechRecognitionLifecycleForTest(
            partialHypotheses = listOf("आज मुझे", "आज मुझे Rahul को कॉल"),
            finalTranscript = "आज मुझे Rahul को कॉल करना है",
            languageTag = "hi-IN",
            confidence = 0.93f
        )

        voiceManager.dispatchRecognizedSpeech(result.primaryText)

        assertEquals(listOf("आज मुझे Rahul को कॉल करना है"), receivedAgentPrompts)
        assertEquals("आज मुझे Rahul को कॉल करना है", voiceManager.transcribedText.value)
    }
}

