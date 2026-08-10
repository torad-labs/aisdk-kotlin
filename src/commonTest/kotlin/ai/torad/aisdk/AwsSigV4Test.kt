package ai.torad.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AwsSigV4Test {
    @Test
    fun `signer matches AWS IAM canonical request example`() {
        val headers = AwsSigV4.awsSigV4SignedHeaders(
            method = "GET",
            url = "https://iam.amazonaws.com/?Action=ListUsers&Version=2010-05-08",
            service = "iam",
            region = "us-east-1",
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=utf-8"),
            body = "",
            credentials = AwsSigV4Credentials(
                accessKeyId = "AKIDEXAMPLE",
                secretAccessKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            ),
            amzDate = "20150830T123600Z",
        )

        assertEquals("iam.amazonaws.com", headers.headerValue("host"))
        assertEquals("20150830T123600Z", headers.headerValue("x-amz-date"))
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/iam/aws4_request, SignedHeaders=content-type;host;x-amz-date, Signature=5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7",
            headers.headerValue("Authorization"),
        )
    }

    @Test
    fun `signer preserves session token and encoded path for provider requests`() {
        val headers = AwsSigV4.awsSigV4SignedHeaders(
            method = "POST",
            url = "https://bedrock-runtime.us-east-1.amazonaws.com/model/amazon.nova-lite-v1%3A0/converse",
            service = "bedrock",
            region = "us-east-1",
            headers = mapOf("content-type" to "application/json"),
            body = """{"messages":[]}""",
            credentials = AwsSigV4Credentials(
                accessKeyId = "id",
                secretAccessKey = "secret",
                sessionToken = "token",
            ),
            amzDate = "20260102T030405Z",
        )

        assertEquals("token", headers.headerValue("x-amz-security-token"))
        assertTrue(
            headers.headerValue(
                "Authorization"
            ).orEmpty().contains("Credential=id/20260102/us-east-1/bedrock/aws4_request")
        )
        assertTrue(
            headers.headerValue(
                "Authorization"
            ).orEmpty().contains("SignedHeaders=content-type;host;x-amz-date;x-amz-security-token")
        )
    }

    @Test
    fun `signer matches boto3 Bedrock colon model id signature`() {
        val canonicalPath = "/model/anthropic.claude-3-5-sonnet-20240620-v1%3A0/converse"
        val headers = AwsSigV4.awsSigV4SignedHeaders(
            method = "POST",
            url = "https://bedrock-runtime.us-east-1.amazonaws.com$canonicalPath",
            service = "bedrock",
            region = "us-east-1",
            headers = mapOf("Content-Type" to "application/json"),
            body = """{"messages":[]}""",
            credentials = AwsSigV4Credentials(
                accessKeyId = "AKIDEXAMPLE",
                secretAccessKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            ),
            amzDate = "20150830T123600Z",
        )

        assertTrue(canonicalPath.contains("%3A"))
        assertTrue(!canonicalPath.contains("%253A"))
        assertEquals("bedrock-runtime.us-east-1.amazonaws.com", headers.headerValue("host"))
        assertEquals("20150830T123600Z", headers.headerValue("x-amz-date"))
        // boto3/botocore signs the canonical path DOUBLE-encoded for every service except S3, i.e.
        // over `/model/anthropic.claude-3-5-sonnet-20240620-v1%253A0/converse` for this wire path.
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/bedrock/aws4_request, " +
                "SignedHeaders=content-type;host;x-amz-date, " +
                "Signature=40f11609b51d318bc71127993eb5b075cc12b3bde24aa11d2a25186d1fc1d196",
            headers.headerValue("Authorization"),
        )
    }

    @Test
    fun `a raw reserved char in the path signs the same as its pre-encoded form`() {
        // Non-S3 SigV4 canonicalization double-encodes the path, so `/foo+bar` and `/foo%2Bbar`
        // describe the same resource and MUST produce the same canonical path — `%252B` — and so
        // the same signature. Escaping '%' alone left the raw form single-encoded at `%2B`, which
        // is a hard 403 SignatureDoesNotMatch against any host whose base-URL prefix carries a raw
        // reserved char (a custom bedrockRuntimeBaseURL or Anthropic AWS base URL; every in-repo
        // caller pre-encodes, which is why the pinned botocore vector never caught it).
        //
        // Asserted as an equivalence rather than against a hardcoded signature: the botocore
        // vector above remains the absolute oracle, and this pins the property that fix restores
        // without inventing a second oracle by hand.
        fun sign(path: String): String? = AwsSigV4.awsSigV4SignedHeaders(
            method = "POST",
            url = "https://bedrock-runtime.us-east-1.amazonaws.com$path",
            service = "bedrock",
            region = "us-east-1",
            headers = mapOf("Content-Type" to "application/json"),
            body = """{"messages":[]}""",
            credentials = AwsSigV4Credentials(
                accessKeyId = "AKIDEXAMPLE",
                secretAccessKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            ),
            amzDate = "20150830T123600Z",
        ).headerValue("Authorization")

        assertEquals(
            sign("/model/vendor.model-v1%2B0/converse"),
            sign("/model/vendor.model-v1+0/converse"),
            "a raw reserved char must canonicalize to the same double-encoded path as its " +
                "pre-encoded twin, or requests to a host with such a prefix 403",
        )
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
