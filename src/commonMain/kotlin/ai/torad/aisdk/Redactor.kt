package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** @since 0.3.0-beta01 */
public interface Redactor {
    /** @since 0.3.0-beta01 */
    public fun redactText(value: String): String

    /** @since 0.3.0-beta01 */
    public fun redactHeaders(headers: Map<String, String>): Map<String, String>

    /** @since 0.3.0-beta01 */
    public fun redactJson(value: JsonElement): JsonElement
}

@Poko
/** @since 0.3.0-beta01 */
public class RedactionOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val replacement: String = "[REDACTED]",
    /** @since 0.3.0-beta01 */
    public val maxStringLength: Int = 256,
    /** @since 0.3.0-beta01 */
    public val minBase64Length: Int = 64,
)

/** @since 0.3.0-beta01 */
public class RedactionOptionsBuilder {
    private var replacement: String = "[REDACTED]"
    private var maxStringLength: Int = 256
    private var minBase64Length: Int = 64

    /** @since 0.3.0-beta01 */
    public fun replacement(value: String): RedactionOptionsBuilder {
        replacement = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun maxStringLength(value: Int): RedactionOptionsBuilder {
        maxStringLength = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun minBase64Length(value: Int): RedactionOptionsBuilder {
        minBase64Length = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): RedactionOptions =
        RedactionOptions(
            replacement = replacement,
            maxStringLength = maxStringLength,
            minBase64Length = minBase64Length,
        )
}

/** @since 0.3.0-beta01 */
public fun RedactionOptions(
    block: RedactionOptionsBuilder.() -> Unit = {},
): RedactionOptions =
    RedactionOptionsBuilder().apply(block).build()

private val TOKEN_PATTERNS: List<Regex> = listOf(
    Regex("\\b(Bearer|Basic)\\s+[-._~+/=A-Za-z0-9]+", RegexOption.IGNORE_CASE),
    Regex("\\b(api[-_ ]?key|token|secret)\\s*[:=]\\s*[-._~+/=A-Za-z0-9]+", RegexOption.IGNORE_CASE),
)

/** @since 0.3.0-beta01 */
public class DefaultRedactor(
    private val options: RedactionOptions = RedactionOptions {},
) : Redactor {
    override fun redactText(value: String): String {
        val tokenRedacted = TOKEN_PATTERNS.fold(value) { current, pattern ->
            pattern.replace(current) { match ->
                val prefix = match.groups[1]?.value.orEmpty()
                if (prefix.isBlank()) options.replacement else "$prefix ${options.replacement}"
            }
        }
        return when {
            tokenRedacted.length > options.maxStringLength ->
                "${options.replacement}(length=${tokenRedacted.length})"
            with(RedactionPredicates) { tokenRedacted.isLikelyBase64(options.minBase64Length) } ->
                "${options.replacement}(base64,length=${tokenRedacted.length})"
            else -> tokenRedacted
        }
    }

    override fun redactHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (name, value) ->
            if (with(RedactionPredicates) { name.isSensitiveKey() }) options.replacement else redactText(value)
        }

    override fun redactJson(value: JsonElement): JsonElement = when (value) {
        JsonNull -> JsonNull
        is JsonArray -> JsonArray(value.map(::redactJson))
        is JsonObject -> JsonObject(
            value.mapValues { (name, element) ->
                when {
                    with(RedactionPredicates) { name.isSensitiveKey() } -> JsonPrimitive(options.replacement)
                    with(RedactionPredicates) { name.isPayloadKey() } -> redactPayloadElement(element)
                    else -> redactJson(element)
                }
            },
        )
        is JsonPrimitive -> {
            val content = value.contentOrNull
            if (value.isString && content != null) JsonPrimitive(redactText(content)) else value
        }
    }

    private fun redactPayloadElement(value: JsonElement): JsonElement {
        val content = (value as? JsonPrimitive)?.contentOrNull
        return if (
            content != null &&
            (
                content.length > options.maxStringLength ||
                    with(RedactionPredicates) { content.isLikelyBase64(options.minBase64Length) }
                )
        ) {
            JsonPrimitive("${options.replacement}(payload,length=${content.length})")
        } else {
            redactJson(value)
        }
    }
}

/** @since 0.3.0-beta01 */
public val AiSdkDefaultRedactor: Redactor = DefaultRedactor()

internal object RedactionPredicates {
    fun String.isSensitiveKey(): Boolean {
        val normalized = replace(CAMEL_CASE_BOUNDARY, "$1-$2")
            .lowercase()
            .replace("_", "-")
            .replace(" ", "-")
        // INCLUSIVE by shape, not a list of names. The previous version enumerated five exact
        // header names, and this SDK writes credential headers it did not list: BlackForestLabs
        // sends `x-key` (BlackForestLabsProvider.kt:309) and Gladia sends `x-gladia-key`
        // (GladiaProvider.kt:781), so both API keys were emitted VERBATIM into telemetry — the
        // same `x-key` that was separately leaking to a third-party CDN. A caller's `Cookie` or
        // `Proxy-Authorization` escaped too. Redacting one value too many costs a debugging hint;
        // redacting one too few discloses a credential, so this errs wide deliberately.
        return normalized == "authorization" ||
            normalized == "token" ||
            normalized == "cookie" ||
            normalized.endsWith("-key") ||
            normalized.endsWith("-token") ||
            normalized.endsWith("-secret") ||
            normalized.endsWith("-password") ||
            normalized.endsWith("-credential") ||
            normalized.endsWith("-credentials") ||
            normalized.contains("auth") ||
            normalized.contains("cookie") ||
            normalized.contains("secret") ||
            normalized.contains("password") ||
            normalized.contains("apikey")
    }

    fun String.isPayloadKey(): Boolean {
        val normalized = lowercase()
        return normalized == "base64" ||
            normalized == "file" ||
            normalized == "payload" ||
            normalized == "data" ||
            normalized.endsWith("base64") ||
            normalized.endsWith("bytes")
    }

    @Suppress("ReturnCount", "MagicNumber")
    fun String.isLikelyBase64(minLength: Int): Boolean {
        if (length < minLength) return false
        val trimmed = trim()
        if (trimmed.startsWith("data:", ignoreCase = true) && ";base64," in trimmed) return true
        // No `% 4 == 0` requirement. Base64URL is routinely emitted UNPADDED (JWT segments, signed
        // asset URLs, many provider tokens), so demanding a multiple of four silently exempted a
        // whole family of real credentials: a 101-char unpadded token is under maxStringLength, so
        // the length branch does not catch it either, and it was emitted verbatim. The charset test
        // below is what actually distinguishes a blob from prose — prose contains spaces and
        // punctuation and fails it — so the modulus was buying almost no false-positive protection
        // in exchange for a live hole. Same trade as isSensitiveKey: redacting one string too many
        // costs a debugging hint, one too few discloses a credential.
        if (trimmed.length < minLength) return false
        // ASCII-only on purpose: base64 and base64url are ASCII alphabets (RFC 4648), so a
        // Unicode-aware isLetterOrDigit() accepts characters no credential can contain while
        // swallowing scriptio-continua prose — a 75-char CJK sentence has no space or punctuation
        // to fail on, sits under maxStringLength, and was summarized away as a blob. Narrowing
        // here loses no credential coverage.
        return trimmed.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' ||
                it == '+' || it == '/' || it == '=' || it == '-' || it == '_'
        }
    }

    private val CAMEL_CASE_BOUNDARY: Regex = Regex("([a-z0-9])([A-Z])")
}
