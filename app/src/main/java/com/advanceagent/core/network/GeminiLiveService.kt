package com.advanceagent.core.network

import android.content.Context
import android.util.Base64
import com.advanceagent.core.audio.AudioStreamManager
import com.advanceagent.core.data.SecurePreferences
import com.advanceagent.core.data.chat.ChatRepository
import com.advanceagent.core.tools.AgentToolRouter
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * High-level service managing the Google AI Studio Gemini Live bidirectional WebSocket
 * (`wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent`),
 * 16kHz PCM16 microphone audio capture, 24kHz PCM16 audio playback, on-demand vision JPEG streaming,
 * and natural barge-in interruption handling via the server's `interrupted` flag.
 */
class GeminiLiveService(
    private val context: Context? = null,
    private val preferences: SecurePreferences = SecurePreferences(context),
    private val audioStreamManager: AudioStreamManager = AudioStreamManager(),
    private val toolRouter: AgentToolRouter = AgentToolRouter(context)
) {
    companion object {
        const val BIDI_ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val INPUT_SAMPLE_RATE_HZ = AudioStreamManager.INPUT_SAMPLE_RATE // 16000 Hz
        const val OUTPUT_SAMPLE_RATE_HZ = AudioStreamManager.OUTPUT_SAMPLE_RATE // 24000 Hz
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private var webSocketClient: GeminiLiveWebSocket? = null

    private val _sessionState = MutableStateFlow("IDLE")
    val sessionState: StateFlow<String> = _sessionState.asStateFlow()

    private val _isBargeInInterrupted = MutableStateFlow(false)
    val isBargeInInterrupted: StateFlow<Boolean> = _isBargeInInterrupted.asStateFlow()

    private val _bargeInInterruptionCount = MutableStateFlow(0)
    val bargeInInterruptionCount: StateFlow<Int> = _bargeInInterruptionCount.asStateFlow()

    private val _lastAudioOutputSampleRate = MutableStateFlow(OUTPUT_SAMPLE_RATE_HZ)
    val lastAudioOutputSampleRate: StateFlow<Int> = _lastAudioOutputSampleRate.asStateFlow()

    /**
     * Starts a bidirectional Gemini Live session and begins 16kHz microphone capture & 24kHz audio output.
     */
    fun startLiveSession(
        memories: List<String> = emptyList(),
        startHardwareAudio: Boolean = context != null
    ): Boolean {
        val apiKey = preferences.geminiApiKey.trim()
        val ephemeralToken = preferences.ephemeralAccessToken.trim()
        if (apiKey.isEmpty() && ephemeralToken.isEmpty()) {
            _sessionState.value = "ERROR_MISSING_API_KEY"
            return false
        }

        _isBargeInInterrupted.value = false
        _sessionState.value = "CONNECTING"

        webSocketClient?.disconnect()
        val client = GeminiLiveWebSocket(
            apiKey = apiKey,
            modelId = preferences.geminiModel,
            voiceName = preferences.voiceName,
            userName = preferences.userName,
            memories = memories,
            assistantName = preferences.assistantName,
            personalityMode = preferences.personalityMode,
            includeGoogleSearchGrounding = preferences.googleSearchGroundingEnabled,
            enableLiveTranscription = preferences.liveTranscriptionEnabled,
            enableSessionResumption = preferences.sessionResumptionEnabled,
            initialSessionResumptionHandle = preferences.lastSessionResumptionHandle,
            ephemeralAccessToken = ephemeralToken,
            onAudioReceived = { pcm24kBytes ->
                handleIncoming24kHzAudio(pcm24kBytes)
            },
            onInterrupted = {
                handleBargeInInterruption()
            },
            onToolCall = { functionCall ->
                scope.launch {
                    _sessionState.value = "EXECUTING"
                    val result = toolRouter.executeTool(functionCall.name, functionCall.args)
                    webSocketClient?.sendToolResponse(functionCall.id, functionCall.name, result)
                    _sessionState.value = "LISTENING"
                }
            },
            onStateChanged = { newState ->
                _sessionState.value = newState
            },
            onSessionHandleUpdated = { newHandle ->
                preferences.lastSessionResumptionHandle = newHandle
            }
        )
        webSocketClient = client
        client.connect()

        if (startHardwareAudio) {
            audioStreamManager.startCapture(
                scope = scope,
                onPcmShortChunk = { _, _ -> },
                onAmplitude = null,
                onPcmByteChunk = { pcm16kBytes ->
                    stream16kHzPcmInput(pcm16kBytes)
                }
            )
        }
        return true
    }

    /**
     * Streams a 16kHz 16-bit mono PCM audio chunk to Gemini Live (`audio/pcm;rate=16000`).
     */
    fun stream16kHzPcmInput(pcm16kBytes: ByteArray): BidiRealtimeInputMessage {
        val base64Audio = try {
            Base64.encodeToString(pcm16kBytes, Base64.NO_WRAP)
        } catch (_: Throwable) {
            java.util.Base64.getEncoder().encodeToString(pcm16kBytes)
        }
        val msg = BidiRealtimeInputMessage(
            realtimeInput = RealtimeInputPayload(
                mediaChunks = listOf(
                    MediaChunk(
                        mimeType = "audio/pcm;rate=$INPUT_SAMPLE_RATE_HZ",
                        data = base64Audio
                    )
                )
            )
        )
        webSocketClient?.sendPcmAudioChunk(pcm16kBytes)
        return msg
    }

    /**
     * Streams an on-demand JPEG screen or camera frame (`image/jpeg`) into the active Gemini Live session.
     */
    fun streamVisionJpegFrame(jpegBase64: String): Boolean {
        if (jpegBase64.isBlank()) return false
        return webSocketClient?.sendJpegFrame(jpegBase64, "image/jpeg") == true
    }

    /**
     * Enqueues a 24kHz 16-bit mono PCM audio chunk received from Gemini Live (`audio/pcm;rate=24000`).
     */
    fun handleIncoming24kHzAudio(pcm24kBytes: ByteArray) {
        if (pcm24kBytes.isEmpty()) return
        _isBargeInInterrupted.value = false
        _lastAudioOutputSampleRate.value = OUTPUT_SAMPLE_RATE_HZ
        _sessionState.value = "SPEAKING"
        if (context != null) {
            audioStreamManager.writePlaybackPcm(pcm24kBytes)
        }
    }

    /**
     * Natural barge-in interruption handler triggered when Gemini Live sends `serverContent.interrupted == true`
     * or when the user speaks over the assistant. Immediately flushes the 24kHz playback buffer and switches
     * the session back to `LISTENING`.
     */
    fun handleBargeInInterruption() {
        _isBargeInInterrupted.value = true
        _bargeInInterruptionCount.value += 1
        audioStreamManager.flushPlaybackForBargeIn()
        _sessionState.value = "LISTENING"
    }

    /**
     * Parses and processes a raw `BidiGenerateContent` server JSON message, handling `interrupted: true`
     * barge-in, 24kHz inline PCM audio chunks, real-time transcriptions, and tool calls deterministically.
     */
    fun processServerMessageJson(rawJson: String): GeminiServerMessage? {
        val parsed = gson.fromJson(rawJson, GeminiServerMessage::class.java) ?: return null

        if (parsed.setupComplete != null) {
            _sessionState.value = "LISTENING"
            return parsed
        }

        parsed.serverContent?.let { content ->
            if (content.interrupted == true) {
                handleBargeInInterruption()
                return parsed
            }

            content.inputTranscription?.text?.takeIf { it.isNotBlank() }?.let {
                ChatRepository.recordUserTranscript(it)
            }

            content.outputTranscription?.text?.takeIf { it.isNotBlank() }?.let {
                ChatRepository.recordAssistantTranscript(it)
            }

            content.modelTurn?.parts?.forEach { part ->
                part.inlineData?.let { inline ->
                    if (inline.mimeType?.startsWith("audio/pcm") == true && !inline.data.isNullOrBlank()) {
                        val decoded = try {
                            Base64.decode(inline.data, Base64.DEFAULT)
                        } catch (_: Throwable) {
                            java.util.Base64.getDecoder().decode(inline.data)
                        }
                        handleIncoming24kHzAudio(decoded)
                    }
                }
            }

            if (content.turnComplete == true) {
                _sessionState.value = "LISTENING"
            }
        }
        return parsed
    }

    fun stopLiveSession() {
        webSocketClient?.disconnect()
        webSocketClient = null
        audioStreamManager.releaseAll()
        _sessionState.value = "IDLE"
    }
}
