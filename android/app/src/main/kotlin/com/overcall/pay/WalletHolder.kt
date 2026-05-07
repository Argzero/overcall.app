package com.overcall.pay

import com.solana.publickey.SolanaPublicKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-scoped holder for the most recently connected wallet.
 *
 * MWA doesn't expose a long-lived "session" object — each `transact { }`
 * call re-invokes the wallet picker (or uses cached auth). Between
 * transacts we still want to know which wallet the user is logged in as
 * so the bubble's PanelActivity can pre-build instructions with the
 * right fee payer.
 *
 * MainActivity writes here after connect; PanelActivity reads from here
 * and prompts the user to open MainActivity first if it's still null.
 */
object WalletHolder {
    private val _connected = MutableStateFlow<WalletInfo?>(null)
    val connected: StateFlow<WalletInfo?> = _connected.asStateFlow()

    fun set(info: WalletInfo?) { _connected.value = info }
    fun get(): WalletInfo? = _connected.value
    fun pubkey(): SolanaPublicKey? = _connected.value?.pubkey
}
