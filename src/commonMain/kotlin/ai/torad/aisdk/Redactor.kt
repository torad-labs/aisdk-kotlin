package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

public interface Redactor {
    public fun redactText(value: String): String
    public fun redactHeaders(headers: Map<String, String>): Map<String, String>
    public fun redactJson(value: JsonElement): JsonElement
}

@Poko
public class RedactionOptions internal constructor(
    public val replacement: String = "[REDACTED]",
    public val maxStringLength: Int = 256,
    public val minBase64Length: Int = 64,
)

public class RedactionOptionsBuilder {
    private var replacement: String = "[REDACTED]"
    private var maxStringLength: Int = 256
    private var minBase64Length: Int = 64

    public fun replacement(value: String): RedactionOptionsBuilder {
        replacement = value
        return this
    }

    public fun maxStringLength(value: Int): RedactionOptionsBuilder {
        maxStringLength = value
        return this
    }

    public fun minBase64Length(value: Int): RedactionOptionsBuilder {
        minBase64Length = value
        return this
    }

    public fun build(): RedactionOptions =
        RedactionOptions(
            replacement = replacement,
            maxStringLength = maxStringLength,
            minBase64Length = minBase64Length,
        )
}

public fun RedactionOptions(
    block: RedactionOptionsBuilder.() -> Unit = {},
): RedactionOptions =
    RedactionOptionsBuilder().apply(block).build()

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

    private companion object {
        private val TOKEN_PATTERNS: List<Regex> = listOf(
            Regex("\\b(Bearer|Basic)\\s+[-._~+/=A-Za-z0-9]+", RegexOption.IGNORE_CASE),
            Regex("\\b(api[-_ ]?key|token|secret)\\s*[:=]\\s*[-._~+/=A-Za-z0-9]+", RegexOption.IGNORE_CASE),
        )
    }
}

public val AiSdkDefaultRedactor: Redactor = DefaultRedactor()

internal object RedactionPredicates {
    fun String.isSensitiveKey(): Boolean {
        val normalized = replace(CAMEL_CASE_BOUNDARY, "$1-$2")
            .lowercase()
            .replace("_", "-")
            .replace(" ", "-")
        return normalized == "authorization" ||
            normalized == "api-key" ||
            normalized == "x-api-key" ||
            normalized == "x-goog-api-key" ||
            normalized == "xi-api-key" ||
            normalized == "token" ||
            normalized.endsWith("-token") ||
            normalized.endsWith("-secret") ||
            normalized.endsWith("-api-key")
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
        if (trimmed.length < minLength || trimmed.length % 4 != 0) return false
        return trimmed.all {
            it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_'
        }
    }

    private val CAMEL_CASE_BOUNDARY: Regex = Regex("([a-z0-9])([A-Z])")
}
