package ai.torad.aisdk

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import kotlin.random.Random

// Shared by every Kotlin/Native target (iOS + Linux). Draws entropy from
// /dev/urandom — the OS cryptographic random source present on all of them —
// so OAuth state / PKCE code_verifier get a real CSPRNG instead of the seeded
// kotlin.random.Random.Default that RFC 7636 §4.1 forbids.
internal actual fun SecureRandom(): Random = UrandomSecureRandom

@OptIn(ExperimentalForeignApi::class)
private object UrandomSecureRandom : Random() {
    override fun nextBits(bitCount: Int): Int {
        if (bitCount == 0) return 0
        val bytes = ByteArray(4)
        readEntropy(bytes)
        val value = (bytes[0].toInt() and 0xFF) or
            ((bytes[1].toInt() and 0xFF) shl 8) or
            ((bytes[2].toInt() and 0xFF) shl 16) or
            ((bytes[3].toInt() and 0xFF) shl 24)
        return value ushr (32 - bitCount)
    }

    private fun readEntropy(buffer: ByteArray) {
        val stream = fopen("/dev/urandom", "rb")
            ?: throw SecureRandomUnavailableError("Unable to open /dev/urandom")
        try {
            buffer.usePinned { pinned ->
                var offset = 0
                while (offset < buffer.size) {
                    val read = fread(
                        pinned.addressOf(offset),
                        1.convert(),
                        (buffer.size - offset).convert(),
                        stream,
                    ).toInt()
                    if (read <= 0) throw SecureRandomUnavailableError("Short read from /dev/urandom")
                    offset += read
                }
            }
        } finally {
            fclose(stream)
        }
    }
}
