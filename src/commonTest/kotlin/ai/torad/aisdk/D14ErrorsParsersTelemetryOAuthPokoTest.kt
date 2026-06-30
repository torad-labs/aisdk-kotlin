package ai.torad.aisdk

import ai.torad.aisdk.providers.BasetenErrorData
import ai.torad.aisdk.providers.CerebrasErrorData
import ai.torad.aisdk.providers.FireworksErrorData
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class D14ErrorsParsersTelemetryOAuthPokoTest {
    @Test
    fun `D14 OAuth Poko payloads round trip with stable wire names`() {
        val tokens = OAuthTokens(
            accessToken = "access",
            tokenType = "Bearer",
            idToken = "id",
            expiresIn = 3600,
            scope = "read write",
            refreshToken = "refresh",
        )
        val encodedTokens = assertMcpRoundTrip(OAuthTokens.serializer(), tokens)
        val tokenKeys = mcpJson.parseToJsonElement(encodedTokens).jsonObject.keys
        assertTrue("access_token" in tokenKeys, encodedTokens)
        assertTrue("token_type" in tokenKeys, encodedTokens)
        assertTrue("id_token" in tokenKeys, encodedTokens)
        assertTrue("expires_in" in tokenKeys, encodedTokens)
        assertTrue("refresh_token" in tokenKeys, encodedTokens)

        val authorization = AuthorizationServerMetadata(
            issuer = "https://auth.example",
            authorizationEndpoint = "https://auth.example/authorize",
            tokenEndpoint = "https://auth.example/token",
            registrationEndpoint = "https://auth.example/register",
            responseTypesSupported = listOf("code"),
            grantTypesSupported = listOf("authorization_code", "refresh_token"),
            tokenEndpointAuthMethodsSupported = listOf("client_secret_post"),
            codeChallengeMethodsSupported = listOf("S256"),
        )
        val encodedAuthorization = assertMcpRoundTrip(AuthorizationServerMetadata.serializer(), authorization)
        val authorizationKeys = mcpJson.parseToJsonElement(encodedAuthorization).jsonObject.keys
        assertTrue("authorization_endpoint" in authorizationKeys, encodedAuthorization)
        assertTrue("token_endpoint" in authorizationKeys, encodedAuthorization)
        assertTrue("registration_endpoint" in authorizationKeys, encodedAuthorization)
        assertTrue("response_types_supported" in authorizationKeys, encodedAuthorization)
        assertTrue("grant_types_supported" in authorizationKeys, encodedAuthorization)
        assertTrue("token_endpoint_auth_methods_supported" in authorizationKeys, encodedAuthorization)
        assertTrue("code_challenge_methods_supported" in authorizationKeys, encodedAuthorization)

        val protectedResource = OAuthProtectedResourceMetadata(
            resource = "https://mcp.example",
            authorizationServers = listOf("https://auth.example"),
            jwksUri = "https://mcp.example/jwks.json",
            scopesSupported = listOf("read"),
            bearerMethodsSupported = listOf("header"),
            resourceName = "MCP",
        )
        val encodedProtectedResource = assertMcpRoundTrip(
            OAuthProtectedResourceMetadata.serializer(),
            protectedResource,
        )
        val protectedResourceKeys = mcpJson.parseToJsonElement(encodedProtectedResource).jsonObject.keys
        assertTrue("authorization_servers" in protectedResourceKeys, encodedProtectedResource)
        assertTrue("jwks_uri" in protectedResourceKeys, encodedProtectedResource)
        assertTrue("scopes_supported" in protectedResourceKeys, encodedProtectedResource)
        assertTrue("bearer_methods_supported" in protectedResourceKeys, encodedProtectedResource)
        assertTrue("resource_name" in protectedResourceKeys, encodedProtectedResource)
    }

    @Test
    fun `D14 provider error Poko payloads round trip through aiSdkJson`() {
        assertAiSdkRoundTrip(BasetenErrorData.serializer(), BasetenErrorData(error = "baseten failed"))
        assertAiSdkRoundTrip(
            CerebrasErrorData.serializer(),
            CerebrasErrorData(
                message = "bad request",
                type = "invalid_request_error",
                param = "model",
                code = "invalid_model",
            ),
        )
        assertAiSdkRoundTrip(FireworksErrorData.serializer(), FireworksErrorData(error = "fireworks failed"))
    }

    @Test
    fun `D14 parser error and telemetry Poko types keep value semantics`() {
        val context = TypeValidationContext(field = "name", entityName = "tool", entityId = "lookup")
        val equalContext = TypeValidationContext(field = "name", entityName = "tool", entityId = "lookup")
        val differentContext = TypeValidationContext(field = "age", entityName = "tool", entityId = "lookup")
        assertValueSemantics(context, equalContext, differentContext)

        val throwable = IllegalStateException("retry")
        val attempt = RetryAttemptDetail(attempt = 1, error = throwable, retryable = true, delayMs = 25)
        val equalAttempt = RetryAttemptDetail(attempt = 1, error = throwable, retryable = true, delayMs = 25)
        val differentAttempt = RetryAttemptDetail(attempt = 2, error = throwable, retryable = true, delayMs = 25)
        assertValueSemantics(attempt, equalAttempt, differentAttempt)

        val parsed: ParseResult<JsonObject> = ParseResult.Success(JsonObject(mapOf("ok" to JsonPrimitive(true))))
        val equalParsed: ParseResult<JsonObject> = ParseResult.Success(JsonObject(mapOf("ok" to JsonPrimitive(true))))
        val differentParsed: ParseResult<JsonObject> = ParseResult.Failure(IllegalArgumentException("bad"), "bad")
        assertValueSemantics(parsed, equalParsed, differentParsed)

        val partial = PartialJsonResult(JsonPrimitive("ok"), PartialJsonState.RepairedParse)
        val equalPartial = PartialJsonResult(JsonPrimitive("ok"), PartialJsonState.RepairedParse)
        val differentPartial = PartialJsonResult(null, PartialJsonState.FailedParse)
        assertValueSemantics(partial, equalPartial, differentPartial)

        val call = TelemetryCall(callId = "c1", agentId = "agent", agentVersion = "1", modelId = "m", functionId = "fn")
        val equalCall = TelemetryCall(callId = "c1", agentId = "agent", agentVersion = "1", modelId = "m", functionId = "fn")
        val differentCall = TelemetryCall(callId = "c2", agentId = "agent", agentVersion = "1", modelId = "m", functionId = "fn")
        assertValueSemantics(call, equalCall, differentCall)

        val status: TelemetrySpanStatus = TelemetrySpanStatus.Error("failed")
        val equalStatus: TelemetrySpanStatus = TelemetrySpanStatus.Error("failed")
        val differentStatus: TelemetrySpanStatus = TelemetrySpanStatus.Error("different")
        assertValueSemantics(status, equalStatus, differentStatus)
    }

    @Test
    fun `D14 downloaded asset and devtools Poko types keep value semantics`() {
        val asset = DownloadedAsset(base64 = "aW1hZ2U=", mediaType = "image/png")
        val equalAsset = DownloadedAsset(base64 = "aW1hZ2U=", mediaType = "image/png")
        val differentAsset = DownloadedAsset(base64 = "ZmlsZQ==", mediaType = "image/png")
        assertValueSemantics(asset, equalAsset, differentAsset)

        val step = DevToolsStep(
            id = "step-1",
            runId = "run-1",
            stepNumber = 1,
            type = "generate",
            modelId = "model",
            provider = "test",
            input = JsonPrimitive("prompt"),
            providerOptions = ProviderOptions.None,
        )
        val equalStep = DevToolsStep(
            id = "step-1",
            runId = "run-1",
            stepNumber = 1,
            type = "generate",
            modelId = "model",
            provider = "test",
            input = JsonPrimitive("prompt"),
            providerOptions = ProviderOptions.None,
        )
        val differentStep = DevToolsStep(
            id = "step-2",
            runId = "run-1",
            stepNumber = 1,
            type = "generate",
            modelId = "model",
            provider = "test",
            input = JsonPrimitive("prompt"),
            providerOptions = ProviderOptions.None,
        )
        assertValueSemantics(step, equalStep, differentStep)

        val result = DevToolsStepResult(
            durationMs = 10,
            output = JsonPrimitive("done"),
            usage = Usage.of(promptTokens = 1, completionTokens = 2),
            error = null,
            rawRequest = JsonPrimitive("request"),
            rawResponse = JsonPrimitive("response"),
            rawChunks = listOf(JsonPrimitive("chunk")),
        )
        val equalResult = DevToolsStepResult(
            durationMs = 10,
            output = JsonPrimitive("done"),
            usage = Usage.of(promptTokens = 1, completionTokens = 2),
            error = null,
            rawRequest = JsonPrimitive("request"),
            rawResponse = JsonPrimitive("response"),
            rawChunks = listOf(JsonPrimitive("chunk")),
        )
        val differentResult = DevToolsStepResult(
            durationMs = 11,
            output = JsonPrimitive("done"),
            usage = Usage.of(promptTokens = 1, completionTokens = 2),
            error = null,
            rawRequest = JsonPrimitive("request"),
            rawResponse = JsonPrimitive("response"),
            rawChunks = listOf(JsonPrimitive("chunk")),
        )
        assertValueSemantics(result, equalResult, differentResult)
    }

    private fun <T> assertMcpRoundTrip(serializer: KSerializer<T>, value: T): String {
        val encoded = mcpJson.encodeToString(serializer, value)
        val decoded = mcpJson.decodeFromString(serializer, encoded)
        assertEquals(value, decoded)
        return encoded
    }

    private fun <T> assertAiSdkRoundTrip(serializer: KSerializer<T>, value: T) {
        val encoded = aiSdkJson.encodeToString(serializer, value)
        val decoded = aiSdkJson.decodeFromString(serializer, encoded)
        assertEquals(value, decoded)
    }

    private fun <T> assertValueSemantics(value: T, equal: T, different: T) {
        assertEquals(value, equal)
        assertEquals(value.hashCode(), equal.hashCode())
        assertNotEquals(value, different)
    }
}
