package ai.torad.aisdk

import kotlin.io.encoding.Base64

internal typealias Uint8Array = ByteArray

public object Base64Codec {

    public fun decode(base64String: String): ByteArray =
        Base64.Default.decode(base64String.replace('-', '+').replace('_', '/'))

    public fun encode(array: ByteArray): String = Base64.Default.encode(array)

    internal fun decodeToUint8Array(base64String: String): Uint8Array = decode(base64String)

    internal fun encodeFromUint8Array(array: Uint8Array): String = encode(array)

    internal fun encodeString(value: String): String = value
}
