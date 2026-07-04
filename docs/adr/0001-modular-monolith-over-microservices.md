# ADR 0001: Modular monolith over microservices for the core financial platform

## Status
Accepted

## Context
Atlas moves customer money. The dominant risk is financial correctness, not
horizontal scale — our transaction volume fits comfortably on a single well-tuned
Postgres primary. A microservice-per-domain topology would push the double-entry
invariant across network boundaries, forcing distributed transactions or eventual
consistency into the *money path*, which is exactly where we want strong
consistency and a single transactional writer.

## Decision
Build the core platform as a **modular monolith** using Spring Modulith. Each
domain (ledger, wallets, deposits, treasury, settlement, reconciliation,
compliance, portfolio, identity) is an enforced module with a published API and
private internals. Modules communicate synchronously via published APIs and
asynchronously via Spring Modulith application events relayed to Kafka.

The analytics/quant service is a **separate** FastAPI process because it is a
read-model with a different runtime (Python/Polars) and different scaling profile;
it never writes the ledger.

## Consequences
- The ledger keeps ACID guarantees with no distributed-transaction complexity.
- Module boundaries are verified in CI (`ModularityTests`), so we get most of the
  discipline of services without the operational cost.
- If a module ever needs independent scaling, its clean boundary makes extraction
  to a service a mechanical refactor rather than a rewrite.
- We accept a single deployable unit and coordinated releases as the trade-off.
