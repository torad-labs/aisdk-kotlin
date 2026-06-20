@file:Suppress("FunctionName", "PropertyName")

package ai.torad.aisdk

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement

@Serializable
public data class OAuthTokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("id_token") val idToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val scope: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
)

@Serializable
public data class OAuthClientInformation(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String? = null,
    @SerialName("client_id_issued_at") val clientIdIssuedAt: Long? = null,
    @SerialName("client_secret_expires_at") val clientSecretExpiresAt: Long? = null,
)

@Serializable
public data class OAuthClientMetadata(
    @SerialName("redirect_uris") val redirectUris: List<String>,
    @SerialName("token_endpoint_auth_method") val tokenEndpointAuthMethod: String? = null,
    @SerialName("grant_types") val grantTypes: List<String>? = null,
    @SerialName("response_types") val responseTypes: List<String>? = null,
    @SerialName("client_name") val clientName: String? = null,
    @SerialName("client_uri") val clientUri: String? = null,
    @SerialName("logo_uri") val logoUri: String? = null,
    val scope: String? = null,
    val contacts: List<String>? = null,
    @SerialName("tos_uri") val tosUri: String? = null,
    @SerialName("policy_uri") val policyUri: String? = null,
    @SerialName("jwks_uri") val jwksUri: String? = null,
    val jwks: JsonElement? = null,
    @SerialName("software_id") val softwareId: String? = null,
    @SerialName("software_version") val softwareVersion: String? = null,
    @SerialName("software_statement") val softwareStatement: String? = null,
)

@Serializable
public data class AuthorizationServerMetadata(
    val issuer: String? = null,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String? = null,
    @SerialName("token_endpoint") val tokenEndpoint: String? = null,
    @SerialName("registration_endpoint") val registrationEndpoint: String? = null,
    @SerialName("response_types_supported") val responseTypesSupported: List<String>? = null,
    @SerialName("grant_types_supported") val grantTypesSupported: List<String>? = null,
    @SerialName("token_endpoint_auth_methods_supported") val tokenEndpointAuthMethodsSupported: List<String>? = null,
    @SerialName("code_challenge_methods_supported") val codeChallengeMethodsSupported: List<String>? = null,
)

@Serializable
public data class OAuthProtectedResourceMetadata(
    val resource: String,
    @SerialName("authorization_servers") val authorizationServers: List<String>? = null,
    @SerialName("jwks_uri") val jwksUri: String? = null,
    @SerialName("scopes_supported") val scopesSupported: List<String>? = null,
    @SerialName("bearer_methods_supported") val bearerMethodsSupported: List<String>? = null,
    @SerialName("resource_name") val resourceName: String? = null,
)

public class UnauthorizedError(message: String = "Unauthorized") : AiSdkException(message)

public enum class AuthResult {
    AUTHORIZED,
    REDIRECT,
}

public data class AuthOptions(
    val serverUrl: String,
    val authorizationCode: String? = null,
    val callbackState: String? = null,
    val scope: String? = null,
    val resourceMetadataUrl: String? = null,
    val client: HttpClient? = null,
)

/** Resource-URL canonicalization for OAuth 2.0 Protected Resource validation. */
internal object McpResourceUrl {
    fun fromServerUrl(serverUrl: String): String {
        val noFragment = serverUrl.substringBefore('#')
        return stripSlash(noFragment)
    }

    fun stripSlash(resource: String): String =
        if (resource.endsWith("/") && resource.removeSuffix("/").count { it == '/' } == 2) {
            resource.removeSuffix("/")
        } else {
            resource
        }

    fun checkAllowed(requestedResource: String, configuredResource: String): Boolean {
        val requested = parse(requestedResource) ?: return false
        val configured = parse(configuredResource) ?: return false
        if (requested.origin != configured.origin) return false
        val requestedPath = requested.path.ensureTrailingSlash()
        val configuredPath = configured.path.ensureTrailingSlash()
        return requestedPath.startsWith(configuredPath)
    }

    private data class Parsed(val origin: String, val path: String)

    private fun parse(value: String): Parsed? {
        val withoutFragment = value.substringBefore('#')
        val schemeEnd = withoutFragment.indexOf("://")
        if (schemeEnd <= 0) return null
        val authorityStart = schemeEnd + 3
        val pathStart = withoutFragment.indexOf('/', authorityStart)
        val origin = if (pathStart == -1) withoutFragment else withoutFragment.substring(0, pathStart)
        val rawPath = if (pathStart == -1) {
            "/"
        } else {
            withoutFragment.substring(pathStart).substringBefore('?').ifBlank { "/" }
        }
        return Parsed(origin = origin, path = rawPath)
    }

    private fun String.ensureTrailingSlash(): String =
        if (endsWith('/')) this else "$this/"
}

/**
 * MCP OAuth 2.0 authorization-code + PKCE flow: metadata discovery, dynamic
 * client registration, the authorization-URL builder, and the token endpoint
 * (exchange / refresh). The orchestration entry point [auth] lives alongside the
 * transports; these are the round-trips it drives.
 */
internal object McpOAuthFlow {
    suspend fun discoverAuthorizationServerMetadata(
        client: HttpClient,
        authorizationServerUrl: String,
    ): AuthorizationServerMetadata? {
        val urls = authorizationDiscoveryUrls(authorizationServerUrl)
        for (url in urls) {
            val response = client.request(url) {
                method = HttpMethod.Get
                header("MCP-Protocol-Version", LATEST_PROTOCOL_VERSION)
            }
            if (response.status.value in 400..499) continue
            if (response.status.value !in 200..299) {
                throw MCPClientError("HTTP ${response.status.value} trying to load OAuth metadata from $url")
            }
            return mcpJson.decodeFromString<AuthorizationServerMetadata>(response.bodyAsText())
        }
        return null
    }

    suspend fun discoverOAuthProtectedResourceMetadata(
        client: HttpClient,
        serverUrl: String,
        resourceMetadataUrl: String? = null,
    ): OAuthProtectedResourceMetadata {
        val urls = protectedResourceDiscoveryUrls(serverUrl, resourceMetadataUrl)
        for (url in urls) {
            val response = client.request(url) {
                method = HttpMethod.Get
                header("MCP-Protocol-Version", LATEST_PROTOCOL_VERSION)
            }
            if (response.status.value == 404) continue
            if (response.status.value in 400..499 && resourceMetadataUrl == null) continue
            if (response.status.value !in 200..299) {
                throw MCPClientError(
                    "HTTP ${response.status.value} trying to load well-known OAuth protected resource metadata."
                )
            }
            return mcpJson.decodeFromString<OAuthProtectedResourceMetadata>(response.bodyAsText())
        }
        throw MCPClientError("Resource server does not implement OAuth 2.0 Protected Resource Metadata.")
    }

    suspend fun registerClient(
        client: HttpClient,
        authorizationServerUrl: String,
        metadata: AuthorizationServerMetadata?,
        clientMetadata: OAuthClientMetadata,
    ): OAuthClientInformation {
        val registrationUrl = metadata?.registrationEndpoint ?: "${authorizationServerUrl.trimEnd('/')}/register"
        val response = client.request(registrationUrl) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(mcpJson.encodeToString(clientMetadata))
        }
        if (response.status.value !in 200..299) {
            throw MCPClientError("OAuth client registration failed (${response.status.value}): ${response.bodyAsText()}")
        }
        return mcpJson.decodeFromString(response.bodyAsText())
    }

    suspend fun exchangeAuthorization(
        client: HttpClient,
        authorizationServerUrl: String,
        metadata: AuthorizationServerMetadata?,
        clientInformation: OAuthClientInformation,
        authorizationCode: String,
        codeVerifier: String,
        redirectUri: String,
        resource: String? = null,
        addClientAuthentication: suspend (
            MutableMap<String, String>,
            MutableMap<String, String>,
            String,
            AuthorizationServerMetadata?,
        ) -> Unit = { _, _, _, _ -> },
    ): OAuthTokens {
        val grantType = "authorization_code"
        if (metadata?.grantTypesSupported != null && grantType !in metadata.grantTypesSupported) {
            throw MCPClientError("Incompatible auth server: does not support grant type $grantType")
        }
        val tokenUrl = metadata?.tokenEndpoint ?: "${authorizationServerUrl.trimEnd('/')}/token"
        val headers = linkedMapOf(
            HttpHeaders.ContentType to "application/x-www-form-urlencoded",
            HttpHeaders.Accept to "application/json",
        )
        val params = linkedMapOf(
            "grant_type" to grantType,
            "code" to authorizationCode,
            "code_verifier" to codeVerifier,
            "redirect_uri" to redirectUri,
        )
        resource?.let { params["resource"] = it }
        applyOAuthClientAuthentication(
            headers,
            params,
            authorizationServerUrl,
            metadata,
            clientInformation,
            addClientAuthentication
        )
        return postOAuthTokenRequest(client, tokenUrl, headers, params)
    }

    suspend fun refreshAuthorization(
        client: HttpClient,
        authorizationServerUrl: String,
        metadata: AuthorizationServerMetadata?,
        clientInformation: OAuthClientInformation,
        refreshToken: String,
        resource: String? = null,
        addClientAuthentication: suspend (
            MutableMap<String, String>,
            MutableMap<String, String>,
            String,
            AuthorizationServerMetadata?,
        ) -> Unit = { _, _, _, _ -> },
    ): OAuthTokens {
        val grantType = "refresh_token"
        if (metadata?.grantTypesSupported != null && grantType !in metadata.grantTypesSupported) {
            throw MCPClientError("Incompatible auth server: does not support grant type $grantType")
        }
        val tokenUrl = metadata?.tokenEndpoint ?: "${authorizationServerUrl.trimEnd('/')}/token"
        val headers = linkedMapOf(
            HttpHeaders.ContentType to "application/x-www-form-urlencoded",
            HttpHeaders.Accept to "application/json",
        )
        val params = linkedMapOf(
            "grant_type" to grantType,
            "refresh_token" to refreshToken,
        )
        resource?.let { params["resource"] = it }
        applyOAuthClientAuthentication(
            headers,
            params,
            authorizationServerUrl,
            metadata,
            clientInformation,
            addClientAuthentication
        )
        val tokens = postOAuthTokenRequest(client, tokenUrl, headers, params)
        return if (tokens.refreshToken.isNullOrBlank()) tokens.copy(refreshToken = refreshToken) else tokens
    }

    fun startAuthorization(
        serverUrl: String,
        metadata: AuthorizationServerMetadata?,
        clientInformation: OAuthClientInformation,
        redirectUrl: String,
        scope: String?,
        state: String,
        codeVerifier: String,
        resource: String?,
    ): String {
        val responseType = "code"
        val codeChallengeMethod = "S256"
        val authorizationEndpoint = if (metadata != null) {
            if (metadata.responseTypesSupported != null && responseType !in metadata.responseTypesSupported) {
                throw MCPClientError("Incompatible auth server: does not support response type $responseType")
            }
            if (metadata.codeChallengeMethodsSupported != null && codeChallengeMethod !in metadata.codeChallengeMethodsSupported) {
                throw MCPClientError(
                    "Incompatible auth server: does not support code challenge method $codeChallengeMethod"
                )
            }
            metadata.authorizationEndpoint ?: throw MCPClientError("OAuth metadata is missing authorization_endpoint.")
        } else {
            serverUrl.trimEnd('/') + "/authorize"
        }
        val codeChallenge = pkceS256Challenge(codeVerifier)
        val params = listOfNotNull(
            "response_type=$responseType",
            "client_id=${urlComponent(clientInformation.clientId)}",
            "code_challenge=${urlComponent(codeChallenge)}",
            "code_challenge_method=$codeChallengeMethod",
            "redirect_uri=${urlComponent(redirectUrl)}",
            scope?.let { "scope=${urlComponent(it)}" },
            "state=${urlComponent(state)}",
            if (scope?.split(' ')?.contains("offline_access") == true) "prompt=consent" else null,
            resource?.let { "resource=${urlComponent(it)}" },
        )
        return "$authorizationEndpoint?${params.joinToString("&")}"
    }

    private fun urlComponent(value: String): String = buildString {
        for (char in value) {
            when (char) {
                ' ' -> append("%20")
                ':' -> append("%3A")
                '/' -> append("%2F")
                '?' -> append("%3F")
                '&' -> append("%26")
                '=' -> append("%3D")
                '+' -> append("%2B")
                '#' -> append("%23")
                '%' -> append("%25")
                else -> append(char)
            }
        }
    }

    private fun pkceS256Challenge(codeVerifier: String): String =
        Base64Codec.encode(McpSha256.hash(codeVerifier.encodeToByteArray()))
            .replace('+', '-')
            .replace('/', '_')
            .trimEnd('=')

    private fun authorizationDiscoveryUrls(authorizationServerUrl: String): List<String> {
        val trimmed = authorizationServerUrl.trimEnd('/')
        val origin = McpUrl.origin(trimmed)
        val path = trimmed.removePrefix(origin).ifBlank { "/" }.trimEnd('/')
        return if (path == "/") {
            listOf(
                "$origin/.well-known/oauth-authorization-server",
                "$origin/.well-known/openid-configuration",
            )
        } else {
            listOf(
                "$origin/.well-known/oauth-authorization-server$path",
                "$origin/.well-known/oauth-authorization-server",
                "$origin/.well-known/openid-configuration$path",
                "$trimmed/.well-known/openid-configuration",
            )
        }
    }

    private fun protectedResourceDiscoveryUrls(serverUrl: String, resourceMetadataUrl: String?): List<String> {
        if (resourceMetadataUrl != null) return listOf(resourceMetadataUrl)
        val trimmed = serverUrl.trimEnd('/')
        val origin = McpUrl.origin(trimmed)
        val path = trimmed.removePrefix(origin).ifBlank { "/" }.trimEnd('/')
        return if (path == "/") {
            listOf("$origin/.well-known/oauth-protected-resource")
        } else {
            listOf(
                "$origin/.well-known/oauth-protected-resource$path",
                "$origin/.well-known/oauth-protected-resource",
            )
        }
    }

    private suspend fun applyOAuthClientAuthentication(
        headers: MutableMap<String, String>,
        params: MutableMap<String, String>,
        tokenUrl: String,
        metadata: AuthorizationServerMetadata?,
        clientInformation: OAuthClientInformation,
        addClientAuthentication: suspend (
            MutableMap<String, String>,
            MutableMap<String, String>,
            String,
            AuthorizationServerMetadata?,
        ) -> Unit,
    ) {
        addClientAuthentication(headers, params, tokenUrl, metadata)
        if ("client_id" in params || headers.keys.any { it.equals(HttpHeaders.Authorization, ignoreCase = true) }) {
            return
        }
        when (selectOAuthClientAuthMethod(clientInformation, metadata?.tokenEndpointAuthMethodsSupported.orEmpty())) {
            "client_secret_basic" -> {
                val secret = clientInformation.clientSecret
                    ?: throw MCPClientError("client_secret_basic authentication requires a client_secret")
                headers[HttpHeaders.Authorization] = "Basic ${Base64Codec.encode("${clientInformation.clientId}:$secret".encodeToByteArray())}"
            }
            "client_secret_post" -> {
                params["client_id"] = clientInformation.clientId
                clientInformation.clientSecret?.let { params["client_secret"] = it }
            }
            else -> params["client_id"] = clientInformation.clientId
        }
    }

    private fun selectOAuthClientAuthMethod(
        clientInformation: OAuthClientInformation,
        supportedMethods: List<String>,
    ): String {
        val hasSecret = !clientInformation.clientSecret.isNullOrBlank()
        if (supportedMethods.isEmpty()) return if (hasSecret) "client_secret_post" else "none"
        if (hasSecret && "client_secret_basic" in supportedMethods) return "client_secret_basic"
        if (hasSecret && "client_secret_post" in supportedMethods) return "client_secret_post"
        return if ("none" in supportedMethods) "none" else if (hasSecret) "client_secret_post" else "none"
    }

    private suspend fun postOAuthTokenRequest(
        client: HttpClient,
        tokenUrl: String,
        headers: Map<String, String>,
        params: Map<String, String>,
    ): OAuthTokens {
        val response = client.request(tokenUrl) {
            method = HttpMethod.Post
            headers.forEach { (name, value) -> header(name, value) }
            setBody(params.oauthFormBody())
        }
        if (response.status.value !in 200..299) {
            throw MCPClientError("OAuth token request failed (${response.status.value}): ${response.bodyAsText()}")
        }
        return mcpJson.decodeFromString(response.bodyAsText())
    }

    private fun Map<String, String>.oauthFormBody(): String =
        entries.joinToString("&") { (key, value) -> "${urlComponent(key)}=${urlComponent(value)}" }
}
