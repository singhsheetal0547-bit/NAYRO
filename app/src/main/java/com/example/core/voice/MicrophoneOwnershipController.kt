package com.example.core.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Deterministic voice microphone lifecycle phase (P0-4).
 * Enforces safe sequential ownership transitions:
 * `WAKE_LISTENING -> COMMAND_CAPTURE -> PROCESSING -> RESPONSE -> WAKE_LISTENING`
 */
enum class MicrophoneLifecyclePhase {
    IDLE,
    WAKE_LISTENING,
    COMMAND_CAPTURE,
    ENROLLMENT_CAPTURE,
    PROCESSING,
    RESPONSE,
    INTERRUPTED,
    ERROR
}

data class MicrophoneOwnershipSnapshot(
    val owner: AudioSessionOwner = AudioSessionOwner.NONE,
    val phase: MicrophoneLifecyclePhase = MicrophoneLifecyclePhase.IDLE,
    val activeSessionId: Long = 0L,
    val lastReason: String = "Idle",
    val updatedAtMs: Long = System.currentTimeMillis()
)

data class MicrophoneTransitionRecord(
    val fromPhase: MicrophoneLifecyclePhase,
    val toPhase: MicrophoneLifecyclePhase,
    val owner: AudioSessionOwner,
    val sessionId: Long,
    val reason: String,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Centralized, thread-safe Microphone Ownership & Concurrency Coordinator (P0-4).
 *
 * Owned by the single application-scoped [VoiceManager] instance (never a second independent manager).
 * Guarantees that at most ONE active microphone capture owner (`WAKE_WORD_ENGINE`, `SPEECH_RECOGNIZER`,
 * or `ENROLLMENT_CAPTURE`) holds the microphone at any instant, prevents duplicate concurrent recording
 * sessions, enforces safe lifecycle transitions, and handles interruptions & permission revocations cleanly.
 */
class MicrophoneOwnershipController {

    private val lock = Any()
    private val sessionSeq = AtomicLong(0L)

    private val _snapshot = MutableStateFlow(MicrophoneOwnershipSnapshot())
    val snapshot: StateFlow<MicrophoneOwnershipSnapshot> = _snapshot.asStateFlow()

    private val _owner = MutableStateFlow(AudioSessionOwner.NONE)
    val owner: StateFlow<AudioSessionOwner> = _owner.asStateFlow()

    private val _phase = MutableStateFlow(MicrophoneLifecyclePhase.IDLE)
    val phase: StateFlow<MicrophoneLifecyclePhase> = _phase.asStateFlow()

    private val transitionHistory = ArrayDeque<MicrophoneTransitionRecord>()

    private fun isCaptureOwner(candidate: AudioSessionOwner): Boolean {
        return candidate == AudioSessionOwner.WAKE_WORD_ENGINE ||
            candidate == AudioSessionOwner.SPEECH_RECOGNIZER ||
            candidate == AudioSessionOwner.ENROLLMENT_CAPTURE
    }

    /**
     * Attempts to acquire exclusive microphone ownership for [requestedOwner] in [targetPhase].
     *
     * Rules:
     * - Blocked while [AudioSessionOwner.PHONE_CALL_INTERRUPTION] is active unless [allowPreemptInterruption] is true.
     * - Prevents duplicate concurrent microphone capture unless [allowPreemptWake] is true (e.g., transitioning
     *   from `WAKE_LISTENING` to `COMMAND_CAPTURE` after wake detection or manual tap) or [allowPreemptSameOwner] is true.
     * - Returns a positive session token (`> 0L`) on success, or `null` if denied due to active conflict.
     */
    fun tryAcquire(
        requestedOwner: AudioSessionOwner,
        targetPhase: MicrophoneLifecyclePhase,
        reason: String,
        allowPreemptWake: Boolean = false,
        allowPreemptSameOwner: Boolean = false
    ): Long? = synchronized(lock) {
        val current = _snapshot.value

        if (current.owner == AudioSessionOwner.PHONE_CALL_INTERRUPTION &&
            requestedOwner != AudioSessionOwner.PHONE_CALL_INTERRUPTION
        ) {
            return null
        }

        if (isCaptureOwner(requestedOwner) && isCaptureOwner(current.owner)) {
            val sameOwnerAllowed = allowPreemptSameOwner && current.owner == requestedOwner
            val wakePreemptAllowed = allowPreemptWake && current.owner == AudioSessionOwner.WAKE_WORD_ENGINE
            if (!sameOwnerAllowed && !wakePreemptAllowed) {
                return null
            }
        }

        val newSessionId = sessionSeq.incrementAndGet()
        commitLocked(requestedOwner, targetPhase, newSessionId, reason)
        newSessionId
    }

    /**
     * Releases ownership if [expectedOwner] matches the current holder (and optional [sessionId] matches
     * when provided). Transitions safely to [nextPhase] (default [MicrophoneLifecyclePhase.IDLE]).
     */
    fun release(
        expectedOwner: AudioSessionOwner,
        sessionId: Long? = null,
        nextPhase: MicrophoneLifecyclePhase = MicrophoneLifecyclePhase.IDLE,
        reason: String = "Released by $expectedOwner"
    ): Boolean = synchronized(lock) {
        val current = _snapshot.value
        if (current.owner != expectedOwner) return false
        if (sessionId != null && current.activeSessionId != sessionId) return false
        commitLocked(AudioSessionOwner.NONE, nextPhase, 0L, reason)
        true
    }

    /**
     * Transitions from `COMMAND_CAPTURE` to `PROCESSING` while releasing active microphone hardware ownership.
     */
    fun enterProcessing(reason: String = "Command captured; processing with AgentRuntime") = synchronized(lock) {
        commitLocked(AudioSessionOwner.NONE, MicrophoneLifecyclePhase.PROCESSING, 0L, reason)
    }

    /**
     * Transitions into `RESPONSE` (TTS playback) while guaranteeing no microphone capture owner holds the mic.
     */
    fun enterResponsePlayback(reason: String = "Speaking assistant response"): Long = synchronized(lock) {
        val id = sessionSeq.incrementAndGet()
        commitLocked(AudioSessionOwner.TTS_PLAYBACK, MicrophoneLifecyclePhase.RESPONSE, id, reason)
        id
    }

    /**
     * Forces immediate release of any active capture owner due to phone call, audio focus loss,
     * permission revocation, or fatal hardware error.
     */
    fun forceInterrupt(
        reason: String,
        asError: Boolean = false
    ) = synchronized(lock) {
        val nextOwner = if (asError) AudioSessionOwner.NONE else AudioSessionOwner.PHONE_CALL_INTERRUPTION
        val nextPhase = if (asError) MicrophoneLifecyclePhase.ERROR else MicrophoneLifecyclePhase.INTERRUPTED
        commitLocked(nextOwner, nextPhase, 0L, reason)
    }

    /**
     * Clears an active external interruption (e.g., phone call ended or audio focus regained)
     * and returns to [MicrophoneLifecyclePhase.IDLE] so callers can safely attempt recovery.
     */
    fun clearInterruption(reason: String = "External interruption ended") = synchronized(lock) {
        if (_snapshot.value.owner == AudioSessionOwner.PHONE_CALL_INTERRUPTION ||
            _snapshot.value.phase == MicrophoneLifecyclePhase.INTERRUPTED
        ) {
            commitLocked(AudioSessionOwner.NONE, MicrophoneLifecyclePhase.IDLE, 0L, reason)
        }
    }

    /**
     * Forces immediate reset to `NONE` / `IDLE` (used on explicit user Stop, End Session, or destroy).
     */
    fun forceReleaseAll(reason: String = "Explicit stop / release") = synchronized(lock) {
        commitLocked(AudioSessionOwner.NONE, MicrophoneLifecyclePhase.IDLE, 0L, reason)
    }

    fun hasActiveCaptureOwner(): Boolean = synchronized(lock) {
        isCaptureOwner(_snapshot.value.owner)
    }

    fun getCurrentOwner(): AudioSessionOwner = synchronized(lock) { _snapshot.value.owner }

    fun getCurrentPhase(): MicrophoneLifecyclePhase = synchronized(lock) { _snapshot.value.phase }

    fun getTransitionHistory(): List<MicrophoneTransitionRecord> = synchronized(lock) {
        transitionHistory.toList()
    }

    fun clearTransitionHistory() = synchronized(lock) {
        transitionHistory.clear()
    }

    private fun commitLocked(
        newOwner: AudioSessionOwner,
        newPhase: MicrophoneLifecyclePhase,
        sessionId: Long,
        reason: String
    ) {
        val prevPhase = _snapshot.value.phase
        val now = System.currentTimeMillis()
        val nextSnapshot = MicrophoneOwnershipSnapshot(
            owner = newOwner,
            phase = newPhase,
            activeSessionId = sessionId,
            lastReason = reason,
            updatedAtMs = now
        )
        _snapshot.value = nextSnapshot
        _owner.value = newOwner
        _phase.value = newPhase
        if (transitionHistory.size >= 64) {
            transitionHistory.removeFirst()
        }
        transitionHistory.addLast(
            MicrophoneTransitionRecord(
                fromPhase = prevPhase,
                toPhase = newPhase,
                owner = newOwner,
                sessionId = sessionId,
                reason = reason,
                timestampMs = now
            )
        )
    }
}
