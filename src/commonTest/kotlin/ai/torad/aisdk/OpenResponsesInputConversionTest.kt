@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk
import ai.torad.aisdk.ToolResultOutputs.toJsonElement
import ai.torad.aisdk.providers.OpenResponses
import ai.torad.aisdk.providers.OpenResponsesProviderSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
class OpenResponsesInputConversionTest {
    @Test
    fun `tool call and tool result content convert back to Open Responses input`() = runTest {
        val seenBodies = mutableListOf<JsonObject>()
        val client = HttpClient(
            MockEngine { request ->
                seenBodies += Json.parseToJsonElement(requestBodyText(request)).jsonObject
                respond(
                    content = """{"id":"resp_2","created_at":1780000001,"model":"gpt-resp","output":[{"type":"message","id":"msg_2","role":"assistant","content":[{"type":"output_text","text":"done"}]}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = OpenResponses(client, OpenResponsesProviderSettings {
            url("https://api.test/v1/responses");
            name("openresponses")
        })

        val modelVisible = ToolResultOutput.Content(
            value = listOf(
                buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive("rendered"))
                },
                buildJsonObject {
                    put("type", JsonPrimitive("image-data"))
                    put("mediaType", JsonPrimitive("image/png"))
                    put("data", JsonPrimitive("iVBORw0="))
                },
            ),
        ).toJsonElement()
        provider.languageModel("gpt-resp").generate(
            LanguageModelCallParams {
                messages(
                    listOf(
                        ModelMessage(
                            MessageRole.Assistant,
                            listOf(
                                ContentPart.ToolCall(
                                    toolCallId = "call_1",
                                    toolName = "render",
                                    input = buildJsonObject { put("topic", JsonPrimitive("logo")) },
                                ),
                            ),
                        ),
                        ModelMessage(
                            MessageRole.Tool,
                            listOf(
                                ContentPart.ToolResult(
                                    toolCallId = "call_1",
                                    toolName = "render",
                                    output = JsonPrimitive("full"),
                                    modelVisible = modelVisible,
                                ),
                            ),
                        ),
                    )
                )
            },
        )

        val input = seenBodies.single()["input"]!!.jsonArray
        assertEquals("function_call", input[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("function_call_output", input[1].jsonObject["type"]!!.jsonPrimitive.content)
        val output = input[1].jsonObject["output"]!!.jsonArray
        assertEquals("input_text", output[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("rendered", output[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("input_image", output[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/png;base64,iVBORw0=", output[1].jsonObject["image_url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `raw tool result colliding on a tag discriminator is forwarded verbatim`() = runTest {
        val seenBodies = mutableListOf<JsonObject>()
        val client = HttpClient(
            MockEngine { request ->
                seenBodies += Json.parseToJsonElement(requestBodyText(request)).jsonObject
                respond(
                    content = """{"id":"resp_4","created_at":1780000003,"model":"gpt-resp",""" +
                        """"output":[{"type":"message","id":"msg_4","role":"assistant",""" +
                        """"content":[{"type":"output_text","text":"done"}]}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = OpenResponses(client, OpenResponsesProviderSettings {
            url("https://api.test/v1/responses");
            name("openresponses")
        })

        // modelVisible defaults to the tool's RAW output. This success object collides on the
        // "text" discriminator yet carries no `value` companion: the old path threw a hard
        // model-call failure here. It must now be forwarded to the model verbatim (regression
        // for OpenResponsesProvider:996).
        val rawModelVisible = buildJsonObject {
            put("type", JsonPrimitive("text"))
            put("message", JsonPrimitive("hi"))
        }
        provider.languageModel("gpt-resp").generate(
            LanguageModelCallParams {
                messages(
                    listOf(
                        ModelMessage(
                            MessageRole.Tool,
                            listOf(
                                ContentPart.ToolResult(
                                    toolCallId = "call_raw",
                                    toolName = "lookup",
                                    output = rawModelVisible,
                                    modelVisible = rawModelVisible,
                                ),
                            ),
                        ),
                    )
                )
            },
        )

        val output = seenBodies.single().getValue("input").jsonArray.single().jsonObject
        assertEquals("function_call_output", output.getValue("type").jsonPrimitive.content)
        assertEquals(rawModelVisible.toString(), output.getValue("output").jsonPrimitive.content)
    }

    @Test
    fun `user file content does not infer file id from decodable base64 prefix`() = runTest {
        val seenBodies = mutableListOf<JsonObject>()
        val client = HttpClient(
            MockEngine { request ->
                seenBodies += Json.parseToJsonElement(requestBodyText(request)).jsonObject
                respond(
                    content = """{"id":"resp_3","created_at":1780000002,"model":"gpt-resp","output":[{"type":"message","id":"msg_3","role":"assistant","content":[{"type":"output_text","text":"ok"}]}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = OpenResponses(
            client,
            OpenResponsesProviderSettings {
                url("https://api.test/v1/responses")
                name("openresponses")
                fileIdPrefixes(listOf("file-"))
            },
        )

        provider.languageModel("gpt-resp").generate(
            LanguageModelCallParams {
                messages(
                    listOf(
                        ModelMessage(
                            MessageRole.User,
                            listOf(
                                ContentPart.File(
                                    mediaType = "application/pdf",
                                    base64 = "file-abc",
                                    filename = "payload.pdf",
                                ),
                                ContentPart.File(
                                    mediaType = "application/pdf",
                                    base64 = "ignored",
                                    filename = "remote.pdf",
                                    providerMetadata = ProviderMetadata.Raw(
                                        JsonObject(
                                            mapOf(
                                                "openai" to buildJsonObject {
                                                    put("file_id", JsonPrimitive("file-explicit"))
                                                },
                                            )
                                        )
                                    ),
                                ),
                            ),
                        ),
                    )
                )
            },
        )

        val content = seenBodies.single()["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        val payload = content[0].jsonObject
        val explicit = content[1].jsonObject
        assertEquals("data:application/pdf;base64,file-abc", payload["file_data"]!!.jsonPrimitive.content)
        assertEquals(null, payload["file_id"])
        assertEquals("file-explicit", explicit["file_id"]!!.jsonPrimitive.content)
        assertEquals(null, explicit["file_data"])
    }

    @Test
    fun `user URL media maps to URL fields and base64 media stays inline`() = runTest {
        val seenBodies = mutableListOf<JsonObject>()
        val client = HttpClient(
            MockEngine { request ->
                seenBodies += Json.parseToJsonElement(requestBodyText(request)).jsonObject
                respond(
                    content = """{"id":"resp_5","created_at":1780000004,"model":"gpt-resp","output":[{"type":"message","id":"msg_5","role":"assistant","content":[{"type":"output_text","text":"ok"}]}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = OpenResponses(client, OpenResponsesProviderSettings {
            url("https://api.test/v1/responses");
            name("openresponses")
        })
        val imageUrl = "https://cdn.test/image.png"
        val fileUrl = "https://cdn.test/paper.pdf"

        provider.languageModel("gpt-resp").generate(
            LanguageModelCallParams {
                messages(
                    listOf(
                        ModelMessage(
                            MessageRole.User,
                            listOf(
                                ContentPart.Image(mediaType = "image/png", url = imageUrl),
                                ContentPart.File(mediaType = "application/pdf", url = fileUrl, filename = "paper.pdf"),
                                ContentPart.Image(mediaType = "image/jpeg", base64 = "aW1n"),
                                ContentPart.File(mediaType = "text/plain", base64 = "ZG9j", filename = "note.txt"),
                            ),
                        ),
                    )
                )
            },
        )

        val content = seenBodies.single().getValue("input")
            .jsonArray
            .single()
            .jsonObject
            .getValue("content")
            .jsonArray
        val urlImage = content[0].jsonObject
        assertEquals("input_image", urlImage.getValue("type").jsonPrimitive.content)
        assertEquals(imageUrl, urlImage.getValue("image_url").jsonPrimitive.content)
        assertEquals(null, urlImage["file_id"])
        val urlFile = content[1].jsonObject
        assertEquals("input_file", urlFile.getValue("type").jsonPrimitive.content)
        assertEquals(fileUrl, urlFile.getValue("file_url").jsonPrimitive.content)
        assertEquals(null, urlFile["file_data"])
        val base64Image = content[2].jsonObject
        assertEquals("input_image", base64Image.getValue("type").jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,aW1n", base64Image.getValue("image_url").jsonPrimitive.content)
        assertEquals(null, base64Image["file_url"])
        val base64File = content[3].jsonObject
        assertEquals("input_file", base64File.getValue("type").jsonPrimitive.content)
        assertEquals("data:text/plain;base64,ZG9j", base64File.getValue("file_data").jsonPrimitive.content)
        assertEquals("note.txt", base64File.getValue("filename").jsonPrimitive.content)
        assertEquals(null, base64File["file_url"])
    }

    private fun requestBodyText(request: HttpRequestData): String =
        when (val body = request.body) {
            is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
            is OutgoingContent.NoContent -> ""
            else -> body.toString()
        }
}
