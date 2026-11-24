package com.example.core.browser

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import com.example.core.accessibility.AccessibilityActionController
import com.example.core.accessibility.NayroAccessibilityService
import com.example.core.agent.ActionExecutor
import com.example.core.agent.VerificationStatus
import com.example.core.provider.AIProviderManager
import com.example.core.provider.model.AIResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class BrowserActionOutcome(
    val isSuccess: Boolean,
    val message: String,
    val status: VerificationStatus,
    val resolvedUrl: String? = null
)

data class WebSearchHit(
    val title: String,
    val url: String,
    val snippet: String
)

data class BrowserSearchOutcome(
    val isSuccess: Boolean,
    val query: String,
    val hits: List<WebSearchHit> = emptyList(),
    val summary: String,
    val status: VerificationStatus
)

data class WebPageExtraction(
    val isSuccess: Boolean,
    val url: String,
    val title: String = "",
    val extractedText: String = "",
    val errorMessage: String? = null
)

data class MultiSourceComparisonResult(
    val isSuccess: Boolean,
    val topic: String,
    val sourcesAnalyzed: List<WebPageExtraction>,
    val comparisonSummary: String,
    val status: VerificationStatus
)

data class BrowserDownloadOutcome(
    val isSuccess: Boolean,
    val downloadId: Long? = null,
    val fileName: String? = null,
    val message: String,
    val status: VerificationStatus
)

/**
 * Real Browser & Internet Agent for NAYRO.
 * Capabilities:
 * - SSRF-validated URL normalization & Chrome/default browser launch
 * - Real Internet search & web page content extraction
 * - Multi-source comparison & AI-assisted synthesis
 * - Accessibility-based browser form filling & navigation
 * - Scoped-Storage-compliant permitted file downloads via Android DownloadManager
 */
class BrowserAutomationManager(
    private val context: Context?,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {

    companion object {
        const val CHROME_PACKAGE = "com.android.chrome"
        private const val MAX_PAGE_BYTES = 512 * 1024 // 512 KB cap
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        fun normalizeAndValidateUrl(rawUrl: String): Result<String> {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) {
                return Result.failure(IllegalArgumentException("URL cannot be empty."))
            }
            val candidate = if (trimmed.contains("://")) {
                trimmed
            } else if (trimmed.contains(".") && !trimmed.contains(" ")) {
                "https://$trimmed"
            } else {
                return Result.failure(IllegalArgumentException("Invalid URL format: '$trimmed'."))
            }
            return ActionExecutor.validateSafeExternalUrl(candidate).map { it.toString() }
        }

        fun extractReadableTextFromHtml(html: String, maxChars: Int = 3200): Pair<String, String> {
            val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(html)?.groupValues?.getOrNull(1)
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                .orEmpty()

            val cleaned = html
                .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<noscript[\\s\\S]*?</noscript>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<nav[\\s\\S]*?</nav>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<footer[\\s\\S]*?</footer>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<[^>]+>"), " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(maxChars)

            return title to cleaned
        }

        fun parseDuckDuckGoHtmlHits(html: String, maxResults: Int = 5): List<WebSearchHit> {
            val hits = mutableListOf<WebSearchHit>()
            val anchorRegex = Regex(
                "<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            val snippetRegex = Regex(
                "class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</(?:a|div|span)>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            val snippets = snippetRegex.findAll(html).map { m ->
                m.groupValues[1].replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
            }.toList()

            anchorRegex.findAll(html).forEachIndexed { idx, match ->
                if (hits.size >= maxResults) return@forEachIndexed
                val rawHref = match.groupValues[1].trim()
                val decodedUrl = extractRedirectTarget(rawHref)
                val title = match.groupValues[2].replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
                val snippet = snippets.getOrNull(idx).orEmpty()
                if (title.isNotBlank() && decodedUrl.startsWith("http")) {
                    hits.add(WebSearchHit(title = title, url = decodedUrl, snippet = snippet))
                }
            }
            return hits
        }

        private fun extractRedirectTarget(rawHref: String): String {
            val uddgMatch = Regex("[?&]uddg=([^&]+)").find(rawHref)
            if (uddgMatch != null) {
                return try {
                    java.net.URLDecoder.decode(uddgMatch.groupValues[1], "UTF-8")
                } catch (_: Exception) {
                    rawHref
                }
            }
            return if (rawHref.startsWith("//")) "https:$rawHref" else rawHref
        }
    }

    /**
     * Opens a verified external URL in Google Chrome (with graceful fallback to the default Android browser).
     */
    fun openInChrome(rawUrl: String): BrowserActionOutcome {
        val safeUrl = normalizeAndValidateUrl(rawUrl).getOrElse { err ->
            return BrowserActionOutcome(
                isSuccess = false,
                message = "Blocked unsafe or invalid URL: ${err.message}",
                status = VerificationStatus.FAILED
            )
        }
        val ctx = context ?: return BrowserActionOutcome(
            isSuccess = false,
            message = "Android Context unavailable to launch browser for $safeUrl.",
            status = VerificationStatus.NOT_SUPPORTED,
            resolvedUrl = safeUrl
        )

        return try {
            val uri = Uri.parse(safeUrl)
            val chromeIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(CHROME_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pm = ctx.packageManager
            val launchedTarget = if (chromeIntent.resolveActivity(pm) != null) {
                ctx.startActivity(chromeIntent)
                "Google Chrome"
            } else {
                val defaultIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (defaultIntent.resolveActivity(pm) == null) {
                    return BrowserActionOutcome(
                        isSuccess = false,
                        message = "No web browser is installed on this device to open $safeUrl.",
                        status = VerificationStatus.NOT_SUPPORTED,
                        resolvedUrl = safeUrl
                    )
                }
                ctx.startActivity(defaultIntent)
                "default browser"
            }

            BrowserActionOutcome(
                isSuccess = true,
                message = "Opened $safeUrl in $launchedTarget.",
                status = VerificationStatus.VERIFIED,
                resolvedUrl = safeUrl
            )
        } catch (e: Exception) {
            BrowserActionOutcome(
                isSuccess = false,
                message = "Failed to open $safeUrl: ${e.localizedMessage ?: e.javaClass.simpleName}",
                status = VerificationStatus.FAILED,
                resolvedUrl = safeUrl
            )
        }
    }

    /**
     * Performs a real web search over HTTPS and returns structured, source-cited hits.
     */
    suspend fun searchInternet(query: String, maxResults: Int = 5): BrowserSearchOutcome = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) {
            return@withContext BrowserSearchOutcome(
                isSuccess = false,
                query = "",
                summary = "Missing search query.",
                status = VerificationStatus.FAILED
            )
        }
        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val searchUrl = "https://html.duckduckgo.com/html/?q=$encoded"
        try {
            val request = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext BrowserSearchOutcome(
                        isSuccess = false,
                        query = cleanQuery,
                        summary = "Web search failed with HTTP ${response.code}.",
                        status = VerificationStatus.FAILED
                    )
                }
                val body = response.body?.string().orEmpty().take(MAX_PAGE_BYTES)
                val hits = parseDuckDuckGoHtmlHits(body, maxResults = maxResults)
                if (hits.isEmpty()) {
                    return@withContext BrowserSearchOutcome(
                        isSuccess = false,
                        query = cleanQuery,
                        summary = "No structured web results were returned for '$cleanQuery'.",
                        status = VerificationStatus.INCONCLUSIVE
                    )
                }
                val formatted = buildString {
                    appendLine("Web Search Results for \"$cleanQuery\":")
                    hits.forEachIndexed { idx, hit ->
                        appendLine("${idx + 1}. ${hit.title}")
                        if (hit.snippet.isNotBlank()) appendLine("   ${hit.snippet}")
                        appendLine("   Source: ${hit.url}")
                    }
                }.trim()
                BrowserSearchOutcome(
                    isSuccess = true,
                    query = cleanQuery,
                    hits = hits,
                    summary = formatted,
                    status = VerificationStatus.VERIFIED
                )
            }
        } catch (e: Exception) {
            BrowserSearchOutcome(
                isSuccess = false,
                query = cleanQuery,
                summary = "Internet search failed: ${e.localizedMessage ?: e.javaClass.simpleName}",
                status = VerificationStatus.FAILED
            )
        }
    }

    /**
     * Fetches an SSRF-validated web page and extracts clean readable article content.
     */
    suspend fun fetchAndExtractPage(rawUrl: String): WebPageExtraction = withContext(Dispatchers.IO) {
        val safeUrl = normalizeAndValidateUrl(rawUrl).getOrElse { err ->
            return@withContext WebPageExtraction(
                isSuccess = false,
                url = rawUrl,
                errorMessage = "Blocked unsafe URL: ${err.message}"
            )
        }
        try {
            val request = Request.Builder()
                .url(safeUrl)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext WebPageExtraction(
                        isSuccess = false,
                        url = safeUrl,
                        errorMessage = "HTTP ${response.code} fetching $safeUrl"
                    )
                }
                val html = response.body?.string().orEmpty().take(MAX_PAGE_BYTES)
                val (title, text) = extractReadableTextFromHtml(html)
                if (text.isBlank()) {
                    return@withContext WebPageExtraction(
                        isSuccess = false,
                        url = safeUrl,
                        title = title,
                        errorMessage = "Page loaded but contained no readable text."
                    )
                }
                WebPageExtraction(
                    isSuccess = true,
                    url = safeUrl,
                    title = title.ifBlank { safeUrl },
                    extractedText = text
                )
            }
        } catch (e: Exception) {
            WebPageExtraction(
                isSuccess = false,
                url = safeUrl,
                errorMessage = "Failed to fetch $safeUrl: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
        }
    }

    /**
     * Compares multiple web sources (explicit URLs or top Internet search hits for [topic])
     * and synthesizes key similarities/differences with source citations.
     */
    suspend fun compareSources(
        topic: String,
        urls: List<String> = emptyList(),
        providerManager: AIProviderManager? = null
    ): MultiSourceComparisonResult = withContext(Dispatchers.IO) {
        val targetUrls = if (urls.isNotEmpty()) {
            urls.take(3)
        } else {
            val search = searchInternet(topic, maxResults = 3)
            if (!search.isSuccess || search.hits.isEmpty()) {
                return@withContext MultiSourceComparisonResult(
                    isSuccess = false,
                    topic = topic,
                    sourcesAnalyzed = emptyList(),
                    comparisonSummary = "Could not gather web sources to compare for '$topic': ${search.summary}",
                    status = VerificationStatus.FAILED
                )
            }
            search.hits.map { it.url }.take(3)
        }

        val extractions = targetUrls.map { fetchAndExtractPage(it) }.filter { it.isSuccess }
        if (extractions.isEmpty()) {
            return@withContext MultiSourceComparisonResult(
                isSuccess = false,
                topic = topic,
                sourcesAnalyzed = emptyList(),
                comparisonSummary = "Failed to read content from target sources for '$topic'.",
                status = VerificationStatus.FAILED
            )
        }

        // Synthesize comparison via active online AI provider when available
        val aiSynthesis = if (providerManager != null && providerManager.activeProvider.value != null) {
            val corpus = extractions.mapIndexed { i, ext ->
                "Source ${i + 1} (${ext.title} — ${ext.url}):\n${ext.extractedText.take(1200)}"
            }.joinToString("\n\n")
            val prompt = "Compare the following web sources about \"$topic\". Highlight consensus facts, key differences, and cite sources clearly.\n\n$corpus"
            when (val res = providerManager.sendRequest(prompt)) {
                is AIResult.Success -> res.response.content
                is AIResult.Error -> null
            }
        } else {
            null
        }

        val summary = aiSynthesis ?: buildString {
            appendLine("Multi-Source Comparison for \"$topic\" (${extractions.size} sources verified):")
            extractions.forEachIndexed { idx, ext ->
                appendLine("${idx + 1}. ${ext.title} (${ext.url})")
                appendLine("   Excerpt: ${ext.extractedText.take(280)}...")
            }
        }.trim()

        MultiSourceComparisonResult(
            isSuccess = true,
            topic = topic,
            sourcesAnalyzed = extractions,
            comparisonSummary = summary,
            status = VerificationStatus.VERIFIED
        )
    }

    /**
     * Performs Accessibility-based form filling on an open browser page.
     * Verifies NayroAccessibilityService is active and honestly reports missing accessibility or missing fields.
     */
    suspend fun fillWebFormViaAccessibility(
        fieldLabel: String,
        value: String,
        submitButtonLabel: String? = null
    ): BrowserActionOutcome {
        val service = NayroAccessibilityService.instance
        if (service == null) {
            return BrowserActionOutcome(
                isSuccess = false,
                message = "Browser form automation requires NAYRO Accessibility Service to be enabled in Android Settings.",
                status = VerificationStatus.REQUIRES_PERMISSION
            )
        }
        if (fieldLabel.isBlank() || value.isBlank()) {
            return BrowserActionOutcome(
                isSuccess = false,
                message = "Missing form field label or input value for browser form filling.",
                status = VerificationStatus.FAILED
            )
        }

        val typed = AccessibilityActionController.performSetText(
            text = value,
            viewId = null,
            targetText = fieldLabel
        )
        if (!typed) {
            return BrowserActionOutcome(
                isSuccess = false,
                message = "Could not locate editable browser form field matching '$fieldLabel'.",
                status = VerificationStatus.FAILED
            )
        }

        if (!submitButtonLabel.isNullOrBlank()) {
            val submitted = AccessibilityActionController.performClickWithCoordinateFallback(
                targetText = submitButtonLabel,
                viewId = null
            )
            return if (submitted) {
                BrowserActionOutcome(
                    isSuccess = true,
                    message = "Filled '$fieldLabel' and clicked '$submitButtonLabel' in browser.",
                    status = VerificationStatus.VERIFIED
                )
            } else {
                BrowserActionOutcome(
                    isSuccess = false,
                    message = "Filled '$fieldLabel', but could not find submit button '$submitButtonLabel'.",
                    status = VerificationStatus.INCONCLUSIVE
                )
            }
        }

        return BrowserActionOutcome(
            isSuccess = true,
            message = "Filled browser form field '$fieldLabel' with requested value.",
            status = VerificationStatus.VERIFIED
        )
    }

    /**
     * Enqueues an SSRF-safe HTTPS file download via Android DownloadManager into public Downloads
     * (Android 10–16 Scoped Storage compliant — no legacy storage permissions needed).
     */
    fun downloadPermittedFile(rawUrl: String, fileName: String? = null): BrowserDownloadOutcome {
        val safeUrl = normalizeAndValidateUrl(rawUrl).getOrElse { err ->
            return BrowserDownloadOutcome(
                isSuccess = false,
                message = "Download blocked — invalid or unsafe URL: ${err.message}",
                status = VerificationStatus.FAILED
            )
        }
        val ctx = context ?: return BrowserDownloadOutcome(
            isSuccess = false,
            message = "Android Context unavailable to enqueue download for $safeUrl.",
            status = VerificationStatus.NOT_SUPPORTED
        )

        val resolvedFileName = (fileName?.trim()?.takeIf { it.isNotBlank() }
            ?: safeUrl.substringAfterLast('/').substringBefore('?').takeIf { it.contains('.') }
            ?: "nayro_download_${System.currentTimeMillis()}.bin")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")

        return try {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                ?: return BrowserDownloadOutcome(
                    isSuccess = false,
                    message = "Android DownloadManager service is unavailable.",
                    status = VerificationStatus.NOT_SUPPORTED
                )
            val request = DownloadManager.Request(Uri.parse(safeUrl))
                .setTitle(resolvedFileName)
                .setDescription("Downloaded via NAYRO Browser Agent")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, resolvedFileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)

            val downloadId = dm.enqueue(request)
            BrowserDownloadOutcome(
                isSuccess = true,
                downloadId = downloadId,
                fileName = resolvedFileName,
                message = "Download started via Android DownloadManager: '$resolvedFileName' (ID=$downloadId) -> Downloads.",
                status = VerificationStatus.VERIFIED
            )
        } catch (e: SecurityException) {
            BrowserDownloadOutcome(
                isSuccess = false,
                fileName = resolvedFileName,
                message = "Android security policy denied download enqueue: ${e.localizedMessage}",
                status = VerificationStatus.REQUIRES_PERMISSION
            )
        } catch (e: Exception) {
            BrowserDownloadOutcome(
                isSuccess = false,
                fileName = resolvedFileName,
                message = "Failed to download '$resolvedFileName': ${e.localizedMessage ?: e.javaClass.simpleName}",
                status = VerificationStatus.FAILED
            )
        }
    }
}
