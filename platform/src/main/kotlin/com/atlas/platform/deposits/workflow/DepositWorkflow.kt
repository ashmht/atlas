package com.atlas.platform.deposits.workflow

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.math.BigDecimal
import java.util.UUID

/**
 * STABLECOIN DEPOSIT — the reference money-movement workflow.
 *
 * A deposit is a long-running, partially-failing distributed process. We model it
 * as a Temporal workflow so that each step is durable, retried, and replayable,
 * and the whole thing is idempotent end to end. Temporal owns the orchestration
 * state; the ledger owns the money.
 *
 * Happy path:
 *   1. Observe an on-chain transfer to a customer's deposit address (Bridge/Fireblocks
 *      webhook -> DepositDetected signal).
 *   2. Poll for N block confirmations (short checkConfirmations activity in a
 *      deterministic workflow loop — see impl for why not one long activity).
 *   3. Run compliance screening (sanctions, travel-rule, source-of-funds). A hard
 *      hit routes to manual review instead of failing.
 *   4. Post the double-entry ledger transaction crediting the customer liability
 *      and debiting the omnibus asset account. Idempotency key = deposit id.
 *   5. Emit DepositSettled; downstream treasury sweep picks it up.
 *
 * Failure handling:
 *   - Any activity that fails after (4) has committed does NOT re-post the ledger
 *     (idempotency), so retries are safe.
 *   - A compliance freeze parks the workflow in a queryable state awaiting a human
 *     ApproveDeposit / RejectDeposit signal; a reject issues a compensating
 *     ledger reversal.
 */
@WorkflowInterface
interface DepositWorkflow {
    @WorkflowMethod
    fun process(input: DepositInput): DepositResult

    // Signals — external events delivered durably into the running workflow.
    @io.temporal.workflow.SignalMethod fun onChainConfirmed(txHash: String)
    @io.temporal.workflow.SignalMethod fun complianceDecision(approved: Boolean, reason: String)

    // Query — inspect live state without disturbing the workflow.
    @io.temporal.workflow.QueryMethod fun status(): DepositStatus
}

data class DepositInput(
    val depositId: UUID,
    val organizationId: UUID,
    val walletId: UUID,
    val asset: String,
    val amount: BigDecimal,
    val sourceAddress: String,
    val destinationAddress: String,
    val requiredConfirmations: Int = 12,
)

enum class DepositStatus { DETECTED, CONFIRMING, SCREENING, AWAITING_REVIEW, POSTING, SETTLED, REJECTED, EXPIRED }

data class DepositResult(val depositId: UUID, val status: DepositStatus, val ledgerEntryId: UUID?)

/**
 * Activities are the side-effecting steps. Temporal retries them with backoff per
 * the configured RetryPolicy; each must be idempotent because it may run more than
 * once. Activity implementations live in the adapter layer and delegate to domain
 * services (e.g. the ledger's PostJournalEntryService).
 */
@ActivityInterface
interface DepositActivities {
    /** Short, single-shot check: current confirmation count >= required? */
    @ActivityMethod fun checkConfirmations(txHash: String, required: Int): Boolean
    @ActivityMethod fun screenCompliance(input: DepositInput): ComplianceOutcome
    @ActivityMethod fun postDepositToLedger(input: DepositInput): UUID // returns ledger entry id
    @ActivityMethod fun reverseDeposit(ledgerEntryId: UUID, reason: String)
    @ActivityMethod fun notifySettlement(depositId: UUID, ledgerEntryId: UUID)
}

enum class ComplianceOutcome { CLEAR, REVIEW, BLOCK }

/**
 * Thrown by activities for BUSINESS rejections (e.g. the ledger returned 422).
 * Registered as non-retryable in the workflow's RetryOptions: retrying a
 * deterministic business rejection ten times is pure noise and delays the
 * failure signal. Infrastructure faults (timeouts, 5xx, DB down) throw ordinary
 * exceptions and ARE retried.
 */
class BusinessRejection(message: String) : RuntimeException(message)
