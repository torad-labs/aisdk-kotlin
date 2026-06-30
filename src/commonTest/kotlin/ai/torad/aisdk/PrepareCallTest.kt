@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import ai.torad.aisdk.providers.MockLanguageModel
import ai.torad.aisdk.providers.MockLanguageModelTextOnly
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Invariant I-5 — `prepareCall` runs once per invocation, before the loop. */
class PrepareCallTest {

    @Serializable(with = TopicOptionsSerializer::class)
    private data class TopicOptions(val topic: String)

    private object TopicOptionsSerializer : KSerializer<TopicOptions> {
        override val descriptor = buildClassSerialDescriptor("TopicOptions") {
            element<String>("topic")
        }

        override fun serialize(encoder: Encoder, value: TopicOptions) {
            validateTopic(value.topic)
            encoder.encodeStructure(descriptor) {
                encodeStringElement(descriptor, 0, value.topic)
            }
        }

        override fun deserialize(decoder: Decoder): TopicOptions = decoder.decodeStructure(descriptor) {
            var topic: String? = null
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> topic = decodeStringElement(descriptor, 0)
                    else -> throw SerializationException("Unknown index $index")
                }
            }
            TopicOptions(validateTopic(topic ?: throw SerializationException("topic is required")))
        }

        private fun validateTopic(topic: String): String {
            if (topic != "legal" && topic != "medical") {
                throw SerializationException("topic must be legal or medical")
            }
            return topic
        }
    }

    private class CountingModel : LanguageModel {
        var called = false
        override val modelId: String = "test/counting"

        override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
            called = true
            return LanguageModelResult("reply", finishReason = FinishReason.Stop, usage = Usage())
        }

        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
            called = true
            emit(StreamEvent.TextStart("t1"))
            emit(StreamEvent.TextDelta("t1", "reply"))
            emit(StreamEvent.TextEnd("t1"))
            emit(StreamEvent.StepFinish(1, FinishReason.Stop, Usage()))
        }
    }

    @Test
    fun `prepareCall_runs_once_and_overrides_instructions`() = runTest {
        var callCount = 0
        val agent = TestToolLoopAgent<String, String>(
            model = MockLanguageModelTextOnly("done"),
            instructions = "default instructions",
            tools = ToolSet(),
            prepareCall = {
                callCount += 1
                AgentSettings {
                    instructions("overridden for ${options ?: "no-context"}")
                }
            },
        )
        agent.generate("hello", options = "marcos").first()
        assertEquals(1, callCount, "prepareCall ran exactly once")
    }

    @Test
    fun `prepareCall_can_provide_typed_context`() = runTest {
        var observedContext: String? = null
        val agent = TestToolLoopAgent<String, String>(
            model = MockLanguageModelTextOnly("done"),
            instructions = "x",
            tools = ToolSet(),
            prepareCall = {
                observedContext = options
                AgentSettings()
            },
        )
        agent.generate("hi", options = "example-context").first()
        assertNotNull(observedContext)
        assertEquals("example-context", observedContext)
    }

    @Test
    fun `callOptionsSchema rejects invalid options before invoking the model`() = runTest {
        val model = CountingModel()
        val agent = TestToolLoopAgent<TopicOptions, String>(
            model = model,
            instructions = "x",
            tools = ToolSet(),
            callOptionsSchema = TopicOptionsSerializer,
        )

        val error = assertFailsWith<AgentError.InvalidCallOptions> {
            agent.generate("hi", options = TopicOptions("evil")).first()
        }

        assertTrue(error.message.orEmpty().contains("Type validation failed for options"))
        assertFalse(model.called)
    }

    @Test
    fun `callOptionsSchema passes valid options into prepareCall`() = runTest {
        val model = CountingModel()
        var observed: TopicOptions? = null
        val agent = TestToolLoopAgent<TopicOptions, String>(
            model = model,
            instructions = "x",
            tools = ToolSet(),
            callOptionsSchema = TopicOptionsSerializer,
            prepareCall = {
                observed = options
                AgentSettings()
            },
        )

        val result = agent.generate("hi", options = TopicOptions("legal")).first()

        assertEquals("reply", result.text)
        assertTrue(model.called)
        assertEquals(TopicOptions("legal"), observed)
    }
}
