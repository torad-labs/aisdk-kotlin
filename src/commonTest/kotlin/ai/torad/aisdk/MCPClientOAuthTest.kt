package ai.torad.aisdk

import ai.torad.aisdk.JSONRPCMessage.Companion.toJsonElement
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

@OptIn(ExperimentalAiSdkApi::class, ExperimentalCoroutinesApi::class, InternalAiSdkApi::class)
class MCPClientOAuthTest : MCPClientTestBase() {

    @Test
    fun `auth returns authorized with existing tokens and redirect when tokens are absent`() = runTest {
        val authorized = MemoryOAuthProvider(tokens = OAuthTokens(accessToken = "token", tokenType = "Bearer"))
        assertEquals(AuthResult.AUTHORIZED, McpAuth.auth(authorized, AuthOptions { serverUrl("https://mcp.example.com") }))

        val redirect = MemoryOAuthProvider(tokens = null)
        assertEquals(AuthResult.REDIRECT, McpAuth.auth(redirect, AuthOptions {
            serverUrl("https://mcp.example.com")
            scope("tools")
        }))
        assertNotNull(redirect.lastAuthorizationUrl)
        assertTrue(redirect.lastAuthorizationUrl!!.startsWith("https://mcp.example.com/authorize?"))
        assertTrue("scope=tools" in redirect.lastAuthorizationUrl!!)
    }

    @Test
    fun `reauthorize does not treat an access token without refresh token as already authorized`() = runTest {
        val provider = MemoryOAuthProvider(tokens = OAuthTokens(accessToken = "stale-token", tokenType = "Bearer"))

        assertEquals(
            AuthResult.REDIRECT,
            McpAuth.auth(provider, AuthOptions { serverUrl("https://mcp.example.com") }, reauthorize = true),
        )
        assertNotNull(provider.lastAuthorizationUrl)
    }

    @Test
    fun `auth starts authorization with dynamic registration and PKCE`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://auth.example.com/.well-known/oauth-authorization-server" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "authorization_endpoint":"https://login.example.com/oauth/authorize",
                              "token_endpoint":"https://login.example.com/oauth/token",
                              "registration_endpoint":"https://login.example.com/oauth/register",
                              "response_types_supported":["code"],
                              "code_challenge_methods_supported":["S256"]
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
                "https://login.example.com/oauth/register" to UrlHandler(
                    { request, _ ->
                        assertTrue(request.body.contains("\"client_name\":\"client-1\""))
                        UrlResponse.JsonValue(Json.parseToJsonElement("""{"client_id":"registered-client","client_secret":"registered-secret"}"""))
                    },
                ),
            ),
        )
        fixture.server.start()
        val provider = MemoryOAuthProvider(tokens = null, clientInformation = null)

        assertEquals(
            AuthResult.REDIRECT,
            McpAuth.auth(
                provider,
                AuthOptions {
                    serverUrl("https://auth.example.com")
                    scope("tools offline_access")
                    client(fixture.httpClient())
                },
            ),
        )

        assertEquals("registered-client", provider.clientInformation()?.clientId)
        assertEquals("registered-secret", provider.clientInformation()?.clientSecret)
        val authorizationUrl = assertNotNull(provider.lastAuthorizationUrl)
        assertTrue(authorizationUrl.startsWith("https://login.example.com/oauth/authorize?"))
        assertTrue("client_id=registered-client" in authorizationUrl)
        assertTrue("scope=tools%20offline_access" in authorizationUrl)
        assertTrue("code_challenge=" in authorizationUrl)
        assertTrue("code_challenge_method=S256" in authorizationUrl)
        assertTrue("prompt=consent" in authorizationUrl)
        assertTrue(provider.savedCodeVerifier.isNotBlank())
    }

    @Test
    fun `auth exchanges authorization code and awaits async client authentication`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://auth.example.com/.well-known/oauth-authorization-server" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "authorization_endpoint":"https://auth.example.com/authorize",
                              "token_endpoint":"https://auth.example.com/token",
                              "response_types_supported":["code"],
                              "grant_types_supported":["authorization_code"],
                              "token_endpoint_auth_methods_supported":["client_secret_post"],
                              "code_challenge_methods_supported":["S256"]
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
                "https://auth.example.com/token" to UrlHandler(
                    { request, _ ->
                        assertTrue(request.body.contains("grant_type=authorization_code"))
                        assertTrue(request.body.contains("code=code123"))
                        assertTrue(request.body.contains("code_verifier=verifier"))
                        assertTrue(request.body.contains("client_id=set-by-async-provider"))
                        assertTrue(request.body.contains("client_secret=secret-by-async-provider"))
                        assertTrue(request.body.contains("example_url=https%3A%2F%2Fauth.example.com"))
                        UrlResponse.JsonValue(
                            Json.parseToJsonElement(
                                """{"access_token":"async-token","token_type":"Bearer","refresh_token":"refresh-token"}""",
                            ),
                        )
                    },
                ),
            ),
        )
        fixture.server.start()
        val provider = MemoryOAuthProvider(
            tokens = null,
            clientInformation = OAuthClientInformation {
                clientId("client-id")
                clientSecret("client-secret")
            },
            onAddClientAuthentication = { _, _, _, _ ->
                delay(5)
                ClientAuthResult(additionalParams = mapOf(
                    "client_id" to "set-by-async-provider",
                    "client_secret" to "secret-by-async-provider",
                    "example_url" to "https://auth.example.com",
                ))
            },
        )
        provider.saveState("state-1")

        assertEquals(
            AuthResult.AUTHORIZED,
            McpAuth.auth(
                provider,
                AuthOptions {
                    serverUrl("https://auth.example.com")
                    authorizationCode("code123")
                    callbackState("state-1")
                    client(fixture.httpClient())
                },
            ),
        )

        assertEquals("async-token", provider.tokens()?.accessToken)
        assertEquals("refresh-token", provider.tokens()?.refreshToken)
    }

    @Test
    fun `auth refreshes tokens preserves refresh token and awaits async client authentication`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://auth.example.com/.well-known/oauth-authorization-server" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "authorization_endpoint":"https://auth.example.com/authorize",
                              "token_endpoint":"https://auth.example.com/token",
                              "response_types_supported":["code"],
                              "grant_types_supported":["refresh_token"],
                              "token_endpoint_auth_methods_supported":["client_secret_post"],
                              "code_challenge_methods_supported":["S256"]
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
                "https://auth.example.com/token" to UrlHandler(
                    { request, _ ->
                        assertTrue(request.body.contains("grant_type=refresh_token"))
                        assertTrue(request.body.contains("refresh_token=old-refresh"))
                        assertTrue(request.body.contains("client_id=set-by-async-provider"))
                        assertTrue(request.body.contains("client_secret=secret-by-async-provider"))
                        assertTrue(request.body.contains("example_url=https%3A%2F%2Fauth.example.com"))
                        UrlResponse.JsonValue(Json.parseToJsonElement("""{"access_token":"new-token","token_type":"Bearer"}"""))
                    },
                ),
            ),
        )
        fixture.server.start()
        val provider = MemoryOAuthProvider(
            tokens = OAuthTokens(accessToken = "old-token", tokenType = "Bearer", refreshToken = "old-refresh"),
            clientInformation = OAuthClientInformation {
                clientId("client-id")
                clientSecret("client-secret")
            },
            onAddClientAuthentication = { _, _, _, _ ->
                delay(5)
                ClientAuthResult(additionalParams = mapOf(
                    "client_id" to "set-by-async-provider",
                    "client_secret" to "secret-by-async-provider",
                    "example_url" to "https://auth.example.com",
                ))
            },
        )

        assertEquals(AuthResult.AUTHORIZED, McpAuth.auth(provider, AuthOptions {
            serverUrl("https://auth.example.com")
            client(fixture.httpClient())
        }))

        assertEquals("new-token", provider.tokens()?.accessToken)
        assertEquals("old-refresh", provider.tokens()?.refreshToken)
    }

    @Test
    fun `auth falls back to redirect when reauthorize refresh token is invalid grant`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://auth.example.com/.well-known/oauth-authorization-server" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "authorization_endpoint":"https://auth.example.com/authorize",
                              "token_endpoint":"https://auth.example.com/token",
                              "response_types_supported":["code"],
                              "grant_types_supported":["authorization_code","refresh_token"],
                              "token_endpoint_auth_methods_supported":["client_secret_post"],
                              "code_challenge_methods_supported":["S256"]
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
                "https://auth.example.com/token" to UrlHandler(
                    { request, _ ->
                        assertTrue(request.body.contains("grant_type=refresh_token"))
                        assertTrue(request.body.contains("refresh_token=revoked-refresh"))
                        UrlResponse.Error(
                            status = 400,
                            body = """{"error":"invalid_grant"}""",
                            headers = mapOf(HttpHeaders.ContentType to "application/json"),
                        )
                    },
                ),
            ),
        )
        fixture.server.start()
        val provider = MemoryOAuthProvider(
            tokens = OAuthTokens(accessToken = "expired-token", tokenType = "Bearer", refreshToken = "revoked-refresh"),
            clientInformation = OAuthClientInformation {
                clientId("client-id")
                clientSecret("client-secret")
            },
        )

        assertEquals(
            AuthResult.REDIRECT,
            McpAuth.auth(
                provider,
                AuthOptions {
                    serverUrl("https://auth.example.com")
                    client(fixture.httpClient())
                },
                reauthorize = true,
            ),
        )

        val authorizationUrl = assertNotNull(provider.lastAuthorizationUrl)
        assertTrue(authorizationUrl.startsWith("https://auth.example.com/authorize?"))
        assertTrue("client_id=client-id" in authorizationUrl)
        assertTrue("code_challenge=" in authorizationUrl)
        assertEquals("expired-token", provider.tokens()?.accessToken)
    }

    @Test
    fun `auth uses protected resource metadata for authorization server and redirect resource`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://api.example.com/.well-known/oauth-protected-resource/mcp-server" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"resource":"https://api.example.com/mcp-server","authorization_servers":["https://auth.example.com"]}""",
                        ),
                    ),
                ),
                "https://auth.example.com/.well-known/oauth-authorization-server" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "authorization_endpoint":"https://auth.example.com/authorize",
                              "token_endpoint":"https://auth.example.com/token",
                              "response_types_supported":["code"],
                              "code_challenge_methods_supported":["S256"]
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = MemoryOAuthProvider(tokens = null)

        assertEquals(
            AuthResult.REDIRECT,
            McpAuth.auth(provider, AuthOptions {
                serverUrl("https://api.example.com/mcp-server")
                client(fixture.httpClient())
            }),
        )

        val authorizationUrl = assertNotNull(provider.lastAuthorizationUrl)
        assertTrue(authorizationUrl.startsWith("https://auth.example.com/authorize?"))
        assertTrue("resource=https%3A%2F%2Fapi.example.com%2Fmcp-server" in authorizationUrl)
        assertEquals(
            listOf(
                "https://api.example.com/.well-known/oauth-protected-resource/mcp-server",
                "https://auth.example.com/.well-known/oauth-authorization-server",
            ),
            fixture.calls.map { it.requestUrl },
        )
    }

    @Test
    fun `auth carries protected resource through code exchange and refresh`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://api.example.com/.well-known/oauth-protected-resource/mcp-server" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"resource":"https://api.example.com/mcp-server","authorization_servers":["https://auth.example.com"]}""",
                        ),
                    ),
                ),
                "https://auth.example.com/.well-known/oauth-authorization-server" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "authorization_endpoint":"https://auth.example.com/authorize",
                              "token_endpoint":"https://auth.example.com/token",
                              "response_types_supported":["code"],
                              "grant_types_supported":["authorization_code","refresh_token"],
                              "token_endpoint_auth_methods_supported":["client_secret_post"],
                              "code_challenge_methods_supported":["S256"]
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
                "https://auth.example.com/token" to UrlHandler(
                    { request, options ->
                        assertTrue(request.body.contains("resource=https%3A%2F%2Fapi.example.com%2Fmcp-server"))
                        if (options.callNumber == 2) {
                            assertTrue(request.body.contains("grant_type=authorization_code"))
                            UrlResponse.JsonValue(
                                Json.parseToJsonElement(
                                    """{"access_token":"access-token","token_type":"Bearer","refresh_token":"refresh-token"}""",
                                ),
                            )
                        } else {
                            assertTrue(request.body.contains("grant_type=refresh_token"))
                            UrlResponse.JsonValue(Json.parseToJsonElement("""{"access_token":"refreshed","token_type":"Bearer"}"""))
                        }
                    },
                ),
            ),
        )
        fixture.server.start()
        val provider = MemoryOAuthProvider(
            tokens = null,
            clientInformation = OAuthClientInformation {
                clientId("client-id")
                clientSecret("client-secret")
            },
        )
        provider.saveState("state-1")

        assertEquals(
            AuthResult.AUTHORIZED,
            McpAuth.auth(
                provider,
                AuthOptions {
                    serverUrl("https://api.example.com/mcp-server")
                    authorizationCode("code")
                    callbackState("state-1")
                    client(fixture.httpClient())
                },
            ),
        )
        assertEquals("access-token", provider.tokens()?.accessToken)

        assertEquals(
            AuthResult.AUTHORIZED,
            McpAuth.auth(provider, AuthOptions {
                serverUrl("https://api.example.com/mcp-server")
                client(fixture.httpClient())
            }),
        )
        assertEquals("refreshed", provider.tokens()?.accessToken)
        assertEquals("refresh-token", provider.tokens()?.refreshToken)
    }
}
