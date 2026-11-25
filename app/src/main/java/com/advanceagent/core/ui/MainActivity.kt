package com.advanceagent.core.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.advanceagent.core.AgentApplication
import com.advanceagent.core.data.chat.ChatRepository
import com.advanceagent.core.data.chat.ChatTurn
import com.advanceagent.core.service.AgentPowerManager
import com.advanceagent.core.service.WakeWordForegroundService
import com.advanceagent.core.vision.ScreenCaptureManager

class MainActivity : ComponentActivity() {

    private var hudWebView: WebView? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        startAgentIfPoweredOn()
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureManager.initProjection(
                applicationContext,
                result.resultCode,
                result.data!!
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNecessaryRuntimePermissions()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AdvanceVoiceAgentHudScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun AdvanceVoiceAgentHudScreen() {
        val prefs = remember { (application as AgentApplication).prefs }
        val orbState by WakeWordForegroundService.agentOrbStateFlow.collectAsState()
        val amplitude by WakeWordForegroundService.audioAmplitudeFlow.collectAsState()
        val chatTurns by ChatRepository.turns.collectAsState()
        var isVisionReady by remember { mutableStateOf(ScreenCaptureManager.isReady()) }
        var showChatHistorySheet by remember { mutableStateOf(false) }

        LaunchedEffect(orbState, amplitude) {
            val safeAmp = String.format("%.3f", amplitude)
            hudWebView?.evaluateJavascript(
                "if(window.updateAgentHud) window.updateAgentHud('$orbState', $safeAmp);",
                null
            )
            isVisionReady = ScreenCaptureManager.isReady()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF050811))
        ) {
            // Animated WebView HUD / Orb
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        webViewClient = WebViewClient()
                        loadUrl("file:///android_asset/hud/index.html")
                        hudWebView = this
                    }
                }
            )

            // Top Status Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 36.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "NAYRO • ${prefs.assistantName.uppercase()} LIVE",
                        color = Color(0xFF00E5FF),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "STATE: $orbState • ${prefs.personalityMode.label}",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showChatHistorySheet = true },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8))
                    ) {
                        Text("Transcript (${chatTurns.size})", fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Settings", color = Color(0xFF38BDF8))
                    }
                }
            }

            // Bottom Control Dock
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Vision Permission & Live Screen Frame Stream Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { requestMediaProjectionPermission() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (isVisionReady) Color(0xFF22C55E) else Color(0xFFF59E0B)
                        )
                    ) {
                        Text(
                            text = if (isVisionReady) "Vision Ready" else "Enable Screen Vision",
                            fontSize = 13.sp
                        )
                    }

                    if (isVisionReady) {
                        OutlinedButton(
                            onClick = {
                                val streamIntent = Intent(
                                    this@MainActivity,
                                    WakeWordForegroundService::class.java
                                ).apply {
                                    action = WakeWordForegroundService.ACTION_STREAM_SCREEN_VISION
                                }
                                startService(streamIntent)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFF00E5FF)
                            )
                        ) {
                            Text("Stream Screen Frame", fontSize = 13.sp)
                        }
                    }
                }

                // Trigger / Stop Live Session Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            if (!prefs.isMasterPowerOn) {
                                AgentPowerManager.turnOn(applicationContext)
                            }
                            val intent = Intent(
                                this@MainActivity,
                                WakeWordForegroundService::class.java
                            ).apply {
                                action = WakeWordForegroundService.ACTION_MANUAL_TRIGGER
                            }
                            startService(intent)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = if (orbState == "SPEAKING") "Barge-In / Interrupt" else "Talk to ${prefs.assistantName}",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Button(
                        onClick = {
                            val intent = Intent(
                                this@MainActivity,
                                WakeWordForegroundService::class.java
                            ).apply {
                                action = WakeWordForegroundService.ACTION_STOP_SESSION
                            }
                            startService(intent)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Stop Session", fontWeight = FontWeight.SemiBold, color = Color(0xFFE2E8F0))
                    }
                }
            }

            if (showChatHistorySheet) {
                ModalBottomSheet(
                    onDismissRequest = { showChatHistorySheet = false },
                    containerColor = Color(0xFF0F172A),
                    contentColor = Color(0xFFF8FAFC)
                ) {
                    ChatHistoryBottomSheetContent(
                        assistantName = prefs.assistantName,
                        turns = chatTurns,
                        onClear = { ChatRepository.clearAll() }
                    )
                }
            }
        }
    }

    @Composable
    private fun ChatHistoryBottomSheetContent(
        assistantName: String,
        turns: List<ChatTurn>,
        onClear: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 280.dp, max = 520.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Live Voice & Tool Transcript",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                    Text(
                        text = "Real-time input/output audio transcription & verified tools",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
                if (turns.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Text("Clear", color = Color(0xFFF87171))
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (turns.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No live voice turns yet. Say \"Hi $assistantName\" or tap Talk to start.",
                        color = Color(0xFF64748B),
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(turns, key = { it.id }) { turn ->
                        val isUser = turn.role == "user"
                        val isTool = turn.role == "tool"
                        val cardBg = when {
                            isUser -> Color(0xFF1E293B)
                            isTool -> Color(0xFF064E3B)
                            else -> Color(0xFF0F2942)
                        }
                        val label = when {
                            isUser -> "YOU"
                            isTool -> "TOOL • ${turn.toolBadge ?: "ACTION"} (${if (turn.isVerified) "VERIFIED" else "PENDING"})"
                            else -> assistantName.uppercase()
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(cardBg, RoundedCornerShape(12.dp))
                                .border(
                                    1.dp,
                                    if (isTool) Color(0xFF10B981) else Color(0xFF334155),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(12.dp)
                        ) {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isTool) Color(0xFF34D399) else Color(0xFF38BDF8)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = turn.text,
                                fontSize = 14.sp,
                                color = Color(0xFFF1F5F9)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    private fun requestMediaProjectionPermission() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun requestNecessaryRuntimePermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            startAgentIfPoweredOn()
        }
    }

    private fun startAgentIfPoweredOn() {
        val prefs = (application as AgentApplication).prefs
        if (prefs.isMasterPowerOn) {
            AgentPowerManager.turnOn(applicationContext)
        }
    }
}
