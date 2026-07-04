package com.atlas.platform.ledger.domain

import com.atlas.platform.shared.AccountId
import com.atlas.platform.shared.Asset
import com.atlas.platform.shared.Money
import com.atlas.platform.shared.OrganizationId

/**
 * Accounting classification. `normalBalance` determines whether a debit increases
 * or decreases the balance — the core of double-entry and the most commonly
 * botched detail in home-grown ledgers.
 *
 *   ASSET, EXPENSE             -> normal DEBIT  (debit increases)
 *   LIABILITY, EQUITY, REVENUE -> normal CREDIT (credit increases)
 */
enum class AccountType(val normalBalance: Direction) {
    ASSET(Direction.DEBIT),
    LIABILITY(Direction.CREDIT),
    EQUITY(Direction.CREDIT),
    REVENUE(Direction.CREDIT),
    EXPENSE(Direction.DEBIT),
}

enum class Direction { DEBIT, CREDIT;
    fun opposite() = if (this == DEBIT) CREDIT else DEBIT
}

/**
 * A ledger Account aggregate. Its balance is a projection maintained
 * transactionally alongside the immutable postings that produced it.
 *
 * Concurrency control lives in the database: postings acquire row locks via
 * SELECT ... FOR UPDATE in a deterministic order, and the persistence entity
 * carries a JPA @Version that defends any code path that bypasses the locking
 * query. The domain object holds `version` as read-only provenance only.
 */
class Account internal constructor(
    val id: AccountId,
    val organizationId: OrganizationId,
    val code: String,          // e.g. "1000.USDC.omnibus"
    val type: AccountType,
    val asset: Asset,
    val allowNegative: Boolean,
    balance: Money,
    val version: Long,
) {
    var balance: Money = balance
        private set

    /**
     * Apply a single posting to the running balance. A debit to a DEBIT-normal
     * account increases it; a debit to a CREDIT-normal account decreases it.
     */
    fun apply(direction: Direction, amount: Money): AppliedPosting {
        require(amount.asset == asset) { "posting asset ${amount.asset} != account asset $asset" }
        require(amount.isPositive) { "posting amount must be strictly positive" }

        val signed = if (direction == type.normalBalance) amount else amount.negate()
        val next = balance + signed
        if (!allowNegative && next.isNegative) {
            return AppliedPosting.Rejected("account $code would go negative: $balance -> $next")
        }
        balance = next
        return AppliedPosting.Accepted(next)
    }

    companion object {
        fun open(
            organizationId: OrganizationId,
            code: String,
            type: AccountType,
            asset: Asset,
            allowNegative: Boolean = false,
            id: AccountId = AccountId.new(),
        ) = Account(id, organizationId, code, type, asset, allowNegative, asset.zero(), version = 0)

        fun rehydrate(
            id: AccountId, organizationId: OrganizationId, code: String, type: AccountType,
            asset: Asset, allowNegative: Boolean, balance: Money, version: Long,
        ) = Account(id, organizationId, code, type, asset, allowNegative, balance, version)
    }
}

sealed interface AppliedPosting {
    data class Accepted(val newBalance: Money) : AppliedPosting
    data class Rejected(val reason: String) : AppliedPosting
}
