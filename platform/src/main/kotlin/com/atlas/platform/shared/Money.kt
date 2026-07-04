package com.atlas.platform.shared

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A stablecoin / fiat asset identifier plus the number of decimal places it is
 * denominated in on-ledger. The ledger NEVER silently rounds — excess precision
 * is a caller error and fails loudly (mapped to 422 at the API edge).
 */
enum class Asset(val decimals: Int) {
    USDC(6),
    USDT(6),
    PYUSD(6),
    USD(2);

    fun zero(): Money = Money.of(BigDecimal.ZERO, this)
}

/**
 * Money is an immutable value object. All arithmetic is exact BigDecimal — no
 * floating point anywhere in the money path. Cross-asset arithmetic is rejected:
 * you cannot add USDC to USD without an explicit FX posting.
 *
 * INVARIANT (enforced, not aspirational): amount.scale() == asset.decimals,
 * always. This makes the data-class equals()/hashCode() safe — BigDecimal
 * equality is scale-sensitive, so without a strict scale invariant
 * Money("100") != Money("100.000000"), a bug class this constructor eliminates.
 * All construction goes through of(), which normalizes trailing zeros and
 * throws ArithmeticException on genuine excess precision.
 */
class Money private constructor(
    val amount: BigDecimal,
    val asset: Asset,
) : Comparable<Money> {

    // Deliberately NOT a data class: data-class copy() would expose the private
    // constructor and let callers bypass scale normalization, reintroducing the
    // scale-sensitive-equality bug this type exists to eliminate. Equality is
    // explicit and safe because the invariant guarantees identical scale.
    override fun equals(other: Any?): Boolean =
        other is Money && asset == other.asset && amount == other.amount
    override fun hashCode(): Int = 31 * amount.hashCode() + asset.hashCode()

    companion object {
        fun of(amount: BigDecimal, asset: Asset): Money =
            Money(amount.setScale(asset.decimals, RoundingMode.UNNECESSARY), asset)

        fun of(amount: String, asset: Asset): Money = of(BigDecimal(amount), asset)
    }

    val isPositive: Boolean get() = amount.signum() > 0
    val isNegative: Boolean get() = amount.signum() < 0
    val isZero: Boolean get() = amount.signum() == 0

    operator fun plus(other: Money): Money {
        requireSameAsset(other)
        return of(amount.add(other.amount), asset)
    }

    operator fun minus(other: Money): Money {
        requireSameAsset(other)
        return of(amount.subtract(other.amount), asset)
    }

    fun negate(): Money = of(amount.negate(), asset)
    fun abs(): Money = of(amount.abs(), asset)

    override fun compareTo(other: Money): Int {
        requireSameAsset(other)
        return amount.compareTo(other.amount)
    }

    private fun requireSameAsset(other: Money) {
        require(asset == other.asset) {
            "Asset mismatch: cannot combine ${asset.name} with ${other.asset.name} without an FX posting"
        }
    }

    override fun toString(): String = "${amount.toPlainString()} ${asset.name}"
}
