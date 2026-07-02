@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

@Poko
/** @since 0.3.0-beta01 */
public class DeepPartial<T>(public val value: T?)

/** @since 0.3.0-beta01 */
public class StructuredObjectRequest<INPUT> internal constructor(
    /** @since 0.3.0-beta01 */
    public val api: String,
    /** @since 0.3.0-beta01 */
    public val id: String,
    /** @since 0.3.0-beta01 */
    public val input: INPUT,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val abortSignal: AbortSignal = AbortSignalNever,
)

/** @since 0.3.0-beta01 */
public class StructuredObjectRequestBuilder<INPUT> {
    private var api: String? = null
    private var id: String? = null
    private var input: INPUT? = null
    private var inputSet: Boolean = false
    private var headers: Map<String, String> = emptyMap()
    private var abortSignal: AbortSignal = AbortSignalNever

    /** @since 0.3.0-beta01 */
    public fun api(value: String): StructuredObjectRequestBuilder<INPUT> {
        api = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun id(value: String): StructuredObjectRequestBuilder<INPUT> {
        id = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun input(value: INPUT): StructuredObjectRequestBuilder<INPUT> {
        input = value
        inputSet = true
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): StructuredObjectRequestBuilder<INPUT> {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun abortSignal(value: AbortSignal): StructuredObjectRequestBuilder<INPUT> {
        abortSignal = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): StructuredObjectRequest<INPUT> {
        check(inputSet) { "StructuredObjectRequest.input is required" }
        @Suppress("UNCHECKED_CAST")
        return StructuredObjectRequest(
            api = requireNotNull(api) { "StructuredObjectRequest.api is required" },
            id = requireNotNull(id) { "StructuredObjectRequest.id is required" },
            input = input as INPUT,
            headers = headers,
            abortSignal = abortSignal,
        )
    }
}

public fun <INPUT> StructuredObjectRequest(
    block: StructuredObjectRequestBuilder<INPUT>.() -> Unit = {},
): StructuredObjectRequest<INPUT> =
    StructuredObjectRequestBuilder<INPUT>().apply(block).build()

/** @since 0.3.0-beta01 */
public interface StructuredObjectTransport<INPUT> {
    /** @since 0.3.0-beta01 */
    public fun submit(request: StructuredObjectRequest<INPUT>): Flow<String>
}

internal class DirectStructuredObjectTransport<INPUT>(
    private val handler: (StructuredObjectRequest<INPUT>) -> Flow<String>,
) : StructuredObjectTransport<INPUT> {
    override fun submit(request: StructuredObjectRequest<INPUT>): Flow<String> = handler(request)
}

@Poko
/** @since 0.3.0-beta01 */
public class StructuredObjectFinish<RESULT>(
    /** @since 0.3.0-beta01 */
    public val value: RESULT?,
    /** @since 0.3.0-beta01 */
    public val error: Throwable?,
    /** @since 0.3.0-beta01 */
    public val rawValue: JsonElement?,
)

/** @since 0.3.0-beta01 */
public class StructuredObjectOptions<RESULT, INPUT> internal constructor(
    /** @since 0.3.0-beta01 */
    public val api: String,
    /** @since 0.3.0-beta01 */
    public val schema: Schema<RESULT>,
    /** @since 0.3.0-beta01 */
    public val id: String = IdGenerator.generate("object"),
    /** @since 0.3.0-beta01 */
    public val initialValue: RESULT? = null,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val transport: StructuredObjectTransport<INPUT> = DirectStructuredObjectTransport { emptyFlow() },
    /** @since 0.3.0-beta01 */
    public val onFinish: suspend (StructuredObjectFinish<RESULT>) -> Unit = {},
    /** @since 0.3.0-beta01 */
    public val onError: suspend (Throwable) -> Unit = {},
)

/** @since 0.3.0-beta01 */
public class StructuredObjectOptionsBuilder<RESULT, INPUT> {
    private var api: String? = null
    private var schema: Schema<RESULT>? = null
    private var id: String = IdGenerator.generate("object")
    private var initialValue: RESULT? = null
    private var headers: Map<String, String> = emptyMap()
    private var transport: StructuredObjectTransport<INPUT> = DirectStructuredObjectTransport { emptyFlow() }
    private var onFinish: suspend (StructuredObjectFinish<RESULT>) -> Unit = {}
    private var onError: suspend (Throwable) -> Unit = {}

    /** @since 0.3.0-beta01 */
    public fun api(value: String): StructuredObjectOptionsBuilder<RESULT, INPUT> {
        api = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun schema(value: Schema<RESULT>): StructuredObjectOptionsBuilder<RESULT, INPUT> {
        schema = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun id(value: String): StructuredObjectOptionsBuilder<RESULT, INPUT> {
        id = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun initialValue(value: RESULT?): StructuredObjectOptionsBuilder<RESULT, INPUT> {
        initialValue = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): StructuredObjectOptionsBuilder<RESULT, INPUT> {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun transport(value: StructuredObjectTransport<INPUT>): StructuredObjectOptionsBuilder<RESULT, INPUT> {
        transport = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun onFinish(
        value: suspend (StructuredObjectFinish<RESULT>) -> Unit
    ): StructuredObjectOptionsBuilder<RESULT, INPUT> {
        onFinish = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun onError(value: suspend (Throwable) -> Unit): StructuredObjectOptionsBuilder<RESULT, INPUT> {
        onError = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): StructuredObjectOptions<RESULT, INPUT> =
        StructuredObjectOptions(
            api = requireNotNull(api) { "StructuredObjectOptions.api is required" },
            schema = requireNotNull(schema) { "StructuredObjectOptions.schema is required" },
            id = id,
            initialValue = initialValue,
            headers = headers,
            transport = transport,
            onFinish = onFinish,
            onError = onError,
        )
}

public fun <RESULT, INPUT> StructuredObjectOptions(
    block: StructuredObjectOptionsBuilder<RESULT, INPUT>.() -> Unit = {},
): StructuredObjectOptions<RESULT, INPUT> =
    StructuredObjectOptionsBuilder<RESULT, INPUT>().apply(block).build()

/** @since 0.3.0-beta01 */
public sealed class StructuredObjectPhase<out RESULT> {
    /** @since 0.3.0-beta01 */
    public data object Idle : StructuredObjectPhase<Nothing>()

    @Poko
    /** @since 0.3.0-beta01 */
    public class Streaming<out RESULT>(
        /** @since 0.3.0-beta01 */
        public val partial: RESULT?,
        /** @since 0.3.0-beta01 */
        public val raw: JsonElement?,
        /** @since 0.3.0-beta01 */
        public val error: Throwable?,
    ) : StructuredObjectPhase<RESULT>()

    @Poko
    /** @since 0.3.0-beta01 */
    public class Done<out RESULT>(
        /** @since 0.3.0-beta01 */
        public val value: RESULT?,
        /** @since 0.3.0-beta01 */
        public val raw: JsonElement?,
        /** @since 0.3.0-beta01 */
        public val error: Throwable?,
    ) : StructuredObjectPhase<RESULT>()
}

/** @since 0.3.0-beta01 */
public class StructuredObject<RESULT, INPUT>(
    private val options: StructuredObjectOptions<RESULT, INPUT>,
) {
    /** @since 0.3.0-beta01 */
    public val id: String = options.id

    /** @since 0.3.0-beta01 */
    public val api: String = options.api

    private val mutableState = MutableStateFlow<StructuredObjectPhase<RESULT>>(
        if (options.initialValue != null) {
            StructuredObjectPhase.Done(options.initialValue, null, null)
        } else {
            StructuredObjectPhase.Idle
        },
    )

    /** @since 0.3.0-beta01 */
    public val state: StateFlow<StructuredObjectPhase<RESULT>> = mutableState.asStateFlow()

    /** @since 0.3.0-beta01 */
    public val value: RESULT?
        get() = when (val p = mutableState.value) {
            is StructuredObjectPhase.Streaming -> p.partial
            is StructuredObjectPhase.Done -> p.value
            StructuredObjectPhase.Idle -> null
        }

    /** @since 0.3.0-beta01 */
    public val objectValue: RESULT?
        get() = value

    /** @since 0.3.0-beta01 */
    public val rawValue: JsonElement?
        get() = when (val p = mutableState.value) {
            is StructuredObjectPhase.Streaming -> p.raw
            is StructuredObjectPhase.Done -> p.raw
            StructuredObjectPhase.Idle -> null
        }

    /** @since 0.3.0-beta01 */
    public val error: Throwable?
        get() = when (val p = mutableState.value) {
            is StructuredObjectPhase.Streaming -> p.error
            is StructuredObjectPhase.Done -> p.error
            StructuredObjectPhase.Idle -> null
        }

    /** @since 0.3.0-beta01 */
    public val loading: Boolean
        get() = mutableState.value is StructuredObjectPhase.Streaming

    private var abortController: AbortController? = null

    public suspend fun submit(input: INPUT) {
        clearObject()
        val controller = AbortController()
        abortController = controller
        val request = StructuredObjectRequest<INPUT> {
            api(options.api)
            id(options.id)
            input(input)
            headers(options.headers)
            abortSignal(controller.signal)
        }
        try {
            // Reuse the shared parse/validate loop; drive the StateFlow from its phases.
            phases(options.transport.submit(request), options.schema, controller.signal).collect { phase ->
                mutableState.value = phase
            }
            // Normal completion: the validation error (if any) rides on Done.error. An abort also
            // reaches Done (partial preserved) but must NOT fire onFinish — the host didn't finish.
            if (!controller.signal.isAborted) {
                (mutableState.value as? StructuredObjectPhase.Done)?.let {
                    options.onFinish(StructuredObjectFinish(it.value, it.error, it.raw))
                }
            }
        } catch (c: CancellationException) {
            // External/structured cancellation (NOT our explicit AbortError, which `phases` already
            // turned into a Done) must propagate so cooperative cancellation unwinds the job tree.
            throw c
        } catch (t: Throwable) {
            val current = mutableState.value
            val partial = (current as? StructuredObjectPhase.Streaming)?.partial
            val raw = (current as? StructuredObjectPhase.Streaming)?.raw
            mutableState.value = StructuredObjectPhase.Done(partial, raw, t)
            options.onError(t)
        } finally {
            mutableState.update { p ->
                if (p is StructuredObjectPhase.Streaming) {
                    StructuredObjectPhase.Done(p.partial, p.raw, p.error)
                } else {
                    p
                }
            }
            abortController = null
        }
    }

    /** @since 0.3.0-beta01 */
    public fun stop() {
        abortController?.abort()
        abortController = null
        mutableState.update { p ->
            if (p is StructuredObjectPhase.Streaming) {
                StructuredObjectPhase.Done(p.partial, p.raw, p.error)
            } else {
                p
            }
        }
    }

    /** @since 0.3.0-beta01 */
    public fun clear() {
        stop()
        clearObject()
    }

    private fun clearObject() {
        mutableState.value = StructuredObjectPhase.Idle
    }

    public companion object {
        /**
         * The shared parse/validate loop as a cold Flow of phases — the single source of truth
         * reused by [submit] and by [StructuredObjectGenerator]. Emits an initial
         * [StructuredObjectPhase.Streaming], one Streaming per CHANGED partial parse
         * ([PartialJson.parsePartialJson] -> [Schemas.safeValidateTypes]), then a terminal
         * [StructuredObjectPhase.Done] carrying the validated value (or the validation error).
         *
         * An [AbortError] (cooperative stop via [AbortSignal.throwIfAborted]) becomes a Done that
         * preserves the latest partial with no error; a non-Abort `CancellationException` and any
         * other `Throwable` propagate to the caller. `channelFlow` (not `flow {}`) lets us `send`
         * the terminal Done from the abort `catch` without tripping flow exception-transparency.
         */
        internal fun <RESULT> phases(
            chunks: Flow<String>,
            schema: Schema<RESULT>,
            abortSignal: AbortSignal,
        ): Flow<StructuredObjectPhase<RESULT>> = channelFlow {
            send(StructuredObjectPhase.Streaming(null, null, null))
            val accumulated = StringBuilder()
            var latestRaw: JsonElement? = null
            var currentValue: RESULT? = null
            try {
                chunks.collect { chunk ->
                    abortSignal.throwIfAborted()
                    accumulated.append(chunk)
                    val parsed = PartialJson.parsePartialJson(accumulated.toString()).value ?: return@collect
                    if (!latestRaw.isDeepEqual(parsed)) {
                        latestRaw = parsed
                        when (val validated = Schemas.safeValidateTypes(parsed, schema)) {
                            is ValidationResult.Success -> {
                                currentValue = validated.value
                                send(StructuredObjectPhase.Streaming(currentValue, parsed, null))
                            }
                            is ValidationResult.Failure ->
                                send(StructuredObjectPhase.Streaming(currentValue, parsed, validated.error))
                        }
                    }
                }
                val finalRaw = latestRaw
                if (finalRaw == null) {
                    send(
                        StructuredObjectPhase.Done(
                            null,
                            null,
                            TypeValidationError.wrap(
                                null,
                                IllegalArgumentException("Structured object response did not contain JSON"),
                            ),
                        ),
                    )
                } else {
                    when (val validated = Schemas.safeValidateTypes(finalRaw, schema)) {
                        is ValidationResult.Success -> {
                            currentValue = validated.value
                            send(StructuredObjectPhase.Done(currentValue, finalRaw, null))
                        }
                        is ValidationResult.Failure ->
                            send(StructuredObjectPhase.Done(currentValue, finalRaw, validated.error))
                    }
                }
            } catch (ignored: AbortError) {
                // Cooperative stop: keep the latest partial, no error (the host didn't fail).
                send(StructuredObjectPhase.Done(currentValue, latestRaw, null))
            }
        }

        private fun JsonElement?.isDeepEqual(other: JsonElement?): Boolean = when {
            this === other -> true
            this == null || other == null -> false
            this is JsonNull && other is JsonNull -> true
            this is JsonPrimitive && other is JsonPrimitive -> primitiveEquals(this, other)
            this is JsonArray && other is JsonArray -> arraysDeepEqual(this, other)
            this is JsonObject && other is JsonObject -> objectsDeepEqual(this, other)
            else -> false
        }

        private fun arraysDeepEqual(left: JsonArray, right: JsonArray): Boolean =
            left.size == right.size && left.indices.all { left[it].isDeepEqual(right[it]) }

        private fun objectsDeepEqual(left: JsonObject, right: JsonObject): Boolean =
            left.keys == right.keys && left.keys.all { key -> left[key].isDeepEqual(right[key]) }

        private fun primitiveEquals(left: JsonPrimitive, right: JsonPrimitive): Boolean {
            val l = left.jsonPrimitive
            val r = right.jsonPrimitive
            return when {
                l.booleanOrNull != null || r.booleanOrNull != null -> l.booleanOrNull == r.booleanOrNull
                l.doubleOrNull != null || r.doubleOrNull != null -> l.doubleOrNull == r.doubleOrNull
                else -> l.content == r.content
            }
        }
    }
}

/**
 * Wires a [LanguageModel] to the structured-object machinery: constrains the model to emit JSON
 * for [schema], streams its text deltas into [StructuredObject.phases] (the shared parse/validate
 * loop), and surfaces typed partials/value. Mirrors [TextGenerator] — a PascalCase class, not a
 * camelCase top-level `generateObject`/`streamObject`.
 * @since 0.3.0-beta01
 */
public class StructuredObjectGenerator<RESULT>(
    private val model: LanguageModel,
    private val schema: Schema<RESULT>,
    private val config: CallConfig = CallConfig(),
    private val schemaName: String = "object",
    private val schemaDescription: String? = null,
) {
    /**
     * Cold stream of phases: accumulating [StructuredObjectPhase.Streaming] partials terminating in
     * a single [StructuredObjectPhase.Done]. An in-band [StreamEvent.Error] from the model is
     * surfaced (not silently dropped) so a provider failure can't masquerade as an empty object.
     * @since 0.3.0-beta01
     */
    public fun stream(input: GenerationInput): Flow<StructuredObjectPhase<RESULT>> =
        CallTimeout.flow(
            StructuredObject.phases(
                chunks = model.stream(buildParams(input)).textDeltas(),
                schema = schema,
                abortSignal = config.abortSignal,
            ),
            config.timeout,
        )

    /** Text-delta payloads from the model stream, surfacing any in-band [StreamEvent.Error]. */
    private fun Flow<StreamEvent>.textDeltas(): Flow<String> =
        onEach { event ->
            if (event is StreamEvent.Error) throw UiMessageStreamError(event.message, event.cause)
        }.filterIsInstance<StreamEvent.TextDelta>().map { it.text }

    /** One-shot: drives the stream to [StructuredObjectPhase.Done] and returns the typed result. */
    public suspend fun generate(input: GenerationInput): StructuredObjectFinish<RESULT> =
        when (val terminal = stream(input).last()) {
            is StructuredObjectPhase.Done -> StructuredObjectFinish(terminal.value, terminal.error, terminal.raw)
            is StructuredObjectPhase.Streaming -> StructuredObjectFinish(terminal.partial, terminal.error, terminal.raw)
            StructuredObjectPhase.Idle -> StructuredObjectFinish(null, null, null)
        }
    private fun buildParams(input: GenerationInput): LanguageModelCallParams =
        LanguageModelCallParams {
            messages(input.toMessages(null))
            temperature(config.temperature)
            topP(config.topP)
            topK(config.topK)
            maxOutputTokens(config.maxOutputTokens)
            stopSequences(config.stopSequences)
            seed(config.seed)
            providerOptions(config.providerOptions)
            abortSignal(config.abortSignal)
            presencePenalty(config.presencePenalty)
            frequencyPenalty(config.frequencyPenalty)
            headers(config.headers)
            // Constrain the model to JSON for our schema unless the caller pinned a responseFormat.
            responseFormat(
                if (config.responseFormat == ResponseFormat.Text) {
                    ResponseFormat.Json(
                        schemaName = schemaName,
                        schemaDescription = schemaDescription,
                        schemaJson = schema.jsonSchema,
                    )
                } else {
                    config.responseFormat
                },
            )
        }
}
