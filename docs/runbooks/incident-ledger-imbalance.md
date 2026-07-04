# Runbook: Ledger imbalance detected

**Severity:** SEV-1 (financial correctness) — page the on-call Staff engineer.

## Symptom
`LedgerConsistencyCheck` alert fires: sum of postings for an account does not
equal its stored running balance, OR a scheduled trial-balance job reports total
debits != total credits for an organization/asset.

## Immediate actions
1. **Do not mutate anything.** The ledger is append-only by design; there is no
   "quick fix" that edits a balance.
2. Freeze the affected organization's write path via the `ledger.write` feature
   flag (kill switch). Reads remain available.
3. Capture the failing account id(s), asset, and the drift amount from the alert.

## Diagnosis
- Replay postings for the account: `SELECT direction, amount FROM ledger_posting
  WHERE account_id = ?` and recompute the balance. Compare to `ledger_account`.
- If the replay matches the stored balance, the projection is fine and the alert
  is a false positive in the trial-balance aggregation — investigate the report.
- If the replay disagrees with the projection, a concurrency or deploy bug wrote a
  balance without a corresponding posting. Identify the offending entry via the
  outbox / audit log around the drift timestamp.

## Remediation
- Real discrepancy is corrected ONLY by a posted **adjusting entry** through
  `PostJournalEntryService`, referencing this incident id in the description.
  Never `UPDATE ledger_account`.
- Re-run the consistency check to confirm the drift is closed, then lift the flag.

## Prevention / follow-up
- Add a regression test reproducing the concurrency window.
- Verify optimistic-lock version handling on the affected path.
- File a post-incident review within 48h.
