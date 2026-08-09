package ai.torad.aisdk

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class McpOAuthTest {
    /**
     * A provider that parks the SECOND `tokens()` call — the one [McpAuth.auth] makes — so the
     * single-flight winner is deterministically suspended inside the refresh while later callers
     * still get a prompt answer from their own `reauthorizeAfter401` decision.
     */
    private class ParkingOAuthProvider(private val parked: CompletableDeferred<Unit>) : OAuthClientProvider {
        private var tokenCalls: Int = 0
        override val redirectUrl: String = "https://client.example.com/callback"
        override val clientMetadata: OAuthClientMetadata = OAuthClientMetadata {
            redirectUris(listOf(redirectUrl))
            clientName("client-1")
        }

        override suspend fun tokens(): OAuthTokens? {
            tokenCalls += 1
            if (tokenCalls == 2) parked.await()
            return OAuthTokens(accessToken = "stale-token", tokenType = "Bearer", refreshToken = "refresh-token")
        }

        override suspend fun saveTokens(tokens: OAuthTokens) = Unit
        override suspend fun redirectToAuthorization(authorizationUrl: String) = Unit
        override suspend fun saveCodeVerifier(codeVerifier: String) = Unit
        override suspend fun codeVerifier(): String = "verifier"
        override suspend fun clientInformation(): OAuthClientInformation? = OAuthClientInformation {
            clientId("client-id")
        }
    }

    /**
     * Regression: `runReauthorization`'s `catch (error: Throwable)` also caught the WINNER's
     * CancellationException (its request timeout firing mid-refresh, or its caller cancelling)
     * and completed the shared single-flight deferred with it. Waiters parked in the
     * `Decision.Await` branch then rethrew a foreign CancellationException inside their own
     * live, never-cancelled coroutines — a spurious "cancellation" that CE-convention code
     * (retry wrappers, `launch`'s default handling) silently swallows instead of surfacing.
     */
    @Test
    fun `a cancelled reauthorization winner fails waiters with a normal error rather than a cancellation`() = runTest {
        val parked = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine { respond(content = "", status = HttpStatusCode.NotFound) })
        val reauthorizer = McpOAuthReauthorizer(
            ParkingOAuthProvider(parked),
            "https://mcp.test/mcp",
            client,
        )

        // UNDISPATCHED: the winner runs synchronously until it parks inside the refresh, so the
        // waiter below deterministically observes an in-flight reauthorization and takes Await.
        val winner = launch(start = CoroutineStart.UNDISPATCHED) {
            reauthorizer.reauthorizeAfter401("stale-token")
        }
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { reauthorizer.reauthorizeAfter401("stale-token") }
        }

        winner.cancel()
        parked.complete(Unit)

        val failure = waiter.await().exceptionOrNull()
        assertIs<MCPClientError>(
            failure,
            "a waiter that was never cancelled must see a normal failure, not the winner's cancellation",
        )
        client.close()
    }

    @Test
    fun `startAuthorization appends params with ampersand when metadata endpoint already has a query`() {
        val url = McpOAuthFlow.startAuthorization(
            serverUrl = "https://auth.example.com",
            metadata = AuthorizationServerMetadata(
                authorizationEndpoint = "https://login.example.com/oauth/authorize?tenant=acme",
                responseTypesSupported = listOf("code"),
                codeChallengeMethodsSupported = listOf("S256"),
            ),
            clientInformation = OAuthClientInformation {
                clientId("client-1")
            },
            redirectUrl = "https://app.example.com/callback",
            scope = "tools",
            state = "state-1",
            codeVerifier = "verifier-1",
            resource = null,
        )

        assertTrue(url.startsWith("https://login.example.com/oauth/authorize?tenant=acme&"))
        assertTrue("response_type=code" in url)
        assertTrue("client_id=client-1" in url)
        assertEquals(1, Regex("""\?tenant=acme(&|$)""").findAll(url).count())
    }
}
