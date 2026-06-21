package ai.torad.aisdk

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64

/**
 * HMAC-SHA256 signing for tool-approval requests (upstream v6.0.202
 * `tool-approval-signature.ts`). When an agent is configured with an approval
 * secret, every [ContentPart.ToolApprovalRequest] it issues is signed over
 * `(approvalId, toolCallId, toolName, CryptoPrimitives.sha256(canonicalJson(input)))`, and a
 * replayed approval is verified against the same tuple before the tool
 * executes — so a client cannot forge an approval, re-target one to a
 * different call, or swap the tool input under an approval it was granted.
 *
 * The signature is transport-opaque: hosts persist and replay it with the
 * message log; only the secret holder (the agent process) can mint or check
 * it. Canonicalization sorts object keys recursively, so semantically equal
 * JSON signs identically regardless of key order.
 */
public object ToolApprovalSignature {

    /**
     * Canonical JSON for signing: object keys sorted recursively, arrays in
     * order, primitives in their JSON form. Mirrors upstream `canonicalJSON` so
     * a digest is stable across serialization round-trips of the same value.
     */
    internal fun canonicalJson(value: JsonElement): String = when (value) {
        is JsonObject -> value.keys.sorted().joinToString(separator = ",", prefix = "{", postfix = "}") { key ->
            "${JsonPrimitive(key)}:${canonicalJson(value.getValue(key))}"
        }
        is JsonArray -> value.joinToString(separator = ",", prefix = "[", postfix = "]") { canonicalJson(it) }
        is JsonPrimitive -> value.toString()
    }

    private fun toBase64Url(bytes: ByteArray): String =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)

    private fun fromBase64Url(text: String): ByteArray =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(text)

    /** The signed payload: `approvalId\ntoolCallId\ntoolName\ninputDigest` (upstream `buildPayload`). */
    private fun approvalPayload(
        approvalId: String,
        toolCallId: String,
        toolName: String,
        input: JsonElement,
    ): ByteArray {
        val inputDigest = toBase64Url(CryptoPrimitives.sha256(canonicalJson(input).encodeToByteArray()))
        return "$approvalId\n$toolCallId\n$toolName\n$inputDigest".encodeToByteArray()
    }

    /** Sign one approval request. [approvalId] is the EFFECTIVE id (explicit `approvalId ?: toolCallId`). */
    public fun signToolApproval(
        secret: ByteArray,
        approvalId: String,
        toolCallId: String,
        toolName: String,
        input: JsonElement,
    ): String = toBase64Url(CryptoPrimitives.hmacSha256(secret, approvalPayload(approvalId, toolCallId, toolName, input)))

    /** Verify a replayed approval's signature. Constant-time comparison; false on any malformed input. */
    public fun verifyToolApprovalSignature(
        secret: ByteArray,
        signature: String,
        approvalId: String,
        toolCallId: String,
        toolName: String,
        input: JsonElement,
    ): Boolean {
        val provided = runCatching { fromBase64Url(signature) }.getOrNull() ?: return false
        val expected = CryptoPrimitives.hmacSha256(secret, approvalPayload(approvalId, toolCallId, toolName, input))
        return constantTimeEquals(expected, provided)
    }

    /** Sign when a [secret] is configured; null otherwise (upstream `maybeSignApproval`). */
    public fun maybeSignToolApproval(
        secret: ByteArray?,
        approvalId: String,
        toolCallId: String,
        toolName: String,
        input: JsonElement,
    ): String? = secret?.let { signToolApproval(it, approvalId, toolCallId, toolName, input) }

    /** Length-then-XOR-accumulate comparison — no early exit on the first differing byte. */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        a.size == b.size && a.indices.fold(0) { acc, i -> acc or (a[i].toInt() xor b[i].toInt()) } == 0
}
