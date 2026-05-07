package com.overcall.registry

import com.solana.publickey.SolanaPublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/**
 * Minimal Solana JSON-RPC client. Public over-the-wire surface is just
 * `getAccountInfo` for now — Tasks 13/15+ will add `getLatestBlockhash`,
 * `sendTransaction`, and websocket subscriptions.
 */
class SolanaRpc(
    private val url: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json".toMediaType()

    /**
     * Returns the raw account data bytes, or null if the account doesn't
     * exist. Throws on transport / RPC errors.
     */
    suspend fun getAccountData(address: SolanaPublicKey): ByteArray? = withContext(Dispatchers.IO) {
        val payload = """
            {"jsonrpc":"2.0","id":1,"method":"getAccountInfo",
             "params":["${address.base58()}",{"encoding":"base64","commitment":"confirmed"}]}
        """.trimIndent()
        val req = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(mediaType))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            require(resp.isSuccessful) { "RPC HTTP ${resp.code}: ${resp.message}" }
            val bodyText = resp.body?.string() ?: error("empty RPC body")
            val parsed = json.decodeFromString(GetAccountInfoResponse.serializer(), bodyText)
            val value = parsed.result?.value ?: return@use null
            require(value.data.size >= 2 && value.data[1] == "base64") {
                "unexpected encoding: ${value.data}"
            }
            Base64.getDecoder().decode(value.data[0])
        }
    }

    /**
     * Fetch the latest blockhash for transaction recent-blockhash binding.
     * Throws on transport / RPC errors.
     */
    suspend fun getLatestBlockhash(): String = withContext(Dispatchers.IO) {
        val payload = """
            {"jsonrpc":"2.0","id":1,"method":"getLatestBlockhash",
             "params":[{"commitment":"confirmed"}]}
        """.trimIndent()
        val req = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(mediaType))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            require(resp.isSuccessful) { "RPC HTTP ${resp.code}: ${resp.message}" }
            val bodyText = resp.body?.string() ?: error("empty RPC body")
            val parsed = json.decodeFromString(GetLatestBlockhashResponse.serializer(), bodyText)
            parsed.result?.value?.blockhash ?: error("no blockhash in response: $bodyText")
        }
    }

    @Serializable
    private data class GetAccountInfoResponse(val result: Result? = null) {
        @Serializable
        data class Result(val value: Value? = null)
        @Serializable
        data class Value(val data: List<String>)
    }

    @Serializable
    private data class GetLatestBlockhashResponse(val result: Result? = null) {
        @Serializable
        data class Result(val value: Value)
        @Serializable
        data class Value(val blockhash: String, val lastValidBlockHeight: Long)
    }
}
