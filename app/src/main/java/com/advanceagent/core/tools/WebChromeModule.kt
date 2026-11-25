package com.advanceagent.core.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

/**
 * Normalizes spoken Hindi, Hinglish, and English domain names and search expressions
 * into clean HTTPS URLs (Section 7).
 */
object DomainNormalizer {

    private val KNOWN_SITE_SHORTCUTS = mapOf(
        "google" to "https://www.google.com",
        "youtube" to "https://www.youtube.com",
        "github" to "https://github.com",
        "wikipedia" to "https://www.wikipedia.org",
        "gmail" to "https://mail.google.com",
        "amazon" to "https://www.amazon.in",
        "flipkart" to "https://www.flipkart.com",
        "linkedin" to "https://www.linkedin.com",
        "instagram" to "https://www.instagram.com",
        "x" to "https://x.com",
        "twitter" to "https://x.com"
    )

    fun normalizeSpokenTargetToUrl(rawInput: String): String {
        val cleaned = rawInput
            .trim()
            .replace(Regex("^(?i)(open|kholo|khol do|go to|navigate to|visit|browse)\\s+"), "")
            .replace(Regex("(?i)\\s+(kholo|khol do|open karo|par jao|pe jao)$"), "")
            .trim()

        if (cleaned.isEmpty()) return "https://www.google.com"

        if (cleaned.startsWith("http://", ignoreCase = true) ||
            cleaned.startsWith("https://", ignoreCase = true)
        ) {
            return cleaned
        }

        val lower = cleaned.lowercase()
        KNOWN_SITE_SHORTCUTS[lower]?.let { return it }

        // Spoken "dot com", "dot in", "dot org", "slash" normalization
        val spokenDomainReplaced = lower
            .replace(Regex("\\s+dot\\s+"), ".")
            .replace(Regex("\\s+ बिंदु \\s+"), ".")
            .replace(Regex("\\s+slash\\s+"), "/")
            .replace(Regex("\\s+स्लैश\\s+"), "/")
            .trim()

        if (!spokenDomainReplaced.contains(' ') && spokenDomainReplaced.contains('.')) {
            return "https://$spokenDomainReplaced"
        }

        return "https://www.google.com/search?q=${URLEncoder.encode(cleaned, "UTF-8")}"
    }
}

class BuiltInChromeEngine(private val context: Context?) {

    fun openWebTarget(rawTargetOrQuery: String): Map<String, Any> {
        val normalizedUrl = DomainNormalizer.normalizeSpokenTargetToUrl(rawTargetOrQuery)
        val ctx = context ?: return mapOf(
            "status" to "normalized_only",
            "url" to normalizedUrl
        )
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalizedUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            mapOf(
                "status" to "ok",
                "url" to normalizedUrl,
                "verified_intent_dispatched" to true
            )
        } catch (e: Exception) {
            mapOf(
                "status" to "error",
                "url" to normalizedUrl,
                "message" to (e.message ?: "Unable to launch browser")
            )
        }
    }
}
