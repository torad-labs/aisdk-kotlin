// File named for its primary entry point, convertToLanguageModelPrompt; the
// DownloadedAsset/DownloadFunction types are supporting declarations.
@file:Suppress("MatchingDeclarationName")

package ai.torad.aisdk

/** The base64 bytes + media type of a downloaded asset. */
public data class DownloadedAsset(val base64: String, val mediaType: String?)

/** Downloads a remote asset for [convertToLanguageModelPrompt]. */
public typealias DownloadFunction = suspend (url: String) -> DownloadedAsset

/**
 * Resolve a prompt for a specific model — the port of upstream's
 * `convertToLanguageModelPrompt`:
 *
 * - **URL assets:** [ContentPart.Image]/[ContentPart.File] parts that carry a
 *   `url` the model's [supportedUrls] does NOT cover are inlined as base64.
 *   `data:` URLs are decoded directly; remote URLs are fetched via [download]
 *   (when supplied). URLs the model supports pass through untouched.
 * - **Dangling tool calls:** every assistant tool call must have a matching tool
 *   result before the next user/system turn (and by the end), else
 *   [MissingToolResultsError] is thrown — providers otherwise reject the prompt
 *   with a cryptic error.
 *
 * Call this before handing a prompt to a provider that doesn't natively accept
 * asset URLs. It is a no-op for prompts that are already inline and well-formed.
 */
public suspend fun convertToLanguageModelPrompt(
    messages: List<ModelMessage>,
    supportedUrls: Map<String, List<String>> = emptyMap(),
    download: DownloadFunction? = null,
): List<ModelMessage> {
    validateNoDanglingToolCalls(messages)
    return messages.map { message ->
        val converted = message.content.map { part -> resolveAssetPart(part, supportedUrls, download) }
        if (converted == message.content) message else message.copy(content = converted)
    }
}

private suspend fun resolveAssetPart(
    part: ContentPart,
    supportedUrls: Map<String, List<String>>,
    download: DownloadFunction?,
): ContentPart = when (part) {
    is ContentPart.Image -> {
        val resolved = resolveUrl(part.url, part.mediaType, supportedUrls, download)
        if (resolved == null) part else part.copy(base64 = resolved.base64, mediaType = resolved.mediaType, url = null)
    }
    is ContentPart.File -> {
        val resolved = resolveUrl(part.url, part.mediaType, supportedUrls, download)
        if (resolved == null) part else part.copy(base64 = resolved.base64, mediaType = resolved.mediaType, url = null)
    }
    else -> part
}

/** Returns the inlined bytes when [url] must be resolved, or null to leave the part as-is. */
private suspend fun resolveUrl(
    url: String?,
    mediaType: String,
    supportedUrls: Map<String, List<String>>,
    download: DownloadFunction?,
): ResolvedAsset? {
    if (url == null) return null
    return when {
        // data: URLs are decoded directly.
        url.startsWith("data:") -> {
            val data = splitDataUrl(url)
            ResolvedAsset(data.base64, mediaType.ifEmpty { data.mediaType })
        }
        // Provider accepts the URL, or no downloader supplied — leave the part as-is.
        isUrlSupported(url, mediaType, supportedUrls) || download == null -> null
        // Otherwise fetch and inline.
        else -> {
            val asset = download(url)
            ResolvedAsset(asset.base64, mediaType.ifEmpty { asset.mediaType ?: mediaType })
        }
    }
}

private data class ResolvedAsset(val base64: String, val mediaType: String)

/** A model supports a URL if any URL pattern for a matching media-type entry matches it. */
private fun isUrlSupported(url: String, mediaType: String, supportedUrls: Map<String, List<String>>): Boolean =
    supportedUrls.any { (typePattern, urlPatterns) ->
        mediaTypeMatches(typePattern, mediaType) &&
            urlPatterns.any { runCatching { Regex(it).containsMatchIn(url) }.getOrDefault(false) }
    }

// Glob match for media-type patterns: a bare "*" (or wildcard) matches anything;
// an "image/*"-style pattern matches by type prefix; otherwise an exact match.
private fun mediaTypeMatches(pattern: String, mediaType: String): Boolean = when {
    pattern == "*" || pattern == "*/*" -> true
    pattern.endsWith("/*") -> mediaType.startsWith(pattern.removeSuffix("/*") + "/")
    else -> pattern.equals(mediaType, ignoreCase = true)
}

/**
 * Every assistant tool call must be answered by a tool result before the next
 * user/system turn or the end of the prompt; otherwise providers reject it.
 */
private fun validateNoDanglingToolCalls(messages: List<ModelMessage>) {
    val pending = linkedSetOf<String>()
    fun flush() {
        if (pending.isNotEmpty()) throw MissingToolResultsError(pending.toList())
    }
    for (message in messages) {
        when (message.role) {
            MessageRole.Assistant -> {
                // Provider-executed tools are answered server-side, and calls paired
                // with an in-flight approval request are answered after approval —
                // neither needs a client tool result, so they aren't "dangling".
                val approvalPending = message.content
                    .filterIsInstance<ContentPart.ToolApprovalRequest>().map { it.toolCallId }.toSet()
                message.content.filterIsInstance<ContentPart.ToolCall>()
                    .filter { !it.providerExecuted && it.toolCallId !in approvalPending }
                    .forEach { pending += it.toolCallId }
            }
            MessageRole.Tool ->
                message.content.filterIsInstance<ContentPart.ToolResult>().forEach { pending -= it.toolCallId }
            MessageRole.User, MessageRole.System -> flush() // a new turn must not leave calls unanswered
        }
    }
    flush()
}
