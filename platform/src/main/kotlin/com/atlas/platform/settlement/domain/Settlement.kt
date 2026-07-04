package com.atlas.platform.settlement.domain

import com.atlas.platform.shared.*
import java.util.UUID
import java.time.Instant

/**
 * Settlement moves obligations to finality against external rails (banking
 * partners, on-chain transfers, card networks). A SettlementInstruction is a
 * state machine; transitions are the ONLY way status changes, and every
 * transition is idempotent and audited.
 *
 *   PENDING -> SUBMITTED -> CONFIRMED
 *                       \-> FAILED -> (retried as new instruction)
 */
enum class SettlementState { PENDING, SUBMITTED, CONFIRMED, FAILED }

class SettlementInstruction(
    val id: UUID,
    val organizationId: OrganizationId,
    val rail: String,             // "ACH", "WIRE", "ONCHAIN_USDC", "PULSE"
    val amount: Money,
    val ledgerEntryId: JournalEntryId,
    state: SettlementState = SettlementState.PENDING,
) {
    var state: SettlementState = state
        private set
    var externalRef: String? = null
        private set
    var failureReason: String? = null
        private set

    fun submit(externalRef: String): SettlementInstruction = transition(SettlementState.SUBMITTED) {
        this.externalRef = externalRef
    }
    fun confirm(): SettlementInstruction = transition(SettlementState.CONFIRMED) {}
    fun fail(reason: String): SettlementInstruction = transition(SettlementState.FAILED) {
        this.failureReason = reason
    }

    private fun transition(to: SettlementState, effect: () -> Unit): SettlementInstruction {
        val allowed = when (state to to) {
            SettlementState.PENDING to SettlementState.SUBMITTED,
            SettlementState.SUBMITTED to SettlementState.CONFIRMED,
            SettlementState.SUBMITTED to SettlementState.FAILED,
            SettlementState.PENDING to SettlementState.FAILED -> true
            else -> state == to // idempotent self-transition
        }
        require(allowed) { "illegal settlement transition $state -> $to" }
        if (state != to) { state = to; effect() }
        return this
    }
}

