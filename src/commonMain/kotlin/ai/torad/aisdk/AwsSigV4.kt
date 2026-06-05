package ai.torad.aisdk

import kotlin.time.Clock

internal data class AwsSigV4Credentials(
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String? = null,
)

internal fun awsSigV4SignedHeaders(
    method: String,
    url: String,
    service: String,
    region: String,
    headers: Map<String, String>,
    body: String,
    credentials: AwsSigV4Credentials,
    amzDate: String = currentAwsAmzDate(),
): Map<String, String> {
    val date = amzDate.substring(0, 8)
    val parsed = parseAwsUrl(url)
    val normalizedHeaders = linkedMapOf<String, String>()
    headers.forEach { (name, value) ->
        if (!name.equals("authorization", ignoreCase = true)) {
            normalizedHeaders[name.lowercase()] = canonicalHeaderValue(value)
        }
    }
    normalizedHeaders["host"] = parsed.host
    normalizedHeaders["x-amz-date"] = amzDate
    credentials.sessionToken?.takeIf { it.isNotBlank() }?.let {
        normalizedHeaders["x-amz-security-token"] = it
    }

    val sortedHeaders = normalizedHeaders.entries.sortedBy { it.key }
    val canonicalHeaders = sortedHeaders.joinToString(separator = "") { (name, value) -> "$name:$value\n" }
    val signedHeaders = sortedHeaders.joinToString(";") { it.key }
    val payloadHash = sha256Hex(body.encodeToByteArray())
    val canonicalRequest = listOf(
        method.uppercase(),
        canonicalAwsPath(parsed.path),
        canonicalAwsQuery(parsed.query),
        canonicalHeaders,
        signedHeaders,
        payloadHash,
    ).joinToString("\n")

    val credentialScope = "$date/$region/$service/aws4_request"
    val stringToSign = listOf(
        "AWS4-HMAC-SHA256",
        amzDate,
        credentialScope,
        sha256Hex(canonicalRequest.encodeToByteArray()),
    ).joinToString("\n")
    val signingKey = awsSigV4SigningKey(credentials.secretAccessKey, date, region, service)
    val signature = hmacSha256(signingKey, stringToSign.encodeToByteArray()).toHex()

    val result = linkedMapOf<String, String>()
    headers.forEach { (name, value) ->
        if (!name.equals("authorization", ignoreCase = true)) result[name] = value
    }
    result["host"] = parsed.host
    result["x-amz-date"] = amzDate
    credentials.sessionToken?.takeIf { it.isNotBlank() }?.let {
        result["x-amz-security-token"] = it
    }
    result["Authorization"] =
        "AWS4-HMAC-SHA256 Credential=${credentials.accessKeyId}/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"
    return result
}

private data class AwsParsedUrl(
    val host: String,
    val path: String,
    val query: String,
)

internal fun currentAwsAmzDate(clock: Clock = Clock.System): String {
    val instant = clock.now().toString()
    val date = instant.substring(0, 10).filter { it != '-' }
    val time = instant.substring(11, 19).filter { it != ':' }
    return "${date}T${time}Z"
}

private fun parseAwsUrl(url: String): AwsParsedUrl {
    val noFragment = url.substringBefore('#')
    val schemeEnd = noFragment.indexOf("://")
    val authorityStart = if (schemeEnd >= 0) schemeEnd + 3 else 0
    val pathStart = noFragment.indexOf('/', authorityStart).takeIf { it >= 0 }
    val queryStart = noFragment.indexOf('?', authorityStart).takeIf { it >= 0 }
    val authorityEnd = listOfNotNull(pathStart, queryStart).minOrNull() ?: noFragment.length
    val authority = noFragment.substring(authorityStart, authorityEnd).substringAfter('@')
    val path = when {
        pathStart == null -> "/"
        queryStart != null && queryStart > pathStart -> noFragment.substring(pathStart, queryStart)
        else -> noFragment.substring(pathStart)
    }.ifBlank { "/" }
    val query = queryStart?.let { noFragment.substring(it + 1) }.orEmpty()
    return AwsParsedUrl(host = authority.lowercase(), path = path, query = query)
}

private fun canonicalAwsPath(path: String): String =
    uriEncodePreservingEscapes(path.ifBlank { "/" }, encodeSlash = false)

private fun canonicalAwsQuery(query: String): String {
    if (query.isBlank()) return ""
    return query.split('&')
        .filter { it.isNotEmpty() }
        .map { parameter ->
            val name = parameter.substringBefore('=')
            val value = parameter.substringAfter('=', "")
            uriEncodePreservingEscapes(name, encodeSlash = true) to uriEncodePreservingEscapes(value, encodeSlash = true)
        }
        .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
        .joinToString("&") { (name, value) -> "$name=$value" }
}

private fun uriEncodePreservingEscapes(value: String, encodeSlash: Boolean): String = buildString {
    var index = 0
    while (index < value.length) {
        val char = value[index]
        when {
            char.isUnreservedAwsChar() -> append(char)
            char == '/' && !encodeSlash -> append('/')
            char == '%' && index + 2 < value.length && value[index + 1].isHexDigit() && value[index + 2].isHexDigit() -> {
                append('%')
                append(value[index + 1].uppercaseChar())
                append(value[index + 2].uppercaseChar())
                index += 2
            }
            else -> {
                char.toString().encodeToByteArray().forEach { byte ->
                    append('%')
                    append(byte.toInt().and(0xff).toString(16).uppercase().padStart(2, '0'))
                }
            }
        }
        index += 1
    }
}

private fun Char.isUnreservedAwsChar(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '-' || this == '_' || this == '.' || this == '~'

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun canonicalHeaderValue(value: String): String =
    value.trim().replace(Regex("\\s+"), " ")

private fun awsSigV4SigningKey(secretAccessKey: String, date: String, region: String, service: String): ByteArray {
    val dateKey = hmacSha256("AWS4$secretAccessKey".encodeToByteArray(), date.encodeToByteArray())
    val dateRegionKey = hmacSha256(dateKey, region.encodeToByteArray())
    val dateRegionServiceKey = hmacSha256(dateRegionKey, service.encodeToByteArray())
    return hmacSha256(dateRegionServiceKey, "aws4_request".encodeToByteArray())
}
