package ai.torad.aisdk.ui

import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.StreamEvent.Companion.toUIMessageChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonObject

/**
 * Bridge an agent [StreamEvent] flow to the v6 UI-message-stream wire protocol:
 * each emission is a `UIMessageChunk` JSON object (e.g. `{ "type": "text-delta",
 * "id": "...", "delta": "..." }`) ready to be framed as an SSE `data:` line and
 * consumed by a JS `useChat` client. Events with no wire counterpart
 * (response-metadata, tool-input-end, raw) are dropped.
 *
 * This is the server-side counterpart to `streamToUiMessages` (which builds
 * renderable snapshots in-process); use this when serving the stream over HTTP.
 * @since 0.3.0-beta01
 */
public fun ToUIMessageStream(events: Flow<StreamEvent>): Flow<JsonObject> =
    events.mapNotNull { it.toUIMessageChunk() }
