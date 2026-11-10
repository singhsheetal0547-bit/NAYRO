package com.example.core.agent

import com.example.core.agent.tools.ToolRegistryManager
import com.example.core.provider.AIProviderManager
import com.example.core.provider.model.AIChatMessage
import com.example.core.provider.model.AIResult
import com.example.core.skills.SkillManager
import com.example.core.subagent.SubAgentManager
import com.example.core.workspace.WorkspaceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed class IterativeAgentStep {
    object Thinking : IterativeAgentStep()
    data class ActionProposed(val action: AgentAction, val stepIndex: Int) : IterativeAgentStep()
    data class ActionExecuted(val action: AgentAction, val result: ActionExecutionResult) : IterativeAgentStep()
    data class Replanning(val reason: String) : IterativeAgentStep()
    data class FinalResult(val content: String) : IterativeAgentStep()
}

class AgentBrain(
    private val providerManager: AIProviderManager,
    private val actionExecutor: ActionExecutor,
    private val workspaceManager: WorkspaceManager,
    private val skillManager: SkillManager,
    private val subAgentManager: SubAgentManager
) {

    private val _agentStepFlow = MutableStateFlow<IterativeAgentStep>(IterativeAgentStep.Thinking)
    val agentStepFlow: StateFlow<IterativeAgentStep> = _agentStepFlow

    /**
     * Executes the True Iterative Agent Loop.
     * Iterates up to [maxSteps], passing real tool execution results back to the LLM for subsequent reasoning steps.
     */
    suspend fun executeIterativeLoop(
        userGoal: String,
        history: List<AIChatMessage> = emptyList(),
        maxSteps: Int = 8
    ): String {
        val conversationHistory = history.toMutableList()
        val toolsCatalog = ToolRegistryManager.getAllTools().joinToString("\n") {
            "- ${it.actionType.name} (${it.displayName}): ${it.description} Params: ${it.parametersSchema.keys}"
        }

        val systemPrompt = """
            You are Babu, an autonomous, general-purpose Android AI Agent for NAYRO.
            You communicate fluently in Hindi, English, and Hinglish.
            You have access to the following dynamic tools catalog:
            $toolsCatalog

            When responding:
            1. If you need to perform an action using a tool, return a JSON object with:
               {"actionType": "ACTION_NAME", "target": "optional_target", "parameters": {"param1": "value1"}}
            2. If you have completed the goal or no tool is needed, respond directly with your final answer.
        """.trimIndent()

        var currentStep = 0
        var currentInstruction = userGoal

        while (currentStep < maxSteps) {
            currentStep++
            _agentStepFlow.value = IterativeAgentStep.Thinking

            val aiResult = providerManager.sendRequest(
                userPrompt = currentInstruction,
                history = conversationHistory,
                systemPrompt = systemPrompt
            )

            if (aiResult !is AIResult.Success) {
                val err = (aiResult as? AIResult.Error)?.message ?: "AI provider error occurred."
                return "Babu encountered an issue: $err"
            }

            val llmReply = aiResult.response.content.trim()

            // Check if LLM output contains a JSON structured action plan
            val plannedTask = TaskPlanner.planTaskFromJson(llmReply, userGoal)
            if (plannedTask != null && plannedTask.actions.isNotEmpty()) {
                val action = plannedTask.actions.first()
                _agentStepFlow.value = IterativeAgentStep.ActionProposed(action, currentStep)

                // Execute action using ActionExecutor
                val execResult = actionExecutor.execute(action)
                _agentStepFlow.value = IterativeAgentStep.ActionExecuted(action, execResult)

                // Feed real tool execution output back into context for next iteration
                conversationHistory.add(AIChatMessage(role = "user", content = currentInstruction))
                conversationHistory.add(AIChatMessage(role = "assistant", content = llmReply))

                currentInstruction = "Tool '${action.actionType.name}' execution result: ${execResult.output}. State observation complete. Continue or give final answer."
            } else {
                // Final textual answer reached
                _agentStepFlow.value = IterativeAgentStep.FinalResult(llmReply)
                return llmReply
            }
        }

        return "Task completed after reaching maximum step budget ($maxSteps steps)."
    }
}
