package ai.torad.aisdk

/** @since 0.3.0-beta01 */
public class DownloadError(
    /** @since 0.3.0-beta01 */
    public val url: String,
    message: String,
    /** @since 0.3.0-beta01 */
    public val statusCode: Int? = null,
    /** @since 0.3.0-beta01 */
    public val statusText: String? = null,
    cause: Throwable? = null,
) : AiSdkException(message, cause)

internal object UrlOps {

    private const val IPV4_PARTS = 4
    private const val OCTET_BITS = 8
    private const val OCTET_MASK = 0xff
    private const val MAX_OCTET = 255
    private const val HEX_RADIX = 16
    private const val MAX_IPV6_GROUP = 0xffff

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
        val ipv4 = normalizeIPv4(host)
        if (ipv4 != null && isPrivateIPv4(ipv4)) {
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

    // Resolvers accept the BSD shorthand IPv4 spellings `d`, `d.d` and `d.d.d`, where the LAST part
    // fills the remaining low-order bytes — `127.1` and `2130706433` both resolve to 127.0.0.1. The
    // private-range check therefore has to run on the expanded dotted quad, not on the literal as
    // written, or those spellings walk straight past the guard. Returns null for anything that is not
    // a numeric IPv4 literal (a normal hostname).
    private fun normalizeIPv4(hostname: String): String? {
        val parts = hostname.split('.')
        val numbers = parts.mapNotNull(::decimalOrNull)
        if (parts.size > IPV4_PARTS || numbers.size != parts.size) return null
        val leading = numbers.dropLast(1)
        val tailBytes = IPV4_PARTS - leading.size
        val tail = numbers.last()
        return if (leading.any { it > MAX_OCTET } || tail >= (1L shl (OCTET_BITS * tailBytes))) {
            null
        } else {
            val expanded = leading.map { it.toInt() } +
                (tailBytes - 1 downTo 0).map { byte -> (tail shr (OCTET_BITS * byte)).toInt() and OCTET_MASK }
            expanded.joinToString(".")
        }
    }

    private fun decimalOrNull(part: String): Long? =
        part.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }?.toLongOrNull()

    // `::ffff:7f00:1` is the pure-hex spelling of the IPv4-mapped `::ffff:127.0.0.1`.
    private fun hexMappedIPv4(mapped: String): String? {
        val groups = mapped.split(':')
        if (groups.size != 2) return null
        val high = hexGroupOrNull(groups[0])
        val low = hexGroupOrNull(groups[1])
        return if (high == null || low == null) {
            null
        } else {
            "${high shr OCTET_BITS}.${high and OCTET_MASK}.${low shr OCTET_BITS}.${low and OCTET_MASK}"
        }
    }

    private fun hexGroupOrNull(group: String): Int? =
        group.toIntOrNull(radix = HEX_RADIX)?.takeIf { it in 0..MAX_IPV6_GROUP }

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
            normalizeIPv4(mapped)?.let { return isPrivateIPv4(it) }
            hexMappedIPv4(mapped)?.let { return isPrivateIPv4(it) }
        }
        return false
    }
}
