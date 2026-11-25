package com.google.gson

import com.advanceagent.core.network.GeminiServerMessage
import com.advanceagent.core.network.GoAwayNotice
import com.advanceagent.core.network.IncomingFunctionCall
import com.advanceagent.core.network.InlineAudioData
import com.advanceagent.core.network.ModelTurn
import com.advanceagent.core.network.ServerContent
import com.advanceagent.core.network.ServerPart
import com.advanceagent.core.network.SessionResumptionUpdate
import com.advanceagent.core.network.ToolCallCancellation
import com.advanceagent.core.network.ToolCallPayload
import com.advanceagent.core.network.TranscriptionPayload
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight zero-dependency Gson-compatible JSON tree & serializer for NAYRO / com.advanceagent.core.
 */
open class JsonElement(private val rawValue: Any?) {
    val asString: String
        get() = rawValue?.toString().orEmpty()

    val asFloat: Float
        get() = when (rawValue) {
            is Number -> rawValue.toFloat()
            is String -> rawValue.toFloatOrNull() ?: 0f
            else -> 0f
        }

    val asInt: Int
        get() = when (rawValue) {
            is Number -> rawValue.toInt()
            is String -> rawValue.toIntOrNull() ?: 0
            else -> 0
        }

    val asBoolean: Boolean
        get() = when (rawValue) {
            is Boolean -> rawValue
            is String -> rawValue.equals("true", ignoreCase = true)
            else -> false
        }

    fun unwrap(): Any? = rawValue

    override fun toString(): String = rawValue?.toString() ?: "null"
}

class JsonObject(
    private val backing: MutableMap<String, JsonElement> = LinkedHashMap()
) : JsonElement(backing) {

    fun addProperty(key: String, value: String?) {
        if (value == null) backing.remove(key) else backing[key] = JsonElement(value)
    }

    fun addProperty(key: String, value: Number?) {
        if (value == null) backing.remove(key) else backing[key] = JsonElement(value)
    }

    fun addProperty(key: String, value: Boolean?) {
        if (value == null) backing.remove(key) else backing[key] = JsonElement(value)
    }

    fun add(key: String, element: JsonElement?) {
        if (element == null) backing.remove(key) else backing[key] = element
    }

    fun get(key: String): JsonElement? = backing[key]

    fun has(key: String): Boolean = backing.containsKey(key)

    fun keySet(): Set<String> = backing.keys

    fun toMap(): Map<String, Any?> = backing.mapValues { (_, v) ->
        when (v) {
            is JsonObject -> v.toMap()
            else -> v.unwrap()
        }
    }

    companion object {
        fun fromOrgJson(obj: JSONObject): JsonObject {
            val result = JsonObject()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.opt(k)
                if (v == null || v === JSONObject.NULL) continue
                when (v) {
                    is JSONObject -> result.add(k, fromOrgJson(v))
                    else -> result.add(k, JsonElement(v))
                }
            }
            return result
        }
    }
}

class Gson {

    fun toJson(src: Any?): String {
        return toJsonValue(src).toString()
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> fromJson(json: String, classOfT: Class<T>): T {
        val trimmed = json.trim()
        if (classOfT == GeminiServerMessage::class.java) {
            return parseGeminiServerMessage(trimmed) as T
        }
        if (classOfT == JsonObject::class.java) {
            return parseRawJsonObject(trimmed) as T
        }
        throw IllegalArgumentException("Unsupported deserialization target: ${classOfT.name}")
    }

    private fun parseGeminiServerMessage(json: String): GeminiServerMessage {
        val root = parseRawMap(json)
        val setupComplete = if (root.containsKey("setupComplete")) {
            JsonElement(root["setupComplete"] ?: true)
        } else {
            null
        }

        val serverContentMap = root["serverContent"] as? Map<*, *>
        val serverContent = serverContentMap?.let { sc ->
            val interrupted = sc["interrupted"] as? Boolean
            val turnComplete = sc["turnComplete"] as? Boolean
            val modelTurnMap = sc["modelTurn"] as? Map<*, *>
            val partsList = (modelTurnMap?.get("parts") as? List<*>)?.mapNotNull { p ->
                val pm = p as? Map<*, *> ?: return@mapNotNull null
                val text = pm["text"] as? String
                val inlineMap = pm["inlineData"] as? Map<*, *>
                val inlineData = inlineMap?.let {
                    InlineAudioData(
                        mimeType = it["mimeType"] as? String,
                        data = it["data"] as? String
                    )
                }
                ServerPart(text = text, inlineData = inlineData)
            }
            val inputTransMap = sc["inputTranscription"] as? Map<*, *>
            val outputTransMap = sc["outputTranscription"] as? Map<*, *>
            val inputTranscription = inputTransMap?.let {
                TranscriptionPayload(text = it["text"]?.toString())
            }
            val outputTranscription = outputTransMap?.let {
                TranscriptionPayload(text = it["text"]?.toString())
            }
            ServerContent(
                modelTurn = if (partsList != null) ModelTurn(partsList) else null,
                interrupted = interrupted,
                turnComplete = turnComplete,
                inputTranscription = inputTranscription,
                outputTranscription = outputTranscription
            )
        }

        val toolCallMap = root["toolCall"] as? Map<*, *>
        val toolCall = toolCallMap?.let { tc ->
            val calls = (tc["functionCalls"] as? List<*>)?.mapNotNull { fc ->
                val fcm = fc as? Map<*, *> ?: return@mapNotNull null
                val id = fcm["id"]?.toString() ?: ""
                val name = fcm["name"]?.toString() ?: ""
                val argsMap = fcm["args"] as? Map<*, *>
                val argsObj = argsMap?.let { mapToJsonObject(it) }
                IncomingFunctionCall(id = id, name = name, args = argsObj)
            }
            ToolCallPayload(functionCalls = calls)
        }

        val cancelMap = root["toolCallCancellation"] as? Map<*, *>
        val toolCallCancellation = cancelMap?.let { cm ->
            val ids = (cm["ids"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            ToolCallCancellation(ids = ids)
        }

        val resumptionMap = root["sessionResumptionUpdate"] as? Map<*, *>
        val sessionResumptionUpdate = resumptionMap?.let { rm ->
            SessionResumptionUpdate(
                newHandle = rm["newHandle"]?.toString(),
                resumable = rm["resumable"] as? Boolean ?: true
            )
        }

        val goAwayMap = root["goAway"] as? Map<*, *>
        val goAway = goAwayMap?.let { gm ->
            GoAwayNotice(timeLeft = gm["timeLeft"]?.toString())
        }

        return GeminiServerMessage(
            setupComplete = setupComplete,
            serverContent = serverContent,
            toolCall = toolCall,
            toolCallCancellation = toolCallCancellation,
            sessionResumptionUpdate = sessionResumptionUpdate,
            goAway = goAway
        )
    }

    private fun mapToJsonObject(map: Map<*, *>): JsonObject {
        val obj = JsonObject()
        for ((k, v) in map) {
            val key = k?.toString() ?: continue
            when (v) {
                null -> {}
                is Map<*, *> -> obj.add(key, mapToJsonObject(v))
                else -> obj.add(key, JsonElement(v))
            }
        }
        return obj
    }

    private fun parseRawJsonObject(json: String): JsonObject {
        return mapToJsonObject(parseRawMap(json))
    }

    fun parseRawMap(json: String): Map<String, Any?> {
        return try {
            val orgObj = JSONObject(json)
            if (orgObj.length() > 0 || json.trim() == "{}") {
                orgJsonToMap(orgObj)
            } else {
                SimpleJsonParser(json).parseObject()
            }
        } catch (_: Throwable) {
            SimpleJsonParser(json).parseObject()
        }
    }

    private fun orgJsonToMap(obj: JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.opt(k)
            out[k] = when (v) {
                null, JSONObject.NULL -> null
                is JSONObject -> orgJsonToMap(v)
                is JSONArray -> orgJsonToList(v)
                else -> v
            }
        }
        return out
    }

    private fun orgJsonToList(arr: JSONArray): List<Any?> {
        val out = ArrayList<Any?>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.opt(i)
            out.add(
                when (v) {
                    null, JSONObject.NULL -> null
                    is JSONObject -> orgJsonToMap(v)
                    is JSONArray -> orgJsonToList(v)
                    else -> v
                }
            )
        }
        return out
    }

    private fun toJsonValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> quoteString(value)
            is Number, is Boolean -> value.toString()
            is JsonObject -> toJsonValue(value.toMap())
            is JsonElement -> toJsonValue(value.unwrap())
            is Map<*, *> -> {
                val entries = value.entries.mapNotNull { (k, v) ->
                    val keyStr = k?.toString() ?: return@mapNotNull null
                    if (v == null) null else "${quoteString(keyStr)}:${toJsonValue(v)}"
                }
                "{${entries.joinToString(",")}}"
            }
            is Iterable<*> -> {
                "[${value.joinToString(",") { toJsonValue(it) }}]"
            }
            is Array<*> -> {
                "[${value.joinToString(",") { toJsonValue(it) }}]"
            }
            is Enum<*> -> quoteString(value.name)
            else -> {
                val props = LinkedHashMap<String, Any?>()
                for (field in value.javaClass.declaredFields) {
                    if (field.isSynthetic || java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                    field.isAccessible = true
                    val fieldVal = field.get(value)
                    if (fieldVal != null) {
                        props[field.name] = fieldVal
                    }
                }
                toJsonValue(props)
            }
        }
    }

    private fun quoteString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append(String.format("\\u%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}

/**
 * Pure-Kotlin recursive descent JSON parser fallback for local JVM unit tests where android.jar org.json may be stubbed.
 */
internal class SimpleJsonParser(private val input: String) {
    private var pos = 0

    fun parseObject(): Map<String, Any?> {
        skipWhitespace()
        val map = LinkedHashMap<String, Any?>()
        if (pos >= input.length || input[pos] != '{') return map
        pos++ // '{'
        skipWhitespace()
        if (pos < input.length && input[pos] == '}') {
            pos++
            return map
        }
        while (pos < input.length) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            if (pos < input.length && input[pos] == ':') pos++
            skipWhitespace()
            val value = parseValue()
            map[key] = value
            skipWhitespace()
            if (pos < input.length && input[pos] == ',') {
                pos++
                continue
            }
            if (pos < input.length && input[pos] == '}') {
                pos++
                break
            }
            break
        }
        return map
    }

    private fun parseArray(): List<Any?> {
        val list = ArrayList<Any?>()
        pos++ // '['
        skipWhitespace()
        if (pos < input.length && input[pos] == ']') {
            pos++
            return list
        }
        while (pos < input.length) {
            skipWhitespace()
            list.add(parseValue())
            skipWhitespace()
            if (pos < input.length && input[pos] == ',') {
                pos++
                continue
            }
            if (pos < input.length && input[pos] == ']') {
                pos++
                break
            }
            break
        }
        return list
    }

    private fun parseValue(): Any? {
        skipWhitespace()
        if (pos >= input.length) return null
        return when (val ch = input[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't', 'f' -> parseBoolean()
            'n' -> {
                pos = (pos + 4).coerceAtMost(input.length)
                null
            }
            else -> if (ch == '-' || ch.isDigit()) parseNumber() else null
        }
    }

    private fun parseString(): String {
        if (pos >= input.length || input[pos] != '"') return ""
        pos++
        val sb = StringBuilder()
        while (pos < input.length) {
            val ch = input[pos++]
            if (ch == '"') break
            if (ch == '\\' && pos < input.length) {
                when (val esc = input[pos++]) {
                    '"', '\\', '/' -> sb.append(esc)
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (pos + 4 <= input.length) {
                            val hex = input.substring(pos, pos + 4)
                            sb.append(hex.toInt(16).toChar())
                            pos += 4
                        }
                    }
                    else -> sb.append(esc)
                }
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun parseBoolean(): Boolean {
        return if (input.startsWith("true", pos)) {
            pos += 4
            true
        } else {
            pos = (pos + 5).coerceAtMost(input.length)
            false
        }
    }

    private fun parseNumber(): Number {
        val start = pos
        while (pos < input.length && (input[pos].isDigit() || input[pos] in setOf('-', '+', '.', 'e', 'E'))) {
            pos++
        }
        val token = input.substring(start, pos)
        return if (token.contains('.') || token.contains('e', true)) {
            token.toDoubleOrNull() ?: 0.0
        } else {
            token.toLongOrNull() ?: 0L
        }
    }

    private fun skipWhitespace() {
        while (pos < input.length && input[pos].isWhitespace()) {
            pos++
        }
    }
}
