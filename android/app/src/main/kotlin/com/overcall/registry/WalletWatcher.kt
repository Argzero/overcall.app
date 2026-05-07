package com.overcall.registry

import android.util.Log
import com.solana.publickey.SolanaPublicKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Base64

/**
 * Watches a user wallet for incoming transfers settled by phone_registry.
 *
 * Implementation:
 *   1. Subscribe to logs where the wallet pubkey is mentioned (via
 *      logsSubscribe).
 *   2. Per log entry, scan the `Program data: <base64>` lines, decode each
 *      as a [PaymentEvent].
 *   3. Filter to events where event.recipient == self.
 *   4. Reverse-lookup the sender's phone via [RegistryClient.lookupByOwner].
 *   5. Emit [Event.PaymentReceived] downstream.
 *
 * Owns no global state — start/stop tied to bubble lifecycle in
 * OverCallForegroundService.
 */
class WalletWatcher(
    private val ws: SolanaWebSocket,
    private val registry: RegistryClient,
    private val self: SolanaPublicKey,
) {
    sealed interface Event {
        /** Sender's reverse-resolved phone (null if not registered). */
        data class PaymentReceived(
            val senderPubkey: SolanaPublicKey,
            val senderPhoneE164: String?,
            val mint: SolanaPublicKey,
            val amount: Long,
            val fee: Long,
            val signature: String,
        ) : Event
    }

    private val _events = MutableSharedFlow<Event>(replay = 0, extraBufferCapacity = 16)
    val events: Flow<Event> = _events.asSharedFlow()

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            ws.subscribeLogsMentioning(self).collect { entry ->
                processEntry(entry)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun processEntry(entry: SolanaWebSocket.LogEntry) {
        for (line in entry.logs) {
            val data = parseProgramDataLine(line) ?: continue
            val event = PaymentEvent.decode(data) ?: continue
            if (event.recipient.base58() != self.base58()) continue

            val senderPhone = try {
                registry.lookupByOwner(event.sender)?.phoneE164
            } catch (t: Throwable) {
                Log.w(TAG, "reverse lookup failed: ${t.message}")
                null
            }

            _events.tryEmit(
                Event.PaymentReceived(
                    senderPubkey = event.sender,
                    senderPhoneE164 = senderPhone,
                    mint = event.mint,
                    amount = event.amount,
                    fee = event.fee,
                    signature = entry.signature,
                ),
            )
        }
    }

    /**
     * Anchor emits events as `Program data: <base64>` log lines. Pull the
     * base64 chunk and decode it. Returns null for non-event log lines.
     */
    private fun parseProgramDataLine(line: String): ByteArray? {
        val prefix = "Program data: "
        if (!line.startsWith(prefix)) return null
        return try {
            Base64.getDecoder().decode(line.substring(prefix.length).trim())
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        private const val TAG = "WalletWatcher"
    }
}
