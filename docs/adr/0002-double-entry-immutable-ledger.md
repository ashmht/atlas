# ADR 0002: Double-entry, immutable, append-only ledger as source of truth

## Status
Accepted

## Context
Balances stored as mutable columns are unauditable and prone to silent
corruption under concurrency. Regulators and auditors need to reconstruct any
balance at any point in time.

## Decision
The ledger is **double-entry** (every entry balances per asset), **immutable**
(entries and postings are append-only; a Postgres trigger rejects UPDATE/DELETE),
and corrections happen only via **compensating reversal entries** that link to the
original. Running balances are a projection guarded by optimistic locking, and are
independently verifiable by replaying postings.

Idempotency is enforced at three layers: an application-level read check, a
`UNIQUE(organization_id, idempotency_key)` constraint, and Temporal's
exactly-once activity semantics for orchestrated flows.

## Consequences
- Full auditability and time-travel; history can never be rewritten.
- Slightly more storage and a reversal step for corrections — an acceptable price.
- All money movement funnels through one command handler
  (`PostJournalEntryService`), the single place invariants live.
