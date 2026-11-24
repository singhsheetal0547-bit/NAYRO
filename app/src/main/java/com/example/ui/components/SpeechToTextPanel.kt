package com.example.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.core.agent.AgentState
import com.example.core.service.ServiceLifecycleState
import com.example.core.service.VoiceAgentServiceSnapshot
import com.example.core.voice.VoiceState
import com.example.ui.theme.Coral
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.ForestGreen
import com.example.ui.theme.SoftSage
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningAmber

data class SpeechLanguageOption(
    val tag: String,
    val label: String
)

private val DefaultSpeechLanguages = listOf(
    SpeechLanguageOption(tag = "en-IN", label = "Hinglish"),
    SpeechLanguageOption(tag = "hi-IN", label = "Hindi"),
    SpeechLanguageOption(tag = "en-US", label = "English")
)

/**
 * Interactive Speech-to-Text & Autonomous Voice Control Panel for NAYRO.
 *
 * Implements the Android runtime microphone permission flow via
 * [ActivityResultContracts.RequestPermission] for [Manifest.permission.RECORD_AUDIO],
 * renders [NayroAudioVisualizer] driven by real SpeechRecognizer/AudioRecord RMS telemetry,
 * streams partial and final transcripts, supports Hindi/English/Hinglish switching,
 * exposes a dedicated Background Listening ON/OFF toggle and continuous conversation session control,
 * and routes recognized voice commands directly to NAYRO's unified AgentRuntime.
 */
@Composable
fun SpeechToTextVoicePanel(
    assistantName: String,
    voiceState: VoiceState,
    isMicrophoneGranted: Boolean,
    isSpeechRecognitionAvailable: Boolean,
    partialTranscript: String,
    finalTranscript: String,
    audioLevel: Float,
    selectedLanguageTag: String,
    voiceError: String?,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onStopSpeaking: () -> Unit,
    onMicrophonePermissionResult: (Boolean) -> Unit,
    onLanguageSelected: (String) -> Unit,
    onSendRecognizedCommand: (String) -> Unit,
    onClearTranscript: () -> Unit,
    modifier: Modifier = Modifier,
    agentState: AgentState = AgentState.Idle,
    standbyAudioLevel: Float = 0f,
    isBackgroundListeningEnabled: Boolean = false,
    isFollowUpSessionActive: Boolean = false,
    onToggleBackgroundListening: (Boolean) -> Unit = {},
    onEndConversationSession: () -> Unit = {},
    serviceSnapshot: VoiceAgentServiceSnapshot? = null,
    onStartAgentService: () -> Unit = { onToggleBackgroundListening(true) },
    onPauseAgentService: () -> Unit = {},
    onResumeAgentService: () -> Unit = {},
    onStopAgentService: () -> Unit = { onToggleBackgroundListening(false) }
) {
    val context = LocalContext.current
    var permissionDeniedOnce by remember { mutableStateOf(false) }
    var startListeningAfterGrant by remember { mutableStateOf(false) }
    var startServiceAfterGrant by remember { mutableStateOf(false) }

    val hasMicPermission = isMicrophoneGranted || try {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        onMicrophonePermissionResult(granted)
        if (granted) {
            permissionDeniedOnce = false
            if (startServiceAfterGrant) {
                startServiceAfterGrant = false
                onStartAgentService()
            }
            if (startListeningAfterGrant) {
                startListeningAfterGrant = false
                onStartListening()
            }
        } else {
            startListeningAfterGrant = false
            startServiceAfterGrant = false
            permissionDeniedOnce = true
        }
    }

    val isListening = voiceState == VoiceState.LISTENING || voiceState == VoiceState.FOLLOW_UP_LISTENING
    val isTranscribing = voiceState == VoiceState.TRANSCRIBING
    val isSpeaking = voiceState == VoiceState.SPEAKING
    val isActiveCapture = isListening || isTranscribing
    val visualState = resolveVisualizerState(voiceState, agentState)

    val displayTranscript = remember(partialTranscript, finalTranscript, isActiveCapture) {
        when {
            isActiveCapture && partialTranscript.isNotBlank() -> partialTranscript
            finalTranscript.isNotBlank() -> finalTranscript
            else -> partialTranscript
        }
    }

    val cleanAssistantName = assistantName.trim().ifEmpty { "NAYRO" }
    val personaTitle = "$cleanAssistantName • Voice Agent"
    val subtitleText = when {
        isFollowUpSessionActive -> "Continuous voice session active • Speak naturally without wake phrase"
        isBackgroundListeningEnabled -> "Background wake listening ON • Local on-device detection"
        else -> stringResource(R.string.stt_panel_subtitle)
    }

    fun triggerMicAction() {
        when {
            isActiveCapture -> onStopListening()
            isSpeaking -> {
                onStopSpeaking()
                if (hasMicPermission) {
                    onStartListening()
                } else {
                    startListeningAfterGrant = true
                    microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            !hasMicPermission -> {
                startListeningAfterGrant = true
                microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            else -> onStartListening()
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = if (isActiveCapture) 1.5.dp else 1.dp,
            color = if (isActiveCapture) Coral else MaterialTheme.colorScheme.outline
        ),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("stt_voice_panel")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header row: Persona + live Mic Permission status pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (isActiveCapture) Coral else ForestGreen)
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = personaTitle,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = personaTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    color = if (hasMicPermission) SoftSage else Coral.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.testTag("stt_mic_permission_badge")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = if (hasMicPermission) Icons.Default.CheckCircle else Icons.Default.MicOff,
                            contentDescription = null,
                            tint = if (hasMicPermission) SuccessGreen else Coral,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(
                                if (hasMicPermission) R.string.stt_mic_permission_granted
                                else R.string.stt_mic_permission_needed
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (hasMicPermission) ForestGreen else Coral
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Language selection chips for Android SpeechRecognizer
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("stt_language_selector")
            ) {
                items(DefaultSpeechLanguages) { option ->
                    val selected = selectedLanguageTag.equals(option.tag, ignoreCase = true)
                    FilterChip(
                        selected = selected,
                        onClick = { onLanguageSelected(option.tag) },
                        label = {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ForestGreen,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("stt_lang_chip_${option.tag}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Honest device availability check
            if (!isSpeechRecognitionAvailable) {
                Surface(
                    color = WarningAmber.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("stt_unavailable_banner")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = WarningAmber,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.stt_service_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Inline Microphone Permission rationale when not granted
            AnimatedVisibility(
                visible = !hasMicPermission,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .testTag("stt_permission_rationale_card")
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = stringResource(R.string.stt_mic_rationale_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.stt_mic_rationale_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    startListeningAfterGrant = true
                                    microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ForestGreen),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("stt_grant_mic_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.stt_grant_mic_permission))
                            }
                            if (permissionDeniedOnce) {
                                OutlinedButton(
                                    onClick = {
                                        val settingsIntent = Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", context.packageName, null)
                                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        try {
                                            context.startActivity(settingsIntent)
                                        } catch (_: Throwable) {}
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("stt_open_settings_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.stt_open_app_settings))
                                }
                            }
                        }
                    }
                }
            }

            // Central Speech-to-Text Capture Surface: Mic button + live waveform + status
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Primary Push-to-Talk / Toggle Microphone Button (>=48dp touch target)
                        IconButton(
                            onClick = { triggerMicAction() },
                            enabled = isSpeechRecognitionAvailable,
                            modifier = Modifier
                                .size(60.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        !isSpeechRecognitionAvailable -> MaterialTheme.colorScheme.outline
                                        isActiveCapture -> ErrorRed
                                        hasMicPermission -> ForestGreen
                                        else -> Coral
                                    }
                                )
                                .testTag("stt_primary_mic_button")
                        ) {
                            Icon(
                                imageVector = if (isActiveCapture) Icons.Default.Stop else Icons.Default.Mic,
                                contentDescription = if (isActiveCapture) {
                                    stringResource(R.string.stt_stop_listening)
                                } else {
                                    stringResource(R.string.stt_tap_to_speak)
                                },
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    isListening -> stringResource(R.string.stt_listening_prompt)
                                    isTranscribing -> stringResource(R.string.stt_transcribing_prompt)
                                    isSpeaking -> stringResource(R.string.stt_speaking_prompt)
                                    !hasMicPermission -> stringResource(R.string.stt_mic_rationale_title)
                                    visualState != VisualizerVisualState.IDLE -> visualState.accessibleLabel
                                    else -> stringResource(R.string.stt_tap_to_speak)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isActiveCapture) Coral else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("stt_status_label")
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            NayroAudioVisualizer(
                                voiceState = voiceState,
                                agentState = agentState,
                                audioLevel = audioLevel,
                                standbyAudioLevel = standbyAudioLevel,
                                barCount = 18,
                                height = 32.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("stt_audio_waveform")
                            )
                        }

                        if (isSpeaking) {
                            IconButton(
                                onClick = onStopSpeaking,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Coral.copy(alpha = 0.15f))
                                    .testTag("stt_stop_speaking_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                                    contentDescription = stringResource(R.string.stt_stop_speaking),
                                    tint = Coral
                                )
                            }
                        }
                    }

                    // AI Voice Agent Running Service Wrapper Controls
                    Spacer(modifier = Modifier.height(10.dp))
                    val serviceState = serviceSnapshot?.serviceLifecycleState ?: ServiceLifecycleState.STOPPED
                    val isServiceRunning = serviceSnapshot?.isServiceRunning ?: isBackgroundListeningEnabled
                    val isServicePaused = serviceSnapshot?.isPaused ?: (serviceState == ServiceLifecycleState.PAUSED)
                    val serviceStatusLabel = when {
                        !hasMicPermission -> stringResource(R.string.voice_agent_service_mic_required)
                        isActiveCapture -> stringResource(R.string.voice_agent_service_listening)
                        isServicePaused -> stringResource(R.string.voice_agent_service_paused)
                        isServiceRunning -> stringResource(R.string.voice_agent_service_running)
                        else -> stringResource(R.string.voice_agent_service_stopped)
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            1.dp,
                            when {
                                isServiceRunning && !isServicePaused -> SuccessGreen.copy(alpha = 0.5f)
                                isServicePaused -> WarningAmber.copy(alpha = 0.5f)
                                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                            }
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("stt_agent_service_wrapper_card")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.voice_agent_service_title),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = serviceSnapshot?.statusSummary?.takeIf { it.isNotBlank() }
                                            ?: if (isBackgroundListeningEnabled) {
                                                "Background Listening: ON"
                                            } else {
                                                "Background Listening: OFF"
                                            },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.testTag("stt_agent_service_status_text")
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    color = when {
                                        !hasMicPermission -> Coral.copy(alpha = 0.14f)
                                        isServicePaused -> WarningAmber.copy(alpha = 0.16f)
                                        isServiceRunning -> SoftSage
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    shape = RoundedCornerShape(999.dp),
                                    modifier = Modifier.testTag("stt_agent_service_state_pill")
                                ) {
                                    Text(
                                        text = serviceStatusLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = when {
                                            !hasMicPermission -> Coral
                                            isServicePaused -> WarningAmber
                                            isServiceRunning -> ForestGreen
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (!isServiceRunning) {
                                        Button(
                                            onClick = {
                                                if (hasMicPermission) {
                                                    onStartAgentService()
                                                } else {
                                                    startServiceAfterGrant = true
                                                    microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = ForestGreen),
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("stt_start_agent_service_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(stringResource(R.string.voice_agent_service_start))
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = {
                                                if (isServicePaused) onResumeAgentService() else onPauseAgentService()
                                            },
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("stt_pause_resume_agent_service_button")
                                        ) {
                                            Icon(
                                                imageVector = if (isServicePaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                stringResource(
                                                    if (isServicePaused) R.string.voice_agent_service_resume
                                                    else R.string.voice_agent_service_pause
                                                )
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = onStopAgentService,
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("stt_stop_agent_service_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Stop,
                                                contentDescription = null,
                                                tint = ErrorRed,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                stringResource(R.string.voice_agent_service_stop),
                                                color = ErrorRed
                                            )
                                        }
                                    }

                                    if (isFollowUpSessionActive) {
                                        TextButton(
                                            onClick = onEndConversationSession,
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("stt_end_session_button")
                                        ) {
                                            Text("End Session", color = Coral, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }

                                Switch(
                                    checked = isBackgroundListeningEnabled,
                                    onCheckedChange = { enable ->
                                        if (enable && !hasMicPermission) {
                                            startServiceAfterGrant = true
                                            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        } else {
                                            onToggleBackgroundListening(enable)
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Coral,
                                        checkedTrackColor = ForestGreen
                                    ),
                                    modifier = Modifier.testTag("stt_bg_listening_toggle")
                                )
                            }
                        }
                    }

                    // Live / Final Recognized Speech Transcript Box
                    AnimatedVisibility(visible = displayTranscript.isNotBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (isActiveCapture) "Live Transcript" else "Recognized Voice Command",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isActiveCapture) Coral else ForestGreen
                                        )
                                        if (!isActiveCapture) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                TextButton(
                                                    onClick = onClearTranscript,
                                                    modifier = Modifier
                                                        .minimumInteractiveComponentSize()
                                                        .testTag("stt_clear_transcript_button")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Clear,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(2.dp))
                                                    Text(stringResource(R.string.stt_clear_transcript))
                                                }
                                                IconButton(
                                                    onClick = { onSendRecognizedCommand(displayTranscript) },
                                                    modifier = Modifier
                                                        .minimumInteractiveComponentSize()
                                                        .testTag("stt_resend_transcript_button")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                                        contentDescription = "Run recognized voice command again",
                                                        tint = ForestGreen
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Text(
                                        text = "\"$displayTranscript\"",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.testTag("stt_transcript_text")
                                    )
                                }
                            }
                        }
                    }

                    // Actionable SpeechRecognizer Error Banner
                    AnimatedVisibility(visible = !voiceError.isNullOrBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = voiceError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = ErrorRed,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("stt_error_text")
                            )
                            TextButton(
                                onClick = { triggerMicAction() },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("stt_retry_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint = ForestGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.stt_retry_listening), color = ForestGreen)
                            }
                        }
                    }
                }
            }
        }
    }
}
