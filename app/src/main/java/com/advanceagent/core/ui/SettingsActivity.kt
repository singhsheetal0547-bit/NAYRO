package com.advanceagent.core.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.advanceagent.core.AgentApplication
import com.advanceagent.core.data.NayroPersonalityMode
import com.advanceagent.core.data.SecurePreferences
import com.advanceagent.core.service.AgentPowerManager
import com.advanceagent.core.tools.YouTubeApiClient
import com.advanceagent.core.ui.orb.FloatingOrbService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AdvanceVoiceAgentSettingsScreen()
            }
        }
    }

    @Composable
    private fun AdvanceVoiceAgentSettingsScreen() {
        val app = remember { application as AgentApplication }
        val prefs = remember { app.prefs }
        val coroutineScope = rememberCoroutineScope()

        var isMasterPowerOn by remember { mutableStateOf(prefs.isMasterPowerOn) }
        var geminiApiKey by remember { mutableStateOf(prefs.geminiApiKey) }
        var ephemeralToken by remember { mutableStateOf(prefs.ephemeralAccessToken) }
        var geminiModelId by remember { mutableStateOf(prefs.geminiModel) }
        var assistantName by remember { mutableStateOf(prefs.assistantName) }
        var userName by remember { mutableStateOf(prefs.userName) }
        var selectedVoice by remember { mutableStateOf(prefs.voiceName) }
        var selectedPersonality by remember { mutableStateOf(prefs.personalityMode) }

        var appWakeEnabled by remember { mutableStateOf(prefs.appWakeWordEnabled) }
        var appWakeWord by remember { mutableStateOf(prefs.appWakeWord) }
        var userWakeEnabled by remember { mutableStateOf(prefs.userWakeWordEnabled) }
        var userCustomWakeWords by remember { mutableStateOf(prefs.userCustomWakeWordsRaw) }
        var wakeThreshold by remember { mutableFloatStateOf(prefs.wakeTriggerThreshold) }
        var wakeBoosting by remember { mutableFloatStateOf(prefs.wakeBoostingScore) }

        var googleSearchGrounding by remember { mutableStateOf(prefs.googleSearchGroundingEnabled) }
        var liveTranscription by remember { mutableStateOf(prefs.liveTranscriptionEnabled) }
        var sessionResumption by remember { mutableStateOf(prefs.sessionResumptionEnabled) }
        var soundAndHaptics by remember { mutableStateOf(prefs.soundAndHapticsEnabled) }
        var requireWhatsAppConfirmation by remember { mutableStateOf(prefs.requireWhatsAppConfirmation) }
        var autoStartOnBoot by remember { mutableStateOf(prefs.autoStartOnBoot) }

        var youtubeApiEnabled by remember { mutableStateOf(prefs.youtubeApiEnabled) }
        var youtubeApiKey by remember { mutableStateOf(prefs.youtubeApiKey) }
        var youtubeValidationMessage by remember { mutableStateOf<String?>(null) }
        var savedMemories by remember { mutableStateOf<List<String>>(emptyList()) }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                try {
                    savedMemories = app.memoryDb.memoryDao().getRecentMemories().map { it.fact }
                } catch (_: Exception) {
                }
            }
        }

        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0E17))
                .verticalScroll(scrollState)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                text = "NAYRO ($assistantName) • Agent Settings",
                color = Color(0xFF00E5FF),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )

            // 1. MASTER POWER SWITCH & COMPLETE SHUTDOWN CARD
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isMasterPowerOn) Color(0xFF0F291E) else Color(0xFF2A1215)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isMasterPowerOn) "MASTER POWER: ON" else "MASTER POWER: OFF (SHUTDOWN)",
                                color = if (isMasterPowerOn) Color(0xFF4ADE80) else Color(0xFFF87171),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "Turning OFF immediately terminates Gemini Live WebSocket, Microphone, Sherpa-ONNX KWS, Floating Orb, MediaProjection & WakeLock.",
                                color = Color(0xFFCBD5E1),
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = isMasterPowerOn,
                            onCheckedChange = { turnedOn ->
                                isMasterPowerOn = turnedOn
                                if (turnedOn) {
                                    AgentPowerManager.turnOn(applicationContext)
                                    Toast.makeText(
                                        this@SettingsActivity,
                                        "NAYRO Powered ON",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    AgentPowerManager.completeShutdown(applicationContext)
                                    Toast.makeText(
                                        this@SettingsActivity,
                                        "NAYRO Completely Shut Down",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        )
                    }
                }
            }

            // 2. IDENTITY, PERSONALITY MODES & VOICE
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF151C2C)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Identity, Personality Mode & Voice",
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = assistantName,
                            onValueChange = { assistantName = it },
                            label = { Text("Assistant Name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = userName,
                            onValueChange = { userName = it },
                            label = { Text("Your Title (e.g. Boss)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text("Personality Mode", color = Color(0xFFE2E8F0), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NayroPersonalityMode.entries.forEach { mode ->
                            val selected = selectedPersonality == mode
                            FilterChip(
                                selected = selected,
                                onClick = { selectedPersonality = mode },
                                label = { Text(mode.label.removeSuffix(" Mode"), fontSize = 12.sp) }
                            )
                        }
                    }

                    Text("Gemini Live Prebuilt Voice", color = Color(0xFFE2E8F0), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SecurePreferences.SUPPORTED_VOICES.forEach { voice ->
                            FilterChip(
                                selected = selectedVoice.equals(voice, ignoreCase = true),
                                onClick = { selectedVoice = voice },
                                label = { Text(voice, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }

            // 3. GEMINI LIVE API & GOOGLE AI STUDIO FEATURES
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF151C2C)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Gemini Live API & AI Studio Capabilities",
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )

                    OutlinedTextField(
                        value = geminiApiKey,
                        onValueChange = { geminiApiKey = it },
                        label = { Text("Google AI Studio Gemini API Key (BYOK)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = ephemeralToken,
                        onValueChange = { ephemeralToken = it },
                        label = { Text("Optional Ephemeral Access Token (overrides API Key)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = geminiModelId,
                        onValueChange = { geminiModelId = it },
                        label = { Text("Gemini Live Model ID (e.g. models/gemini-2.0-flash-live-001)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    SettingToggleRow(
                        title = "Google Search Grounding",
                        subtitle = "Enable live web search grounding in Gemini Live setup",
                        checked = googleSearchGrounding,
                        onCheckedChange = { googleSearchGrounding = it }
                    )

                    SettingToggleRow(
                        title = "Real-Time Audio Transcription",
                        subtitle = "Transcribe user & assistant voice turns into Chat History",
                        checked = liveTranscription,
                        onCheckedChange = { liveTranscription = it }
                    )

                    SettingToggleRow(
                        title = "Session Resumption & Sliding Window Compression",
                        subtitle = "Automatically resume interrupted WebSocket sessions",
                        checked = sessionResumption,
                        onCheckedChange = { sessionResumption = it }
                    )
                }
            }

            // 4. DUAL WAKE WORD CONFIGURATION (SHERPA-ONNX KWS)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF151C2C)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Dual Wake Word Engine (100% Offline KWS)",
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Enable Default Wake Words (Hi Babu / Hi Nayro)", color = Color.White, fontSize = 14.sp)
                        Switch(checked = appWakeEnabled, onCheckedChange = { appWakeEnabled = it })
                    }

                    OutlinedTextField(
                        value = appWakeWord,
                        onValueChange = { appWakeWord = it },
                        label = { Text("Default Wake Phrases (Comma-separated)") },
                        singleLine = true,
                        enabled = appWakeEnabled,
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(color = Color(0xFF334155))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Enable User Custom Wake Word(s)", color = Color.White, fontSize = 14.sp)
                        Switch(checked = userWakeEnabled, onCheckedChange = { userWakeEnabled = it })
                    }

                    OutlinedTextField(
                        value = userCustomWakeWords,
                        onValueChange = { userCustomWakeWords = it },
                        label = { Text("Custom Wake Phrases (e.g. HEY ARJUN, WAKE UP BOSS)") },
                        enabled = userWakeEnabled,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "Trigger Threshold: ${String.format("%.2f", wakeThreshold)} • Boosting: +${String.format("%.1f", wakeBoosting)}",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                    Slider(
                        value = wakeThreshold,
                        onValueChange = { wakeThreshold = it },
                        valueRange = 0.05f..0.60f
                    )
                }
            }

            // 5. YOUTUBE DATA API v3 & VERIFIED WHATSAPP SAFETY
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF151C2C)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "YouTube Data API v3 & WhatsApp Verification",
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )

                    SettingToggleRow(
                        title = "Enable YouTube Data API v3 Search",
                        subtitle = "Search & auto-play top video directly (falls back to YouTube query if off)",
                        checked = youtubeApiEnabled,
                        onCheckedChange = { youtubeApiEnabled = it }
                    )

                    if (youtubeApiEnabled) {
                        OutlinedTextField(
                            value = youtubeApiKey,
                            onValueChange = { youtubeApiKey = it },
                            label = { Text("YouTube Data API v3 Key (BYOK)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    youtubeValidationMessage = "Validating key..."
                                    coroutineScope.launch {
                                        val res = YouTubeApiClient().validateApiKey(youtubeApiKey)
                                        youtubeValidationMessage = res.second
                                    }
                                },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Validate YouTube Key", fontSize = 12.sp)
                            }
                            youtubeValidationMessage?.let { msg ->
                                Text(msg, color = Color(0xFF94A3B8), fontSize = 12.sp)
                            }
                        }
                    }

                    HorizontalDivider(color = Color(0xFF334155))

                    SettingToggleRow(
                        title = "Require Confirmation Before Sending WhatsApp",
                        subtitle = "Stage recipient & message first so Babu never sends unconfirmed messages",
                        checked = requireWhatsAppConfirmation,
                        onCheckedChange = { requireWhatsAppConfirmation = it }
                    )

                    SettingToggleRow(
                        title = "Wake & Action Sound Effects + Haptics",
                        subtitle = "Play distinct audio tone and vibration on wake detection and completion",
                        checked = soundAndHaptics,
                        onCheckedChange = { soundAndHaptics = it }
                    )
                }
            }

            // 6. PERSISTENT MEMORY MANAGER
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF151C2C)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Persistent Memory Vault (${savedMemories.size})",
                            color = Color(0xFF38BDF8),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        if (savedMemories.isNotEmpty()) {
                            TextButton(onClick = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    try {
                                        app.memoryDb.memoryDao().clearAll()
                                        savedMemories = emptyList()
                                    } catch (_: Exception) {
                                    }
                                }
                            }) {
                                Text("Clear All", color = Color(0xFFF87171), fontSize = 12.sp)
                            }
                        }
                    }
                    if (savedMemories.isEmpty()) {
                        Text(
                            text = "No saved voice memories yet. Ask Babu to remember a fact during a live session.",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                    } else {
                        savedMemories.take(10).forEach { fact ->
                            Text(
                                text = "• $fact",
                                color = Color(0xFFE2E8F0),
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            // 7. SYSTEM PERMISSIONS & FLOATING ORB
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF151C2C)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Permissions & Floating Overlay",
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )

                    SettingToggleRow(
                        title = "Auto-Start Standby Wake Listener on Device Boot",
                        subtitle = "Resume offline wake listener when Master Power is ON",
                        checked = autoStartOnBoot,
                        onCheckedChange = { autoStartOnBoot = it }
                    )

                    Button(
                        onClick = {
                            if (Settings.canDrawOverlays(this@SettingsActivity)) {
                                startService(Intent(this@SettingsActivity, FloatingOrbService::class.java))
                            } else {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                                startActivity(intent)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))
                    ) {
                        Text("1. Launch / Grant Floating Orb Overlay")
                    }

                    Button(
                        onClick = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))
                    ) {
                        Text("2. Grant Accessibility Service (UI Grounding & Gestures)")
                    }
                }
            }

            // SAVE & HOT-RELOAD BUTTON
            Button(
                onClick = {
                    val (isValidModel, _) = SecurePreferences.validateGeminiLiveModelId(geminiModelId)
                    val finalModel = if (isValidModel) {
                        SecurePreferences.normalizeModelId(geminiModelId)
                    } else {
                        SecurePreferences.DEFAULT_MODEL
                    }

                    prefs.geminiApiKey = geminiApiKey.trim()
                    prefs.ephemeralAccessToken = ephemeralToken.trim()
                    prefs.geminiModel = finalModel
                    geminiModelId = finalModel
                    prefs.assistantName = assistantName.trim().ifEmpty { "Babu" }
                    prefs.userName = userName.trim().ifEmpty { "Boss" }
                    prefs.voiceName = selectedVoice
                    prefs.personalityMode = selectedPersonality

                    prefs.appWakeWordEnabled = appWakeEnabled
                    prefs.appWakeWord = appWakeWord.trim().uppercase()
                    prefs.userWakeWordEnabled = userWakeEnabled
                    prefs.userCustomWakeWordsRaw = userCustomWakeWords.trim().uppercase()
                    prefs.wakeTriggerThreshold = wakeThreshold
                    prefs.wakeBoostingScore = wakeBoosting

                    prefs.googleSearchGroundingEnabled = googleSearchGrounding
                    prefs.liveTranscriptionEnabled = liveTranscription
                    prefs.sessionResumptionEnabled = sessionResumption
                    prefs.soundAndHapticsEnabled = soundAndHaptics
                    prefs.requireWhatsAppConfirmation = requireWhatsAppConfirmation
                    prefs.autoStartOnBoot = autoStartOnBoot

                    prefs.youtubeApiEnabled = youtubeApiEnabled
                    prefs.youtubeApiKey = youtubeApiKey.trim()

                    if (prefs.isMasterPowerOn) {
                        AgentPowerManager.reloadWakeWordsIfRunning(applicationContext)
                    }

                    Toast.makeText(
                        this@SettingsActivity,
                        "Settings Saved & Wake Words Hot-Reloaded!",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Save & Apply Configuration", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    private fun SettingToggleRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, color = Color(0xFF94A3B8), fontSize = 11.sp)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
