package com.atlas.platform.portfolio.domain

import com.atlas.platform.shared.*
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Portfolio & yield management tracks positions across venues and computes accrued
 * yield that is then posted to the ledger as REVENUE. Accrual is deterministic and
 * day-count-aware so the ledger and the analytics service always agree.
 */
data class Position(val venue: String, val principal: Money, val aprBps: Int)

object YieldAccrual {
    /**
     * Daily accrual: principal * (aprBps/10000) * (days/365), rounded DOWN to the
     * asset's precision. Rounding DOWN never over-credits the customer; the
     * analytics service applies the identical formula and rounding so the two
     * systems agree to the unit.
     */
    fun accrue(position: Position, days: Int): Money {
        val apr = BigDecimal(position.aprBps).divide(BigDecimal(10_000))
        val raw = position.principal.amount
            .multiply(apr)
            .multiply(BigDecimal(days))
            .divide(BigDecimal(365), position.principal.asset.decimals, RoundingMode.DOWN)
        return Money.of(raw, position.principal.asset)
    }
}
