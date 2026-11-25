package com.advanceagent.core.network

import android.util.Base64
import android.util.Log
import com.advanceagent.core.data.NayroPersonalityMode
import com.advanceagent.core.data.PromptGenerator
import com.advanceagent.core.data.chat.ChatRepository
import com.advanceagent.core.tools.AgentToolDeclarations
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.Collections
import java.util.concurrent.TimeUnit

class GeminiLiveWebSocket(
    private val apiKey: String,
    private val modelId: String,
    private val voiceName: String,
    private val userName: String,
    private val memories: List<String> = emptyList(),
    private val assistantName: String = "Babu",
    private val personalityMode: NayroPersonalityMode = NayroPersonalityMode.ASSISTANT,
    private val includeGoogleSearchGrounding: Boolean = true,
    private val enableLiveTranscription: Boolean = true,
    private val enableSessionResumption: Boolean = true,
    private val initialSessionResumptionHandle: String = "",
    private val ephemeralAccessToken: String = "",
    private val onAudioReceived: (ByteArray) -> Unit,
    private val onInterrupted: () -> Unit,
    private val onToolCall: (IncomingFunctionCall) -> Unit,
    private val onStateChanged: (String) -> Unit,
    private val onSessionHandleUpdated: (String) -> Unit = {},
    private val onTranscriptReceived: (String, Boolean) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "GeminiLiveWS"
        private const val HOST = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    @Volatile var isSetupComplete = false
        private set

    @Volatile private var currentResumptionHandle: String = initialSessionResumptionHandle
    @Volatile private var manualDisconnect = false
    @Volatile private var useCompatibilityFallbackSetup = false
    private var reconnectAttempt = 0
    private val cancelledToolCallIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    fun connect() {
        manualDisconnect = false
        val authParam = if (ephemeralAccessToken.isNotBlank()) {
            "access_token=${ephemeralAccessToken.trim()}"
        } else {
            "key=${apiKey.trim()}"
        }
        val url = "$HOST?$authParam"
        val request = Request.Builder().url(url).build()
        onStateChanged("CONNECTING")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Connected to Gemini Live WebSocket (compatFallback=$useCompatibilityFallbackSetup)")
                reconnectAttempt = 0
                sendInitialSetup(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerPayload(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleServerPayload(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket Closing: $code / $reason")
                val failedBeforeSetup = !isSetupComplete
                isSetupComplete = false
                if (!manualDisconnect && failedBeforeSetup && !useCompatibilityFallbackSetup && code != 1000) {
                    Log.w(TAG, "Setup rejected ($code / $reason); retrying with universal models/gemini-2.0-flash-exp compatibility config")
                    useCompatibilityFallbackSetup = true
                    scheduleReconnect()
                    return
                }
                onStateChanged("DISCONNECTED")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket Failure: ${t.message}", t)
                val failedBeforeSetup = !isSetupComplete
                isSetupComplete = false
                if (!manualDisconnect && failedBeforeSetup && !useCompatibilityFallbackSetup) {
                    useCompatibilityFallbackSetup = true
                }
                onStateChanged("ERROR")
                if (!manualDisconnect && (enableSessionResumption || failedBeforeSetup) && reconnectAttempt < 3) {
                    scheduleReconnect()
                }
            }
        })
    }

    private fun scheduleReconnect() {
        val attempt = ++reconnectAttempt
        val backoffMs = (1000L * (1 shl (attempt - 1))).coerceAtMost(8000L)
        Log.i(TAG, "Scheduling Gemini Live session resumption reconnect #$attempt in ${backoffMs}ms")
        scope.launch {
            delay(backoffMs)
            if (!manualDisconnect) {
                connect()
            }
        }
    }

    internal fun buildInitialSetupMessage(compatibilityMode: Boolean = useCompatibilityFallbackSetup): BidiSetupMessage {
        val systemPrompt = PromptGenerator.buildGeminiLiveSystemInstruction(
            assistantName = assistantName,
            userName = userName,
            personalityMode = personalityMode,
            memories = memories
        )
        val resolvedModel = if (compatibilityMode) {
            "models/gemini-2.0-flash-exp"
        } else if (modelId.startsWith("models/")) {
            modelId
        } else {
            "models/$modelId"
        }

        return if (compatibilityMode) {
            BidiSetupMessage(
                setup = BidiSetupConfig(
                    model = resolvedModel,
                    generationConfig = GenerationConfig(
                        responseModalities = listOf("AUDIO"),
                        speechConfig = SpeechConfig(
                            voiceConfig = VoiceConfig(
                                prebuiltVoiceConfig = PrebuiltVoiceConfig(voiceName = voiceName)
                            )
                        )
                    ),
                    systemInstruction = SystemInstruction(parts = listOf(TextPart(systemPrompt))),
                    tools = AgentToolDeclarations.allTools(includeGoogleSearchGrounding = false)
                )
            )
        } else {
            BidiSetupMessage(
                setup = BidiSetupConfig(
                    model = resolvedModel,
                    generationConfig = GenerationConfig(
                        responseModalities = listOf("AUDIO"),
                        speechConfig = SpeechConfig(
                            voiceConfig = VoiceConfig(
                                prebuiltVoiceConfig = PrebuiltVoiceConfig(voiceName = voiceName)
                            )
                        )
                    ),
                    systemInstruction = SystemInstruction(parts = listOf(TextPart(systemPrompt))),
                    tools = AgentToolDeclarations.allTools(includeGoogleSearchGrounding = includeGoogleSearchGrounding),
                    inputAudioTranscription = if (enableLiveTranscription) emptyMap() else null,
                    outputAudioTranscription = if (enableLiveTranscription) emptyMap() else null,
                    sessionResumption = if (enableSessionResumption && currentResumptionHandle.isNotBlank()) {
                        SessionResumptionConfig(handle = currentResumptionHandle)
                    } else null
                )
            )
        }
    }

    private fun sendInitialSetup(ws: WebSocket) {
        val setupMsg = buildInitialSetupMessage(useCompatibilityFallbackSetup)
        val json = gson.toJson(setupMsg)
        ws.send(json)
    }

    private fun handleServerPayload(jsonText: String) {
        try {
            val msg = gson.fromJson(jsonText, GeminiServerMessage::class.java) ?: return

            if (msg.setupComplete != null) {
                isSetupComplete = true
                Log.i(TAG, "Gemini Live setupComplete acknowledged")
                onStateChanged("LISTENING")
                return
            }

            msg.sessionResumptionUpdate?.let { update ->
                if (update.resumable && !update.newHandle.isNullOrBlank()) {
                    currentResumptionHandle = update.newHandle
                    onSessionHandleUpdated(update.newHandle)
                }
            }

            msg.goAway?.let { goAway ->
                Log.w(TAG, "Gemini Live goAway received (timeLeft=${goAway.timeLeft}). Will reconnect with session handle.")
                if (!manualDisconnect && enableSessionResumption) {
                    scheduleReconnect()
                }
            }

            msg.toolCallCancellation?.let { cancellation ->
                if (cancellation.ids.isNotEmpty()) {
                    Log.w(TAG, "Gemini Live cancelled in-flight tool calls: ${cancellation.ids}")
                    cancelledToolCallIds.addAll(cancellation.ids)
                }
            }

            msg.serverContent?.let { content ->
                if (content.interrupted == true) {
                    Log.i(TAG, "User barge-in interruption detected")
                    onInterrupted()
                    return
                }

                content.inputTranscription?.text?.takeIf { it.isNotBlank() }?.let { transcript ->
                    if (enableLiveTranscription) {
                        ChatRepository.recordUserTranscript(transcript)
                        onTranscriptReceived(transcript, true)
                    }
                }

                content.outputTranscription?.text?.takeIf { it.isNotBlank() }?.let { transcript ->
                    if (enableLiveTranscription) {
                        ChatRepository.recordAssistantTranscript(transcript)
                        onTranscriptReceived(transcript, false)
                    }
                }

                content.modelTurn?.parts?.forEach { part ->
                    part.text?.takeIf { it.isNotBlank() }?.let { textChunk ->
                        if (enableLiveTranscription) {
                            ChatRepository.recordAssistantTranscript(textChunk)
                        }
                    }
                    part.inlineData?.let { inline ->
                        if (inline.mimeType?.startsWith("audio/pcm") == true && inline.data != null) {
                            val pcmBytes = Base64.decode(inline.data, Base64.DEFAULT)
                            onAudioReceived(pcmBytes)
                        }
                    }
                }

                if (content.turnComplete == true) {
                    onStateChanged("LISTENING")
                }
            }

            msg.toolCall?.functionCalls?.forEach { call ->
                if (cancelledToolCallIds.contains(call.id)) {
                    Log.i(TAG, "Skipping cancelled toolCall id=${call.id} (${call.name})")
                } else {
                    Log.i(TAG, "Executing FunctionCall: ${call.name} (${call.id})")
                    onStateChanged("THINKING")
                    onToolCall(call)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing server message: ${e.message}", e)
        }
    }

    fun isToolCallCancelled(id: String): Boolean = cancelledToolCallIds.contains(id)

    fun sendPcmAudioChunk(pcm16kBytes: ByteArray) {
        if (!isSetupComplete) return
        val base64Audio = Base64.encodeToString(pcm16kBytes, Base64.NO_WRAP)
        val payload = BidiRealtimeInputMessage(
            realtimeInput = RealtimeInputPayload(
                mediaChunks = listOf(
                    MediaChunk(
                        mimeType = "audio/pcm;rate=16000",
                        data = base64Audio
                    )
                )
            )
        )
        webSocket?.send(gson.toJson(payload))
    }

    /**
     * Streams an on-demand Camera or MediaProjection JPEG frame to the active Gemini Live session.
     */
    fun sendJpegFrame(jpegBase64: String, mimeType: String = "image/jpeg"): Boolean {
        if (!isSetupComplete || jpegBase64.isBlank()) return false
        val payload = BidiRealtimeInputMessage(
            realtimeInput = RealtimeInputPayload(
                mediaChunks = listOf(
                    MediaChunk(
                        mimeType = mimeType,
                        data = jpegBase64
                    )
                )
            )
        )
        return webSocket?.send(gson.toJson(payload)) == true
    }

    fun sendToolResponse(callId: String, functionName: String, result: Map<String, Any>) {
        if (cancelledToolCallIds.remove(callId)) {
            Log.i(TAG, "Not sending toolResponse for cancelled callId=$callId")
            return
        }
        val msg = BidiToolResponseMessage(
            toolResponse = ToolResponsePayload(
                functionResponses = listOf(
                    FunctionResponseItem(
                        id = callId,
                        name = functionName,
                        response = result
                    )
                )
            )
        )
        webSocket?.send(gson.toJson(msg))
    }

    fun disconnect() {
        manualDisconnect = true
        isSetupComplete = false
        try {
            webSocket?.close(1000, "Session Ended")
        } catch (_: Exception) {}
        webSocket = null
    }
}
