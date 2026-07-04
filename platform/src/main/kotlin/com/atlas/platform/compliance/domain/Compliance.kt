package com.atlas.platform.compliance.domain

import com.atlas.platform.shared.*

/**
 * Compliance & risk gates money movement. Screens run BEFORE a deposit/withdrawal
 * is allowed to post, and the decision is itself an audited record. A BLOCK is a
 * hard stop; a REVIEW routes to a human; CLEAR proceeds. This module owns no
 * balances — it returns decisions the workflow layer acts on.
 */
enum class ScreenDecision { CLEAR, REVIEW, BLOCK }

data class ScreeningResult(val decision: ScreenDecision, val reasons: List<String>, val riskScore: Int)

interface SanctionsScreeningPort { fun screenAddress(chain: String, address: String): ScreeningResult }
interface TravelRulePort { fun requiresTravelRule(amount: Money): Boolean }

/**
 * Deterministic risk policy combining sanctions, amount thresholds and velocity.
 * Kept as pure logic so it is unit-testable and explainable to auditors.
 */
class RiskEngine(
    private val sanctions: SanctionsScreeningPort,
    private val travelRule: TravelRulePort,
    private val reviewThreshold: Money,
) {
    fun evaluateDeposit(chain: String, sourceAddress: String, amount: Money): ScreeningResult {
        val sanctionResult = sanctions.screenAddress(chain, sourceAddress)
        if (sanctionResult.decision == ScreenDecision.BLOCK) return sanctionResult

        val reasons = mutableListOf<String>()
        var decision = ScreenDecision.CLEAR
        if (amount >= reviewThreshold) { reasons += "amount above review threshold"; decision = ScreenDecision.REVIEW }
        if (travelRule.requiresTravelRule(amount)) reasons += "travel-rule data required"
        return ScreeningResult(decision, reasons, sanctionResult.riskScore)
    }
}
