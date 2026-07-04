package com.atlas.platform.wallets.domain

import com.atlas.platform.shared.*

/**
 * A Wallet is a custody boundary: it maps a customer/organization to on-chain
 * deposit addresses and to the ledger accounts that mirror on-chain balances.
 * Atlas never treats the chain as the source of truth — the ledger is. Wallets
 * are the reconciliation seam between the two.
 */
enum class WalletKind { OMNIBUS, SEGREGATED, TREASURY, SETTLEMENT }

data class DepositAddress(val chain: String, val address: String, val asset: Asset, val derivationPath: String?)

class Wallet(
    val id: WalletId,
    val organizationId: OrganizationId,
    val kind: WalletKind,
    val asset: Asset,
    /** Ledger account this wallet's balance is mirrored into. */
    val ledgerAccountId: AccountId,
    val addresses: MutableList<DepositAddress> = mutableListOf(),
) {
    fun addAddress(a: DepositAddress) {
        require(a.asset == asset) { "address asset must match wallet asset" }
        addresses += a
    }
}

/** Port implemented by a KMS/HSM-backed custody provider (Fireblocks, self-custody). */
interface CustodyProvider {
    fun deriveDepositAddress(walletId: WalletId, asset: Asset): DepositAddress
    fun broadcastWithdrawal(walletId: WalletId, to: String, amount: Money): String // returns tx hash
}
