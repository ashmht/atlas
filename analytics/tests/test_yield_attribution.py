from decimal import Decimal

from app.services.yield_attribution import Position, attribute_yield, portfolio_apy


def test_portfolio_apy_is_principal_weighted():
    positions = [
        Position("mmf", Decimal("1000000"), 500, 365),
        Position("tbill", Decimal("3000000"), 520, 365),
    ]
    # (1M*500 + 3M*520) / 4M = 515
    assert portfolio_apy(positions) == 515


def test_accrual_is_exact_decimal_not_float():
    # 0.1 + 0.2 style traps must not exist: everything is Decimal.
    rows = attribute_yield([Position("v", Decimal("1000000"), 500, 30)])
    accrued = Decimal(rows[0]["accrued"])
    # 1,000,000 * 0.05 * 30/365 = 4109.589041... -> 4109.589041 (ROUND_DOWN @ 6dp)
    assert accrued == Decimal("4109.589041")


def test_attribution_shares_sum_close_to_10000_bps():
    rows = attribute_yield([
        Position("mmf", Decimal("1000000"), 500, 30),
        Position("tbill", Decimal("1000000"), 520, 30),
    ])
    assert abs(sum(r["share_bps"] for r in rows) - 10_000) <= 2  # integer rounding


def test_empty_is_safe():
    assert attribute_yield([]) == []
    assert portfolio_apy([]) == 0
