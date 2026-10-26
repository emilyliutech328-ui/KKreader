package io.legado.app.service.edge

import java.security.MessageDigest
import java.util.Locale

/**
 * Edge 朗读 v2 的 Sec-MS-GEC 时间证明。
 *
 * 协议来自开源项目 rany2/edge-tts（LGPL-3.0），这里按其公开算法实现，
 * 用于让语音合成请求通过服务端校验。这不是加密，只是请求校验值。
 */
object EdgeProtocol {

    const val VERSION = "2-143.0.3650.75"
    const val CLIENT_ID = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    private const val MASK = 0xffffffffL

    private val INITIAL = longArrayOf(
        0x6a09e667L, 0xbb67ae85L, 0x3c6ef372L, 0xa54ff53aL,
        0x510e527fL, 0x9b05688cL, 0x1f83d9abL, 0x5be0cd19L
    )

    private val WORKING = longArrayOf(
        0xfd9fbea2L, 0x91243ce7L, 0xbe499094L, 0xcd2e1e0aL,
        0x6c006f49L, 0x04bd35abL, 0xfeb0a7e4L, 0x03effbd2L
    )

    private val SCHEDULE = longArrayOf(
        0xeb24bf9eL, 0x1558691fL, 0xa0c09ee2L, 0x0cfa32baL,
        0xa622ee8cL, 0xd1dd9491L, 0xc0dd25a0L, 0xf9e70ab6L,
        0xf0c31573L, 0x7058ce4eL, 0x7188e606L, 0x00e5ab33L,
        0xd258b060L, 0x72766564L
    )

    private val ROUND_CONSTANTS = longArrayOf(
        0x428a2f98L, 0x71374491L, 0xb5c0fbcfL, 0xe9b5dba5L, 0x3956c25bL, 0x59f111f1L,
        0x923f82a4L, 0xab1c5ed5L, 0xd807aa98L, 0x12835b01L, 0x243185beL, 0x550c7dc3L,
        0x72be5d74L, 0x80deb1feL, 0x9bdc06a7L, 0xc19bf174L, 0xe49b69c1L, 0xefbe4786L,
        0x0fc19dc6L, 0x240ca1ccL, 0x2de92c6fL, 0x4a7484aaL, 0x5cb0a9dcL, 0x76f988daL,
        0x983e5152L, 0xa831c66dL, 0xb00327c8L, 0xbf597fc7L, 0xc6e00bf3L, 0xd5a79147L,
        0x06ca6351L, 0x14292967L, 0x27b70a85L, 0x2e1b2138L, 0x4d2c6dfcL, 0x53380d13L,
        0x650a7354L, 0x766a0abbL, 0x81c2c92eL, 0x92722c85L, 0xa2bfe8a1L, 0xa81a664bL,
        0xc24b8b70L, 0xc76c51a3L, 0xd192e819L, 0xd6990624L, 0xf40e3585L, 0x106aa070L,
        0x19a4c116L, 0x1e376c08L, 0x2748774cL, 0x34b0bcb5L, 0x391c0cb3L, 0x4ed8aa4aL,
        0x5b9cca4fL, 0x682e6ff3L, 0x748f82eeL, 0x78a5636fL, 0x84c87814L, 0x8cc70208L,
        0x90befffaL, 0xa4506cebL, 0xbef9a3f7L, 0xc67178f2L
    )

    /** 系数表：每项 (乘数, 偏移) */
    private val COEFFICIENTS = arrayOf(
        0xe82e91b1L to 0xb32700d0L,
        0x44de24c6L to 0x88dff51dL,
        0x3beb65bcL to 0x29514038L,
        0xc89c5933L to 0xb328cbdfL,
        0x8d1ec211L to 0xd405b4d5L,
        0x314ef5d5L to 0x26e55f3cL,
        0xe92491b1L to 0x607801c6L,
        0xf0a5b9d5L to 0x82b2c1fbL,
        0x06064cf3L to 0xfa51a3daL,
        0xda79fa60L to 0x207cc464L,
        0xbed20560L to 0x3c3c74f3L,
        0x89f9c9f8L to 0xd2d14b61L,
        0x35fa38c6L to 0xbb04a2c1L,
        0xcb49e3c6L to 0xc5626167L
    )

    @Volatile
    private var cachedWindow: Long = -1

    @Volatile
    private var cachedProof: String? = null

    /**
     * 按 300 秒窗口生成时间证明，窗口内复用结果。
     */
    fun timeProof(nowMillis: Long = System.currentTimeMillis()): String {
        val seconds = nowMillis / 1000L + 11644473600L
        val window = seconds - seconds % 300L
        val cache = cachedProof
        if (cachedWindow == window && cache != null) return cache
        val data = (window * 10000000L).toString().plus(CLIENT_ID).toByteArray(Charsets.US_ASCII)
        val digest = digest(data)
        val cs = cs64(data, digest)
        val bytes = ByteArray(8)
        writeUInt32LE(bytes, 0, cs.first)
        writeUInt32LE(bytes, 4, cs.second)
        val proof = hex(bytes) + "-" + hex(digest)
        cachedWindow = window
        cachedProof = proof
        return proof
    }

    private fun writeUInt32LE(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = (value and 0xff).toByte()
        buf[offset + 1] = ((value shr 8) and 0xff).toByte()
        buf[offset + 2] = ((value shr 16) and 0xff).toByte()
        buf[offset + 3] = ((value shr 24) and 0xff).toByte()
    }

    private fun hex(data: ByteArray): String {
        val sb = StringBuilder(data.size * 2)
        data.forEach { b -> sb.append(String.format(Locale.US, "%02X", b)) }
        return sb.toString()
    }

    private fun rotate(x: Long, n: Int): Long =
        ((x shr n) or (x shl (32 - n))) and MASK

    private fun swap(x: Long): Long =
        ((x shl 16) or (x and MASK shr 16)) and MASK

    private fun sigma0(x: Long): Long = rotate(x, 7) xor rotate(x, 18) xor (x shr 3)
    private fun sigma1(x: Long): Long = rotate(x, 17) xor rotate(x, 19) xor (x shr 10)

    private fun expand(words: MutableList<Long>) {
        var i = words.size
        while (i < 64) {
            words.add(
                (
                        sigma1(words[i - 2]) +
                                words[i - 7] +
                                sigma0(words[i - 15]) +
                                words[i - 16]
                        ) and MASK
            )
            i++
        }
    }

    private fun rounds(state: LongArray, words: List<Long>, start: Int = 0): LongArray {
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        var i = start
        while (i < 64) {
            val constant = if (start == 14 && i == 14) 0xc9dc06a7L else ROUND_CONSTANTS[i]
            val t1 = h +
                    (rotate(e, 6) xor rotate(e, 11) xor rotate(e, 25)) +
                    (g xor (e and (f xor g))) +
                    constant + words[i]
            val t2 = (rotate(a, 2) xor rotate(a, 13) xor rotate(a, 22)) +
                    ((a and b) or (c and (a or b)))
            h = g; g = f; f = e
            e = (d + t1) and MASK
            d = c; c = b; b = a
            a = (t1 + t2) and MASK
            i++
        }
        return longArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun digest(data: ByteArray): ByteArray {
        val words = ArrayList<Long>(64)
        repeat(14) { words.add(0L) }
        words.add(((data[0].toLong() and 0xff) shl 16) or
                ((data[1].toLong() and 0xff) shl 8) or
                (data[2].toLong() and 0xff))
        words.add(readUInt32BE(data, 3))

        var i = 16
        while (i < 30) {
            var value = SCHEDULE[i - 16]
            if (i - 2 >= 14) {
                value += sigma1(words[i - 2]) xor (if (i == 16) 0x000b92c0L else 0L)
            }
            if (i - 7 >= 14) value += words[i - 7]
            if (i - 15 >= 14) {
                value += sigma0(words[i - 15]) xor (if (i == 29) 0x059c0b80L else 0L)
            }
            words.add(value and MASK)
            i++
        }
        words.add(
            (
                    sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] +
                            0xc9dc06a7L - ROUND_CONSTANTS[14]
                    ) and MASK
        )
        expand(words)

        val working = rounds(WORKING, words, 14)
        var state = LongArray(8) { (INITIAL[it] + working[it]) and MASK }

        val length = 57L + data.size
        val padding = ((55 - length) % 64 + 64) % 64
        val tail = ByteArray((data.size - 7 + 1 + padding + 8).toInt())
        System.arraycopy(data, 7, tail, 0, data.size - 7)
        tail[data.size - 7] = 0x80.toByte()
        // 末尾 64 位长度
        val bitLen = length * 8
        var k = 0
        while (k < 8) {
            tail[tail.size - 8 + k] = ((bitLen shr (56 - 8 * k)) and 0xff).toByte()
            k++
        }
        var offset = 0
        while (offset < tail.size) {
            val block = ArrayList<Long>(64)
            var j = 0
            while (j < 16) {
                block.add(readUInt32BE(tail, offset + j * 4))
                j++
            }
            expand(block)
            val result = rounds(state, block)
            state = LongArray(8) { (state[it] + result[it]) and MASK }
            offset += 64
        }
        val out = ByteArray(32)
        i = 0
        while (i < 8) {
            writeUInt32BE(out, i * 4, state[i])
            i++
        }
        return out
    }

    private fun cs64(data: ByteArray, digest: ByteArray): Pair<Long, Long> {
        val keys = longArrayOf(
            (readUInt32LE(digest, 0) or 1L) and MASK,
            (readUInt32LE(digest, 4) or 1L) and MASK
        )
        val factors = longArrayOf(keys[0] * 0x82893933L and MASK, keys[1] * 0x82893933L and MASK)
        val terms = ArrayList<Long>()
        val offsets = ArrayList<Long>()
        COEFFICIENTS.forEachIndexed { index, (mul, off) ->
            terms.add(mul * factors[index % 2] and MASK)
            offsets.add(off)
        }

        val padded = ByteArray(((data.size + 1 + 7) / 8) * 8)
        System.arraycopy(data, 0, padded, 1, data.size)

        var idx = 0
        while (idx < padded.size / 4) {
            val word = readUInt32LE(padded, idx * 4)
            var term = word * keys[idx % 2]
            var offset = swap(word) * (if (idx % 2 == 0) 0xef0569fbL else 0xc31713dbL)
            if (idx == 0) {
                term += 0xf2f90b1aL * factors[0]
                offset += 0x0b1a0000L
            }
            terms.add(term and MASK)
            offsets.add(offset and MASK)
            idx++
        }

        var swapAcc = 0L
        var swapLast = 0L
        var state = 0L
        var acc = 0L
        var i = 0
        while (i < terms.size) {
            val x = terms[i]
            val y = terms[i + 1]
            var q = swap((offsets[i] + x) and MASK) * 0x76f50f34L and MASK
            swapAcc += (swap(q) * 0xc3efea97L + q) and MASK
            q = swap((offsets[i + 1] + y) and MASK) * 0x1881ce3cL and MASK
            swapLast = (swap(q) * 0x35bd1ec9L + q) and MASK
            swapAcc = (swapAcc + swapLast) and MASK
            state = (state * keys[0] + x) and MASK
            for (factor in longArrayOf(0xcf98b111L, 0x87085b9fL, 0x12ceb96dL, 0x257e1d83L)) {
                state = swap(state) * factor and MASK
            }
            acc += state
            var t = (state * keys[1] + y) and MASK
            for (factor in longArrayOf(0xa27416f5L, 0xd38396ffL, 0x7c932b89L, 0xbfa49f69L)) {
                t = swap(t) * factor and MASK
            }
            state = t
            acc = (acc + state) and MASK
            i += 2
        }
        return (swapLast xor state) to (swapAcc xor acc)
    }

    private fun readUInt32BE(buf: ByteArray, offset: Int): Long =
        (((buf[offset].toLong() and 0xff) shl 24) or
                ((buf[offset + 1].toLong() and 0xff) shl 16) or
                ((buf[offset + 2].toLong() and 0xff) shl 8) or
                (buf[offset + 3].toLong() and 0xff)) and MASK

    private fun readUInt32LE(buf: ByteArray, offset: Int): Long =
        (((buf[offset + 3].toLong() and 0xff) shl 24) or
                ((buf[offset + 2].toLong() and 0xff) shl 16) or
                ((buf[offset + 1].toLong() and 0xff) shl 8) or
                (buf[offset].toLong() and 0xff)) and MASK

    private fun writeUInt32BE(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = ((value shr 24) and 0xff).toByte()
        buf[offset + 1] = ((value shr 16) and 0xff).toByte()
        buf[offset + 2] = ((value shr 8) and 0xff).toByte()
        buf[offset + 3] = (value and 0xff).toByte()
    }

    /** 连接ID：16 字节随机十六进制，用于 Cookie 与请求头 */
    fun connectionId(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return hex(bytes).lowercase(Locale.US)
    }

    @Suppress("unused")
    fun sha256Hex(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(text.toByteArray()).joinToString("") {
            String.format(Locale.US, "%02x", it)
        }
    }
}
