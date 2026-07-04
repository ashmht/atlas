# Atlas Domain Model

## Bounded contexts and the language they share
Atlas is organized around the **ledger** as the shared kernel that every other
context speaks to. No context mutates balances directly; they express intent as
journal entries and let the ledger enforce correctness.

| Context | Aggregate roots | Responsibility | Talks to ledger via |
|---|---|---|---|
| Identity & Organizations | Organization, Member | Tenancy, RBAC scopes | (authorizes) |
| Wallets & Accounts | Wallet, DepositAddress | Custody boundary, chain↔ledger mapping | account provisioning |
| Ledger | JournalEntry, Account | Double-entry source of truth | — (is the ledger) |
| Deposits / Withdrawals | Deposit workflow | On-chain money in/out | posts entries |
| Treasury | TreasuryPolicy, SweepInstruction | Allocate float to yield venues | posts sweeps |
| Settlement | SettlementInstruction | Finality against external rails | references entries |
| Reconciliation | ReconBreak | Ledger vs external truth check | proposes adjustments |
| Compliance & Risk | RiskEngine, ScreeningResult | Gate money movement | (decides, does not post) |
| Portfolio & Yield | Position, YieldAccrual | Positions + accrued yield | posts revenue |
| Audit & Reporting | (read models) | Trial balance, statements | replays entries |

## The one rule
> **Money only moves through `PostJournalEntryService.post()`.**
Every workflow is idempotent, every entry is balanced and immutable, every
correction is a reversal. If a change can't be expressed as a balanced journal
entry, it isn't a money movement — it's a modeling error.

## Consistency model
- **Strong** inside the ledger (single Postgres transaction, optimistic locking).
- **Eventual** across contexts (Kafka events from the transactional outbox).
- **Durable + replayable** for orchestration (Temporal).
