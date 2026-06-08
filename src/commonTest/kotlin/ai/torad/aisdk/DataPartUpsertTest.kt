package ai.torad.aisdk

import ai.torad.aisdk.ui.UIMessagePart
import ai.torad.aisdk.ui.streamToUiMessages
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataPartUpsertTest {
    private fun dataChunk(type: String, value: String, id: String? = null, transient: Boolean = false) =
        StreamEvent.Raw(
            buildJsonObject {
                put("type", JsonPrimitive(type))
                put("data", JsonPrimitive(value))
                if (id != null) put("id", JsonPrimitive(id))
                if (transient) put("transient", JsonPrimitive(true))
            },
        )

    @Test
    fun `keyed data parts upsert in place rather than duplicating`() = runTest {
        val events = flowOf(
            dataChunk("data-progress", "10%", id = "p1"),
            dataChunk("data-progress", "50%", id = "p1"),
            dataChunk("data-progress", "100%", id = "p1"),
        )
        val final = streamToUiMessages(events, assistantMessageId = "a1").toList().last()
        val dataParts = final.parts.filterIsInstance<UIMessagePart.Data>()
        assertEquals(1, dataParts.size, "same id collapses to one part")
        assertEquals(JsonPrimitive("100%"), dataParts.single().data, "last value wins")
        assertEquals("p1", dataParts.single().id)
    }

    @Test
    fun `unkeyed data parts append and the transient flag is carried`() = runTest {
        val events = flowOf(
            dataChunk("data-note", "a"),
            dataChunk("data-note", "b"),
            dataChunk("data-toast", "hi", transient = true),
        )
        val final = streamToUiMessages(events, assistantMessageId = "a1").toList().last()
        val dataParts = final.parts.filterIsInstance<UIMessagePart.Data>()
        assertEquals(3, dataParts.size, "unkeyed parts each append")
        assertTrue(dataParts.any { it.transient }, "transient flag carried through")
    }
}
