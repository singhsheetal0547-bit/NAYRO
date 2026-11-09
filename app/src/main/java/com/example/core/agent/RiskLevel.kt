package com.example.core.agent

/**
 * Risk levels defined by NAYRO execution architecture.
 * Determines runtime confirmation behavior before calling native Android tools.
 */
enum class RiskLevel(val code: String, val requiresConfirmation: Boolean) {
    R0_LOCAL_READONLY("R0", false),
    R1_LOW_IMPACT_REVERSIBLE("R1", false),
    R2_EXTERNAL_COMMUNICATION("R2", true),
    R3_DESTRUCTIVE_SENSITIVE("R3", true),
    R4_FINANCIAL_PROHIBITED("R4", true) // Not automated in v1
}
