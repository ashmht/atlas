# Disaster Recovery

## Objectives
- **RPO:** <= 5 minutes (Postgres PITR via WAL archiving).
- **RTO:** <= 60 minutes for the core platform.

## Backups
- RDS automated snapshots (35-day retention) + continuous WAL for point-in-time
  restore. Snapshots are cross-region replicated.
- Temporal history is the durable orchestration record; the ledger is the durable
  money record. Restoring both to a consistent point recovers in-flight workflows.

## Restore procedure (abridged)
1. Provision a new RDS instance from the target PITR timestamp.
2. Point the platform at the restored DB; run Flyway `validate` (never `migrate`
   during DR).
3. Reconnect Temporal; workflows resume from history.
4. Run the trial-balance + consistency checks before re-enabling writes.
5. Reconcile against external rails (bank + chain) for the gap window.
