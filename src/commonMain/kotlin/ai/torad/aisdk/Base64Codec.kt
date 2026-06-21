package ai.torad.aisdk

import kotlin.io.encoding.Base64

public object Base64Codec {

    public fun decode(base64String: String): ByteArray =
        Base64.Default.decode(base64String.replace('-', '+').replace('_', '/'))

    public fun encode(array: ByteArray): String = Base64.Default.encode(array)
}
