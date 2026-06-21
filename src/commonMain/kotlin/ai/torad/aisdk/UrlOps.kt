package ai.torad.aisdk

public class DownloadError(
    public val url: String,
    message: String,
    public val statusCode: Int? = null,
    public val statusText: String? = null,
    cause: Throwable? = null,
) : AiSdkException(message, cause)

internal object UrlOps {

    fun withoutTrailingSlash(url: String?): String? = url?.removeSuffix("/")

    fun encode(value: String): String =
        buildString {
            value.encodeToByteArray().forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                val char = unsigned.toChar()
                // Only ASCII letters/digits are unreserved. isLetterOrDigit() uses Unicode
                // semantics, so a multibyte UTF-8 byte (0x80-0xFF) maps to a Latin-1 letter and
                // would pass through unencoded — guard on unsigned < 128 so those bytes get %XX.
                if ((unsigned < 128 && char.isLetterOrDigit()) || char in setOf('-', '_', '.', '~')) {
                    append(char)
                } else {
                    append('%')
                    append(unsigned.toString(16).uppercase().padStart(2, '0'))
                }
            }
        }

    fun validateDownload(url: String) {
        val parsed = parseUrl(url) ?: throw DownloadError(url, "Invalid URL: $url")
        if (parsed.scheme == "data") return
        if (parsed.scheme != "http" && parsed.scheme != "https") {
            throw DownloadError(url, "URL scheme must be http, https, or data, got ${parsed.scheme}:")
        }
        if (parsed.hostname.isBlank()) {
            throw DownloadError(url, "URL must have a hostname")
        }
        val host = parsed.hostname.lowercase().trim('[', ']')
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".localhost")) {
            throw DownloadError(url, "URL with hostname ${parsed.hostname} is not allowed")
        }
        if (isIPv4(host) && isPrivateIPv4(host)) {
            throw DownloadError(url, "URL with IP address $host is not allowed")
        }
        if (isPrivateIPv6(host)) {
            throw DownloadError(url, "URL with IPv6 address ${parsed.hostname} is not allowed")
        }
    }

    private data class ParsedUrl(val scheme: String, val hostname: String)

    private fun parseUrl(url: String): ParsedUrl? {
        val match = Regex("^([A-Za-z][A-Za-z0-9+.-]*):(.*)$").find(url) ?: return null
        val scheme = match.groupValues[1].lowercase()
        if (scheme == "data") return ParsedUrl(scheme, "")
        val rest = match.groupValues[2]
        if (!rest.startsWith("//")) return null
        val authority = rest.removePrefix("//").substringBefore('/').substringBefore('?').substringBefore('#')
        // Strip userinfo FIRST, then detect a bracketed IPv6 literal. Doing the bracket check on
        // the whole authority misses `user@[::1]` (it starts with 'u'), and substringBefore(':')
        // would then stop at the first colon INSIDE the brackets, yielding host "[" — which slips
        // past the private-IP / IPv6 SSRF guard in validateDownload.
        val hostPart = authority.substringAfter('@')
        val host = if (hostPart.startsWith("[")) {
            hostPart.substringBefore(']') + "]"
        } else {
            hostPart.substringBefore(':')
        }
        return ParsedUrl(scheme, host)
    }

    private fun isIPv4(hostname: String): Boolean {
        val parts = hostname.split('.')
        return parts.size == 4 && parts.all { part ->
            val number = part.toIntOrNull()
            number != null && number in 0..255 && number.toString() == part
        }
    }

    private fun isPrivateIPv4(ip: String): Boolean {
        val parts = ip.split('.').map { it.toInt() }
        val first = parts[0]
        val second = parts[1]
        return first == 0 ||
            first == 10 ||
            first == 127 ||
            (first == 169 && second == 254) ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
    }

    private fun isPrivateIPv6(ip: String): Boolean {
        val normalized = ip.lowercase()
        if (normalized == "::1" || normalized == "::") return true
        if (normalized.startsWith("fc") || normalized.startsWith("fd")) return true
        if (normalized.startsWith("fe80")) return true
        if (normalized.startsWith("::ffff:")) {
            val mapped = normalized.removePrefix("::ffff:")
            if (isIPv4(mapped)) return isPrivateIPv4(mapped)
        }
        return false
    }
}
