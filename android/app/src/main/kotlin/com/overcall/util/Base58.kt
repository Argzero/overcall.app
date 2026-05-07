package com.overcall.util

/**
 * Bitcoin-flavored base58 (the same alphabet Solana uses everywhere). The
 * library's `SolanaPublicKey.base58()` only handles 32-byte keys; we need
 * arbitrary byte lengths for things like 64-byte transaction signatures.
 */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""

        // Count leading zero bytes — preserved as leading '1's in the output.
        var leadingZeros = 0
        while (leadingZeros < input.size && input[leadingZeros] == 0.toByte()) {
            leadingZeros++
        }

        // Long-divide the input by 58 repeatedly, collecting remainders.
        val temp = input.copyOf()
        val encoded = ByteArray(input.size * 2) // upper bound on output length
        var startIdx = leadingZeros
        var encodedIdx = encoded.size

        while (startIdx < temp.size) {
            val mod = divmod(temp, startIdx, 256, 58)
            if (temp[startIdx] == 0.toByte()) startIdx++
            encoded[--encodedIdx] = mod
        }

        // Skip leading zero remainders.
        while (encodedIdx < encoded.size && encoded[encodedIdx] == 0.toByte()) {
            encodedIdx++
        }

        // Re-introduce one '1' (alphabet[0]) per leading zero byte.
        var leadingsLeft = leadingZeros
        while (leadingsLeft-- > 0) {
            encoded[--encodedIdx] = 0
        }

        val sb = StringBuilder(encoded.size - encodedIdx)
        while (encodedIdx < encoded.size) {
            sb.append(ALPHABET[encoded[encodedIdx++].toInt() and 0xFF])
        }
        return sb.toString()
    }

    /** In-place number /= base producing a digit in `divisor` base. */
    private fun divmod(number: ByteArray, firstDigit: Int, base: Int, divisor: Int): Byte {
        var remainder = 0
        for (i in firstDigit until number.size) {
            val digit = number[i].toInt() and 0xFF
            val temp = remainder * base + digit
            number[i] = (temp / divisor).toByte()
            remainder = temp % divisor
        }
        return remainder.toByte()
    }
}
