package ai.torad.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals

class CryptoTest {
    /**
     * Regression: HMAC-SHA256 with a key LONGER than the 64-byte block size threw
     * IndexOutOfBoundsException — the `key.size > blockSize` branch hashed the key to 32 bytes
     * but did NOT re-pad it back to the block size, so the 0..63 XOR loop overran at index 32.
     * A tool-approval secret is arbitrary user bytes, so a >64-byte secret crashed every gated
     * call. Locked with RFC 4231 Test Case 6 (131-byte key).
     */
    @Test
    fun `hmacSha256 handles a key longer than the block size - RFC 4231 case 6`() {
        val key = ByteArray(131) { 0xaa.toByte() }
        val data = "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray()

        val mac = CryptoPrimitives.hmacSha256(key, data)

        assertEquals(32, mac.size, "HMAC-SHA256 output is always 32 bytes")
        assertEquals(
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            with(CryptoPrimitives) { mac.toHex() },
            "matches the RFC 4231 case-6 vector",
        )
    }

    /** A <= block-size key (the previously-only-tested path) still matches RFC 4231 case 2. */
    @Test
    fun `hmacSha256 still matches the short-key vector - RFC 4231 case 2`() {
        val mac = CryptoPrimitives.hmacSha256(
            key = "Jefe".encodeToByteArray(),
            message = "what do ya want for nothing?".encodeToByteArray(),
        )
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            with(CryptoPrimitives) { mac.toHex() },
        )
    }
}
