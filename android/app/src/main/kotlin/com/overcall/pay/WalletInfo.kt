package com.overcall.pay

import android.net.Uri
import com.solana.publickey.SolanaPublicKey

/**
 * Snapshot of a connected wallet, beyond just the pubkey, so the UI can
 * render an informed badge ("Seed Vault — hardware-backed" vs the wallet's
 * URI host).
 */
data class WalletInfo(
    val pubkey: SolanaPublicKey,
    val walletUriBase: Uri?,
    val hardwareBacked: Boolean,
) {
    val displayLabel: String
        get() = walletUriBase?.host ?: "wallet"
}
