package com.atlas.platform.treasury.domain

import com.atlas.platform.shared.*
import java.math.BigDecimal

/**
 * Treasury management decides how idle customer float is allocated across venues
 * (money-market funds, tokenized T-bills, on-chain yield) subject to policy
 * constraints. Every movement is expressed as a ledger transfer — treasury never
 * mutates balances directly.
 *
 * A TreasuryPolicy is the guardrail: min operating liquidity, per-venue caps, and
 * a max duration. Sweeps that would breach policy are rejected before posting.
 */
data class VenueLimit(val venue: String, val maxAllocationBps: Int) // basis points of AUM

data class TreasuryPolicy(
    val organizationId: OrganizationId,
    val minOperatingLiquidity: Money,     // must remain instantly redeemable
    val venueLimits: List<VenueLimit>,
    val maxDurationDays: Int,
) {
    fun canAllocate(venue: String, amount: Money, aum: Money, currentAllocation: Money): Boolean {
        val limit = venueLimits.find { it.venue == venue } ?: return false
        val proposed = (currentAllocation + amount).amount
        val cap = aum.amount.multiply(BigDecimal(limit.maxAllocationBps)).divide(BigDecimal(10_000))
        return proposed <= cap
    }
}

/** A proposed sweep from operating float into a yield venue, pending policy check + ledger post. */
data class SweepInstruction(
    val organizationId: OrganizationId,
    val fromAccount: AccountId,
    val toVenueAccount: AccountId,
    val venue: String,
    val amount: Money,
    val idempotencyKey: IdempotencyKey,
)
