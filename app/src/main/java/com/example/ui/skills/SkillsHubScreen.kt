package com.example.ui.skills

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.skills.SkillDefinition
import com.example.core.skills.SkillManager
import com.example.ui.theme.Coral
import com.example.ui.theme.ForestGreen

@Composable
fun SkillsHubScreen(
    skillManager: SkillManager,
    onExecuteSkill: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val skills = remember { skillManager.getAllSkills() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("skills_hub_screen")
    ) {
        Text(
            text = "Procedural Skills Hub",
            style = MaterialTheme.typography.headlineSmall,
            color = ForestGreen
        )
        Text(
            text = "Reusable procedural skills for research, WhatsApp workflows, code inspection, and automation.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(skills, key = { it.skillId }) { skill ->
                SkillCard(
                    skill = skill,
                    onExecute = { onExecuteSkill(skill.skillId) }
                )
            }
        }
    }
}

@Composable
fun SkillCard(
    skill: SkillDefinition,
    onExecute: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().testTag("skill_card_${skill.skillId}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = ForestGreen
                )
                Text(
                    text = "v${skill.version}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Coral
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = skill.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Actions: ${skill.requiredActionTypes.joinToString { it.name }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onExecute,
                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreen),
                    modifier = Modifier.testTag("execute_skill_${skill.skillId}")
                ) {
                    Text("Execute", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
