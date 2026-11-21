package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.core.agent.AgentState
import com.example.core.voice.VoiceState
import com.example.ui.theme.Coral
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.ForestGreen
import com.example.ui.theme.SoftSage
import com.example.ui.theme.SuccessGreen
import kotlin.math.PI
import kotlin.math.sin

/**
 * Canonical visual states for NAYRO's Audio-Reactive Visualizer (Section 7).
 */
enum class VisualizerVisualState(val accessibleLabel: String) {
    IDLE("NAYRO idle and ready"),
    STANDBY("Background wake-word standby active"),
    WAKE_DETECTED("Wake phrase detected"),
    LISTENING("Listening to live microphone audio"),
    TRANSCRIBING("Transcribing speech to text"),
    THINKING("NAYRO agent reasoning and planning"),
    EXECUTING("Executing autonomous Android task"),
    SPEAKING("NAYRO speaking response"),
    ERROR("Voice or agent error")
}

/**
 * Deterministically resolves the single active [VisualizerVisualState] from the unified
 * application-scoped [VoiceState] and [AgentState] without conflicting state machines.
 */
fun resolveVisualizerState(
    voiceState: VoiceState,
    agentState: AgentState
): VisualizerVisualState {
    return when {
        voiceState == VoiceState.ERROR || agentState is AgentState.Error -> VisualizerVisualState.ERROR
        voiceState == VoiceState.WAKE_DETECTED -> VisualizerVisualState.WAKE_DETECTED
        voiceState == VoiceState.LISTENING ||
            voiceState == VoiceState.FOLLOW_UP_LISTENING ||
            agentState is AgentState.UserListening -> VisualizerVisualState.LISTENING
        voiceState == VoiceState.TRANSCRIBING || agentState is AgentState.Transcribing -> VisualizerVisualState.TRANSCRIBING
        voiceState == VoiceState.SPEAKING || agentState is AgentState.Speaking -> VisualizerVisualState.SPEAKING
        voiceState == VoiceState.EXECUTING ||
            agentState is AgentState.ExecutingTask ||
            agentState is AgentState.ExecutingAction -> VisualizerVisualState.EXECUTING
        voiceState == VoiceState.THINKING ||
            agentState is AgentState.Thinking ||
            agentState is AgentState.PlanningTask ||
            agentState is AgentState.ProposingAction -> VisualizerVisualState.THINKING
        voiceState == VoiceState.STANDBY || agentState is AgentState.StandbyListening -> VisualizerVisualState.STANDBY
        else -> VisualizerVisualState.IDLE
    }
}

/**
 * Pure telemetry bar-height calculator driven strictly by real microphone RMS/audio level (`0f..1f`)
 * during [VisualizerVisualState.LISTENING] and deterministic phase curves during processing states.
 * Never fabricates random microphone spikes when real audio level is zero.
 */
fun computeVisualizerBarHeights(
    state: VisualizerVisualState,
    realAudioLevel: Float,
    phase: Float,
    barCount: Int = 18
): List<Float> {
    val safeCount = barCount.coerceAtLeast(3)
    val clampedAudio = if (realAudioLevel.isFinite()) realAudioLevel.coerceIn(0f, 1f) else 0f

    return List(safeCount) { index ->
        val normalizedPos = (index.toFloat() / (safeCount - 1)) * 2f - 1f // -1f..1f
        val centerEnvelope = (1f - (normalizedPos * normalizedPos * 0.65f)).coerceIn(0.22f, 1f)

        when (state) {
            VisualizerVisualState.IDLE -> {
                0.14f + 0.08f * centerEnvelope
            }
            VisualizerVisualState.STANDBY -> {
                // Calm breathing envelope lightly modulated by real standby RMS when AudioRecord is active
                val breathe = 0.5f + 0.5f * sin(phase * 2.0 * PI).toFloat()
                val levelBoost = clampedAudio * 0.25f
                (0.14f + (0.14f * breathe + levelBoost) * centerEnvelope).coerceIn(0.10f, 0.55f)
            }
            VisualizerVisualState.WAKE_DETECTED -> {
                val ripple = 0.5f + 0.5f * sin((phase * 2.0 * PI) - kotlin.math.abs(normalizedPos) * 3.0).toFloat()
                (0.32f + 0.58f * ripple * centerEnvelope).coerceIn(0.20f, 0.96f)
            }
            VisualizerVisualState.LISTENING -> {
                // Strictly proportional to real SpeechRecognizer RMS audio level (0 -> quiet flat baseline)
                val harmonicWeight = if (index % 2 == 0) 1.0f else 0.78f
                val baseline = 0.12f
                val reactive = clampedAudio * centerEnvelope * harmonicWeight * 0.86f
                (baseline + reactive).coerceIn(0.10f, 0.98f)
            }
            VisualizerVisualState.TRANSCRIBING -> {
                val wave = 0.5f + 0.5f * sin((phase * 2.0 * PI) + index * 0.55).toFloat()
                (0.20f + 0.52f * wave * centerEnvelope).coerceIn(0.16f, 0.80f)
            }
            VisualizerVisualState.THINKING -> {
                val orbit = 0.5f + 0.5f * sin((phase * 2.0 * PI) + normalizedPos * PI).toFloat()
                (0.18f + 0.58f * orbit * centerEnvelope).coerceIn(0.16f, 0.82f)
            }
            VisualizerVisualState.EXECUTING -> {
                // Sweeping progress scanner across bars
                val scannerPos = (phase * 2f) - 1f
                val dist = kotlin.math.abs(normalizedPos - scannerPos)
                val highlight = (1f - dist * 2.4f).coerceIn(0f, 1f)
                (0.20f + 0.68f * highlight).coerceIn(0.18f, 0.92f)
            }
            VisualizerVisualState.SPEAKING -> {
                // Smooth TTS-synchronized vocal cadence active only while TTS is genuinely playing
                val cadence = 0.5f + 0.5f * sin((phase * 2.0 * PI) + index * 0.42).toFloat()
                val secondary = 0.5f + 0.5f * sin((phase * 4.0 * PI) - index * 0.28).toFloat()
                (0.22f + (0.44f * cadence + 0.24f * secondary) * centerEnvelope).coerceIn(0.18f, 0.92f)
            }
            VisualizerVisualState.ERROR -> {
                0.24f
            }
        }
    }
}

/**
 * Professional, battery-efficient Jetpack Compose Audio-Reactive Visualizer for NAYRO.
 *
 * - Runs zero infinite animation loops in IDLE or ERROR states to conserve battery.
 * - Reacts strictly to real microphone RMS audio telemetry in LISTENING and STANDBY states.
 * - Visually distinguishes all 9 agent/voice states with accessible semantics.
 */
@Composable
fun NayroAudioVisualizer(
    voiceState: VoiceState,
    agentState: AgentState,
    audioLevel: Float,
    standbyAudioLevel: Float = 0f,
    barCount: Int = 18,
    height: Dp = 36.dp,
    modifier: Modifier = Modifier
) {
    val visualState = resolveVisualizerState(voiceState, agentState)
    val effectiveRealAudioLevel = when (visualState) {
        VisualizerVisualState.LISTENING -> audioLevel
        VisualizerVisualState.STANDBY -> standbyAudioLevel
        else -> 0f
    }

    val smoothedLevel by animateFloatAsState(
        targetValue = effectiveRealAudioLevel.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 460f),
        label = "nayro_visualizer_audio_level"
    )

    // Battery-efficient phase driver: only animate continuously during active non-static states
    val phase = when (visualState) {
        VisualizerVisualState.IDLE,
        VisualizerVisualState.ERROR,
        VisualizerVisualState.LISTENING -> 0f
        else -> {
            val durationMs = when (visualState) {
                VisualizerVisualState.STANDBY -> 2800
                VisualizerVisualState.WAKE_DETECTED -> 650
                VisualizerVisualState.TRANSCRIBING -> 1100
                VisualizerVisualState.THINKING -> 1400
                VisualizerVisualState.EXECUTING -> 1000
                VisualizerVisualState.SPEAKING -> 900
                else -> 1600
            }
            val transition = rememberInfiniteTransition(label = "nayro_visualizer_phase")
            val animatedPhase by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = durationMs, easing = if (visualState == VisualizerVisualState.STANDBY) FastOutSlowInEasing else LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "phase_val"
            )
            animatedPhase
        }
    }

    val barHeights = computeVisualizerBarHeights(
        state = visualState,
        realAudioLevel = smoothedLevel,
        phase = phase,
        barCount = barCount
    )

    val activeColor: Color = when (visualState) {
        VisualizerVisualState.IDLE -> SoftSage
        VisualizerVisualState.STANDBY -> ForestGreen.copy(alpha = 0.72f)
        VisualizerVisualState.WAKE_DETECTED -> Coral
        VisualizerVisualState.LISTENING -> Coral
        VisualizerVisualState.TRANSCRIBING -> ForestGreen
        VisualizerVisualState.THINKING -> ForestGreen
        VisualizerVisualState.EXECUTING -> SuccessGreen
        VisualizerVisualState.SPEAKING -> Coral.copy(alpha = 0.90f)
        VisualizerVisualState.ERROR -> ErrorRed
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = visualState.accessibleLabel }
            .testTag("nayro_audio_visualizer")
    ) {
        val count = barHeights.size
        val spacing = size.width / (count * 2.1f)
        val barWidth = ((size.width - spacing * (count - 1)) / count).coerceAtLeast(3f)
        val centerY = size.height / 2f

        for (i in 0 until count) {
            val h = (size.height * barHeights[i]).coerceAtLeast(4f)
            val left = i * (barWidth + spacing)
            drawRoundRect(
                color = activeColor,
                topLeft = Offset(left, centerY - h / 2f),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }

        if (visualState == VisualizerVisualState.WAKE_DETECTED) {
            drawCircle(
                color = Coral.copy(alpha = (1f - phase).coerceIn(0f, 0.55f)),
                radius = (size.height * 0.45f) + (size.height * 0.55f * phase),
                center = Offset(size.width / 2f, centerY),
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}
