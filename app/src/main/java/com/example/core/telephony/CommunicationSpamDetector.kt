package com.example.core.telephony

enum class SpamRiskLevel(val label: String) {
    SAFE("Safe / Normal"),
    SUSPICIOUS("Suspicious — Verify Before Acting"),
    HIGH_RISK_SPAM("High-Risk Spam / Scam Detected")
}

data class SpamAssessmentResult(
    val riskLevel: SpamRiskLevel,
    val score: Int, // 0..100
    val reasons: List<String>,
    val recommendation: String
) {
    val isSuspiciousOrSpam: Boolean
        get() = riskLevel != SpamRiskLevel.SAFE
}

/**
 * Deterministic, privacy-preserving on-device Spam & Suspicious Communication Detector
 * for NAYRO's Autonomous Communication Agent (calls, SMS, and communication notifications).
 * Runs fully locally without uploading private call logs or SMS content to external servers.
 */
object CommunicationSpamDetector {

    private val PHISHING_URL_REGEX = Regex(
        "(?i)(https?://\\S*(?:bit\\.ly|tinyurl|t\\.ly|rb\\.gy|shorturl|kyc|verify|update-bank|claim-prize|free-gift|apk)\\S*)"
    )
    private val SCAM_PHRASE_PATTERNS = listOf(
        Regex("(?i)\\b(won\\s+a\\s+lottery|lucky\\s+draw|congratulations.*won|claim\\s+your\\s+prize|free\\s+cash)\\b") to
            "Lottery or prize-claim scam phrasing detected",
        Regex("(?i)\\b(kyc\\s*(?:expired|suspended|update|pending|blocked)|pan\\s+card\\s+blocked|sim\\s+will\\s+be\\s+blocked|electricity\\s+disconnected)\\b") to
            "Urgent account/KYC/utility suspension lure detected",
        Regex("(?i)\\b(share\\s+(?:your\\s+)?otp|send\\s+otp|tell\\s+(?:me\\s+)?otp|pin\\s+batao|otp\\s+batao|otp\\s+bhejo)\\b") to
            "OTP/PIN credential harvesting request detected",
        Regex("(?i)\\b(instant\\s+loan\\s+approved|pre-approved\\s+loan|guaranteed\\s+returns|double\\s+your\\s+money|work\\s+from\\s+home.*\\d{4,})\\b") to
            "Predatory financial or job-scam pattern detected",
        Regex("(?i)\\b(click\\s+(?:here|immediately|now)|urgent\\s+action\\s+required|verify\\s+immediately)\\b") to
            "High-pressure urgency / phishing call-to-action detected"
    )

    private val TELEMARKETER_NUMBER_REGEX = Regex("^(?:\\+?91)?140\\d{7}$")
    private val REPEATED_DIGIT_NUMBER_REGEX = Regex("^(\\d)\\1{6,}$")

    /**
     * Evaluates an incoming or historical phone call for telemarketer / spam signals.
     */
    fun assessIncomingCall(
        phoneNumber: String?,
        isKnownContact: Boolean,
        isVerifiedStirShakenPass: Boolean = false
    ): SpamAssessmentResult {
        val clean = phoneNumber?.filter { it.isDigit() || it == '+' }.orEmpty()
        val reasons = mutableListOf<String>()
        var score = 0

        if (isKnownContact) {
            return SpamAssessmentResult(
                riskLevel = SpamRiskLevel.SAFE,
                score = 0,
                reasons = listOf("Caller matches a saved contact in your address book."),
                recommendation = "Recognized contact."
            )
        }

        if (clean.isEmpty()) {
            score += 40
            reasons.add("Hidden or private caller ID with no visible phone number.")
        } else {
            val digitsOnly = clean.filter { it.isDigit() }
            if (TELEMARKETER_NUMBER_REGEX.matches(clean)) {
                score += 70
                reasons.add("Matches 140-series registered telemarketing / promotional caller prefix.")
            }
            if (REPEATED_DIGIT_NUMBER_REGEX.matches(digitsOnly)) {
                score += 60
                reasons.add("Suspicious repeated-digit spoofed caller number pattern.")
            }
            if (digitsOnly.length < 7 || digitsOnly.length > 15) {
                score += 35
                reasons.add("Non-standard caller number length (${digitsOnly.length} digits).")
            }
        }

        if (!isVerifiedStirShakenPass && !isKnownContact && score > 0) {
            score += 10
        }

        return finalizeAssessment(score, reasons, isCall = true)
    }

    /**
     * Evaluates an SMS or communication notification body + sender for phishing/scam/spam indicators.
     */
    fun assessMessage(
        sender: String?,
        body: String,
        isKnownContact: Boolean = false
    ): SpamAssessmentResult {
        val trimmed = body.trim()
        val reasons = mutableListOf<String>()
        var score = 0

        if (PHISHING_URL_REGEX.containsMatchIn(trimmed)) {
            score += 45
            reasons.add("Contains a shortened or phishing-style URL.")
        }

        for ((pattern, explanation) in SCAM_PHRASE_PATTERNS) {
            if (pattern.containsMatchIn(trimmed)) {
                score += 35
                reasons.add(explanation)
            }
        }

        val cleanSender = sender?.trim().orEmpty()
        if (!isKnownContact && cleanSender.isNotEmpty() && TELEMARKETER_NUMBER_REGEX.matches(cleanSender.filter { it.isDigit() || it == '+' })) {
            score += 30
            reasons.add("Sent from a promotional 140-series telemarketer number.")
        }

        // Known contacts get slightly lower base suspicion unless strong phishing signals appear
        if (isKnownContact && score in 1..35) {
            score = (score - 15).coerceAtLeast(0)
        }

        return finalizeAssessment(score, reasons, isCall = false)
    }

    private fun finalizeAssessment(score: Int, reasons: List<String>, isCall: Boolean): SpamAssessmentResult {
        val clamped = score.coerceIn(0, 100)
        val level = when {
            clamped >= 65 -> SpamRiskLevel.HIGH_RISK_SPAM
            clamped >= 30 -> SpamRiskLevel.SUSPICIOUS
            else -> SpamRiskLevel.SAFE
        }
        val recommendation = when (level) {
            SpamRiskLevel.HIGH_RISK_SPAM -> if (isCall) {
                "Likely spam or telemarketer. Recommend rejecting or ignoring the call."
            } else {
                "High-risk scam/phishing message. Do NOT click links or share OTPs/PINs."
            }
            SpamRiskLevel.SUSPICIOUS -> if (isCall) {
                "Unknown caller with suspicious signals. Verify identity before sharing personal details."
            } else {
                "Suspicious message. Verify sender through an official channel before acting."
            }
            SpamRiskLevel.SAFE -> "No spam or phishing indicators detected."
        }
        return SpamAssessmentResult(
            riskLevel = level,
            score = clamped,
            reasons = if (reasons.isEmpty()) listOf("No suspicious patterns found.") else reasons,
            recommendation = recommendation
        )
    }
}
