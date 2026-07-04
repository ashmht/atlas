"""Atlas Analytics — quantitative read-model for treasury and portfolio.

This service never moves money; it consumes ledger events and computes yield
attribution, effective APY, and risk metrics. The Kotlin platform remains the
sole writer of the ledger. All money values cross this API as decimal STRINGS
(never JSON floats) and are computed with decimal.Decimal internally.
"""
from __future__ import annotations

from datetime import date
from decimal import Decimal
from typing import Annotated

from fastapi import FastAPI
from pydantic import BaseModel, Field

from app.services.yield_attribution import Position, attribute_yield, portfolio_apy

app = FastAPI(title="Atlas Analytics", version="0.2.0")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


class PositionIn(BaseModel):
    venue: str
    principal: Annotated[Decimal, Field(gt=0, max_digits=38, decimal_places=6)]
    apr_bps: int
    days: int


class YieldRequest(BaseModel):
    as_of: date
    positions: list[PositionIn]


@app.post("/v1/analytics/yield-attribution")
def yield_attribution(req: YieldRequest) -> dict:
    positions = [Position(p.venue, Decimal(p.principal), p.apr_bps, p.days) for p in req.positions]
    return {
        "as_of": req.as_of.isoformat(),
        "portfolio_apy_bps": portfolio_apy(positions),
        "attribution": attribute_yield(positions),
    }
