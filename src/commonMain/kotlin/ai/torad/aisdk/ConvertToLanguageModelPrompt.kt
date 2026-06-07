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
    val resolved = messages.map { message ->
        val converted = message.content.map { part -> resolveAssetPart(part, supportedUrls, download) }
        if (converted == message.content) message else message.copy(content = converted)
    }
    return combineConsecutiveToolMessages(resolved)
}

/**
 * Merge adjacent `role: tool` messages into a single tool message (upstream
 * parity) — providers that expect one combined tool turn reject a split one.
 */
private fun combineConsecutiveToolMessages(messages: List<ModelMessage>): List<ModelMessage> {
    val result = mutableListOf<ModelMessage>()
    for (message in messages) {
        val last = result.lastOrNull()
        if (message.role == MessageRole.Tool && last?.role == MessageRole.Tool) {
            result[result.size - 1] = last.copy(content = last.content + message.content)
        } else {
            result += message
        }
    }
    return result
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
        // base64 data: URLs are decoded directly. Non-base64 data URLs
        // (e.g. data:text/plain,Hello) aren't representable as our base64 part, so
        // they're left untouched for the provider rather than crashing splitDataUrl.
        url.startsWith("data:") && ";base64," in url -> {
            val data = splitDataUrl(url)
            ResolvedAsset(data.base64, mediaType.ifEmpty { data.mediaType })
        }
        url.startsWith("data:") -> null
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
/**
 * Tool calls exempt from needing a tool result: those awaiting approval (an
 * approval request exists) or already approved (a response exists, correlated by
 * approvalId or directly by toolCallId) — they are answered after approval, not by
 * a tool result. Mirrors upstream's approvalId→toolCallId correlation.
 */
private fun approvalExemptCallIds(messages: List<ModelMessage>): Set<String> {
    val approvalIdToToolCallId = mutableMapOf<String, String>()
    val exempt = mutableSetOf<String>()
    val parts = messages.flatMap { it.content }
    parts.filterIsInstance<ContentPart.ToolApprovalRequest>().forEach {
        it.approvalId?.let { id -> approvalIdToToolCallId[id] = it.toolCallId }
        exempt += it.toolCallId
    }
    parts.filterIsInstance<ContentPart.ToolApprovalResponse>().forEach {
        exempt += it.toolCallId
        it.approvalId?.let { id -> approvalIdToToolCallId[id]?.let { call -> exempt += call } }
    }
    return exempt
}

private fun validateNoDanglingToolCalls(messages: List<ModelMessage>) {
    val exempt = approvalExemptCallIds(messages)
    val pending = linkedSetOf<String>()
    fun flush() {
        if (pending.isNotEmpty()) throw MissingToolResultsError(pending.toList())
    }
    for (message in messages) {
        when (message.role) {
            MessageRole.Assistant ->
                // Provider-executed and approval-exempt calls are answered elsewhere.
                message.content.filterIsInstance<ContentPart.ToolCall>()
                    .filter { !it.providerExecuted && it.toolCallId !in exempt }
                    .forEach { pending += it.toolCallId }
            MessageRole.Tool ->
                message.content.filterIsInstance<ContentPart.ToolResult>().forEach { pending -= it.toolCallId }
            MessageRole.User, MessageRole.System -> flush() // a new turn must not leave calls unanswered
        }
    }
    flush()
}
