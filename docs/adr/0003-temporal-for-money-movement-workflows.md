# ADR 0003: Temporal for long-running money-movement workflows

## Status
Accepted

## Context
Deposits, withdrawals, and treasury sweeps are multi-step, long-running, and must
survive process restarts, tolerate partial failure, and support human-in-the-loop
review. Hand-rolling this with a job table + cron leads to fragile,
hard-to-observe state machines.

## Decision
Use **Temporal** to orchestrate money-movement workflows. Workflow code is
deterministic; all side effects are activities with retry policies. Compliance
holds are modeled as durable `await` points resolved by external signals. The
ledger post is idempotent, so workflow retries never double-move money.

## Consequences
- Durable, replayable, queryable workflows with first-class retries and timers.
- A new infrastructure dependency (Temporal) and a determinism constraint on
  workflow code that engineers must learn.
- Clear separation: Temporal owns *orchestration* state, the ledger owns *money*.
