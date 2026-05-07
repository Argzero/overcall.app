package com.overcall.registry

import android.util.Log
import com.solana.publickey.SolanaPublicKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Minimal logsSubscribe over Solana's JSON-RPC websocket. We subscribe to
 * any tx whose accounts list mentions the given pubkey, parse the
 * `Program data: <base64>` log lines, and emit them as raw [LogEntry]s.
 * Decoding is the caller's job (see PaymentEvent.decode + WalletWatcher).
 *
 * Failures (network blip, server hiccup) are recovered via
 * exponential-backoff reconnection — collectors see a continuous stream.
 * Cancelling the collector closes the websocket and stops retrying.
 */
class SolanaWebSocket(
    httpUrl: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    /** https://api.devnet.solana.com -> wss://api.devnet.solana.com */
    private val wsUrl: String = httpUrl.replaceFirst(Regex("^https?://"), "wss://")

    data class LogEntry(
        val signature: String,
        val logs: List<String>,
    )

    fun subscribeLogsMentioning(pubkey: SolanaPublicKey): Flow<LogEntry> =
        innerSubscribe(pubkey).retryWhen { cause, attempt ->
            if (cause is CancellationException) return@retryWhen false
            val capped = attempt.toInt().coerceAtMost(MAX_BACKOFF_BIT)
            val backoffMs = (BASE_BACKOFF_MS shl capped).coerceAtMost(MAX_BACKOFF_MS)
            Log.w(TAG, "ws reconnecting in ${backoffMs}ms (attempt ${attempt + 1}): ${cause.message}")
            delay(backoffMs)
            true
        }

    private fun innerSubscribe(pubkey: SolanaPublicKey): Flow<LogEntry> = callbackFlow {
        val req = Request.Builder().url(wsUrl).build()
        val pubkeyBase58 = pubkey.base58()
        val json = Json { ignoreUnknownKeys = true }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val sub = """
                    {"jsonrpc":"2.0","id":1,"method":"logsSubscribe",
                     "params":[{"mentions":["$pubkeyBase58"]},
                               {"commitment":"confirmed"}]}
                """.trimIndent()
                webSocket.send(sub)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val parsed = try {
                    json.decodeFromString(LogsNotification.serializer(), text)
                } catch (_: Throwable) { return }
                val value = parsed.params?.result?.value ?: return
                trySend(LogEntry(value.signature, value.logs))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(t) // surfaces to retryWhen
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                close(IllegalStateException("ws closed: $code $reason"))
            }
        }

        val ws = httpClient.newWebSocket(req, listener)
        awaitClose { ws.close(1000, null) }
    }

    @Serializable
    private data class LogsNotification(
        val method: String? = null,
        val params: Params? = null,
    ) {
        @Serializable
        data class Params(val result: Result? = null)
        @Serializable
        data class Result(val value: Value)
        @Serializable
        data class Value(val signature: String, val err: kotlinx.serialization.json.JsonElement? = null, val logs: List<String>)
    }

    companion object {
        private const val TAG = "SolanaWebSocket"
        private const val BASE_BACKOFF_MS = 500L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val MAX_BACKOFF_BIT = 6   // 2^6 * 500 = 32_000ms (capped)
    }
}
