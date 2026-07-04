"""Yield attribution with exact decimal arithmetic.

Money math runs on decimal.Decimal end to end, quantized to the asset's
precision with ROUND_DOWN to match the ledger's YieldAccrual — floats cannot
represent decimal amounts exactly, so they never touch the money path. Polars
stays in the stack for heavy analytical queries (time-series, aggregation over
event streams) and is deliberately kept off the money-precision path.
"""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, ROUND_DOWN

SCALE = Decimal("0.000001")  # 6dp, matching on-ledger USDC precision
YEAR_DAYS = Decimal(365)
BPS = Decimal(10_000)


@dataclass(frozen=True)
class Position:
    venue: str
    principal: Decimal
    apr_bps: int
    days: int


def _accrued(p: Position) -> Decimal:
    """principal * (apr_bps/10000) * (days/365), quantized DOWN to 6dp —
    the same formula and rounding as the Kotlin YieldAccrual, so the two
    systems agree to the unit."""
    raw = p.principal * Decimal(p.apr_bps) / BPS * Decimal(p.days) / YEAR_DAYS
    return raw.quantize(SCALE, rounding=ROUND_DOWN)


def attribute_yield(positions: list[Position]) -> list[dict]:
    """Per-venue accrued yield and its share of total portfolio yield (bps)."""
    if not positions:
        return []
    accrued = {p.venue: _accrued(p) for p in positions}
    total = sum(accrued.values()) or Decimal(1)
    return [
        {
            "venue": p.venue,
            "principal": str(p.principal),
            "accrued": str(accrued[p.venue]),
            "share_bps": int((accrued[p.venue] / total * BPS).to_integral_value(rounding=ROUND_DOWN)),
        }
        for p in positions
    ]


def portfolio_apy(positions: list[Position]) -> int:
    """Principal-weighted APY in basis points, exact until the final int."""
    if not positions:
        return 0
    weighted = sum(p.principal * Decimal(p.apr_bps) for p in positions)
    principal = sum(p.principal for p in positions) or Decimal(1)
    return int((weighted / principal).to_integral_value(rounding=ROUND_DOWN))
