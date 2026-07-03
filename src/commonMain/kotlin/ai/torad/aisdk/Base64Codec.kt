package ai.torad.aisdk

import kotlin.io.encoding.Base64

internal object Base64Codec {

    /** @since 0.3.0-beta01 */
    public fun decode(base64String: String): ByteArray =
        Base64.Default.decode(base64String.replace('-', '+').replace('_', '/'))

    /** @since 0.3.0-beta01 */
    public fun encode(array: ByteArray): String = Base64.Default.encode(array)
}
