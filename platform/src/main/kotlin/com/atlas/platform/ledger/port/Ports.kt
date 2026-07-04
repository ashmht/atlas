package com.atlas.platform.ledger.port

import com.atlas.platform.ledger.domain.Account
import com.atlas.platform.ledger.domain.JournalEntry
import com.atlas.platform.shared.AccountId
import com.atlas.platform.shared.IdempotencyKey
import com.atlas.platform.shared.JournalEntryId
import com.atlas.platform.shared.OrganizationId

/**
 * OUTBOUND PORTS — the domain depends on these interfaces, adapters implement them.
 * This is the "hexagon" boundary: the application core knows nothing about JPA,
 * Postgres, or Kafka.
 */

interface AccountRepository {
    /** Load accounts FOR UPDATE within the current transaction, in a deterministic
     *  order to avoid deadlocks when multiple entries touch overlapping accounts. */
    fun lockAll(ids: Set<AccountId>): List<Account>
    fun findByCode(org: OrganizationId, code: String): Account?
    fun save(account: Account)
    fun open(account: Account): Account
}

interface JournalEntryRepository {
    fun save(entry: JournalEntry)
    fun findById(id: JournalEntryId): JournalEntry?
    /** Returns a previously-posted entry for this idempotency key, if any. */
    fun findByIdempotencyKey(org: OrganizationId, key: IdempotencyKey): JournalEntry?
}

/**
 * Transactional outbox: domain events are written in the SAME database transaction
 * as the ledger mutation, then relayed to Kafka by a separate poller. This gives
 * exactly-once *effect* even though Kafka delivery is at-least-once.
 */
interface LedgerEventOutbox {
    fun enqueue(event: LedgerEvent)
}

sealed interface LedgerEvent {
    val entryId: JournalEntryId
    val organizationId: OrganizationId

    data class JournalEntryPosted(
        override val entryId: JournalEntryId,
        override val organizationId: OrganizationId,
        val description: String,
        val postingCount: Int,
    ) : LedgerEvent

    data class JournalEntryReversed(
        override val entryId: JournalEntryId,
        override val organizationId: OrganizationId,
        val reversesEntryId: JournalEntryId,
    ) : LedgerEvent
}
