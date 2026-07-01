@file:Suppress("FunctionName", "PropertyName")

package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
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
@Poko
/** @since 0.3.0-beta01 */
public class OAuthTokens(
    @SerialName("access_token") public val accessToken: String,
    @SerialName("token_type") public val tokenType: String,
    @SerialName("id_token") public val idToken: String? = null,
    @SerialName("expires_in") public val expiresIn: Long? = null,
    /** @since 0.3.0-beta01 */
    public val scope: String? = null,
    @SerialName("refresh_token") public val refreshToken: String? = null,
)

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class OAuthClientInformation internal constructor(
    @SerialName("client_id") public val clientId: String,
    @SerialName("client_secret") public val clientSecret: String? = null,
    @SerialName("client_id_issued_at") public val clientIdIssuedAt: Long? = null,
    @SerialName("client_secret_expires_at") public val clientSecretExpiresAt: Long? = null,
)

/** @since 0.3.0-beta01 */
public class OAuthClientInformationBuilder {
    private var clientId: String? = null
    private var clientSecret: String? = null
    private var clientIdIssuedAt: Long? = null
    private var clientSecretExpiresAt: Long? = null

    /** @since 0.3.0-beta01 */
    public fun clientId(value: String): OAuthClientInformationBuilder {
        clientId = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun clientSecret(value: String?): OAuthClientInformationBuilder {
        clientSecret = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun clientIdIssuedAt(value: Long?): OAuthClientInformationBuilder {
        clientIdIssuedAt = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun clientSecretExpiresAt(value: Long?): OAuthClientInformationBuilder {
        clientSecretExpiresAt = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): OAuthClientInformation =
        OAuthClientInformation(
            clientId = requireNotNull(clientId) { "OAuthClientInformation.clientId is required" },
            clientSecret = clientSecret,
            clientIdIssuedAt = clientIdIssuedAt,
            clientSecretExpiresAt = clientSecretExpiresAt,
        )
}

/** @since 0.3.0-beta01 */
public fun OAuthClientInformation(
    block: OAuthClientInformationBuilder.() -> Unit = {},
): OAuthClientInformation =
    OAuthClientInformationBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class OAuthClientMetadata internal constructor(
    @SerialName("redirect_uris") public val redirectUris: List<String>,
    @SerialName("token_endpoint_auth_method") public val tokenEndpointAuthMethod: String? = null,
    @SerialName("grant_types") public val grantTypes: List<String>? = null,
    @SerialName("response_types") public val responseTypes: List<String>? = null,
    @SerialName("client_name") public val clientName: String? = null,
    @SerialName("client_uri") public val clientUri: String? = null,
    @SerialName("logo_uri") public val logoUri: String? = null,
    /** @since 0.3.0-beta01 */
    public val scope: String? = null,
    /** @since 0.3.0-beta01 */
    public val contacts: List<String>? = null,
    @SerialName("tos_uri") public val tosUri: String? = null,
    @SerialName("policy_uri") public val policyUri: String? = null,
    @SerialName("jwks_uri") public val jwksUri: String? = null,
    /** @since 0.3.0-beta01 */
    public val jwks: JsonElement? = null,
    @SerialName("software_id") public val softwareId: String? = null,
    @SerialName("software_version") public val softwareVersion: String? = null,
    @SerialName("software_statement") public val softwareStatement: String? = null,
)

/** @since 0.3.0-beta01 */
public class OAuthClientMetadataBuilder {
    private var redirectUris: List<String>? = null
    private var tokenEndpointAuthMethod: String? = null
    private var grantTypes: List<String>? = null
    private var responseTypes: List<String>? = null
    private var clientName: String? = null
    private var clientUri: String? = null
    private var logoUri: String? = null
    private var scope: String? = null
    private var contacts: List<String>? = null
    private var tosUri: String? = null
    private var policyUri: String? = null
    private var jwksUri: String? = null
    private var jwks: JsonElement? = null
    private var softwareId: String? = null
    private var softwareVersion: String? = null
    private var softwareStatement: String? = null

    /** @since 0.3.0-beta01 */
    public fun redirectUris(value: List<String>): OAuthClientMetadataBuilder {
        redirectUris = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun tokenEndpointAuthMethod(value: String?): OAuthClientMetadataBuilder {
        tokenEndpointAuthMethod = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun grantTypes(value: List<String>?): OAuthClientMetadataBuilder {
        grantTypes = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun responseTypes(value: List<String>?): OAuthClientMetadataBuilder {
        responseTypes = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun clientName(value: String?): OAuthClientMetadataBuilder {
        clientName = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun clientUri(value: String?): OAuthClientMetadataBuilder {
        clientUri = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun logoUri(value: String?): OAuthClientMetadataBuilder {
        logoUri = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun scope(value: String?): OAuthClientMetadataBuilder {
        scope = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun contacts(value: List<String>?): OAuthClientMetadataBuilder {
        contacts = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun tosUri(value: String?): OAuthClientMetadataBuilder {
        tosUri = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun policyUri(value: String?): OAuthClientMetadataBuilder {
        policyUri = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun jwksUri(value: String?): OAuthClientMetadataBuilder {
        jwksUri = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun jwks(value: JsonElement?): OAuthClientMetadataBuilder {
        jwks = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun softwareId(value: String?): OAuthClientMetadataBuilder {
        softwareId = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun softwareVersion(value: String?): OAuthClientMetadataBuilder {
        softwareVersion = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun softwareStatement(value: String?): OAuthClientMetadataBuilder {
        softwareStatement = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): OAuthClientMetadata =
        OAuthClientMetadata(
            redirectUris = requireNotNull(redirectUris) { "OAuthClientMetadata.redirectUris is required" },
            tokenEndpointAuthMethod = tokenEndpointAuthMethod,
            grantTypes = grantTypes,
            responseTypes = responseTypes,
            clientName = clientName,
            clientUri = clientUri,
            logoUri = logoUri,
            scope = scope,
            contacts = contacts,
            tosUri = tosUri,
            policyUri = policyUri,
            jwksUri = jwksUri,
            jwks = jwks,
            softwareId = softwareId,
            softwareVersion = softwareVersion,
            softwareStatement = softwareStatement,
        )
}

/** @since 0.3.0-beta01 */
public fun OAuthClientMetadata(
    block: OAuthClientMetadataBuilder.() -> Unit = {},
): OAuthClientMetadata =
    OAuthClientMetadataBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class AuthorizationServerMetadata(
    /** @since 0.3.0-beta01 */
    public val issuer: String? = null,
    @SerialName("authorization_endpoint") public val authorizationEndpoint: String? = null,
    @SerialName("token_endpoint") public val tokenEndpoint: String? = null,
    @SerialName("registration_endpoint") public val registrationEndpoint: String? = null,
    @SerialName("response_types_supported") public val responseTypesSupported: List<String>? = null,
    @SerialName("grant_types_supported") public val grantTypesSupported: List<String>? = null,
    @SerialName("token_endpoint_auth_methods_supported") public val tokenEndpointAuthMethodsSupported: List<String>? = null,
    @SerialName("code_challenge_methods_supported") public val codeChallengeMethodsSupported: List<String>? = null,
)

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class OAuthProtectedResourceMetadata(
    /** @since 0.3.0-beta01 */
    public val resource: String,
    @SerialName("authorization_servers") public val authorizationServers: List<String>? = null,
    @SerialName("jwks_uri") public val jwksUri: String? = null,
    @SerialName("scopes_supported") public val scopesSupported: List<String>? = null,
    @SerialName("bearer_methods_supported") public val bearerMethodsSupported: List<String>? = null,
    @SerialName("resource_name") public val resourceName: String? = null,
)

/** @since 0.3.0-beta01 */
public class UnauthorizedError(message: String = "Unauthorized") : AiSdkException(message)

/** @since 0.3.0-beta01 */
public enum class AuthResult {
    AUTHORIZED,
    REDIRECT,
}

/** @since 0.3.0-beta01 */
public class AuthOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val serverUrl: String,
    /** @since 0.3.0-beta01 */
    public val authorizationCode: String? = null,
    /** @since 0.3.0-beta01 */
    public val callbackState: String? = null,
    /** @since 0.3.0-beta01 */
    public val scope: String? = null,
    /** @since 0.3.0-beta01 */
    public val resourceMetadataUrl: String? = null,
    /** @since 0.3.0-beta01 */
    public val client: HttpClient? = null,
)

/** @since 0.3.0-beta01 */
public class AuthOptionsBuilder {
    private var serverUrl: String? = null
    private var authorizationCode: String? = null
    private var callbackState: String? = null
    private var scope: String? = null
    private var resourceMetadataUrl: String? = null
    private var client: HttpClient? = null

    /** @since 0.3.0-beta01 */
    public fun serverUrl(value: String): AuthOptionsBuilder {
        serverUrl = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun authorizationCode(value: String?): AuthOptionsBuilder {
        authorizationCode = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun callbackState(value: String?): AuthOptionsBuilder {
        callbackState = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun scope(value: String?): AuthOptionsBuilder {
        scope = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun resourceMetadataUrl(value: String?): AuthOptionsBuilder {
        resourceMetadataUrl = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun client(value: HttpClient?): AuthOptionsBuilder {
        client = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): AuthOptions =
        AuthOptions(
            serverUrl = requireNotNull(serverUrl) { "AuthOptions.serverUrl is required" },
            authorizationCode = authorizationCode,
            callbackState = callbackState,
            scope = scope,
            resourceMetadataUrl = resourceMetadataUrl,
            client = client,
        )
}

/** @since 0.3.0-beta01 */
public fun AuthOptions(
    block: AuthOptionsBuilder.() -> Unit = {},
): AuthOptions =
    AuthOptionsBuilder().apply(block).build()

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
 * (exchange / refresh). The orchestration entry point `auth` lives alongside the
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
            val text = with(HttpTransport) { response.bodyAsTextCapped(url) }
            return mcpJson.decodeFromString<AuthorizationServerMetadata>(text)
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
            val text = with(HttpTransport) { response.bodyAsTextCapped(url) }
            return mcpJson.decodeFromString<OAuthProtectedResourceMetadata>(text)
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
        val responseText = with(HttpTransport) { response.bodyAsTextCapped(registrationUrl) }
        if (response.status.value !in 200..299) {
            throw MCPClientError("OAuth client registration failed (${response.status.value}): $responseText")
        }
        return mcpJson.decodeFromString(responseText)
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
            tokenUrl,
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
            tokenUrl,
            metadata,
            clientInformation,
            addClientAuthentication
        )
        val tokens = postOAuthTokenRequest(client, tokenUrl, headers, params)
        return if (tokens.refreshToken.isNullOrBlank()) {
            OAuthTokens(
                accessToken = tokens.accessToken,
                tokenType = tokens.tokenType,
                idToken = tokens.idToken,
                expiresIn = tokens.expiresIn,
                scope = tokens.scope,
                refreshToken = refreshToken,
            )
        } else {
            tokens
        }
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
        val separator = when {
            '?' !in authorizationEndpoint -> "?"
            authorizationEndpoint.endsWith("?") || authorizationEndpoint.endsWith("&") -> ""
            else -> "&"
        }
        return "$authorizationEndpoint$separator${params.joinToString("&")}"
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
        val responseText = with(HttpTransport) { response.bodyAsTextCapped(tokenUrl) }
        if (response.status.value !in 200..299) {
            throw MCPClientError("OAuth token request failed (${response.status.value}): $responseText")
        }
        return mcpJson.decodeFromString(responseText)
    }

    private fun Map<String, String>.oauthFormBody(): String =
        entries.joinToString("&") { (key, value) -> "${urlComponent(key)}=${urlComponent(value)}" }
}
