package ai.torad.aisdk

internal object ProviderHeaders {

    fun combine(vararg headers: Map<String, String?>?): Map<String, String?> =
        headers.fold(linkedMapOf()) { acc, current ->
            current?.let { acc.putAll(it) }
            acc
        }

    fun normalize(headers: Map<String, String?>?): Map<String, String> =
        headers.orEmpty()
            .filterValues { it != null }
            .mapKeys { it.key.lowercase() }
            .mapValues { it.value.orEmpty() }

    fun withUserAgentSuffix(
        headers: Map<String, String?>?,
        vararg userAgentSuffixParts: String,
    ): Map<String, String> {
        val normalized = normalize(headers).toMutableMap()
        val suffix = userAgentSuffixParts.filter { it.isNotBlank() }
        normalized["user-agent"] = (listOfNotNull(normalized["user-agent"]?.takeIf { it.isNotBlank() }) + suffix)
            .joinToString(" ")
        return normalized
    }

    fun build(
        settingsHeaders: Map<String, String>,
        callHeaders: Map<String, String>,
        userAgentSuffix: String?,
        auth: (MutableMap<String, String?>) -> Unit,
    ): Map<String, String> {
        val base = linkedMapOf<String, String?>()
        auth(base)
        base.putAll(settingsHeaders)
        base.putAll(callHeaders)
        return userAgentSuffix
            ?.let { ProviderHeaders.withUserAgentSuffix(base, it) }
            ?: normalize(base)
    }

    fun prepare(
        defaultHeaders: Map<String, String> = emptyMap(),
        headers: Map<String, String>? = null,
    ): Map<String, String> = defaultHeaders + (headers ?: emptyMap())
}
