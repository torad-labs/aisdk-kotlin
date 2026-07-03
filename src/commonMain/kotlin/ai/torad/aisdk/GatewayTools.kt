package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.json.JsonElement

@Poko
/** @since 0.3.0-beta01 */
public class GatewayTools(
    /** @since 0.3.0-beta01 */
    public val parallelSearch: Tool<JsonElement, JsonElement, Any?> = ProviderExecutedTool(
        name = "parallelSearch",
        description = "Search the web using Parallel AI's Search API for LLM-optimized excerpts.",
        inputSerializer = JsonElement.serializer(),
        outputSerializer = JsonElement.serializer(),
    ),
    /** @since 0.3.0-beta01 */
    public val perplexitySearch: Tool<JsonElement, JsonElement, Any?> = ProviderExecutedTool(
        name = "perplexitySearch",
        description = "Search the web using Perplexity's Search API for real-time information.",
        inputSerializer = JsonElement.serializer(),
        outputSerializer = JsonElement.serializer(),
    ),
)
