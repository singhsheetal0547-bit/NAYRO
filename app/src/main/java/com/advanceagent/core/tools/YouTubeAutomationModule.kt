package com.advanceagent.core.tools

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class YouTubeVideoItem(
    val videoId: String,
    val title: String,
    val channelTitle: String
)

/**
 * YouTube Data API v3 client supporting BYOK key validation and structured video search,
 * with automatic fallback to YouTube app UI automation when no API key is configured.
 */
class YouTubeApiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    private val gson = Gson()

    suspend fun validateApiKey(apiKey: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val clean = apiKey.trim()
        if (clean.isEmpty()) {
            return@withContext false to "YouTube API Key is empty."
        }
        val url = "https://www.googleapis.com/youtube/v3/search?part=snippet&maxResults=1&type=video&q=music&key=" +
            URLEncoder.encode(clean, "UTF-8")
        return@withContext try {
            val req = Request.Builder().url(url).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    true to "YouTube Data API v3 Key verified (HTTP 200)."
                } else {
                    false to "YouTube API Key rejected (HTTP ${resp.code})."
                }
            }
        } catch (e: Exception) {
            false to "Network error validating YouTube key: ${e.message}"
        }
    }

    suspend fun searchVideos(query: String, apiKey: String, maxResults: Int = 5): List<YouTubeVideoItem> =
        withContext(Dispatchers.IO) {
            val cleanKey = apiKey.trim()
            val cleanQuery = query.trim()
            if (cleanKey.isEmpty() || cleanQuery.isEmpty()) return@withContext emptyList()

            val url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video" +
                "&maxResults=${maxResults.coerceIn(1, 10)}" +
                "&q=${URLEncoder.encode(cleanQuery, "UTF-8")}" +
                "&key=${URLEncoder.encode(cleanKey, "UTF-8")}"

            return@withContext try {
                val req = Request.Builder().url(url).get().build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    val body = resp.body?.string().orEmpty()
                    parseSearchItems(body)
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    internal fun parseSearchItems(jsonBody: String): List<YouTubeVideoItem> {
        val map = gson.parseRawMap(jsonBody)
        val items = map["items"] as? List<*> ?: return emptyList()
        return items.mapNotNull { item ->
            val itemMap = item as? Map<*, *> ?: return@mapNotNull null
            val idMap = itemMap["id"] as? Map<*, *>
            val videoId = idMap?.get("videoId")?.toString()?.trim().orEmpty()
            if (videoId.isEmpty()) return@mapNotNull null
            val snippet = itemMap["snippet"] as? Map<*, *>
            val title = snippet?.get("title")?.toString()?.trim().orEmpty()
            val channel = snippet?.get("channelTitle")?.toString()?.trim().orEmpty()
            YouTubeVideoItem(videoId = videoId, title = title, channelTitle = channel)
        }
    }
}

class YouTubeController(private val context: Context?) {

    fun openVideoById(videoId: String): Boolean {
        val ctx = context ?: return false
        val cleanId = videoId.trim()
        if (cleanId.isEmpty()) return false
        return try {
            val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$cleanId")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (ctx.packageManager != null && appIntent.resolveActivity(ctx.packageManager) != null) {
                ctx.startActivity(appIntent)
            } else {
                val webIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/watch?v=$cleanId")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(webIntent)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun openSearchQuery(query: String): Boolean {
        val ctx = context ?: return false
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return try {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=$encoded")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun dispatchMediaControl(command: String): Boolean {
        val ctx = context ?: return false
        val keyCode = when (command.trim().uppercase()) {
            "PLAY" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "PAUSE" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "PLAY_PAUSE", "TOGGLE" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "NEXT" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "PREVIOUS", "PREV" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return false
        }
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            val now = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
            true
        } catch (_: Exception) {
            false
        }
    }
}

class YouTubeAutomationManager(
    context: Context?,
    private val apiClient: YouTubeApiClient = YouTubeApiClient(),
    private val controller: YouTubeController = YouTubeController(context)
) {
    suspend fun searchAndPlay(
        query: String,
        youtubeApiKey: String = "",
        useApiWhenAvailable: Boolean = true
    ): Map<String, Any> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) {
            return mapOf("status" to "error", "message" to "Search query cannot be empty.")
        }

        if (useApiWhenAvailable && youtubeApiKey.isNotBlank()) {
            val results = apiClient.searchVideos(cleanQuery, youtubeApiKey, maxResults = 3)
            val top = results.firstOrNull()
            if (top != null) {
                val launched = controller.openVideoById(top.videoId)
                return mapOf(
                    "status" to if (launched) "ok" else "api_resolved_only",
                    "mode" to "youtube_data_api_v3",
                    "video_id" to top.videoId,
                    "title" to top.title,
                    "channel" to top.channelTitle,
                    "query" to cleanQuery
                )
            }
        }

        val opened = controller.openSearchQuery(cleanQuery)
        return mapOf(
            "status" to if (opened) "ok" else "failed",
            "mode" to "youtube_app_search_fallback",
            "query" to cleanQuery
        )
    }

    fun controlPlayback(action: String): Map<String, Any> {
        val ok = controller.dispatchMediaControl(action)
        return mapOf(
            "status" to if (ok) "ok" else "failed",
            "action" to action.uppercase()
        )
    }
}
