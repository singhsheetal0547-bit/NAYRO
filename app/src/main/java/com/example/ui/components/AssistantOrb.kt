package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.core.agent.AgentState
import com.example.ui.theme.Coral
import com.example.ui.theme.DeepGreen
import com.example.ui.theme.ForestGreen
import com.example.ui.theme.SoftSage
import com.example.ui.theme.WarmIvory

@Composable
fun AssistantOrb(
    agentState: AgentState,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    onClick: () -> Unit = {}
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_pulse")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val (primaryGradient, glowColor) = when (agentState) {
        is AgentState.UserListening -> listOf(Coral, WarmIvory) to Coral.copy(alpha = 0.5f)
        is AgentState.Thinking -> listOf(ForestGreen, SoftSage) to SoftSage.copy(alpha = 0.4f)
        is AgentState.Speaking -> listOf(Coral, ForestGreen) to Coral.copy(alpha = 0.4f)
        is AgentState.Error -> listOf(Color(0xFFB9423A), Coral) to Color(0xFFB9423A).copy(alpha = 0.4f)
        else -> listOf(ForestGreen, DeepGreen) to SoftSage.copy(alpha = 0.2f)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .scale(if (agentState is AgentState.UserListening || agentState is AgentState.Thinking) pulseScale else 1f)
            .clip(CircleShape)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(glowColor, Color.Transparent)
                )
            )
            .clickable(onClick = onClick)
            .testTag("assistant_orb")
    ) {
        Canvas(modifier = Modifier.size(size * 0.75f)) {
            drawCircle(
                brush = Brush.linearGradient(
                    colors = primaryGradient
                )
            )
        }
    }
}
