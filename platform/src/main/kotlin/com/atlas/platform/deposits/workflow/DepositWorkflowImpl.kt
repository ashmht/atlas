package com.atlas.platform.deposits.workflow

import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration
import java.util.UUID

/**
 * Deterministic orchestration logic — no I/O, no clocks, no randomness, no
 * threading primitives. Temporal executes workflow code on a single thread per
 * workflow and replays it from history, so signal handlers and the main method
 * never run concurrently and plain fields need no synchronization.
 */
class DepositWorkflowImpl : DepositWorkflow {

    private val activities = Workflow.newActivityStub(
        DepositActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(10))
            .setHeartbeatTimeout(Duration.ofSeconds(30)) // long-poll activities must heartbeat
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(2))
                    .setBackoffCoefficient(2.0)
                    .setMaximumAttempts(10)
                    // Business rejections are deterministic — retrying them is
                    // noise. Only infrastructure faults get the retry budget.
                    .setDoNotRetry(BusinessRejection::class.qualifiedName)
                    .build()
            )
            .build()
    )

    private var state: DepositStatus = DepositStatus.DETECTED
    private var confirmedTxHash: String? = null
    private var reviewDecision: Pair<Boolean, String>? = null

    override fun status(): DepositStatus = state

    override fun onChainConfirmed(txHash: String) { confirmedTxHash = txHash }

    override fun complianceDecision(approved: Boolean, reason: String) {
        reviewDecision = approved to reason
    }

    override fun process(input: DepositInput): DepositResult {
        // 1. Wait for the chain webhook, WITH a deadline — an unbounded await
        //    would park a workflow forever for a deposit that never confirms.
        //    Unconfirmed after 24h => EXPIRED, operators are alerted, and the
        //    customer support flow takes over.
        state = DepositStatus.CONFIRMING
        val confirmed = Workflow.await(Duration.ofHours(24)) { confirmedTxHash != null }
        if (!confirmed) {
            state = DepositStatus.EXPIRED
            return DepositResult(input.depositId, state, ledgerEntryId = null)
        }
        // Confirmation depth via a workflow-driven polling loop rather than one
        // long-blocking activity. A single activity is bounded by its total retry
        // budget (10 attempts, exponential backoff ~= 17 minutes); a congested
        // chain exceeding that would fail the whole workflow with an activity
        // error instead of the graceful EXPIRED path. Here each poll is a cheap
        // single-shot activity; the wait lives in durable, deterministic
        // Workflow.sleep, and the deadline produces a clean business outcome.
        val confirmationDeadline = Workflow.currentTimeMillis() + Duration.ofHours(6).toMillis()
        while (!activities.checkConfirmations(confirmedTxHash!!, input.requiredConfirmations)) {
            if (Workflow.currentTimeMillis() >= confirmationDeadline) {
                state = DepositStatus.EXPIRED
                return DepositResult(input.depositId, state, ledgerEntryId = null)
            }
            Workflow.sleep(Duration.ofSeconds(30))
        }

        // 2. Compliance screening. BLOCK is terminal; REVIEW parks for a human
        //    decision with a 7-day SLA before auto-rejecting.
        state = DepositStatus.SCREENING
        when (activities.screenCompliance(input)) {
            ComplianceOutcome.BLOCK -> {
                state = DepositStatus.REJECTED
                return DepositResult(input.depositId, state, ledgerEntryId = null)
            }
            ComplianceOutcome.REVIEW -> {
                state = DepositStatus.AWAITING_REVIEW
                val decided = Workflow.await(Duration.ofDays(7)) { reviewDecision != null }
                val approved = decided && reviewDecision!!.first
                if (!approved) {
                    state = DepositStatus.REJECTED
                    return DepositResult(input.depositId, state, ledgerEntryId = null)
                }
            }
            ComplianceOutcome.CLEAR -> Unit
        }

        // 3. Post to the ledger. Idempotent on deposit id, so any retry after
        //    this point cannot double-credit the customer. NOTE the ordering:
        //    compliance resolves BEFORE money is ledgered, so the reject paths
        //    above need no compensation — nothing has been posted yet. The
        //    reverseDeposit activity exists for the one flow that does need it:
        //    a post-settlement clawback (chain reorg, bank return), which is
        //    driven by the reconciliation module, not this workflow.
        state = DepositStatus.POSTING
        val ledgerEntryId: UUID = activities.postDepositToLedger(input)

        // 4. Fan out to settlement. If this keeps failing the money is still
        //    correctly on the ledger; reconciliation closes the loop.
        activities.notifySettlement(input.depositId, ledgerEntryId)
        state = DepositStatus.SETTLED
        return DepositResult(input.depositId, state, ledgerEntryId)
    }
}
