package com.atlas.platform.ledger.domain

import com.atlas.platform.shared.AccountId
import com.atlas.platform.shared.Asset
import com.atlas.platform.shared.DomainError
import com.atlas.platform.shared.IdempotencyKey
import com.atlas.platform.shared.JournalEntryId
import com.atlas.platform.shared.Money
import com.atlas.platform.shared.OrganizationId
import com.atlas.platform.shared.Result
import java.math.BigDecimal
import java.time.Instant

/** A single line of a journal entry. Always positive; direction carries the sign. */
data class Posting(
    val accountId: AccountId,
    val direction: Direction,
    val amount: Money,
) {
    init { require(amount.isPositive) { "posting amount must be positive; use direction for sign" } }
}

/**
 * The kind of an entry is fixed at creation and never changes — entries are
 * immutable, so a mutable "status" was a modeling error (an original entry can
 * never transition to REVERSED; the reversal linkage IS the record). Whether an
 * entry has been reversed is answered by querying for a REVERSAL that points at
 * it via reversesEntryId.
 */
enum class EntryKind { STANDARD, REVERSAL }

/**
 * The JournalEntry is the immutable financial fact. Once posted it is never
 * mutated or deleted; corrections happen exclusively through a compensating
 * REVERSAL entry linking back via `reversesEntryId`.
 *
 * Construction enforces double-entry invariants BEFORE any account is touched:
 *   1. at least two postings
 *   2. per-asset sum(debits) == sum(credits)
 */
class JournalEntry private constructor(
    val id: JournalEntryId,
    val organizationId: OrganizationId,
    val idempotencyKey: IdempotencyKey,
    val description: String,
    val postings: List<Posting>,
    val kind: EntryKind,
    val reversesEntryId: JournalEntryId?,
    val effectiveAt: Instant,
) {
    fun debitTotals(): Map<Asset, BigDecimal> =
        postings.filter { it.direction == Direction.DEBIT }
            .groupBy { it.amount.asset }
            .mapValues { (_, ps) -> ps.fold(BigDecimal.ZERO) { acc, p -> acc + p.amount.amount } }

    fun reverse(idempotencyKey: IdempotencyKey, reason: String, at: Instant = Instant.now()): JournalEntry {
        check(kind == EntryKind.STANDARD) { "a reversal cannot itself be reversed; post a new standard entry" }
        val flipped = postings.map { it.copy(direction = it.direction.opposite()) }
        return JournalEntry(
            id = JournalEntryId.new(),
            organizationId = organizationId,
            idempotencyKey = idempotencyKey,
            description = "REVERSAL of ${id.value}: $reason",
            postings = flipped,
            kind = EntryKind.REVERSAL,
            reversesEntryId = id,
            effectiveAt = at,
        )
    }

    companion object {
        fun draft(
            organizationId: OrganizationId,
            idempotencyKey: IdempotencyKey,
            description: String,
            postings: List<Posting>,
            effectiveAt: Instant = Instant.now(),
            id: JournalEntryId = JournalEntryId.new(),
        ): Result<JournalEntry> {
            if (postings.size < 2) {
                return Result.Err(DomainError.Validation("a journal entry needs at least two postings"))
            }
            val byAsset: Map<Asset, List<Posting>> = postings.groupBy { it.amount.asset }
            for ((asset, ps) in byAsset) {
                val debits = ps.filter { it.direction == Direction.DEBIT }
                    .fold(BigDecimal.ZERO) { a, p -> a + p.amount.amount }
                val credits = ps.filter { it.direction == Direction.CREDIT }
                    .fold(BigDecimal.ZERO) { a, p -> a + p.amount.amount }
                if (debits.compareTo(credits) != 0) {
                    return Result.Err(
                        DomainError.Unbalanced(
                            "entry does not balance for ${asset.name}: debits=$debits credits=$credits"
                        )
                    )
                }
            }
            return Result.Ok(
                JournalEntry(id, organizationId, idempotencyKey, description, postings,
                    EntryKind.STANDARD, reversesEntryId = null, effectiveAt = effectiveAt)
            )
        }

        /** Convenience for the common two-legged transfer. */
        fun transfer(
            organizationId: OrganizationId,
            idempotencyKey: IdempotencyKey,
            description: String,
            debit: AccountId,
            credit: AccountId,
            amount: Money,
        ): Result<JournalEntry> = draft(
            organizationId, idempotencyKey, description,
            listOf(Posting(debit, Direction.DEBIT, amount), Posting(credit, Direction.CREDIT, amount)),
        )

        /**
         * Trusted rehydration from persistence. Bypasses re-validation — the entry
         * was validated at post time and storage is append-only — and preserves
         * `kind` and `reversesEntryId` so a reversal read back from the database
         * keeps its audit linkage to the entry it reverses. Never route reads
         * through draft(): it is for new entries and would drop the linkage.
         */
        fun rehydrate(
            id: JournalEntryId,
            organizationId: OrganizationId,
            idempotencyKey: IdempotencyKey,
            description: String,
            postings: List<Posting>,
            kind: EntryKind,
            reversesEntryId: JournalEntryId?,
            effectiveAt: Instant,
        ): JournalEntry = JournalEntry(
            id, organizationId, idempotencyKey, description, postings, kind, reversesEntryId, effectiveAt
        )
    }
}
