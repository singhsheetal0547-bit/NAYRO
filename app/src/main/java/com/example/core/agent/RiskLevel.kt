package com.example.core.agent

/**
 * Risk levels defined by NAYRO execution architecture.
 * Determines runtime confirmation behavior before calling native Android tools.
 */
enum class RiskLevel(val code: String, val requiresConfirmation: Boolean, val description: String) {
    R0_LOCAL_READONLY("R0", false, "Read-only information, local clock, calculator, screen reading"),
    R1_LOW_IMPACT_REVERSIBLE("R1", false, "Low-impact reversible navigation and app opening"),
    R2_APPLICATION_STATE("R2", false, "In-app typing, drafting, and state changes"),
    R3_EXTERNAL_COMMUNICATION("R3", true, "Sending messages, placing calls, or deleting records"),
    R4_FINANCIAL_PROHIBITED("R4", true, "Financial transactions and purchases (Prohibited in v1)");

    fun isAutomatedAllowed(): Boolean = this != R4_FINANCIAL_PROHIBITED
}
