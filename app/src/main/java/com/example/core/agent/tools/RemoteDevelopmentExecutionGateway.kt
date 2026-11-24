package com.example.core.agent.tools

import com.example.core.security.EncryptedStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class RemoteCodeExecutionResult(
    val isSuccess: Boolean,
    val exitCode: Int?,
    val stdout: String = "",
    val stderr: String = "",
    val durationMs: Long? = null,
    val errorMessage: String? = null
)

/**
 * Authenticated HTTPS bridge to a user-operated, isolated CPython/Node execution service.
 *
 * The service must implement POST /v1/nayro/execute and return JSON containing an integer
 * exitCode plus stdout/stderr. NAYRO never pretends its restricted local Kotlin evaluator is
 * CPython or Node. No endpoint is built in, and code is sent only after explicit configuration.
 */
class RemoteDevelopmentExecutionGateway(
    private val secureStorage: EncryptedStorageManager,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(130, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val KEY_ENDPOINT = "remote_dev_gateway_endpoint"
        private const val KEY_TOKEN = "remote_dev_gateway_token"
    }

    fun configure(endpoint: String, bearerToken: String) {
        val cleanEndpoint = endpoint.trim().trimEnd('/')
        val token = bearerToken.trim()
        require(cleanEndpoint.startsWith("https://", ignoreCase = true)) {
            "Remote development gateway must use HTTPS."
        }
        require(cleanEndpoint.length <= 512) { "Remote development gateway URL is too long." }
        require(token.isNotBlank()) { "A bearer token is required." }
        secureStorage.savePreference(KEY_ENDPOINT, cleanEndpoint)
        secureStorage.saveApiKey(KEY_TOKEN, token)
    }

    fun clearConfiguration() {
        secureStorage.savePreference(KEY_ENDPOINT, "")
        secureStorage.removeApiKey(KEY_TOKEN)
    }

    fun isConfigured(): Boolean =
        secureStorage.getPreference(KEY_ENDPOINT).startsWith("https://", ignoreCase = true) &&
            secureStorage.hasApiKey(KEY_TOKEN)

    fun configuredEndpoint(): String = secureStorage.getPreference(KEY_ENDPOINT)

    suspend fun execute(
        code: String,
        language: String,
        timeoutMs: Long = 60_000L
    ): RemoteCodeExecutionResult = withContext(Dispatchers.IO) {
        val cleanCode = code.trim()
        val normalizedLanguage = language.trim().lowercase()
        if (cleanCode.isEmpty()) {
            return@withContext RemoteCodeExecutionResult(false, null, errorMessage = "Code cannot be empty.")
        }
        if (normalizedLanguage !in setOf("python", "py", "javascript", "js", "node", "typescript", "ts")) {
            return@withContext RemoteCodeExecutionResult(
                false, null, errorMessage = "Unsupported remote runtime '$language'. Use Python, JavaScript/Node, or TypeScript."
            )
        }
        if (!isConfigured()) {
            return@withContext RemoteCodeExecutionResult(
                false, null, errorMessage = "Remote Python/Node runtime is not configured. Set an HTTPS development gateway first."
            )
        }

        try {
            val base = secureStorage.getPreference(KEY_ENDPOINT).trimEnd('/')
            val url = if (base.endsWith("/v1/nayro/execute", true)) base else "$base/v1/nayro/execute"
            val payload = JSONObject()
                .put("language", when (normalizedLanguage) {
                    "py" -> "python"
                    "js", "node" -> "javascript"
                    "ts" -> "typescript"
                    else -> normalizedLanguage
                })
                .put("code", cleanCode)
                .put("timeoutMs", timeoutMs.coerceIn(1_000L, 120_000L))
                .put("source", "nayro-android-agent")
            val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${secureStorage.getApiKey(KEY_TOKEN)}")
                .header("Accept", "application/json")
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("Development gateway HTTP ${response.code}: ${responseText.take(500).ifBlank { response.message }}")
                }
                if (responseText.isBlank()) throw IOException("Development gateway returned an empty response.")
                val json = JSONObject(responseText)
                if (!json.has("exitCode")) {
                    throw IOException("Development gateway response is invalid: required integer 'exitCode' is missing.")
                }
                val exitCode = json.optInt("exitCode", Int.MIN_VALUE)
                if (exitCode == Int.MIN_VALUE) throw IOException("Development gateway returned a non-integer exitCode.")
                val stdout = json.optString("stdout", "")
                val stderr = json.optString("stderr", "")
                RemoteCodeExecutionResult(
                    isSuccess = exitCode == 0,
                    exitCode = exitCode,
                    stdout = stdout,
                    stderr = stderr,
                    durationMs = if (json.has("durationMs")) json.optLong("durationMs") else null,
                    errorMessage = if (exitCode == 0) null else "Remote program exited with code $exitCode."
                )
            }
        } catch (e: Exception) {
            RemoteCodeExecutionResult(
                false,
                null,
                errorMessage = e.localizedMessage ?: "Remote code execution failed."
            )
        }
    }
}
