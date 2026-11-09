package com.example.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.agent.AgentState
import com.example.ui.components.AssistantOrb
import com.example.ui.components.StatusBadge
import com.example.ui.theme.Coral
import com.example.ui.theme.ForestGreen
import com.example.ui.theme.SoftSage

data class QuickAction(val title: String, val subtitle: String, val icon: ImageVector, val tag: String)

@Composable
fun HomeScreen(
    assistantName: String = "Babu",
    agentState: AgentState = AgentState.Idle,
    isForegroundActive: Boolean = false,
    isFloatingActive: Boolean = false,
    isAiReady: Boolean = false,
    onVoiceTap: () -> Unit = {},
    onSendText: (String) -> Unit = {},
    onQuickActionClick: (String) -> Unit = {}
) {
    var textInput by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    val quickActions = listOf(
        QuickAction("Background & Floating", "Toggle foreground service & Babu bubble", Icons.Default.Layers, "action_background"),
        QuickAction("Permissions", "Review system permissions & special access", Icons.Default.Security, "action_perm"),
        QuickAction("Capability Registry", "Examine live device & feature availability", Icons.Default.Build, "action_caps"),
        QuickAction("Set Provider API Key", "Configure Gemini or Custom AI Provider", Icons.Default.VpnKey, "action_api"),
        QuickAction("Memory Vault", "View user-approved local facts", Icons.Default.Psychology, "action_memory")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Status Row Badges
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatusBadge(
                label = if (isForegroundActive) "Agent Service: ON" else "Agent Service: OFF",
                isActive = isForegroundActive,
                testTag = "badge_agent_service"
            )
            StatusBadge(
                label = if (isFloatingActive) "Floating Bubble: ON" else "Floating Bubble: OFF",
                isActive = isFloatingActive,
                testTag = "badge_floating_bubble"
            )
            StatusBadge(
                label = if (isAiReady) "AI Ready" else "AI Setup Needed",
                isActive = isAiReady,
                testTag = "badge_ai"
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Assistant Greeting Header
        Text(
            text = "Namaste! I'm $assistantName",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Your private, voice-first personal Android AI agent",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Central Assistant Visual Orb
        AssistantOrb(
            agentState = agentState,
            size = 140.dp,
            onClick = onVoiceTap
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = when (agentState) {
                is AgentState.UserListening -> "Listening to you..."
                is AgentState.Thinking -> "Babu is thinking..."
                is AgentState.Speaking -> "Babu is speaking..."
                else -> "Tap Orb or tap mic to speak"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Coral
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Text & Voice Input Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                modifier = Modifier
                    .weight(1f)
                    .testTag("text_input_field"),
                placeholder = { Text("Ask Babu anything (Hindi, English, Hinglish)...") },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ForestGreen,
                    unfocusedBorderColor = SoftSage
                ),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (textInput.isNotEmpty()) {
                IconButton(
                    onClick = {
                        onSendText(textInput)
                        textInput = ""
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(ForestGreen)
                        .testTag("send_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send prompt",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            } else {
                IconButton(
                    onClick = onVoiceTap,
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(Coral)
                        .testTag("mic_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Activate microphone",
                        tint = MaterialTheme.colorScheme.onSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Quick Actions Section
        Text(
            text = "Quick Actions & Controls",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.align(Alignment.Start)
        )
        Spacer(modifier = Modifier.height(12.dp))

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(quickActions) { action ->
                Card(
                    onClick = { onQuickActionClick(action.tag) },
                    modifier = Modifier
                        .width(220.dp)
                        .testTag("quick_action_${action.tag}"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = action.title,
                            tint = ForestGreen
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = action.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = action.subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
