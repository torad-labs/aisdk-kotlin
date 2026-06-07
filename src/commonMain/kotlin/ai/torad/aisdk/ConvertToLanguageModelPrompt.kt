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
    is ContentPart.Image ->
        resolveMedia(part.url, part.base64, part.mediaType, supportedUrls, download)?.let {
            part.copy(base64 = it.base64, mediaType = it.mediaType, url = if (it.clearUrl) null else part.url)
        } ?: part
    is ContentPart.File ->
        resolveMedia(part.url, part.base64, part.mediaType, supportedUrls, download)?.let {
            part.copy(base64 = it.base64, mediaType = it.mediaType, url = if (it.clearUrl) null else part.url)
        } ?: part
    else -> part
}

private data class ResolvedMedia(val base64: String, val mediaType: String, val clearUrl: Boolean)

/**
 * Resolve an image/file part's data + media type: inline its URL (when applicable)
 * and correct the media type from the actual bytes (a PNG mislabeled image/jpeg, or
 * a wildcard image type, is fixed for the provider). Returns null when nothing changed.
 */
private suspend fun resolveMedia(
    url: String?,
    base64In: String,
    mediaTypeIn: String,
    supportedUrls: Map<String, List<String>>,
    download: DownloadFunction?,
): ResolvedMedia? {
    val resolved = resolveUrl(url, mediaTypeIn, supportedUrls, download)
    val base64 = resolved?.base64 ?: base64In
    val mediaType = detectImageMediaType(base64) ?: resolved?.mediaType ?: mediaTypeIn
    return if (resolved == null && mediaType == mediaTypeIn) {
        null
    } else {
        ResolvedMedia(base64, mediaType, clearUrl = resolved != null)
    }
}

/**
 * Detect a common image media type from the leading magic bytes of [base64], or
 * null if it isn't a recognized image (so non-image content is left untouched).
 * Ports upstream's `imageMediaTypeSignatures`. Offset-based formats (avif/heic)
 * are not detected.
 */
@Suppress("MagicNumber") // the hex literals ARE the file-format magic-byte signatures
private fun detectImageMediaType(base64: String): String? {
    val bytes = runCatching {
        if (base64.isEmpty()) null else convertBase64ToByteArray(base64)
    }.getOrNull() ?: return null
    fun hasPrefix(sig: List<Int>, offset: Int = 0): Boolean =
        bytes.size >= offset + sig.size && sig.withIndex().all { (i, b) -> (bytes[offset + i].toInt() and 0xFF) == b }
    val prefixSignatures = listOf(
        listOf(0x89, 0x50, 0x4E, 0x47) to "image/png",
        listOf(0xFF, 0xD8) to "image/jpeg",
        listOf(0x47, 0x49, 0x46) to "image/gif",
        listOf(0x42, 0x4D) to "image/bmp",
        listOf(0x49, 0x49, 0x2A, 0x00) to "image/tiff",
        listOf(0x4D, 0x4D, 0x00, 0x2A) to "image/tiff",
    )
    val byPrefix = prefixSignatures.firstOrNull { hasPrefix(it.first) }?.second
    val isWebp = hasPrefix(listOf(0x52, 0x49, 0x46, 0x46)) && hasPrefix(listOf(0x57, 0x45, 0x42, 0x50), offset = 8)
    return byPrefix ?: if (isWebp) "image/webp" else null
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

// Glob match for media-type patterns, unified with Util.isUrlSupported: strip all
// `*` to a prefix ("*"/"*​/*" → "", "image/*" → "image/") and prefix-match. (Was an
// exact match on non-wildcard keys, which diverged from the public helper.)
private fun mediaTypeMatches(pattern: String, mediaType: String): Boolean {
    val prefix = if (pattern == "*" || pattern == "*/*") "" else pattern.replace("*", "")
    return mediaType.startsWith(prefix, ignoreCase = true)
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
