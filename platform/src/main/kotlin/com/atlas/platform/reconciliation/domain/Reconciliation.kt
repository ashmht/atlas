package com.atlas.platform.reconciliation.domain

import com.atlas.platform.shared.*
import java.time.LocalDate

/**
 * Reconciliation is the truth check: it compares the internal ledger against an
 * external source (bank statement, chain indexer, card-network settlement file)
 * and classifies every difference. Nothing here mutates the ledger; breaks are
 * surfaced as cases for operators, and only an explicit adjusting entry (posted
 * through the ledger service) resolves a real discrepancy.
 */
enum class BreakType { MATCHED, MISSING_INTERNAL, MISSING_EXTERNAL, AMOUNT_MISMATCH, DUPLICATE }

data class ExternalRecord(val ref: String, val amount: Money, val date: LocalDate)
data class InternalRecord(val entryId: JournalEntryId, val amount: Money, val date: LocalDate, val externalRef: String?)

data class ReconBreak(
    val type: BreakType,
    val externalRef: String?,
    val internalEntryId: JournalEntryId?,
    val delta: Money?,
)

/**
 * A deterministic two-way match on external reference, then amount. Real systems
 * layer fuzzy/date-window matching on top; this is the exact-match core that must
 * be provably correct first.
 */
object Reconciler {
    fun reconcile(internal: List<InternalRecord>, external: List<ExternalRecord>): List<ReconBreak> {
        val breaks = mutableListOf<ReconBreak>()

        // Duplicate detection FIRST, by grouping on the external ref. A map keyed
        // by ref would silently collapse duplicates — the exact failure mode (a
        // double-settled file, a replayed webhook) reconciliation exists to catch.
        val externalByRef = external.groupBy { it.ref }
        externalByRef.filterValues { it.size > 1 }.forEach { (ref, dupes) ->
            breaks += ReconBreak(BreakType.DUPLICATE, ref, null, dupes.first().amount)
        }

        val byRefInternal = internal.filter { it.externalRef != null }.associateBy { it.externalRef!! }
        for ((ref, records) in externalByRef) {
            if (records.size > 1) continue // already reported as DUPLICATE
            val ext = records.single()
            val int = byRefInternal[ref]
            when {
                int == null -> breaks += ReconBreak(BreakType.MISSING_INTERNAL, ref, null, ext.amount)
                // Money's strict scale invariant makes equality safe, but recon
                // compares by value on principle: compareTo, never equals.
                int.amount.compareTo(ext.amount) != 0 ->
                    breaks += ReconBreak(BreakType.AMOUNT_MISMATCH, ref, int.entryId, int.amount - ext.amount)
            }
        }
        for (int in internal.filter { it.externalRef != null }) {
            if (!externalByRef.containsKey(int.externalRef)) {
                breaks += ReconBreak(BreakType.MISSING_EXTERNAL, int.externalRef, int.entryId, int.amount)
            }
        }
        return breaks
    }
}
