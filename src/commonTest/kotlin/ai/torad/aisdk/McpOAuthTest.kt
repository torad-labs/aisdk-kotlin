package ai.torad.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpOAuthTest {
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
