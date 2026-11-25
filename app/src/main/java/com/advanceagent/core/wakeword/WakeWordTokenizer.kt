package com.advanceagent.core.wakeword

import android.content.res.AssetManager

class WakeWordTokenizer {

    private val vocab: Set<String>

    constructor(assets: AssetManager, tokensAssetPath: String) {
        val set = HashSet<String>()
        try {
            assets.open(tokensAssetPath).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val token = line.trim().split(" ").firstOrNull()
                    if (!token.isNullOrEmpty()) set.add(token)
                }
            }
        } catch (_: Exception) {
            set.addAll(DEFAULT_FALLBACK_VOCAB)
        }
        if (set.isEmpty()) {
            set.addAll(DEFAULT_FALLBACK_VOCAB)
        }
        vocab = set
    }

    constructor(customVocab: Set<String>) {
        vocab = if (customVocab.isEmpty()) DEFAULT_FALLBACK_VOCAB else customVocab
    }

    fun buildKeywordsSpec(
        phrases: List<String>,
        boostingScore: Float = 2.0f,
        triggerThreshold: Float = 0.25f
    ): String {
        return phrases
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .mapNotNull { phrase ->
                val tokens = tokenizePhrase(phrase)
                val safeLabel = phrase.replace(Regex("\\s+"), "_")
                if (tokens.isEmpty()) null
                else "${tokens.joinToString(" ")} :$boostingScore #$triggerThreshold @$safeLabel"
            }
            .joinToString("\n")
    }

    private fun tokenizePhrase(phrase: String): List<String> {
        val result = mutableListOf<String>()
        val words = phrase.split(Regex("\\s+")).filter { it.isNotBlank() }
        for (word in words) {
            val clean = word.replace(Regex("[^A-Z0-9']"), "")
            if (clean.isEmpty()) continue
            val prefixed = "▁$clean"
            if (vocab.contains(prefixed)) {
                result.add(prefixed)
            } else {
                result.addAll(greedySubwordSplit(prefixed))
            }
        }
        return result
    }

    private fun greedySubwordSplit(text: String): List<String> {
        val pieces = mutableListOf<String>()
        var idx = 0
        while (idx < text.length) {
            var matched: String? = null
            for (end in text.length downTo idx + 1) {
                val candidate = text.substring(idx, end)
                if (vocab.contains(candidate)) {
                    matched = candidate
                    idx = end
                    break
                }
            }
            if (matched != null) {
                pieces.add(matched)
            } else {
                idx += 1
            }
        }
        return pieces
    }

    companion object {
        val DEFAULT_FALLBACK_VOCAB: Set<String> = buildSet {
            add("▁")
            for (c in 'A'..'Z') {
                add(c.toString())
                add("▁$c")
            }
            addAll(
                listOf(
                    "▁HEY", "▁HI", "▁HELLO", "▁OK", "▁OKAY", "▁WAKE", "▁UP",
                    "▁NOVA", "▁BABU", "▁NAYRO", "▁BOSS", "▁ARJUN", "▁AGENT",
                    "ER", "IN", "ON", "AN", "AR", "OR", "AT", "EN", "ES", "AL"
                )
            )
        }
    }
}
