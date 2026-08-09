package ai.torad.aisdk

import kotlin.io.encoding.Base64

internal object Base64Codec {

    // base64url payloads are conventionally emitted WITHOUT padding (JWT parts, provider payloads),
    // and upstream's `atob` accepts them, so decoding tolerates absent padding the same way it
    // tolerates the -_ alphabet below. Encoding stays standard padded base64.
    private val paddingTolerantDecoder = Base64.Default.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    /** @since 0.3.0-beta01 */
    public fun decode(base64String: String): ByteArray =
        paddingTolerantDecoder.decode(base64String.replace('-', '+').replace('_', '/'))

    /** @since 0.3.0-beta01 */
    public fun encode(array: ByteArray): String = Base64.Default.encode(array)
}
