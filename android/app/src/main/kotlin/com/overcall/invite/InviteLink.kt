package com.overcall.invite

import com.solana.publickey.SolanaPublicKey

/**
 * Build the deep-link a sender hand-shares with an unregistered recipient.
 *
 * Format: `overcall://invite/<inviter-pubkey-base58>`
 *
 * The pubkey serves as a referral hint — when the recipient opens the
 * link, the registration flow can show "you were invited by …" — but the
 * link is otherwise inert. There's no on-chain referral tracking.
 *
 * No SMS component, by design. The sender shares the URL through whatever
 * channel they want (iMessage, WhatsApp, AirDrop, in person via QR).
 */
object InviteLink {
    const val SCHEME = "overcall"
    const val HOST = "invite"

    fun forInviter(inviter: SolanaPublicKey): String =
        "$SCHEME://$HOST/${inviter.base58()}"

    /** Returns the inviter pubkey if [link] is a well-formed invite URI; null otherwise. */
    fun parseInviter(link: String): SolanaPublicKey? {
        val expected = "$SCHEME://$HOST/"
        if (!link.startsWith(expected)) return null
        val rest = link.substring(expected.length).trim('/')
        if (rest.isEmpty()) return null
        return try {
            SolanaPublicKey.from(rest)
        } catch (_: Throwable) {
            null
        }
    }
}
