package com.example

import com.example.core.agent.TaskClassifier
import com.example.core.agent.UserIntentType
import com.example.core.identity.AssistantIdentityStore
import com.example.core.service.BackgroundVoicePipelineState
import com.example.core.service.NayroForegroundService
import com.example.core.service.ServiceLifecycleState
import com.example.core.service.ServiceStateRepository
import com.example.core.voice.AudioSessionOwner
import com.example.core.voice.BackgroundVoiceController
import com.example.core.voice.MicrophoneLifecyclePhase
import com.example.core.voice.MicrophoneOwnershipController
import com.example.core.voice.VoiceConfiguration
import com.example.core.voice.VoiceManager
import com.example.core.voice.VoiceState
import com.example.core.voice.assistant.ScreenOffWakeCapabilityManager
import com.example.core.voice.assistant.ScreenOffWakeMode
import com.example.core.voice.assistant.VoiceInteractionServiceLifecycleState
import com.example.core.voice.engine.LocalWakeWordEngine
import com.example.core.voice.engine.WakeAssetFormat
import com.example.core.voice.engine.WakeAudioCaptureFactory
import com.example.core.voice.engine.WakeAudioCaptureSession
import com.example.core.voice.engine.WakeEngineState
import com.example.core.voice.engine.WakePhraseSupportStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin

/**
 * Comprehensive Phase 1–5 verification suite covering ALL required verification areas:
 *  1. Wake-word matching (bundled multi-frame asset + user-enrolled DTW + unrelated speech & noise rejection)
 *  2. Invalid template rejection (malformed JSON, sampleRate/MFCC mismatch, centroid-only/synthetic rejection)
 *  3. AudioRecord initialization & runtime read() failure handling (P0-2)
 *  4. Microphone permission denied fail-closed across every layer
 *  5. Microphone ownership & concurrency transitions (P0-4)
 *  6. Foreground service startup failure, staged pipeline states & bounded recovery retries (P0-3)
 *  7. Background voice recovery from interruptions & runtime permission revocation
 *  8. Screen ON local wake-word mode (`Hi Nayro`, `Hey Nayro`, `Hi Babu`, custom phrases — zero cloud keys)
 *  9. Screen OFF wake-word mode & Android 10–16 + OEM compatibility matrix (Realme/Xiaomi/Samsung/Vivo/OPPO/Pixel)
 * 10. VoiceInteractionService lifecycle & isolated listener coexistence
 * 11. Floating assistant coexistence & immediate microphone release on stop
 * 12. Existing AgentRuntime / TaskClassifier pipeline compatibility
 */
class BackgroundVoiceReliabilityAndScreenOffTests {

    @Before
    fun resetSharedSingletons() {
        ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.STOPPED, "Test reset")
        ServiceStateRepository.updateBackgroundVoicePipeline(
            state = BackgroundVoicePipelineState.STOPPED,
            detail = "Test reset",
            isMicrophoneVerified = false,
            isWakeEngineVerified = false,
            isAudioCaptureVerified = false,
            retryCount = 0
        )
        ScreenOffWakeCapabilityManager.setServiceActive(active = false, dspNote = "Test reset", hasHardwareDsp = false)
    }

    private fun readBundledTemplatesAsset(): String {
        val candidates = listOf(
            File("src/main/assets/nayro_kws_templates.json"),
            File("app/src/main/assets/nayro_kws_templates.json")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("Could not locate nayro_kws_templates.json from ${File(".").absolutePath}")
        return file.readText()
    }

    private fun generateSpeechLikePcm(
        freqHz: Double,
        harmonicHz: Double,
        durationMs: Int = 700,
        amplitude: Double = 6500.0,
        sampleRate: Int = 16000
    ): ShortArray {
        val count = (sampleRate * durationMs) / 1000
        return ShortArray(count) { i ->
            val t = i.toDouble() / sampleRate
            val env = sin(PI * i.toDouble() / count).coerceAtLeast(0.05)
            val signal = 0.65 * sin(2.0 * PI * freqHz * t) +
                0.35 * sin(2.0 * PI * harmonicHz * t)
            (signal * amplitude * env).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun generateWhiteNoisePcm(durationMs: Int = 700, amplitude: Double = 1800.0): ShortArray {
        val count = (16000 * durationMs) / 1000
        var seed = 0x5DEECE66DL
        return ShortArray(count) {
            seed = (seed * 0x5DEECE66DL + 0xBL) and ((1L shl 48) - 1)
            val norm = ((seed ushr 16).toInt().toDouble() / Int.MAX_VALUE.toDouble()) * 2.0 - 1.0
            (norm * amplitude).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun generateSilencePcm(durationMs: Int = 700): ShortArray {
        return ShortArray((16000 * durationMs) / 1000) { 0 }
    }

    private class FakeCaptureSession(
        override val isInitialized: Boolean,
        private val entersRecordingOnStart: Boolean,
        private val throwSecurityOnStart: Boolean = false,
        private val readReturnValue: Int = 160
    ) : WakeAudioCaptureSession {
        override var isRecording: Boolean = false
            private set
        val startCalled = AtomicBoolean(false)
        val stopCalled = AtomicBoolean(false)
        val released = AtomicBoolean(false)

        override fun startRecording() {
            startCalled.set(true)
            if (throwSecurityOnStart) throw SecurityException("RECORD_AUDIO revoked mid-start")
            if (entersRecordingOnStart) {
                isRecording = true
            }
        }

        override fun read(buffer: ShortArray, offset: Int, size: Int): Int {
            if (readReturnValue < 0) return readReturnValue
            Thread.sleep(5)
            return readReturnValue.coerceAtMost(size)
        }

        override fun stop() {
            stopCalled.set(true)
            isRecording = false
        }

        override fun release() {
            released.set(true)
            isRecording = false
        }
    }

    // ========================================================================
    // 1. WAKE-WORD MATCHING (BUNDLED + ENROLLED DTW + FALSE POSITIVE REJECTION)
    // ========================================================================

    @Test
    fun testBundledTemplatesJsonLoadsValidMultiFrameAcousticTemplatesAndMatchesCorrectPhrase() {
        val engine = LocalWakeWordEngine(context = null)
        val rawJson = readBundledTemplatesAsset()

        val validation = engine.loadTemplatesFromJson(rawJson)
        assertTrue("Bundled asset must pass strict validation: ${validation.errors}", validation.isValid)
        assertEquals(16000, validation.sampleRateHz)
        assertEquals(13, validation.numMfccCoefficients)
        assertTrue("Expected at least 6 bundled wake phrases", validation.loadedCount >= 6)
        assertEquals(WakeAssetFormat.JSON_ACOUSTIC_TEMPLATES, engine.getDetectedAssetFormat())
        assertTrue(engine.hasUsableAcousticTemplates())

        // Verify all required core wake phrases are loaded as validated bundled templates
        assertEquals(WakePhraseSupportStatus.READY_BUNDLED_ACOUSTIC_TEMPLATE, engine.getWakePhraseSupportStatus("Hi Nayro"))
        assertEquals(WakePhraseSupportStatus.READY_BUNDLED_ACOUSTIC_TEMPLATE, engine.getWakePhraseSupportStatus("Hey Nayro"))
        assertEquals(WakePhraseSupportStatus.READY_BUNDLED_ACOUSTIC_TEMPLATE, engine.getWakePhraseSupportStatus("Hi Babu"))
        assertEquals(WakePhraseSupportStatus.READY_BUNDLED_ACOUSTIC_TEMPLATE, engine.getWakePhraseSupportStatus("Hey Babu"))
        assertEquals(WakePhraseSupportStatus.REQUIRES_VOICE_ENROLLMENT, engine.getWakePhraseSupportStatus("Hey Jarvis"))

        // Feed the exact bundled multi-frame sequence for "Hi Nayro" into the DTW matcher
        val hiNayroTemplate = engine.getLoadedTemplate("Hi Nayro")
        assertNotNull("Bundled Hi Nayro template must be loaded", hiNayroTemplate)
        assertTrue("Bundled template must have >= 4 reference MFCC frames", hiNayroTemplate!!.mfccSequence.size >= 4)

        val matchResult = engine.matchMfccSequenceAgainstTemplates(
            querySeq = hiNayroTemplate.mfccSequence,
            rms = 2400.0,
            zcr = 0.14,
            voicedRatio = 0.72f,
            triggerCallback = false
        )
        assertNotNull("Matching bundled multi-frame sequence must trigger detection", matchResult)
        assertEquals("hi nayro", matchResult!!.first.lowercase())
        assertTrue("Confidence should be >= 0.75 for exact match", matchResult.second >= 0.75f)
    }

    @Test
    fun testEnrolledCustomWakePhraseMatchesValidSamplesAndRejectsUnrelatedSpeechAndNoise() {
        val engine = LocalWakeWordEngine(context = null)
        engine.addWakePhrase("Hey Priya")

        // Enroll 3 genuine PCM samples for "Hey Priya"
        val enroll1 = generateSpeechLikePcm(freqHz = 280.0, harmonicHz = 840.0, amplitude = 6400.0)
        val enroll2 = generateSpeechLikePcm(freqHz = 285.0, harmonicHz = 855.0, amplitude = 6200.0)
        val enroll3 = generateSpeechLikePcm(freqHz = 276.0, harmonicHz = 828.0, amplitude = 6600.0)

        assertTrue(engine.enrollWakePhraseSample("Hey Priya", enroll1))
        assertTrue(engine.enrollWakePhraseSample("Hey Priya", enroll2))
        assertTrue(engine.enrollWakePhraseSample("Hey Priya", enroll3))

        assertEquals(WakePhraseSupportStatus.READY_USER_ENROLLED_DTW, engine.getWakePhraseSupportStatus("Hey Priya"))
        assertTrue("Engine should report usable templates after real enrollment", engine.hasUsableAcousticTemplates())

        // Valid wake sample matching the enrolled voice signature
        val validProbe = generateSpeechLikePcm(freqHz = 282.0, harmonicHz = 845.0, amplitude = 6300.0)
        val probeFeat = engine.extractAcousticFeatures(validProbe)
        val validResult = engine.matchUtteranceByDtw(validProbe)
        assertNotNull(
            "Valid enrolled wake-word sample must match 'Hey Priya' (rms=${probeFeat[0]}, zcr=${probeFeat[1]}, voiced=${probeFeat[3]}, dtw=${engine.lastDtwDistance}, conf=${engine.lastConfidenceScore}, thresh=${engine.getConfidenceThreshold()})",
            validResult
        )
        assertEquals("hey priya", validResult!!.first.lowercase())

        // Unrelated speech with completely different formant/cadence profile
        val unrelatedSpeech = generateSpeechLikePcm(freqHz = 1850.0, harmonicHz = 4200.0, amplitude = 6200.0)
        val unrelatedResult = engine.matchUtteranceByDtw(unrelatedSpeech)
        assertNull(
            "Unrelated speech must NOT falsely trigger enrolled wake word (got $unrelatedResult)",
            unrelatedResult
        )

        // Background broadband noise must be rejected
        val noiseResult = engine.matchUtteranceByDtw(generateWhiteNoisePcm(amplitude = 2600.0))
        assertNull("Background noise must be rejected", noiseResult)

        // Silence must be rejected
        val silenceResult = engine.matchUtteranceByDtw(generateSilencePcm())
        assertNull("Silence must be rejected", silenceResult)
    }

    // ========================================================================
    // 2. INVALID TEMPLATE REJECTION (P0-1)
    // ========================================================================

    @Test
    fun testInvalidTemplatesAreRejectedAndEngineNeverReportsReadyOrListening() {
        // Case A: Malformed JSON
        run {
            val engine = LocalWakeWordEngine(context = null)
            val result = engine.loadTemplatesFromJson("{ invalid json !!!")
            assertFalse(result.isValid)
            assertEquals(WakeAssetFormat.INVALID, engine.getDetectedAssetFormat())
            assertEquals(WakeEngineState.ERROR, engine.engineState.value)
            assertFalse(engine.hasUsableAcousticTemplates())
            assertFalse("Engine must refuse to start listening with invalid JSON", engine.startListening())
            assertTrue(engine.engineState.value != WakeEngineState.LISTENING_FOR_WAKE)
        }

        // Case B: Wrong sampleRateHz (44100 instead of 16000)
        run {
            val badSampleRateJson = """
                {
                  "model": "NAYRO_BAD",
                  "sampleRateHz": 44100,
                  "numMfccCoefficients": 13,
                  "templates": [
                    {
                      "phrase": "Hi Nayro",
                      "mfccCentroid": [1,2,3,4,5,6,7,8,9,10,11,12,13],
                      "referenceMfccFrames": [
                        [1,2,3,4,5,6,7,8,9,10,11,12,13],
                        [1,2,3,4,5,6,7,8,9,10,11,12,13],
                        [1,2,3,4,5,6,7,8,9,10,11,12,13],
                        [1,2,3,4,5,6,7,8,9,10,11,12,13]
                      ]
                    }
                  ]
                }
            """.trimIndent()
            val engine = LocalWakeWordEngine(context = null)
            val result = engine.loadTemplatesFromJson(badSampleRateJson)
            assertFalse("Wrong sampleRateHz must fail validation", result.isValid)
            assertEquals(WakeEngineState.ERROR, engine.engineState.value)
            assertFalse(engine.startListening())
        }

        // Case C: Wrong MFCC dimensionality (8 instead of 13)
        run {
            val badDimJson = """
                {
                  "model": "NAYRO_BAD",
                  "sampleRateHz": 16000,
                  "numMfccCoefficients": 8,
                  "templates": [
                    {
                      "phrase": "Hi Nayro",
                      "mfccCentroid": [1,2,3,4,5,6,7,8],
                      "referenceMfccFrames": [
                        [1,2,3,4,5,6,7,8],
                        [1,2,3,4,5,6,7,8],
                        [1,2,3,4,5,6,7,8],
                        [1,2,3,4,5,6,7,8]
                      ]
                    }
                  ]
                }
            """.trimIndent()
            val engine = LocalWakeWordEngine(context = null)
            val result = engine.loadTemplatesFromJson(badDimJson)
            assertFalse("Wrong MFCC coefficient count must fail validation", result.isValid)
            assertEquals(WakeEngineState.ERROR, engine.engineState.value)
        }

        // Case D: Missing referenceMfccFrames (synthetic centroid-only fabrication is prohibited!)
        run {
            val centroidOnlyJson = """
                {
                  "model": "NAYRO_CENTROID_ONLY",
                  "sampleRateHz": 16000,
                  "numMfccCoefficients": 13,
                  "templates": [
                    {
                      "phrase": "Hi Nayro",
                      "mfccCentroid": [2.0,-1.0,1.0,-0.5,0.5,-0.3,0.2,-0.1,0.1,-0.1,0.05,-0.02,0.01]
                    }
                  ]
                }
            """.trimIndent()
            val engine = LocalWakeWordEngine(context = null)
            val result = engine.loadTemplatesFromJson(centroidOnlyJson)
            assertFalse("Centroid-only templates without real referenceMfccFrames must be rejected", result.isValid)
            assertEquals(0, result.loadedCount)
            assertEquals(WakeEngineState.ERROR, engine.engineState.value)
            assertFalse(engine.hasUsableAcousticTemplates())
        }
    }

    // ========================================================================
    // 3. AUDIORECORD INITIALIZATION & RUNTIME FAILURE HANDLING (P0-2)
    // ========================================================================

    @Test
    fun testAudioRecordMinBufferFailureDoesNotPublishListeningAndTransitionsToError() {
        val factory = object : WakeAudioCaptureFactory {
            override fun getMinBufferSize(sampleRateHz: Int, channelConfig: Int, audioFormat: Int): Int = -2 // ERROR_BAD_VALUE
            override fun createSession(audioSource: Int, sampleRateHz: Int, channelConfig: Int, audioFormat: Int, bufferSizeInBytes: Int): WakeAudioCaptureSession {
                error("createSession must not be called when minBufferSize <= 0")
            }
        }
        val engine = LocalWakeWordEngine(context = null).apply {
            setAudioCaptureFactoryForTesting(factory)
            setMicPermissionCheckerForTesting { true }
        }
        assertTrue(engine.loadTemplatesFromJson(readBundledTemplatesAsset()).isValid)

        assertFalse("startListening must return false on invalid minBufferSize", engine.startListening())
        assertEquals(WakeEngineState.ERROR, engine.engineState.value)
        assertFalse(engine.isMicrophoneInitialized.value)
        assertFalse(engine.isAudioCaptureActive.value)
    }

    @Test
    fun testAudioRecordStateUninitializedReleasesSessionAndReportsError() {
        val fakeSession = FakeCaptureSession(isInitialized = false, entersRecordingOnStart = false)
        val factory = object : WakeAudioCaptureFactory {
            override fun getMinBufferSize(sampleRateHz: Int, channelConfig: Int, audioFormat: Int): Int = 3200
            override fun createSession(audioSource: Int, sampleRateHz: Int, channelConfig: Int, audioFormat: Int, bufferSizeInBytes: Int): WakeAudioCaptureSession = fakeSession
        }
        val engine = LocalWakeWordEngine(context = null).apply {
            setAudioCaptureFactoryForTesting(factory)
            setMicPermissionCheckerForTesting { true }
        }
        assertTrue(engine.loadTemplatesFromJson(readBundledTemplatesAsset()).isValid)

        assertFalse("startListening must return false when AudioRecord is STATE_UNINITIALIZED", engine.startListening())
        assertTrue("Uninitialized AudioRecord MUST be released cleanly", fakeSession.released.get())
        assertFalse("startRecording must NOT be called when uninitialized", fakeSession.startCalled.get())
        assertEquals(WakeEngineState.ERROR, engine.engineState.value)
        assertFalse(engine.isAudioCaptureActive.value)
    }

    @Test
    fun testAudioRecordNotRecordingAfterStartReleasesSessionAndReportsError() {
        val fakeSession = FakeCaptureSession(isInitialized = true, entersRecordingOnStart = false)
        val factory = object : WakeAudioCaptureFactory {
            override fun getMinBufferSize(sampleRateHz: Int, channelConfig: Int, audioFormat: Int): Int = 3200
            override fun createSession(audioSource: Int, sampleRateHz: Int, channelConfig: Int, audioFormat: Int, bufferSizeInBytes: Int): WakeAudioCaptureSession = fakeSession
        }
        val engine = LocalWakeWordEngine(context = null).apply {
            setAudioCaptureFactoryForTesting(factory)
            setMicPermissionCheckerForTesting { true }
        }
        assertTrue(engine.loadTemplatesFromJson(readBundledTemplatesAsset()).isValid)

        assertFalse("startListening must fail if RECORDSTATE_RECORDING is not confirmed", engine.startListening())
        assertTrue(fakeSession.startCalled.get())
        assertTrue("Failed AudioRecord must be released", fakeSession.released.get())
        assertEquals(WakeEngineState.ERROR, engine.engineState.value)
        assertFalse(engine.isAudioCaptureActive.value)
    }

    @Test
    fun testAudioRecordSecurityExceptionAndRuntimeReadFailureTransitionCleanly() {
        // Case A: SecurityException during startRecording()
        run {
            val fakeSession = FakeCaptureSession(
                isInitialized = true,
                entersRecordingOnStart = false,
                throwSecurityOnStart = true
            )
            val factory = object : WakeAudioCaptureFactory {
                override fun getMinBufferSize(sampleRateHz: Int, channelConfig: Int, audioFormat: Int): Int = 3200
                override fun createSession(audioSource: Int, sampleRateHz: Int, channelConfig: Int, audioFormat: Int, bufferSizeInBytes: Int): WakeAudioCaptureSession = fakeSession
            }
            val engine = LocalWakeWordEngine(context = null).apply {
                setAudioCaptureFactoryForTesting(factory)
                setMicPermissionCheckerForTesting { true }
            }
            assertTrue(engine.loadTemplatesFromJson(readBundledTemplatesAsset()).isValid)
            assertFalse(engine.startListening())
            assertTrue(fakeSession.released.get())
            assertEquals(WakeEngineState.ERROR, engine.engineState.value)
        }

        // Case B: Healthy startRecording() succeeds, then consecutive read() < 0 (ERROR_DEAD_OBJECT) triggers DEGRADED + callback
        run {
            val captureErrorCount = AtomicInteger(0)
            val fakeSession = FakeCaptureSession(
                isInitialized = true,
                entersRecordingOnStart = true,
                readReturnValue = -6 // AudioRecord.ERROR_DEAD_OBJECT
            )
            val factory = object : WakeAudioCaptureFactory {
                override fun getMinBufferSize(sampleRateHz: Int, channelConfig: Int, audioFormat: Int): Int = 3200
                override fun createSession(audioSource: Int, sampleRateHz: Int, channelConfig: Int, audioFormat: Int, bufferSizeInBytes: Int): WakeAudioCaptureSession = fakeSession
            }
            val engine = LocalWakeWordEngine(context = null).apply {
                setAudioCaptureFactoryForTesting(factory)
                setMicPermissionCheckerForTesting { true }
                onCaptureError = { captureErrorCount.incrementAndGet() }
            }
            assertTrue(engine.loadTemplatesFromJson(readBundledTemplatesAsset()).isValid)
            assertTrue("Initial startListening succeeds when session enters RECORDSTATE_RECORDING", engine.startListening())

            val deadline = System.currentTimeMillis() + 2500L
            while (System.currentTimeMillis() < deadline && engine.engineState.value == WakeEngineState.LISTENING_FOR_WAKE) {
                Thread.sleep(20)
            }

            assertTrue(
                "Runtime read() failures must transition out of LISTENING_FOR_WAKE to DEGRADED/ERROR (was ${engine.engineState.value})",
                engine.engineState.value == WakeEngineState.DEGRADED || engine.engineState.value == WakeEngineState.ERROR
            )
            assertFalse(engine.isAudioCaptureActive.value)
            assertTrue("onCaptureError callback must fire on dead AudioRecord", captureErrorCount.get() >= 1)
            assertTrue("AudioRecord session must be released after read failure", fakeSession.released.get())
        }
    }

    // ========================================================================
    // 4. MICROPHONE PERMISSION DENIED ACROSS EVERY LAYER
    // ========================================================================

    @Test
    fun testMicrophonePermissionDeniedFailsClosedAcrossAllLayers() {
        val vm = VoiceManager(context = null)
        vm.setMicPermissionCheckerForTesting { false }
        assertTrue(vm.wakeWordEngine.loadTemplatesFromJson(readBundledTemplatesAsset()).isValid)

        // 1) LocalWakeWordEngine refuses to start
        assertFalse(vm.wakeWordEngine.startListening())
        assertEquals(WakeEngineState.ERROR, vm.wakeWordEngine.engineState.value)
        assertFalse(vm.wakeWordEngine.isMicrophoneInitialized.value)
        assertFalse(vm.wakeWordEngine.isAudioCaptureActive.value)

        // 2) VoiceManager refuses both wake-word and manual listening
        vm.updateConfiguration(VoiceConfiguration(isWakeWordEnabled = true))
        assertEquals(AudioSessionOwner.NONE, vm.audioSessionOwner.value)
        assertEquals(VoiceState.ERROR, vm.voiceState.value)

        vm.startManualListening { }
        assertEquals(AudioSessionOwner.NONE, vm.audioSessionOwner.value)
        assertEquals(VoiceState.ERROR, vm.voiceState.value)

        // 3) BackgroundVoiceController refuses to enable
        val identityStore = AssistantIdentityStore(context = null)
        val controller = BackgroundVoiceController(
            context = null,
            identityStore = identityStore,
            permissionManager = null,
            voiceManager = vm,
            micPermissionCheckOverride = { false }
        )
        val (enabled, msg) = controller.enableBackgroundListening(null)
        assertFalse("BackgroundVoiceController must refuse enable when RECORD_AUDIO denied", enabled)
        assertTrue(msg.contains("RECORD_AUDIO", ignoreCase = true))
        assertFalse(controller.state.value.isEnabled)

        // 4) Foreground service readiness evaluator reports ERROR
        val pipelineStatus = NayroForegroundService.evaluatePipelineReadiness(
            isServiceInForeground = true,
            hasMicPermission = false,
            isWakeTemplatesReady = true,
            isMicInitialized = false,
            isAudioCaptureActive = false
        )
        assertEquals(BackgroundVoicePipelineState.ERROR, pipelineStatus.state)
        assertFalse(pipelineStatus.state.isOperational)

        // 5) ScreenOffWakeCapabilityManager reports UNSUPPORTED_OR_BLOCKED
        val capability = ScreenOffWakeCapabilityManager.evaluateCapability(
            context = null,
            isLocalWakeListening = true,
            sdkInt = 34,
            manufacturer = "Google",
            micGrantedOverride = false,
            roleHeldOverride = true,
            serviceActiveOverride = true,
            hardwareDspSupportedOverride = false,
            batteryExemptOverride = true
        )
        assertEquals(ScreenOffWakeMode.UNSUPPORTED_OR_BLOCKED, capability.mode)
        assertFalse(capability.isScreenOnWakeReady)
        assertFalse(capability.isScreenOffWakeVerified)
    }

    // ========================================================================
    // 5. MICROPHONE OWNERSHIP & CONCURRENCY CONTROLLER (P0-4)
    // ========================================================================

    @Test
    fun testMicrophoneOwnershipControllerEnforcesSingleOwnerMutualExclusion() {
        val controller = MicrophoneOwnershipController()

        // 1) Wake engine acquires microphone -> WAKE_LISTENING
        val wakeToken = controller.tryAcquire(
            requestedOwner = AudioSessionOwner.WAKE_WORD_ENGINE,
            targetPhase = MicrophoneLifecyclePhase.WAKE_LISTENING,
            reason = "Start local wake"
        )
        assertNotNull(wakeToken)
        assertEquals(AudioSessionOwner.WAKE_WORD_ENGINE, controller.owner.value)
        assertEquals(MicrophoneLifecyclePhase.WAKE_LISTENING, controller.phase.value)

        // 2) Duplicate concurrent acquisition by SPEECH_RECOGNIZER is rejected without allowPreemptWake
        assertNull(
            "SpeechRecognizer must not overlap active Wake engine without handoff/preempt",
            controller.tryAcquire(
                requestedOwner = AudioSessionOwner.SPEECH_RECOGNIZER,
                targetPhase = MicrophoneLifecyclePhase.COMMAND_CAPTURE,
                reason = "Overlap attempt",
                allowPreemptWake = false
            )
        )
        assertEquals(AudioSessionOwner.WAKE_WORD_ENGINE, controller.owner.value)

        // 3) Proper handoff: WAKE_LISTENING -> COMMAND_CAPTURE
        val cmdToken = controller.tryAcquire(
            requestedOwner = AudioSessionOwner.SPEECH_RECOGNIZER,
            targetPhase = MicrophoneLifecyclePhase.COMMAND_CAPTURE,
            reason = "Wake detected handoff",
            allowPreemptWake = true
        )
        assertNotNull(cmdToken)
        assertEquals(AudioSessionOwner.SPEECH_RECOGNIZER, controller.owner.value)
        assertEquals(MicrophoneLifecyclePhase.COMMAND_CAPTURE, controller.phase.value)

        // 4) Enrollment capture cannot steal active command capture
        assertNull(
            controller.tryAcquire(
                requestedOwner = AudioSessionOwner.ENROLLMENT_CAPTURE,
                targetPhase = MicrophoneLifecyclePhase.ENROLLMENT_CAPTURE,
                reason = "Concurrent enroll"
            )
        )

        // 5) COMMAND_CAPTURE -> PROCESSING -> RESPONSE -> IDLE -> WAKE_LISTENING
        controller.enterProcessing("Reasoning on command")
        assertEquals(AudioSessionOwner.NONE, controller.owner.value)
        assertEquals(MicrophoneLifecyclePhase.PROCESSING, controller.phase.value)

        val ttsToken = controller.enterResponsePlayback("Speaking TTS reply")
        assertTrue(ttsToken > 0L)
        assertEquals(AudioSessionOwner.TTS_PLAYBACK, controller.owner.value)
        assertEquals(MicrophoneLifecyclePhase.RESPONSE, controller.phase.value)

        assertTrue(controller.release(AudioSessionOwner.TTS_PLAYBACK, sessionId = ttsToken, reason = "TTS finished"))
        assertEquals(AudioSessionOwner.NONE, controller.owner.value)
        assertEquals(MicrophoneLifecyclePhase.IDLE, controller.phase.value)

        assertNotNull(
            controller.tryAcquire(
                requestedOwner = AudioSessionOwner.WAKE_WORD_ENGINE,
                targetPhase = MicrophoneLifecyclePhase.WAKE_LISTENING,
                reason = "Resume standby"
            )
        )
        assertEquals(MicrophoneLifecyclePhase.WAKE_LISTENING, controller.phase.value)

        // 6) Interruption blocks re-acquisition until cleared
        controller.forceInterrupt("Incoming phone call")
        assertEquals(AudioSessionOwner.PHONE_CALL_INTERRUPTION, controller.owner.value)
        assertEquals(MicrophoneLifecyclePhase.INTERRUPTED, controller.phase.value)
        assertNull(
            "Must not allow wake acquisition while interrupted by phone call",
            controller.tryAcquire(
                requestedOwner = AudioSessionOwner.WAKE_WORD_ENGINE,
                targetPhase = MicrophoneLifecyclePhase.WAKE_LISTENING,
                reason = "Premature resume"
            )
        )

        controller.clearInterruption("Call ended")
        assertNotNull(
            controller.tryAcquire(
                requestedOwner = AudioSessionOwner.WAKE_WORD_ENGINE,
                targetPhase = MicrophoneLifecyclePhase.WAKE_LISTENING,
                reason = "Safe post-call resume"
            )
        )
        assertEquals(AudioSessionOwner.WAKE_WORD_ENGINE, controller.owner.value)
        assertTrue("Transition history must record lifecycle audit trail", controller.getTransitionHistory().size >= 6)
    }

    @Test
    fun testVoiceManagerEnrollmentGuardPreventsOverlapWithWakeAndResumesCleanly() = runBlocking {
        val factory = object : WakeAudioCaptureFactory {
            override fun getMinBufferSize(sampleRateHz: Int, channelConfig: Int, audioFormat: Int): Int = 3200
            override fun createSession(audioSource: Int, sampleRateHz: Int, channelConfig: Int, audioFormat: Int, bufferSizeInBytes: Int): WakeAudioCaptureSession {
                return FakeCaptureSession(isInitialized = true, entersRecordingOnStart = true)
            }
        }
        val vm = VoiceManager(context = null)
        vm.setMicPermissionCheckerForTesting { true }
        vm.wakeWordEngine.setAudioCaptureFactoryForTesting(factory)
        assertTrue(vm.wakeWordEngine.loadTemplatesFromJson(readBundledTemplatesAsset()).isValid)

        vm.updateConfiguration(VoiceConfiguration(isWakeWordEnabled = true))
        assertEquals(AudioSessionOwner.WAKE_WORD_ENGINE, vm.audioSessionOwner.value)
        assertEquals(VoiceState.STANDBY, vm.voiceState.value)

        var ownerDuringEnroll: AudioSessionOwner? = null
        var engineStateDuringEnroll: WakeEngineState? = null
        val acquired = vm.runWithEnrollmentMicrophoneOwnership {
            ownerDuringEnroll = vm.audioSessionOwner.value
            engineStateDuringEnroll = vm.wakeWordEngine.engineState.value
        }

        assertTrue("Enrollment guard should acquire microphone cleanly", acquired)
        assertEquals(AudioSessionOwner.ENROLLMENT_CAPTURE, ownerDuringEnroll)
        assertEquals(WakeEngineState.MICROPHONE_BUSY, engineStateDuringEnroll)
        // After enrollment finishes, wake engine must resume cleanly
        assertEquals(AudioSessionOwner.WAKE_WORD_ENGINE, vm.audioSessionOwner.value)
        assertEquals(WakeEngineState.LISTENING_FOR_WAKE, vm.wakeWordEngine.engineState.value)

        vm.wakeWordEngine.stopListening()
    }

    // ========================================================================
    // 6. FOREGROUND SERVICE STARTUP FAILURE, STAGED STATES & BOUNDED RETRIES (P0-3)
    // ========================================================================

    @Test
    fun testServiceStateRepositoryPreventsFakeBackgroundVoiceReadyWhenSubsystemsUnverified() {
        // Attempt to claim BACKGROUND_VOICE_READY while audio capture is NOT verified -> MUST downgrade to DEGRADED!
        ServiceStateRepository.updateBackgroundVoicePipeline(
            state = BackgroundVoicePipelineState.BACKGROUND_VOICE_READY,
            detail = "Attempting premature ready",
            isMicrophoneVerified = true,
            isWakeEngineVerified = true,
            isAudioCaptureVerified = false
        )
        val downgraded = ServiceStateRepository.backgroundVoicePipelineStatus.value
        assertEquals(
            "Repository invariant must block BACKGROUND_VOICE_READY unless mic + wake + capture are ALL verified",
            BackgroundVoicePipelineState.DEGRADED,
            downgraded.state
        )
        assertFalse(downgraded.state.isOperational)

        // Now verify all three prerequisites -> BACKGROUND_VOICE_READY succeeds!
        ServiceStateRepository.updateBackgroundVoicePipeline(
            state = BackgroundVoicePipelineState.BACKGROUND_VOICE_READY,
            detail = "All subsystems verified",
            isMicrophoneVerified = true,
            isWakeEngineVerified = true,
            isAudioCaptureVerified = true
        )
        val verified = ServiceStateRepository.backgroundVoicePipelineStatus.value
        assertEquals(BackgroundVoicePipelineState.BACKGROUND_VOICE_READY, verified.state)
        assertTrue(verified.state.isOperational)
    }

    @Test
    fun testForegroundServicePipelineEvaluationAndBoundedRetryBackoff() {
        // 1) Service not in foreground with failure -> ERROR
        val fgsFail = NayroForegroundService.evaluatePipelineReadiness(
            isServiceInForeground = false,
            hasMicPermission = true,
            isWakeTemplatesReady = true,
            isMicInitialized = false,
            isAudioCaptureActive = false,
            failureReason = "ForegroundServiceStartNotAllowedException"
        )
        assertEquals(BackgroundVoicePipelineState.ERROR, fgsFail.state)
        assertFalse(fgsFail.state.isOperational)

        // 2) Service running, templates missing -> DEGRADED
        val noTemplates = NayroForegroundService.evaluatePipelineReadiness(
            isServiceInForeground = true,
            hasMicPermission = true,
            isWakeTemplatesReady = false,
            isMicInitialized = false,
            isAudioCaptureActive = false
        )
        assertEquals(BackgroundVoicePipelineState.DEGRADED, noTemplates.state)
        assertFalse(noTemplates.state.isOperational)

        // 3) Service running, templates ok, AudioRecord not active -> DEGRADED
        val noCapture = NayroForegroundService.evaluatePipelineReadiness(
            isServiceInForeground = true,
            hasMicPermission = true,
            isWakeTemplatesReady = true,
            isMicInitialized = true,
            isAudioCaptureActive = false
        )
        assertEquals(BackgroundVoicePipelineState.DEGRADED, noCapture.state)
        assertFalse(noCapture.state.isOperational)

        // 4) All verified -> BACKGROUND_VOICE_READY
        val ready = NayroForegroundService.evaluatePipelineReadiness(
            isServiceInForeground = true,
            hasMicPermission = true,
            isWakeTemplatesReady = true,
            isMicInitialized = true,
            isAudioCaptureActive = true
        )
        assertEquals(BackgroundVoicePipelineState.BACKGROUND_VOICE_READY, ready.state)
        assertTrue(ready.state.isOperational)

        // 5) Bounded retry schedule (no infinite restart loop!)
        assertEquals(2_000L, NayroForegroundService.computeBoundedRecoveryDelayMs(1))
        assertEquals(5_000L, NayroForegroundService.computeBoundedRecoveryDelayMs(2))
        assertEquals(12_000L, NayroForegroundService.computeBoundedRecoveryDelayMs(3))
        assertNull("Retry attempt 4 must return null to stop infinite restart loops", NayroForegroundService.computeBoundedRecoveryDelayMs(4))
        assertNull(NayroForegroundService.computeBoundedRecoveryDelayMs(0))
    }

    // ========================================================================
    // 7. BACKGROUND VOICE RECOVERY & PERMISSION REVOCATION
    // ========================================================================

    @Test
    fun testBackgroundVoiceRecoveryHandlesInterruptionsAndPermissionRevocation() {
        val micGranted = AtomicBoolean(true)
        val factory = object : WakeAudioCaptureFactory {
            override fun getMinBufferSize(sampleRateHz: Int, channelConfig: Int, audioFormat: Int): Int = 3200
            override fun createSession(audioSource: Int, sampleRateHz: Int, channelConfig: Int, audioFormat: Int, bufferSizeInBytes: Int): WakeAudioCaptureSession {
                return FakeCaptureSession(isInitialized = true, entersRecordingOnStart = true)
            }
        }
        val vm = VoiceManager(context = null)
        vm.setMicPermissionCheckerForTesting { micGranted.get() }
        vm.wakeWordEngine.setAudioCaptureFactoryForTesting(factory)
        assertTrue(vm.wakeWordEngine.loadTemplatesFromJson(readBundledTemplatesAsset()).isValid)

        vm.updateConfiguration(VoiceConfiguration(isWakeWordEnabled = true))
        assertEquals(WakeEngineState.LISTENING_FOR_WAKE, vm.wakeWordEngine.engineState.value)
        assertEquals(VoiceState.STANDBY, vm.voiceState.value)

        // Simulate phone call interruption
        vm.onExternalAudioInterruption("Incoming phone call")
        assertEquals(AudioSessionOwner.PHONE_CALL_INTERRUPTION, vm.audioSessionOwner.value)
        assertEquals(MicrophoneLifecyclePhase.INTERRUPTED, vm.microphonePhase.value)
        assertEquals(WakeEngineState.MICROPHONE_BUSY, vm.wakeWordEngine.engineState.value)

        // End phone call -> recovers cleanly to STANDBY + LISTENING_FOR_WAKE
        vm.onExternalAudioInterruptionEnded()
        assertEquals(AudioSessionOwner.WAKE_WORD_ENGINE, vm.audioSessionOwner.value)
        assertEquals(WakeEngineState.LISTENING_FOR_WAKE, vm.wakeWordEngine.engineState.value)
        assertEquals(VoiceState.STANDBY, vm.voiceState.value)

        // Now simulate runtime RECORD_AUDIO permission revocation -> stops cleanly and does NOT recover
        micGranted.set(false)
        vm.onMicrophonePermissionRevoked()
        assertEquals(AudioSessionOwner.NONE, vm.audioSessionOwner.value)
        assertEquals(VoiceState.ERROR, vm.voiceState.value)
        assertFalse(vm.wakeWordEngine.isAudioCaptureActive.value)

        // Even if interruption ended fires, revoked permission keeps wake engine OFF
        vm.onExternalAudioInterruptionEnded()
        assertEquals(AudioSessionOwner.NONE, vm.audioSessionOwner.value)
        assertTrue(vm.wakeWordEngine.engineState.value != WakeEngineState.LISTENING_FOR_WAKE)
    }

    // ========================================================================
    // 8. SCREEN ON WAKE-WORD MODE (ZERO CLOUD API KEYS REQUIRED)
    // ========================================================================

    @Test
    fun testScreenOnLocalWakeWordsOperateWithoutAnyCloudApiKeys() {
        val vm = VoiceManager(context = null)
        assertTrue(vm.wakeWordEngine.loadTemplatesFromJson(readBundledTemplatesAsset()).isValid)

        val identityStore = AssistantIdentityStore(context = null)
        identityStore.setWakePhrases(listOf("Hi Nayro", "Hey Nayro", "Hi Babu", "Hey Babu"))
        vm.bindIdentityStore(identityStore)

        // Confirm zero cloud keys are configured or needed for local wake matching
        assertTrue(vm.wakeWordEngine.hasUsableAcousticTemplates())
        for (phrase in listOf("Hi Nayro", "Hey Nayro", "Hi Babu")) {
            val tpl = vm.wakeWordEngine.getLoadedTemplate(phrase)
            assertNotNull("Expected bundled template for $phrase", tpl)
            val matched = vm.wakeWordEngine.matchMfccSequenceAgainstTemplates(
                querySeq = tpl!!.mfccSequence,
                rms = 2500.0,
                zcr = 0.14,
                voicedRatio = 0.72f,
                triggerCallback = false
            )
            assertNotNull("Bundled phrase $phrase must match locally", matched)
            assertEquals(phrase.lowercase(), matched!!.first.lowercase())
        }
    }

    // ========================================================================
    // 9. SCREEN OFF WAKE-WORD MODE & ANDROID 10–16 + OEM COMPATIBILITY MATRIX
    // ========================================================================

    @Test
    fun testScreenOffWakeHonestyAcrossAndroid10To16AndMajorOems() {
        val oems = listOf(
            "realme" to "ColorOS/Realme",
            "OPPO" to "ColorOS/OxygenOS",
            "OnePlus" to "ColorOS/OxygenOS",
            "Xiaomi" to "MIUI/HyperOS",
            "Samsung" to "One UI",
            "vivo" to "FuntouchOS",
            "Google" to "Pixel"
        )
        val sdkLevels = listOf(29, 30, 31, 33, 34, 35, 36)

        for ((manufacturer, expectedToken) in oems) {
            for (sdk in sdkLevels) {
                val report = ScreenOffWakeCapabilityManager.evaluateCapability(
                    context = null,
                    isLocalWakeListening = true,
                    sdkInt = sdk,
                    manufacturer = manufacturer,
                    micGrantedOverride = true,
                    roleHeldOverride = true,
                    serviceActiveOverride = true,
                    hardwareDspSupportedOverride = false,
                    batteryExemptOverride = false,
                    notificationPermissionOverride = true,
                    isWakeTemplatesReady = true,
                    isForceStoppedOrDisabled = false
                )
                assertEquals(ScreenOffWakeMode.LOCAL_FALLBACK_ACTIVE, report.mode)
                assertTrue("Screen-on wake should be ready when capture active", report.isScreenOnWakeReady)
                assertFalse(
                    "Third-party install without DSP MUST NEVER claim guaranteed screen-off wake verification",
                    report.isScreenOffWakeVerified
                )
                assertFalse(report.isHardwareDspHotwordSupported)
                assertTrue(
                    "OEM note for $manufacturer API $sdk should mention $expectedToken: ${report.oemCompatibilityNote}",
                    report.oemCompatibilityNote.contains(expectedToken, ignoreCase = true)
                )
                assertTrue(
                    "Android version note should exist for API $sdk",
                    report.androidVersionCompatibilityNote.isNotBlank()
                )
            }
        }

        // Force-stopped or disabled app state MUST report UNSUPPORTED_OR_BLOCKED
        val forceStoppedReport = ScreenOffWakeCapabilityManager.evaluateCapability(
            context = null,
            isLocalWakeListening = true,
            sdkInt = 35,
            manufacturer = "realme",
            micGrantedOverride = true,
            roleHeldOverride = true,
            serviceActiveOverride = true,
            hardwareDspSupportedOverride = false,
            batteryExemptOverride = true,
            isForceStoppedOrDisabled = true
        )
        assertEquals(ScreenOffWakeMode.UNSUPPORTED_OR_BLOCKED, forceStoppedReport.mode)
        assertFalse(forceStoppedReport.isScreenOnWakeReady)
        assertFalse(forceStoppedReport.isScreenOffWakeVerified)

        // Missing/invalid wake templates MUST NOT report LOCAL_FALLBACK_ACTIVE even if FGS is running!
        val invalidTemplateReport = ScreenOffWakeCapabilityManager.evaluateCapability(
            context = null,
            isLocalWakeListening = true,
            sdkInt = 34,
            manufacturer = "Google",
            micGrantedOverride = true,
            roleHeldOverride = false,
            serviceActiveOverride = false,
            hardwareDspSupportedOverride = false,
            batteryExemptOverride = true,
            isWakeTemplatesReady = false
        )
        assertEquals(ScreenOffWakeMode.UNSUPPORTED_OR_BLOCKED, invalidTemplateReport.mode)
        assertFalse(invalidTemplateReport.isLocalFallbackListeningActive)
        assertFalse(invalidTemplateReport.isScreenOnWakeReady)
    }

    // ========================================================================
    // 10 & 11. VOICEINTERACTIONSERVICE LIFECYCLE + FLOATING ASSISTANT COEXISTENCE
    // ========================================================================

    @Test
    fun testVoiceInteractionServiceLifecycleAndIsolatedListenerCoexistenceWithFloatingAssistant() {
        // Verify VoiceInteractionService lifecycle state transitions honestly
        ScreenOffWakeCapabilityManager.setServiceActive(
            active = true,
            dspNote = "Third-party install assist active",
            hasHardwareDsp = false
        )
        assertEquals(
            VoiceInteractionServiceLifecycleState.READY_SYSTEM_ASSIST_ONLY,
            ScreenOffWakeCapabilityManager.serviceLifecycleState.value
        )

        ScreenOffWakeCapabilityManager.setServiceActive(
            active = true,
            dspNote = "Privileged OEM DSP active",
            hasHardwareDsp = true
        )
        assertEquals(
            VoiceInteractionServiceLifecycleState.READY_HARDWARE_DSP_HOTWORD,
            ScreenOffWakeCapabilityManager.serviceLifecycleState.value
        )

        ScreenOffWakeCapabilityManager.setServiceActive(active = false)
        assertEquals(
            VoiceInteractionServiceLifecycleState.SHUTDOWN,
            ScreenOffWakeCapabilityManager.serviceLifecycleState.value
        )

        // Verify isolated VoiceInteractionSession listener NEVER overwrites Floating or UI listeners!
        val vm = VoiceManager(context = null)
        vm.attachUiListener(onRecognized = { })
        vm.attachFloatingListener { }
        assertTrue(vm.hasActiveUiListener())
        assertTrue(vm.hasActiveFloatingListener())
        assertFalse(vm.hasActiveVoiceInteractionListener())

        // Attach VoiceInteractionSession listener
        vm.attachVoiceInteractionListener { }
        assertTrue(vm.hasActiveVoiceInteractionListener())
        assertTrue("Floating listener MUST remain attached", vm.hasActiveFloatingListener())
        assertTrue("UI listener MUST remain attached", vm.hasActiveUiListener())

        // Detach VoiceInteractionSession listener -> Floating and UI listeners MUST STILL BE INTACT!
        vm.detachVoiceInteractionListener()
        assertFalse(vm.hasActiveVoiceInteractionListener())
        assertTrue("Detaching VoiceInteractionSession MUST NOT detach Floating listener", vm.hasActiveFloatingListener())
        assertTrue("Detaching VoiceInteractionSession MUST NOT detach UI listener", vm.hasActiveUiListener())

        // Stop / endConversationSession must immediately release microphone ownership
        vm.endConversationSession(returnToStandby = false)
        assertEquals(AudioSessionOwner.NONE, vm.audioSessionOwner.value)
        assertEquals(MicrophoneLifecyclePhase.IDLE, vm.microphonePhase.value)
    }

    // ========================================================================
    // 12. EXISTING AGENTRUNTIME / CLASSIFIER COMPATIBILITY
    // ========================================================================

    @Test
    fun testExistingAgentIntentClassificationRemainsCompatibleWithoutRegression() {
        val deviceResult = TaskClassifier.classify("call Prakash")
        assertEquals(UserIntentType.PHONE_CALL, deviceResult.type)

        val chatResult = TaskClassifier.classify("Explain how quantum computers work in simple words")
        assertEquals(UserIntentType.GENERAL_AI_QUERY, chatResult.type)

        val multiStepResult = TaskClassifier.classify("Open YouTube and search for lofi chill beats")
        assertEquals(UserIntentType.YOUTUBE_SEARCH, multiStepResult.type)
    }
}
