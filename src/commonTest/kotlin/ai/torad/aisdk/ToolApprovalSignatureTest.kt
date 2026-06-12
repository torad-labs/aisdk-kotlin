package ai.torad.aisdk

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The HMAC approval-signature primitives (upstream v6.0.202 `tool-approval-signature.ts`). */
class ToolApprovalSignatureTest {

    private val secret = "approval-secret".encodeToByteArray()
    private val input = buildJsonObject {
        put("path", "/tmp/x")
        put("recursive", true)
    }

    @Test
    fun `sign then verify round-trips`() {
        val signature = signToolApproval(secret, "appr_1", "call_1", "deleteFile", input)
        assertTrue(verifyToolApprovalSignature(secret, signature, "appr_1", "call_1", "deleteFile", input))
    }

    @Test
    fun `the signature is base64url with no padding`() {
        val signature = signToolApproval(secret, "appr_1", "call_1", "deleteFile", input)
        assertTrue(signature.none { it == '+' || it == '/' || it == '=' }, "got: $signature")
    }

    @Test
    fun `canonicalization makes key order irrelevant`() {
        val reordered = buildJsonObject {
            put("recursive", true)
            put("path", "/tmp/x")
        }
        assertEquals(canonicalJson(input), canonicalJson(reordered))
        val signature = signToolApproval(secret, "appr_1", "call_1", "deleteFile", input)
        assertTrue(verifyToolApprovalSignature(secret, signature, "appr_1", "call_1", "deleteFile", reordered))
    }

    @Test
    fun `any tuple component change breaks verification`() {
        val signature = signToolApproval(secret, "appr_1", "call_1", "deleteFile", input)
        val tamperedInput = buildJsonObject {
            put("path", "/etc/passwd")
            put("recursive", true)
        }
        assertFalse(verifyToolApprovalSignature(secret, signature, "appr_1", "call_1", "deleteFile", tamperedInput))
        assertFalse(verifyToolApprovalSignature(secret, signature, "appr_2", "call_1", "deleteFile", input))
        assertFalse(verifyToolApprovalSignature(secret, signature, "appr_1", "call_2", "deleteFile", input))
        assertFalse(verifyToolApprovalSignature(secret, signature, "appr_1", "call_1", "readFile", input))
        val otherSecret = "other".encodeToByteArray()
        assertFalse(verifyToolApprovalSignature(otherSecret, signature, "appr_1", "call_1", "deleteFile", input))
    }

    @Test
    fun `a malformed signature is rejected rather than throwing`() {
        assertFalse(verifyToolApprovalSignature(secret, "%%not-base64url%%", "appr_1", "call_1", "deleteFile", input))
    }

    @Test
    fun `maybeSign is null without a secret and signs with one`() {
        assertNull(maybeSignToolApproval(null, "appr_1", "call_1", "deleteFile", input))
        val signed = maybeSignToolApproval(secret, "appr_1", "call_1", "deleteFile", input)
        assertEquals(signToolApproval(secret, "appr_1", "call_1", "deleteFile", input), signed)
    }
}
