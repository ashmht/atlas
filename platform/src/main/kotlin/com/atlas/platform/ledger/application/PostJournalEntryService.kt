package com.atlas.platform.ledger.application

import com.atlas.platform.ledger.domain.AppliedPosting
import com.atlas.platform.ledger.domain.JournalEntry
import com.atlas.platform.ledger.domain.Posting
import com.atlas.platform.ledger.port.AccountRepository
import com.atlas.platform.ledger.port.JournalEntryRepository
import com.atlas.platform.ledger.port.LedgerEvent
import com.atlas.platform.ledger.port.LedgerEventOutbox
import com.atlas.platform.shared.AccountId
import com.atlas.platform.shared.DomainError
import com.atlas.platform.shared.IdempotencyKey
import com.atlas.platform.shared.JournalEntryId
import com.atlas.platform.shared.OrganizationId
import com.atlas.platform.shared.Result
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.interceptor.TransactionAspectSupport
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * The one place where money moves. Correctness guarantees:
 *
 *   IDEMPOTENT   — replay of (org, key) returns the original entry. Enforced by
 *                  a read check here AND a UNIQUE constraint; the constraint-race
 *                  path (two concurrent first-attempts) is handled at the API
 *                  layer, which catches the violation post-rollback and re-reads.
 *   ATOMIC       — entry + balances + outbox event commit in one DB transaction.
 *   BALANCED     — the JournalEntry smart constructor rejects unbalanced drafts
 *                  before any account is touched.
 *   TENANT-SAFE  — every touched account must belong to the commanding
 *                  organization; reversals may only target the caller's own
 *                  entries. Missing/foreign resources return the same error so
 *                  existence is not leaked across tenants.
 *   CONCURRENCY  — accounts are locked FOR UPDATE in deterministic (DB-side)
 *                  order; the entity @Version defends non-locking writers.
 *
 * TRANSACTION SEMANTICS — the sharp edge this class guards against explicitly:
 * Spring only rolls back @Transactional methods on exceptions. This service
 * returns Result.Err for expected failures, which would COMMIT any prior writes.
 * Today no write precedes a rejection, but that invariant is one refactor away
 * from silently breaking, so every Err return after locks are taken marks the
 * transaction rollback-only. Defense in depth, verified by test.
 */
@Service
class PostJournalEntryService(
    private val accounts: AccountRepository,
    private val entries: JournalEntryRepository,
    private val outbox: LedgerEventOutbox,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun post(command: PostEntryCommand): Result<JournalEntry> {
        entries.findByIdempotencyKey(command.organizationId, command.idempotencyKey)?.let { existing ->
            log.info("idempotent replay entry={} key={}", existing.id.value, command.idempotencyKey.value)
            return Result.Ok(existing)
        }

        val entry = when (val draft = JournalEntry.draft(
            organizationId = command.organizationId,
            idempotencyKey = command.idempotencyKey,
            description = command.description,
            postings = command.postings,
            effectiveAt = command.effectiveAt,
        )) {
            is Result.Err -> return draft
            is Result.Ok -> draft.value
        }

        val touched: Set<AccountId> = entry.postings.map { it.accountId }.toSet()
        val loaded = accounts.lockAll(touched).associateBy { it.id }

        // Tenant isolation: an account that is missing OR belongs to another org
        // yields the identical error — a caller must not be able to probe for
        // the existence of accounts outside its organization.
        val invalid = touched.filter { id ->
            val acc = loaded[id]
            acc == null || acc.organizationId != command.organizationId
        }
        if (invalid.isNotEmpty()) return err(DomainError.AccountNotFound(invalid.first()))

        for (p: Posting in entry.postings) {
            when (val applied = loaded.getValue(p.accountId).apply(p.direction, p.amount)) {
                is AppliedPosting.Rejected -> return err(DomainError.InsufficientFunds(applied.reason))
                is AppliedPosting.Accepted -> Unit
            }
        }

        entries.save(entry)
        loaded.values.forEach(accounts::save)
        outbox.enqueue(
            LedgerEvent.JournalEntryPosted(
                entryId = entry.id,
                organizationId = entry.organizationId,
                description = entry.description,
                postingCount = entry.postings.size,
            )
        )

        log.info("posted entry={} postings={}", entry.id.value, entry.postings.size)
        return Result.Ok(entry)
    }

    @Transactional
    fun reverse(command: ReverseEntryCommand): Result<JournalEntry> {
        entries.findByIdempotencyKey(command.organizationId, command.idempotencyKey)?.let {
            return Result.Ok(it)
        }
        val original = entries.findById(command.entryId)
        // Tenant isolation: reversing a foreign org's entry must be
        // indistinguishable from reversing a nonexistent one.
        if (original == null || original.organizationId != command.organizationId) {
            return Result.Err(DomainError.Validation("entry not found: ${command.entryId.value}"))
        }

        val reversal = original.reverse(command.idempotencyKey, command.reason)

        val touched = reversal.postings.map { it.accountId }.toSet()
        val loaded = accounts.lockAll(touched).associateBy { it.id }
        for (p in reversal.postings) {
            val acc = loaded[p.accountId] ?: return err(DomainError.AccountNotFound(p.accountId))
            when (val applied = acc.apply(p.direction, p.amount)) {
                is AppliedPosting.Rejected -> return err(DomainError.InsufficientFunds(applied.reason))
                is AppliedPosting.Accepted -> Unit
            }
        }
        entries.save(reversal)
        loaded.values.forEach(accounts::save)
        outbox.enqueue(LedgerEvent.JournalEntryReversed(reversal.id, reversal.organizationId, original.id))
        return Result.Ok(reversal)
    }

    /**
     * Mark the surrounding transaction rollback-only before returning an expected
     * failure. Guarded so pure unit tests (no Spring TX) still run.
     */
    private fun err(error: DomainError): Result.Err {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()
        }
        return Result.Err(error)
    }
}

data class PostEntryCommand(
    val organizationId: OrganizationId,
    val idempotencyKey: IdempotencyKey,
    val description: String,
    val postings: List<Posting>,
    val effectiveAt: java.time.Instant = java.time.Instant.now(),
)

data class ReverseEntryCommand(
    val organizationId: OrganizationId,
    val idempotencyKey: IdempotencyKey,
    val entryId: JournalEntryId,
    val reason: String,
)
