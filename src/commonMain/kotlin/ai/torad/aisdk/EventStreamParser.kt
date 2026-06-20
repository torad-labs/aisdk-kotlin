package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

public sealed class ParseResult<out T> {
    public data class Success<T>(val value: T) : ParseResult<T>()
    public data class Failure(val error: Throwable, val text: String) : ParseResult<Nothing>()
}

public object EventStreamParser {

    public fun <T> parse(
        chunks: Flow<String>,
        schema: Schema<T>,
        json: Json = Json,
    ): Flow<ParseResult<T>> = flow {
        var buffer = ""
        var eventData = mutableListOf<String>()
        suspend fun flush() {
            if (eventData.isEmpty()) return
            val data = eventData.joinToString("\n")
            eventData = mutableListOf()
            if (data != "[DONE]") emit(safeParseJson(data, schema, json))
        }
        chunks.collect { chunk ->
            buffer += chunk
            while (true) {
                val newline = buffer.indexOf('\n')
                if (newline < 0) break
                val rawLine = buffer.substring(0, newline).removeSuffix("\r")
                buffer = buffer.substring(newline + 1)
                if (rawLine.isEmpty()) {
                    flush()
                } else if (rawLine.startsWith("data:")) {
                    eventData += rawLine.removePrefix("data:").trimStart()
                }
            }
        }
        if (buffer.isNotEmpty()) {
            val rawLine = buffer.removeSuffix("\r")
            if (rawLine.startsWith("data:")) eventData += rawLine.removePrefix("data:").trimStart()
        }
        flush()
    }

    internal fun <T> parse(
        text: String,
        schema: Schema<T>,
        json: Json = Json,
    ): List<ParseResult<T>> =
        serverSentEventData(text).mapNotNull { data ->
            if (data == "[DONE]") null else safeParseJson(data, schema, json)
        }

    private fun serverSentEventData(text: String): List<String> {
        val events = mutableListOf<String>()
        val current = mutableListOf<String>()
        fun flush() {
            if (current.isNotEmpty()) {
                events += current.joinToString("\n")
                current.clear()
            }
        }
        text.lineSequence().forEach { raw ->
            val line = raw.removeSuffix("\r")
            when {
                line.isEmpty() -> flush()
                line.startsWith("data:") -> current += line.removePrefix("data:").trimStart()
            }
        }
        flush()
        return events
    }

    private fun <T> safeParseJson(text: String, schema: Schema<T>, json: Json): ParseResult<T> =
        try {
            val element = json.parseToJsonElement(text)
            @Suppress("UNCHECKED_CAST")
            ParseResult.Success(schema.validate?.invoke(element) ?: (element as T))
        } catch (error: SerializationException) {
            ParseResult.Failure(error, text)
        } catch (error: IllegalArgumentException) {
            ParseResult.Failure(error, text)
        }
}
