package com.advanceagent.core.network

import com.google.gson.JsonElement
import com.google.gson.JsonObject

data class BidiSetupMessage(
    val setup: BidiSetupConfig
)

data class BidiSetupConfig(
    val model: String = "models/gemini-2.5-flash-native-audio-preview-09-2025",
    val generationConfig: GenerationConfig = GenerationConfig(),
    val systemInstruction: SystemInstruction? = null,
    val tools: List<ToolContainer>? = null,
    val realtimeInputConfig: RealtimeInputConfig? = null,
    val inputAudioTranscription: Map<String, Any>? = null,
    val outputAudioTranscription: Map<String, Any>? = null,
    val sessionResumption: SessionResumptionConfig? = null,
    val contextWindowCompression: ContextWindowCompressionConfig? = null
)

data class RealtimeInputConfig(
    val automaticActivityDetection: AutomaticActivityDetectionConfig = AutomaticActivityDetectionConfig()
)

data class AutomaticActivityDetectionConfig(
    val disabled: Boolean = false,
    val prefixPaddingMs: Int = 300,
    val silenceDurationMs: Int = 700
)

data class SessionResumptionConfig(
    val handle: String? = null
)

data class ContextWindowCompressionConfig(
    val slidingWindow: SlidingWindowConfig = SlidingWindowConfig()
)

data class SlidingWindowConfig(
    val targetTokens: Int = 16384
)

data class GenerationConfig(
    val responseModalities: List<String> = listOf("AUDIO"),
    val speechConfig: SpeechConfig = SpeechConfig()
)

data class SpeechConfig(
    val voiceConfig: VoiceConfig = VoiceConfig()
)

data class VoiceConfig(
    val prebuiltVoiceConfig: PrebuiltVoiceConfig = PrebuiltVoiceConfig()
)

data class PrebuiltVoiceConfig(
    val voiceName: String = "Puck"
)

data class SystemInstruction(
    val parts: List<TextPart>
)

data class TextPart(
    val text: String
)

data class ToolContainer(
    val functionDeclarations: List<FunctionDeclaration>? = null,
    val googleSearch: Map<String, Any>? = null
)

data class FunctionDeclaration(
    val name: String,
    val description: String,
    val parameters: ParameterSchema
)

data class ParameterSchema(
    val type: String = "OBJECT",
    val properties: Map<String, PropertySpec> = emptyMap(),
    val required: List<String> = emptyList()
)

data class PropertySpec(
    val type: String,
    val description: String
)

data class BidiRealtimeInputMessage(
    val realtimeInput: RealtimeInputPayload
)

data class RealtimeInputPayload(
    val mediaChunks: List<MediaChunk>
)

data class MediaChunk(
    val mimeType: String,
    val data: String
)

data class BidiToolResponseMessage(
    val toolResponse: ToolResponsePayload
)

data class ToolResponsePayload(
    val functionResponses: List<FunctionResponseItem>
)

data class FunctionResponseItem(
    val id: String,
    val name: String,
    val response: Map<String, Any>
)

data class GeminiServerMessage(
    val setupComplete: JsonElement? = null,
    val serverContent: ServerContent? = null,
    val toolCall: ToolCallPayload? = null,
    val toolCallCancellation: ToolCallCancellation? = null,
    val sessionResumptionUpdate: SessionResumptionUpdate? = null,
    val goAway: GoAwayNotice? = null
)

data class ServerContent(
    val modelTurn: ModelTurn? = null,
    val interrupted: Boolean? = null,
    val turnComplete: Boolean? = null,
    val inputTranscription: TranscriptionPayload? = null,
    val outputTranscription: TranscriptionPayload? = null
)

data class TranscriptionPayload(
    val text: String? = null
)

data class ModelTurn(
    val parts: List<ServerPart>? = null
)

data class ServerPart(
    val text: String? = null,
    val inlineData: InlineAudioData? = null
)

data class InlineAudioData(
    val mimeType: String? = null,
    val data: String? = null
)

data class ToolCallPayload(
    val functionCalls: List<IncomingFunctionCall>? = null
)

data class IncomingFunctionCall(
    val id: String,
    val name: String,
    val args: JsonObject? = null
)

data class ToolCallCancellation(
    val ids: List<String> = emptyList()
)

data class SessionResumptionUpdate(
    val newHandle: String? = null,
    val resumable: Boolean = true
)

data class GoAwayNotice(
    val timeLeft: String? = null
)
