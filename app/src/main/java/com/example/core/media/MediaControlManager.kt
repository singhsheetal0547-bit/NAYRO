package com.example.core.media

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import com.example.core.accessibility.AccessibilityActionController
import com.example.core.accessibility.AppNavigationManager
import com.example.core.accessibility.NayroAccessibilityService
import com.example.core.agent.ActionExecutionResult
import com.example.core.agent.VerificationStatus
import com.example.core.memory.MemoryCategory
import com.example.core.memory.MemoryManager
import com.example.core.screen.ScreenIntelligenceManager
import com.example.data.local.entities.MemoryEntity
import kotlinx.coroutines.delay

enum class MediaOperation {
    PLAY_QUERY,
    PLAY_FAVOURITE_SONG,
    PLAY_FAVOURITE_ARTIST,
    PLAY_BY_GENRE_OR_MOOD,
    PAUSE,
    RESUME,
    NEXT_TRACK,
    PREVIOUS_TRACK,
    STOP,
    CHECK_PLAYBACK_STATUS
}

enum class MediaPlaybackVerificationState {
    APP_OPENED,
    SEARCH_OPENED,
    COMMAND_DISPATCHED,
    PLAYBACK_VERIFIED,
    PAUSED_VERIFIED,
    FAILED,
    INCONCLUSIVE,
    // Backwards-compatible aliases
    PLAYBACK_VERIFIED_ACTIVE,
    PLAYBACK_PAUSED_VERIFIED,
    MEDIA_KEY_DISPATCHED_INCONCLUSIVE,
    APP_SEARCH_OPENED_INCONCLUSIVE,
    PLAYBACK_FAILED
}

data class MediaSessionState(
    var lastPlayedQuery: String? = null,
    var lastArtist: String? = null,
    var lastGenreOrMood: String? = null,
    var lastMediaAppPackage: String = "com.google.android.youtube",
    var lastVerificationState: MediaPlaybackVerificationState = MediaPlaybackVerificationState.INCONCLUSIVE
)

/**
 * Phase 4 & Section 11 — Dedicated Media & Entertainment Intelligence Layer for NAYRO.
 * Supports:
 * - Play music/videos on YouTube, YouTube Music, Spotify, or user's preferred music app
 * - Play specific song, play by artist, play by genre/mood, play user's favourite song from Memory
 * - Next track, previous track, pause, resume via Android AudioManager media key dispatch
 * - Strict playback status verification via AudioManager.isMusicActive, MediaSessionManager, and ScreenIntelligenceManager
 * - Never claims verified playback merely because a search results page opened or a media key was dispatched!
 */
class MediaControlManager(
    private val context: Context,
    private val screenIntelligenceManager: ScreenIntelligenceManager,
    private val memoryManager: MemoryManager
) {

    companion object {
        const val PKG_YOUTUBE = "com.google.android.youtube"
        const val PKG_YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"
        const val PKG_SPOTIFY = "com.spotify.music"
    }

    private val sessionState = MediaSessionState()

    /**
     * Test hook for deterministic unit verification of active vs inactive media playback state.
     */
    var testOverridePlaybackActive: Boolean? = null

    fun getSessionState(): MediaSessionState = sessionState.copy()

    fun isAudioCurrentlyPlaying(): Boolean {
        testOverridePlaybackActive?.let { return it }
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am?.isMusicActive == true) return true
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? android.media.session.MediaSessionManager
            val controllers = try {
                msm?.getActiveSessions(null).orEmpty()
            } catch (_: SecurityException) {
                emptyList()
            }
            controllers.any { controller ->
                controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Resolves the user's favourite song, favourite artist, or preferred music app from saved [memories].
     */
    fun resolveFavouriteSongFromMemories(memories: List<MemoryEntity>): String? {
        for (mem in memories) {
            val combined = "${mem.keyName} ${mem.memoryValue}".lowercase()
            if (combined.contains("favourite song") || combined.contains("favorite song") ||
                combined.contains("pasandida gaana") || combined.contains("favourite gaana") ||
                mem.keyName.equals("favourite_song", ignoreCase = true)
            ) {
                val cleaned = mem.memoryValue
                    .replace(Regex("(?i)^.*?(?:favourite song|favorite song|pasandida gaana|favourite gaana)\\s*(?:is|hai|:|-)?\\s*"), "")
                    .trim()
                if (cleaned.isNotBlank()) return cleaned
            }
        }
        for (mem in memories) {
            val combined = "${mem.keyName} ${mem.memoryValue}".lowercase()
            if (combined.contains("favourite artist") || combined.contains("favorite artist") ||
                combined.contains("favourite singer") || mem.keyName.equals("favourite_artist", ignoreCase = true)
            ) {
                val cleaned = mem.memoryValue
                    .replace(Regex("(?i)^.*?(?:favourite artist|favorite artist|favourite singer)\\s*(?:is|hai|:|-)?\\s*"), "")
                    .trim()
                if (cleaned.isNotBlank()) return "$cleaned songs"
            }
        }
        return null
    }

    fun resolvePreferredMusicApp(explicitApp: String?, memories: List<MemoryEntity>): String {
        if (!explicitApp.isNullOrBlank()) {
            val lower = explicitApp.trim().lowercase()
            return when {
                lower.contains("spotify") -> PKG_SPOTIFY
                lower.contains("youtube music") || lower.contains("yt music") -> PKG_YOUTUBE_MUSIC
                lower.contains("youtube") -> PKG_YOUTUBE
                else -> AppNavigationManager.resolvePackage(context, explicitApp) ?: PKG_YOUTUBE
            }
        }
        for (mem in memories) {
            val combined = "${mem.keyName} ${mem.memoryValue}".lowercase()
            if (combined.contains("music app") || combined.contains("preferred_music_app")) {
                if (combined.contains("spotify")) return PKG_SPOTIFY
                if (combined.contains("youtube music")) return PKG_YOUTUBE_MUSIC
            }
        }
        return sessionState.lastMediaAppPackage
    }

    /**
     * Executes a structured media operation and verifies playback state honestly.
     */
    suspend fun executeMediaCommand(
        operationStr: String,
        query: String? = null,
        artist: String? = null,
        genreOrMood: String? = null,
        targetApp: String? = null,
        savedMemories: List<MemoryEntity> = emptyList()
    ): ActionExecutionResult {
        val op = parseOperation(operationStr, query, genreOrMood)
        val resolvedAppPkg = resolvePreferredMusicApp(targetApp, savedMemories)
        sessionState.lastMediaAppPackage = resolvedAppPkg

        return when (op) {
            MediaOperation.PAUSE -> dispatchMediaTransportKey(
                keyCode = KeyEvent.KEYCODE_MEDIA_PAUSE,
                actionLabel = "Pause playback",
                expectMusicActiveAfter = false
            )

            MediaOperation.RESUME -> dispatchMediaTransportKey(
                keyCode = KeyEvent.KEYCODE_MEDIA_PLAY,
                actionLabel = "Resume playback",
                expectMusicActiveAfter = true
            )

            MediaOperation.STOP -> dispatchMediaTransportKey(
                keyCode = KeyEvent.KEYCODE_MEDIA_STOP,
                actionLabel = "Stop playback",
                expectMusicActiveAfter = false
            )

            MediaOperation.NEXT_TRACK -> dispatchMediaTransportKey(
                keyCode = KeyEvent.KEYCODE_MEDIA_NEXT,
                actionLabel = "Skip to next track",
                expectMusicActiveAfter = true
            )

            MediaOperation.PREVIOUS_TRACK -> dispatchMediaTransportKey(
                keyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                actionLabel = "Return to previous track",
                expectMusicActiveAfter = true
            )

            MediaOperation.CHECK_PLAYBACK_STATUS -> {
                val active = isAudioCurrentlyPlaying()
                val lastTrack = sessionState.lastPlayedQuery ?: "Unknown track"
                val statusMsg = if (active) {
                    sessionState.lastVerificationState = MediaPlaybackVerificationState.PLAYBACK_VERIFIED
                    "Media playback is currently ACTIVE (last requested: $lastTrack on ${sessionState.lastMediaAppPackage}) [STATE=PLAYBACK_VERIFIED]."
                } else {
                    sessionState.lastVerificationState = MediaPlaybackVerificationState.PAUSED_VERIFIED
                    "No active media playback detected by AudioManager (last requested: $lastTrack) [STATE=PAUSED_VERIFIED]."
                }
                ActionExecutionResult(
                    isSuccess = true,
                    outputMessage = statusMsg,
                    status = VerificationStatus.VERIFIED
                )
            }

            MediaOperation.PLAY_FAVOURITE_SONG -> {
                val favSong = resolveFavouriteSongFromMemories(savedMemories)
                    ?: query?.takeIf {
                        !it.equals("favourite", ignoreCase = true) &&
                            !it.equals("favorite", ignoreCase = true) &&
                            !it.contains("favourite song", ignoreCase = true)
                    }
                    ?: sessionState.lastPlayedQuery
                    ?: "Arijit Singh best songs"

                sessionState.lastPlayedQuery = favSong
                playSearchQueryInMediaApp(favSong, resolvedAppPkg, isFavouriteLookup = true)
            }

            MediaOperation.PLAY_FAVOURITE_ARTIST -> {
                val targetArtist = artist?.takeIf { it.isNotBlank() }
                    ?: query?.takeIf { it.isNotBlank() }
                    ?: sessionState.lastArtist
                    ?: "Arijit Singh"
                sessionState.lastArtist = targetArtist
                val fullQuery = if (targetArtist.lowercase().endsWith("songs")) targetArtist else "$targetArtist songs"
                sessionState.lastPlayedQuery = fullQuery
                playSearchQueryInMediaApp(fullQuery, resolvedAppPkg)
            }

            MediaOperation.PLAY_BY_GENRE_OR_MOOD -> {
                val mood = genreOrMood?.takeIf { it.isNotBlank() }
                    ?: query?.takeIf { it.isNotBlank() }
                    ?: sessionState.lastGenreOrMood
                    ?: "sad songs"
                sessionState.lastGenreOrMood = mood
                val fullQuery = if (mood.lowercase().contains("song") || mood.lowercase().contains("music")) {
                    mood
                } else {
                    "$mood songs"
                }
                sessionState.lastPlayedQuery = fullQuery
                playSearchQueryInMediaApp(fullQuery, resolvedAppPkg)
            }

            MediaOperation.PLAY_QUERY -> {
                val cleanQuery = query?.trim()?.takeIf { it.isNotBlank() }
                    ?: artist?.trim()?.takeIf { it.isNotBlank() }?.let { "$it songs" }
                    ?: genreOrMood?.trim()?.takeIf { it.isNotBlank() }?.let { "$it songs" }
                    ?: resolveFavouriteSongFromMemories(savedMemories)
                    ?: sessionState.lastPlayedQuery
                    ?: "Top Hindi Songs"
                sessionState.lastPlayedQuery = cleanQuery
                if (!artist.isNullOrBlank()) {
                    sessionState.lastArtist = artist
                }
                playSearchQueryInMediaApp(cleanQuery, resolvedAppPkg)
            }
        }
    }

    private fun parseOperation(
        operationStr: String,
        query: String?,
        genreOrMood: String?
    ): MediaOperation {
        val norm = operationStr.trim().uppercase()
        return when {
            norm == "PAUSE" || norm == "PAUSE_MEDIA" -> MediaOperation.PAUSE
            norm == "RESUME" || norm == "RESUME_MEDIA" -> MediaOperation.RESUME
            norm == "STOP" || norm == "STOP_MEDIA" -> MediaOperation.STOP
            norm == "NEXT" || norm == "NEXT_TRACK" || norm == "SKIP" -> MediaOperation.NEXT_TRACK
            norm == "PREVIOUS" || norm == "PREVIOUS_TRACK" || norm == "PREV" -> MediaOperation.PREVIOUS_TRACK
            norm == "STATUS" || norm == "CHECK_STATUS" -> MediaOperation.CHECK_PLAYBACK_STATUS
            norm == "PLAY_FAVOURITE" || norm == "PLAY_FAVORITE" || norm == "PLAY_FAVOURITE_SONG" ||
                query?.contains("favourite song", ignoreCase = true) == true ||
                query?.contains("favorite song", ignoreCase = true) == true ||
                query?.contains("pasandida gaana", ignoreCase = true) == true -> MediaOperation.PLAY_FAVOURITE_SONG
            norm == "PLAY_BY_GENRE" || norm == "PLAY_BY_MOOD" || !genreOrMood.isNullOrBlank() -> MediaOperation.PLAY_BY_GENRE_OR_MOOD
            norm == "PLAY_BY_ARTIST" -> MediaOperation.PLAY_FAVOURITE_ARTIST
            else -> MediaOperation.PLAY_QUERY
        }
    }

    private suspend fun dispatchMediaTransportKey(
        keyCode: Int,
        actionLabel: String,
        expectMusicActiveAfter: Boolean
    ): ActionExecutionResult {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        var dispatched = false
        if (am != null) {
            try {
                val eventTime = SystemClock.uptimeMillis()
                val downEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
                val upEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)
                am.dispatchMediaKeyEvent(downEvent)
                am.dispatchMediaKeyEvent(upEvent)
                dispatched = true
            } catch (_: Exception) {
                dispatched = false
            }
        }

        // Also try Accessibility UI button fallback if connected
        if (NayroAccessibilityService.isConnected()) {
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PAUSE -> AccessibilityActionController.performClick(targetText = "Pause")
                KeyEvent.KEYCODE_MEDIA_PLAY -> AccessibilityActionController.performClick(targetText = "Play")
                KeyEvent.KEYCODE_MEDIA_NEXT -> AccessibilityActionController.performClick(targetText = "Next")
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> AccessibilityActionController.performClick(targetText = "Previous")
            }
        }

        delay(150)
        val musicActiveNow = isAudioCurrentlyPlaying()
        if (dispatched) {
            if (!expectMusicActiveAfter && !musicActiveNow) {
                sessionState.lastVerificationState = MediaPlaybackVerificationState.PAUSED_VERIFIED
                return ActionExecutionResult(
                    isSuccess = true,
                    outputMessage = "$actionLabel executed and verified (audio stream is now paused/inactive) [STATE=PAUSED_VERIFIED].",
                    status = VerificationStatus.VERIFIED
                )
            }
            if (expectMusicActiveAfter && musicActiveNow) {
                sessionState.lastVerificationState = MediaPlaybackVerificationState.PLAYBACK_VERIFIED
                return ActionExecutionResult(
                    isSuccess = true,
                    outputMessage = "$actionLabel executed and verified (active audio playback confirmed) [STATE=PLAYBACK_VERIFIED].",
                    status = VerificationStatus.VERIFIED
                )
            }
            // Section 11: Dispatching a media key without verifying the expected playback state is COMMAND_DISPATCHED (INCONCLUSIVE), NOT VERIFIED!
            sessionState.lastVerificationState = MediaPlaybackVerificationState.COMMAND_DISPATCHED
            return ActionExecutionResult(
                isSuccess = false,
                outputMessage = "$actionLabel media key dispatched to Android AudioManager, but active playback state change could not be verified [STATE=COMMAND_DISPATCHED].",
                status = VerificationStatus.INCONCLUSIVE
            )
        }

        sessionState.lastVerificationState = MediaPlaybackVerificationState.FAILED
        return ActionExecutionResult(
            isSuccess = false,
            outputMessage = "Failed to dispatch media control command ($actionLabel): AudioManager unavailable [STATE=FAILED].",
            status = VerificationStatus.FAILED
        )
    }

    private suspend fun playSearchQueryInMediaApp(
        searchQuery: String,
        targetPackage: String,
        isFavouriteLookup: Boolean = false
    ): ActionExecutionResult {
        var intentDispatched = false
        var directPlayIntentUsed = false

        // 1. Try MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH first for direct playback support
        try {
            val playFromSearchIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                putExtra(SearchManager.QUERY, searchQuery)
                setPackage(targetPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (playFromSearchIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(playFromSearchIntent)
                intentDispatched = true
                directPlayIntentUsed = true
            }
        } catch (_: Exception) {}

        // 2. Fallback to YouTube / Spotify search URI or launch intent
        if (!intentDispatched) {
            try {
                val searchUri = if (targetPackage == PKG_SPOTIFY) {
                    Uri.parse("spotify:search:${Uri.encode(searchQuery)}")
                } else {
                    Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(searchQuery)}")
                }
                val viewIntent = Intent(Intent.ACTION_VIEW, searchUri).apply {
                    setPackage(targetPackage)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(viewIntent)
                intentDispatched = true
            } catch (_: Exception) {
                // Fallback without package restriction (opens browser or installed handler)
                try {
                    val webUri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(searchQuery)}")
                    val fallbackIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallbackIntent)
                    intentDispatched = true
                } catch (_: Exception) {
                    intentDispatched = false
                }
            }
        }

        // 3. If Accessibility Service is connected, attempt to tap the first video/song result to start playback
        if (NayroAccessibilityService.isConnected()) {
            delay(350)
            AccessibilityActionController.performClickWithCoordinateFallback(targetText = searchQuery) ||
                AccessibilityActionController.performClickWithCoordinateFallback(targetText = "Play")
            delay(200)
        }

        val musicActive = isAudioCurrentlyPlaying()
        val favPrefix = if (isFavouriteLookup) "Favourite song resolved ('$searchQuery'). " else ""

        return when {
            musicActive -> {
                sessionState.lastVerificationState = MediaPlaybackVerificationState.PLAYBACK_VERIFIED
                ActionExecutionResult(
                    isSuccess = true,
                    outputMessage = "${favPrefix}Playing '$searchQuery' on $targetPackage (playback active) [STATE=PLAYBACK_VERIFIED].",
                    status = VerificationStatus.VERIFIED
                )
            }
            intentDispatched -> {
                // Section 11: Opening a search page or media app without active audio playback is SEARCH_OPENED / APP_OPENED (INCONCLUSIVE), NEVER VERIFIED!
                val state = if (directPlayIntentUsed) MediaPlaybackVerificationState.APP_OPENED else MediaPlaybackVerificationState.SEARCH_OPENED
                sessionState.lastVerificationState = state
                ActionExecutionResult(
                    isSuccess = false,
                    outputMessage = "${favPrefix}Opened '$searchQuery' in $targetPackage, but playback is not yet verified active [STATE=${state.name}]. Select a track or enable NAYRO Accessibility Service for automatic track selection.",
                    status = VerificationStatus.INCONCLUSIVE
                )
            }
            else -> {
                sessionState.lastVerificationState = MediaPlaybackVerificationState.FAILED
                ActionExecutionResult(
                    isSuccess = false,
                    outputMessage = "Could not launch media app ($targetPackage) for query '$searchQuery' [STATE=FAILED].",
                    status = VerificationStatus.FAILED
                )
            }
        }
    }
}
