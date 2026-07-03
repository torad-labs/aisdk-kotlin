package ai.torad.aisdk

import ai.torad.aisdk.providers.AmazonBedrockProviderSettings
import ai.torad.aisdk.providers.BedrockHttp
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class BedrockSigningRegionTest {
    private val sigV4Settings = AmazonBedrockProviderSettings(block = {
        accessKeyId("id")
        secretAccessKey("secret")
    })

    private suspend fun credentialScope(url: String): String {
        val headers = BedrockHttp.bedrockHeaders(
            settings = sigV4Settings,
            extra = emptyMap(),
            url = url,
            body = "{}",
            service = "bedrock",
            amzDate = "20240101T000000Z",
        )
        return BedrockHttp.headerValue(headers, "Authorization").orEmpty()
    }

    @Test
    fun `rerank agent-runtime host signs for its own us-west-2 region`() = runTest {
        // The agent-runtime default endpoint is us-west-2; with the prior
        // us-east-1 signing default the SigV4 region mismatched the host and
        // every out-of-the-box rerank call failed.
        val scope = credentialScope("https://bedrock-agent-runtime.us-west-2.amazonaws.com/rerank")
        assertTrue(scope.contains("/us-west-2/bedrock/aws4_request"), scope)
    }

    @Test
    fun `runtime host signs for its own us-east-1 region`() = runTest {
        val scope = credentialScope("https://bedrock-runtime.us-east-1.amazonaws.com/model/x/converse")
        assertTrue(scope.contains("/us-east-1/bedrock/aws4_request"), scope)
    }

    @Test
    fun `api-aws host derives region from second label`() = runTest {
        val scope = credentialScope("https://bedrock-mantle.eu-west-1.api.aws/v1/chat")
        assertTrue(scope.contains("/eu-west-1/bedrock/aws4_request"), scope)
    }

    @Test
    fun `custom proxy host falls back to configured region`() = runTest {
        val scope = BedrockHttp.headerValue(
            BedrockHttp.bedrockHeaders(
                settings = AmazonBedrockProviderSettings(block = {
                    accessKeyId(sigV4Settings.accessKeyId)
                    secretAccessKey(sigV4Settings.secretAccessKey)
                    region("ap-south-1")
                }),
                extra = emptyMap(),
                url = "https://bedrock.test/model/x/converse",
                body = "{}",
                service = "bedrock",
                amzDate = "20240101T000000Z",
            ),
            "Authorization",
        ).orEmpty()
        assertTrue(scope.contains("/ap-south-1/bedrock/aws4_request"), scope)
    }
}
