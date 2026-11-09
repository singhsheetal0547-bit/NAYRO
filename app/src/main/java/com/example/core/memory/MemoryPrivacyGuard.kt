package com.example.core.memory

/**
 * Safeguards personal privacy: strictly prevents persisting sensitive credentials,
 * passwords, OTPs, CVVs, and API keys to local database.
 */
object MemoryPrivacyGuard {

    private val SENSITIVE_PATTERNS = listOf(
        Regex("(?i)\\b(password|passwd|passcode|secret_key|api_key|token)\\b"),
        Regex("(?i)\\b(otp|verification code|one time password)\\b"),
        Regex("\\b[0-9]{3,4}\\b(?=.*\\b(cvv|cvc|pin)\\b)"),
        Regex("\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13})\\b"), // Credit card regex
        Regex("(?i)AIzaSy[0-9A-Za-z-_]{33}"), // Google API Key format
        Regex("(?i)sk-[a-zA-Z0-9]{20,}") // OpenAI API Key format
    )

    fun isAllowedToPersist(content: String): Boolean {
        for (pattern in SENSITIVE_PATTERNS) {
            if (pattern.containsMatchIn(content)) {
                return false
            }
        }
        return true
    }

    fun sanitizeContent(content: String): String {
        var result = content
        for (pattern in SENSITIVE_PATTERNS) {
            result = result.replace(pattern, "[REDACTED_SENSITIVE]")
        }
        return result
    }
}
