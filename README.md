# Atlas — Institutional Stablecoin Treasury & Settlement Platform

[![ci](https://github.com/ashmht/atlas/actions/workflows/ci.yml/badge.svg)](https://github.com/ashmht/atlas/actions/workflows/ci.yml)

Atlas is the core money-movement infrastructure for an institutional stablecoin
business: an immutable double-entry ledger, stablecoin deposits and withdrawals,
treasury allocation, settlement, reconciliation, compliance gating, and yield
management.

> **Design thesis:** in a money platform, the scarce resource is correctness, not
> features. Every workflow is **idempotent, traceable, replayable, auditable, and
> tolerant of partial failure**. Money moves through exactly one code path, and
> that path enforces the accounting invariants.

The `docs/` directory is the best starting point: the ADRs explain *why* each
major decision was made, the C4 diagrams show the shape of the system, and the
runbooks cover disaster recovery and the ledger-imbalance SEV-1.

---

## Architecture at a glance

- **Modular monolith** (Spring Modulith) for the core financial platform. One
  transactional writer, no distributed transactions in the money path. Module
  boundaries are **enforced in CI** (`ModularityTests`), not just documented.
  → [ADR-0001](docs/adr/0001-modular-monolith-over-microservices.md)
- **Hexagonal** modules: domain → ports → adapters. The ledger core knows nothing
  about JPA, Postgres, or Kafka.
- **Double-entry, immutable, append-only ledger** as the source of truth; a
  Postgres trigger physically rejects `UPDATE`/`DELETE` on entries and postings.
  → [ADR-0002](docs/adr/0002-double-entry-immutable-ledger.md)
- **Temporal** for durable, replayable money-movement workflows with
  human-in-the-loop review. → [ADR-0003](docs/adr/0003-temporal-for-money-movement-workflows.md)
- **Transactional outbox → Kafka** for reliable cross-context events; the
  versioned event contract lives in [`proto/`](proto/ledger_events.proto).
- **Read-model analytics** in Python/Polars, kept off the write path entirely.

C4 diagrams (context, container, ledger component): [docs/c4](docs/c4/context-and-containers.md).

---

## The correctness spine

Money moves in **one** place: `PostJournalEntryService.post()`.

```
platform/src/main/kotlin/com/atlas/platform/
├── shared/                       # Money (exact BigDecimal), typed IDs, Result, IdempotencyKey
└── ledger/
    ├── domain/                   # JournalEntry (balance invariant), Account (normal-balance apply)
    ├── application/              # PostJournalEntryService  ← the single money path
    ├── port/                     # AccountRepository, JournalEntryRepository, LedgerEventOutbox
    └── adapter/
        ├── in/web/               # REST + Idempotency-Key + RBAC + error mapping
        └── out/persistence/      # JPA, FOR UPDATE locking, @Version optimistic lock
```

Guarantees, and where they are enforced:

| Guarantee | Enforced by |
|---|---|
| Entries always balance (per asset) | `JournalEntry.draft()` smart constructor — rejects before any account is touched |
| Exactly-once effect on replay | application read-check **+** `UNIQUE(org, idempotency_key)` with the constraint-race resolved at the API layer (loser re-reads the winner's entry) **+** Temporal activity semantics |
| Atomicity of entry + balances + event | single `@Transactional` boundary + outbox in the same transaction |
| No lost updates under concurrency | DB-side canonical `FOR UPDATE` lock order + entity `@Version` defending non-locking writers |
| History can never be rewritten | append-only tables + Postgres `reject_mutation()` trigger; corrections are reversals |
| Tenant isolation on every money operation | org taken from the verified JWT `org_id` claim; every touched account and reversed entry verified against the commanding org |
| No floating point in money | `Money` value object over `BigDecimal` with asset-aware scale |

The invariants are pinned by fast, framework-free tests:
`platform/src/test/kotlin/com/atlas/platform/ledger/LedgerInvariantsTest.kt`.

---

## Running it locally

```bash
docker compose up   # Postgres, Redis, Redpanda, Temporal(+UI), Keycloak, Prometheus, Grafana, Loki, all 3 apps
```

| Service | URL |
|---|---|
| Platform API | http://localhost:8080 (`/actuator/health`, `/actuator/prometheus`) |
| Analytics | http://localhost:8000 (`/health`, `/docs`) |
| Frontend | http://localhost:3000 |
| Temporal UI | http://localhost:8088 |
| Keycloak | http://localhost:8081 |
| Grafana | http://localhost:3001 |

Post a balanced entry. The organization is taken from the bearer token's
`org_id` claim, never from a request header — obtain a token from Keycloak for
the `atlas-platform` client, then:

```bash
curl -X POST http://localhost:8080/api/v1/ledger/entries \
  -H "Authorization: Bearer <access-token>" \
  -H "Idempotency-Key: dep-001" \
  -H "Content-Type: application/json" \
  -d '{"description":"customer deposit","postings":[
        {"accountId":"<omnibus>","direction":"DEBIT","amount":"100.000000","asset":"USDC"},
        {"accountId":"<liability>","direction":"CREDIT","amount":"100.000000","asset":"USDC"}]}'
```

Replaying with the same `Idempotency-Key` returns the original entry, not a
second post.

---

## Testing strategy

- **Unit** — pure domain (ledger invariants, reconciler, risk engine, yield accrual). No Spring, milliseconds.
- **Architecture** — Spring Modulith `verify()` fails the build on boundary or cycle violations.
- **Integration** — Testcontainers Postgres + Kafka for the persistence and outbox paths.
- **Contract** — the Protobuf schema in `proto/` is the versioned event contract for consumers.
- **Security** — dependency and secret scanning in CI (the `security` job).

```bash
cd platform  && ./gradlew check   # unit + modularity + Testcontainers integration
cd analytics && pytest -q
```

---

## Repository map

```
atlas/
├── platform/     # Kotlin / Java 21 / Spring Modulith core (ledger, deposits, treasury, settlement, recon, compliance, portfolio, identity, wallets)
├── analytics/    # FastAPI + Polars yield/portfolio read-model
├── frontend/     # Next.js treasury dashboard
├── proto/        # Protobuf event contracts
├── infra/        # docker-compose backing config, Terraform + Kubernetes
├── docs/         # ADRs, C4, domain model, runbooks (DR + ledger-imbalance SEV-1)
└── .github/      # CI: platform / analytics / frontend / security jobs
```

---

## Roadmap

1. Wire the deposit Temporal worker and activities to the ledger service and custody webhooks (Bridge/Fireblocks).
2. Flesh out settlement adapters (ACH/wire/on-chain) and the reconciliation ingestion and case UI.
3. Add the trial-balance and `LedgerConsistencyCheck` scheduled jobs referenced in the runbook.
4. Expand the Testcontainers integration suite, then contract tests, k6 load, and fault-injection suites.
5. Promote the Terraform and Kubernetes manifests to fully apply-able modules; add SLOs and Grafana dashboards as code.

---

## Why it's built this way

In payments the expensive bugs are the quiet ones: a rounding drift, a
double-credit on retry, a balance updated without a matching posting. Atlas makes
those bugs **structurally hard** — one money path, balanced-or-rejected entries,
immutable history, idempotency at three layers, and correctness tests that run in
milliseconds.

## License

MIT — see [LICENSE](LICENSE).
