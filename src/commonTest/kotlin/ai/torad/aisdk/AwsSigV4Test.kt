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
        assertTrue(headers.headerValue("Authorization").orEmpty().contains("Credential=id/20260102/us-east-1/bedrock/aws4_request"))
        assertTrue(headers.headerValue("Authorization").orEmpty().contains("SignedHeaders=content-type;host;x-amz-date;x-amz-security-token"))
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
