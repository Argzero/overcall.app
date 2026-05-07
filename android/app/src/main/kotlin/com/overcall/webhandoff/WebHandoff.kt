package com.overcall.webhandoff

import com.overcall.util.Base58
import com.solana.publickey.SolanaPublicKey
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Parses and builds the `overcall://web-register*` URIs used in the
 * laptop ↔ phone handoff for web-initiated registration.
 *
 *  Inbound (laptop → phone, scanned by the phone's camera):
 *      overcall://web-register?phone=<E.164>&wallet=<base58>&nonce=<hex>
 *
 *  Outbound (phone → laptop, displayed as a return QR):
 *      overcall://web-register-result?nonce=<hex>&hash=<base58(32B)>
 *
 * Symmetric counterpart to web/src/lib/handoff.ts. Keep the two in sync.
 *
 * String-based (not `android.net.Uri`) so unit tests run on pure JVM
 * without Robolectric. Same pattern as [com.overcall.invite.InviteLink].
 */
object WebHandoff {

    const val SCHEME = "overcall"
    const val INBOUND_HOST = "web-register"
    const val OUTBOUND_HOST = "web-register-result"

    private const val INBOUND_PREFIX = "$SCHEME://$INBOUND_HOST?"
    private const val OUTBOUND_PREFIX = "$SCHEME://$OUTBOUND_HOST?"

    data class Inbound(
        val phoneE164: String,
        val wallet: SolanaPublicKey,
        val nonce: String,
    )

    fun parseInbound(uri: String): Inbound? {
        if (!uri.startsWith(INBOUND_PREFIX)) return null
        val params = decodeQuery(uri.substring(INBOUND_PREFIX.length)) ?: return null

        val phone = params["phone"]?.takeIf { it.isNotBlank() } ?: return null
        val walletStr = params["wallet"]?.takeIf { it.isNotBlank() } ?: return null
        val nonce = params["nonce"]?.takeIf { it.isNotBlank() } ?: return null

        if (!phone.startsWith("+") || phone.length < 6) return null
        val wallet = try {
            SolanaPublicKey.from(walletStr)
        } catch (_: Throwable) {
            return null
        }
        return Inbound(phoneE164 = phone, wallet = wallet, nonce = nonce)
    }

    fun buildOutbound(nonce: String, hash: ByteArray): String {
        require(hash.size == 32) { "attestation hash must be 32 bytes, was ${hash.size}" }
        val hashB58 = Base58.encode(hash)
        return OUTBOUND_PREFIX +
            "nonce=" + encode(nonce) +
            "&hash=" + encode(hashB58)
    }

    private fun decodeQuery(query: String): Map<String, String>? {
        if (query.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in query.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) return null   // malformed: no key, or no '='
            val key = decode(part.substring(0, eq)) ?: return null
            val value = decode(part.substring(eq + 1)) ?: return null
            out[key] = value
        }
        return out
    }

    private fun decode(s: String): String? = try {
        URLDecoder.decode(s, Charsets.UTF_8.name())
    } catch (_: Throwable) {
        null
    }

    private fun encode(s: String): String =
        URLEncoder.encode(s, Charsets.UTF_8.name())
}
