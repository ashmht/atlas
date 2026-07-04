package com.atlas.platform.ledger

import com.atlas.platform.ledger.application.PostEntryCommand
import com.atlas.platform.ledger.application.PostJournalEntryService
import com.atlas.platform.ledger.application.ReverseEntryCommand
import com.atlas.platform.ledger.domain.Account
import com.atlas.platform.ledger.domain.AccountType
import com.atlas.platform.ledger.domain.Direction
import com.atlas.platform.ledger.domain.EntryKind
import com.atlas.platform.ledger.domain.JournalEntry
import com.atlas.platform.ledger.domain.Posting
import com.atlas.platform.ledger.port.AccountRepository
import com.atlas.platform.ledger.port.JournalEntryRepository
import com.atlas.platform.ledger.port.LedgerEvent
import com.atlas.platform.ledger.port.LedgerEventOutbox
import com.atlas.platform.shared.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Pure domain tests — no Spring, no DB, milliseconds. They pin the accounting
 * rules everything else depends on: balance and idempotency invariants, tenant
 * isolation on posting and reversal, Money scale semantics, and audit-linkage
 * preservation on rehydration.
 */
class LedgerInvariantsTest {

    private val org = OrganizationId.new()
    private val otherOrg = OrganizationId.new()

    private val omnibus = Account.open(org, "1000.USDC.omnibus", AccountType.ASSET, Asset.USDC, allowNegative = false)
    private val custLiability = Account.open(org, "2000.USDC.customer", AccountType.LIABILITY, Asset.USDC, allowNegative = false)
    private val foreignAccount = Account.open(otherOrg, "1000.USDC.omnibus", AccountType.ASSET, Asset.USDC)

    private val accountStore = mutableMapOf(
        omnibus.id to omnibus, custLiability.id to custLiability, foreignAccount.id to foreignAccount,
    )
    private val entryStore = mutableListOf<JournalEntry>()
    private val events = mutableListOf<LedgerEvent>()

    private val service = PostJournalEntryService(
        accounts = object : AccountRepository {
            override fun lockAll(ids: Set<AccountId>) = ids.mapNotNull { accountStore[it] }
            override fun findByCode(o: OrganizationId, code: String) =
                accountStore.values.find { it.organizationId == o && it.code == code }
            override fun save(account: Account) { accountStore[account.id] = account }
            override fun open(account: Account) = account.also { accountStore[it.id] = it }
        },
        entries = object : JournalEntryRepository {
            override fun save(entry: JournalEntry) { entryStore.add(entry) }
            override fun findById(id: JournalEntryId) = entryStore.find { it.id == id }
            override fun findByIdempotencyKey(o: OrganizationId, key: IdempotencyKey) =
                entryStore.find { it.organizationId == o && it.idempotencyKey == key }
        },
        outbox = object : LedgerEventOutbox {
            override fun enqueue(event: LedgerEvent) { events.add(event) }
        },
    )

    private fun usdc(v: String) = Money.of(v, Asset.USDC)

    private fun deposit(key: String, amount: Money) = PostEntryCommand(
        org, IdempotencyKey(key), "customer deposit",
        listOf(Posting(omnibus.id, Direction.DEBIT, amount), Posting(custLiability.id, Direction.CREDIT, amount)),
    )

    // ---------- core double-entry invariants ----------

    @Test
    fun `a balanced deposit moves money and emits one event`() {
        val result = service.post(deposit("dep-1", usdc("100")))
        assertThat(result).isInstanceOf(Result.Ok::class.java)
        assertThat(omnibus.balance).isEqualTo(usdc("100"))
        assertThat(custLiability.balance).isEqualTo(usdc("100"))
        assertThat(events).hasSize(1)
    }

    @Test
    fun `an unbalanced entry is rejected before touching any account`() {
        val draft = JournalEntry.draft(
            org, IdempotencyKey("bad-1"), "lopsided",
            postings = listOf(
                Posting(omnibus.id, Direction.DEBIT, usdc("100")),
                Posting(custLiability.id, Direction.CREDIT, usdc("90")),
            ),
        )
        assertThat(draft).isInstanceOf(Result.Err::class.java)
        assertThat((draft as Result.Err).error).isInstanceOf(DomainError.Unbalanced::class.java)
    }

    @Test
    fun `replaying the same idempotency key does not double-post`() {
        val cmd = deposit("dep-2", usdc("50"))
        service.post(cmd)
        service.post(cmd) // replay
        assertThat(entryStore).hasSize(1)
        assertThat(omnibus.balance).isEqualTo(usdc("50"))
    }

    @Test
    fun `an asset account cannot go negative`() {
        val result = service.post(
            PostEntryCommand(
                org, IdempotencyKey("wd-1"), "over-withdrawal",
                postings = listOf(
                    Posting(custLiability.id, Direction.DEBIT, usdc("10")),
                    Posting(omnibus.id, Direction.CREDIT, usdc("10")),
                ),
            )
        )
        assertThat(result).isInstanceOf(Result.Err::class.java)
        assertThat((result as Result.Err).error).isInstanceOf(DomainError.InsufficientFunds::class.java)
    }

    @Test
    fun `a reversal restores balances exactly and carries the audit link`() {
        val posted = (service.post(deposit("dep-3", usdc("25"))) as Result.Ok).value
        val reversal = (service.reverse(
            ReverseEntryCommand(org, IdempotencyKey("rev-3"), posted.id, "erroneous deposit")
        ) as Result.Ok).value

        assertThat(omnibus.balance).isEqualTo(Asset.USDC.zero())
        assertThat(custLiability.balance).isEqualTo(Asset.USDC.zero())
        assertThat(reversal.kind).isEqualTo(EntryKind.REVERSAL)
        assertThat(reversal.reversesEntryId).isEqualTo(posted.id)
    }

    @Test
    fun `a reversal cannot itself be reversed`() {
        val posted = (service.post(deposit("dep-4", usdc("5"))) as Result.Ok).value
        val reversal = (service.reverse(
            ReverseEntryCommand(org, IdempotencyKey("rev-4"), posted.id, "oops")
        ) as Result.Ok).value
        assertThatThrownBy { reversal.reverse(IdempotencyKey("rev-rev"), "no") }
            .isInstanceOf(IllegalStateException::class.java)
    }

    // ---------- tenant isolation ----------

    @Test
    fun `posting to another organization's account is rejected`() {
        val result = service.post(
            PostEntryCommand(
                org, IdempotencyKey("evil-1"), "cross-tenant attempt",
                postings = listOf(
                    Posting(omnibus.id, Direction.DEBIT, usdc("10")),
                    Posting(foreignAccount.id, Direction.CREDIT, usdc("10")), // not ours
                ),
            )
        )
        assertThat(result).isInstanceOf(Result.Err::class.java)
        assertThat((result as Result.Err).error).isInstanceOf(DomainError.AccountNotFound::class.java)
        assertThat(foreignAccount.balance).isEqualTo(Asset.USDC.zero())
    }

    @Test
    fun `reversing another organization's entry is indistinguishable from not-found`() {
        val posted = (service.post(deposit("dep-5", usdc("7"))) as Result.Ok).value
        val result = service.reverse(
            ReverseEntryCommand(otherOrg, IdempotencyKey("evil-2"), posted.id, "steal-back")
        )
        assertThat(result).isInstanceOf(Result.Err::class.java)
        assertThat(omnibus.balance).isEqualTo(usdc("7")) // untouched
    }

    // ---------- Money scale semantics ----------

    @Test
    fun `money equality is scale-insensitive because scale is normalized on construction`() {
        assertThat(Money.of("100", Asset.USDC)).isEqualTo(Money.of("100.000000", Asset.USDC))
        assertThat(Money.of("100", Asset.USDC).hashCode()).isEqualTo(Money.of("100.000000", Asset.USDC).hashCode())
    }

    @Test
    fun `excess precision fails loudly instead of silently rounding`() {
        assertThatThrownBy { Money.of("100.1234567", Asset.USDC) } // 7dp for a 6dp asset
            .isInstanceOf(ArithmeticException::class.java)
    }

    @Test
    fun `cross-asset arithmetic is rejected`() {
        assertThatThrownBy { Money.of("1", Asset.USDC) + Money.of("1", Asset.USD) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ---------- rehydration preserves audit linkage ----------

    @Test
    fun `rehydrate preserves kind and reverses link`() {
        val posted = (service.post(deposit("dep-6", usdc("3"))) as Result.Ok).value
        val reversal = posted.reverse(IdempotencyKey("rev-6"), "test")
        val roundTripped = JournalEntry.rehydrate(
            reversal.id, reversal.organizationId, reversal.idempotencyKey, reversal.description,
            reversal.postings, reversal.kind, reversal.reversesEntryId, reversal.effectiveAt,
        )
        assertThat(roundTripped.kind).isEqualTo(EntryKind.REVERSAL)
        assertThat(roundTripped.reversesEntryId).isEqualTo(posted.id)
    }

    // ---------- normal-balance semantics ----------

    @Test
    fun `debits decrease credit-normal accounts and increase debit-normal accounts`() {
        service.post(deposit("dep-7", usdc("40")))
        // withdraw 15: liability down (debit), asset down (credit)
        service.post(
            PostEntryCommand(
                org, IdempotencyKey("wd-7"), "withdrawal",
                listOf(
                    Posting(custLiability.id, Direction.DEBIT, usdc("15")),
                    Posting(omnibus.id, Direction.CREDIT, usdc("15")),
                ),
            )
        )
        assertThat(omnibus.balance).isEqualTo(usdc("25"))
        assertThat(custLiability.balance).isEqualTo(usdc("25"))
    }

    @Test
    fun `debit totals aggregate per asset`() {
        val entry = (JournalEntry.draft(
            org, IdempotencyKey("agg-1"), "multi",
            listOf(
                Posting(omnibus.id, Direction.DEBIT, usdc("10")),
                Posting(custLiability.id, Direction.CREDIT, usdc("10")),
            ),
        ) as Result.Ok).value
        assertThat(entry.debitTotals()[Asset.USDC]).isEqualByComparingTo(BigDecimal("10"))
    }
}
