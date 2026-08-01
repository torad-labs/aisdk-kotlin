package ai.torad.aisdk

import ai.torad.aisdk.providers.Voyage
import ai.torad.aisdk.providers.VoyageProviderSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VoyageDefensiveParsingTest {
    /**
     * Regression (the M4 bug-class): voyageErrorMessage probed `message` via
     * `?.jsonPrimitive?.contentOrNull`, which throws on a present-but-non-primitive field — so
     * building the error message for a 4xx crashed with IllegalArgumentException instead of
     * surfacing the structured error. The safe `(X as? JsonPrimitive)?.…` degrades to null -> the
     * raw-body fallback. The parser is private, so this drives it through the public model.
     */
    @Test
    fun `embed surfaces the structured error on a non-primitive message`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"message":{"oops":1}}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val model = Voyage(client, VoyageProviderSettings { apiKey("key") }).embedding(ModelId("voyage-4"))

        val error = assertFails {
            model.embed(
                EmbeddingModelCallParams {
                    values(listOf("hi"))
                }
            )
        }

        assertTrue(
            error.message?.contains("Voyage request failed") == true,
            "the structured Voyage error is built, not an IllegalArgumentException from jsonPrimitive",
        )
    }

    /**
     * Regression (Wave 7b, array-element accessors): the rerank parser read each `data` element via
     * the non-null `item.jsonObject`, throwing ISE on a non-object element. The safe
     * `item as? JsonObject ?: return@mapNotNull null` drops the malformed element; valid ones survive.
     */
    @Test
    fun `rerank drops a malformed data element instead of crashing`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"data":[{"index":0,"relevance_score":0.9},"malformed"]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val result = Voyage(client, VoyageProviderSettings { apiKey("key") })
            .reranking(ModelId("rerank-2"))
            .rerank(
                RerankingParams {
                    query("q")
                    documents(listOf("a", "b"))
                }
            )
        assertEquals(1, result.results.size)
    }

    /**
     * Regression (Wave 7b): the embed parser read each `data` row via the non-null `item.jsonObject`
     * to reach `embedding`. The safe `(item as? JsonObject)?.get("embedding")` degrades a malformed
     * row to an empty embedding in place — the row count (and thus index alignment) is preserved.
     */
    @Test
    fun `embed degrades a malformed data row to an empty embedding instead of crashing`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"data":[{"embedding":[0.1,0.2]},"malformed"]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val result = Voyage(client, VoyageProviderSettings { apiKey("key") })
            .embedding(ModelId("voyage-4"))
            .embed(
                EmbeddingModelCallParams {
                    values(listOf("hi", "yo"))
                }
            )
        assertEquals(2, result.embeddings.size)
        assertTrue(result.embeddings[1].isEmpty(), "the malformed row degrades to an empty embedding, count preserved")
    }

    @Test
    fun `embed rejects custom dtype base64 because its storage is unknowable`() = runTest {
        val error = base64EmbeddingFailure(
            responseBody = """{"data":[{"embedding":"AQI="}]}""",
            outputDtype = "float16",
        )

        assertTrue(error.message.orEmpty().contains("cannot decode base64 for custom output_dtype 'float16'"))
    }

    @Test
    fun `embed rejects float base64 whose byte count is not float32 aligned`() = runTest {
        val error = base64EmbeddingFailure(
            responseBody = """{"data":[{"embedding":"AACA"}]}""",
            outputDtype = "float",
        )

        assertTrue(error.message.orEmpty().contains("float32 byte length divisible by 4, got 3"))
    }

    @Test
    fun `embed rejects malformed base64 for a known dtype`() = runTest {
        val error = base64EmbeddingFailure(
            responseBody = """{"data":[{"embedding":"***"}]}""",
            outputDtype = "uint8",
        )

        assertTrue(error.message.orEmpty().contains("expected valid base64 for output_dtype 'uint8'"))
    }

    @Test
    fun `embed rejects base64 when output dtype is present but not a JSON string`() = runTest {
        val cases = listOf(
            "number" to JsonPrimitive(8),
            "boolean" to JsonPrimitive(true),
            "object" to buildJsonObject { put("kind", JsonPrimitive("invalid")) },
            "array" to JsonArray(listOf(JsonPrimitive("invalid"))),
            "null" to JsonNull,
        )

        for ((name, rawOutputDtype) in cases) {
            val (error, fixture) = base64EmbeddingFailureWithRequest(
                responseBody = """{"data":[{"embedding":"AACAPw=="}]}""",
                outputDtype = rawOutputDtype,
            )

            assertEquals("voyage.embedding", error.provider, name)
            assertEquals("embedding response", error.operation, name)
            assertEquals("$.data[0].embedding", error.path, name)
            assertTrue(
                error.message.orEmpty().contains(
                    "present output_dtype must be a JSON string for base64 decoding",
                ),
                name,
            )
            assertEquals(1, fixture.calls.size, name)
            val requestBody = fixture.calls.single().requestBodyJson.jsonObject
            assertEquals(rawOutputDtype, requestBody["output_dtype"], name)
            assertEquals(JsonPrimitive("base64"), requestBody["encoding_format"], name)
        }
    }

    private suspend fun base64EmbeddingFailure(
        responseBody: String,
        outputDtype: String,
    ): WireDecodeException =
        base64EmbeddingFailureWithRequest(responseBody, JsonPrimitive(outputDtype)).first

    private suspend fun base64EmbeddingFailureWithRequest(
        responseBody: String,
        outputDtype: JsonElement,
    ): Pair<WireDecodeException, CreatedTestServer> {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://voyage.test/v1/embeddings" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement(responseBody)),
                ),
            ),
        )
        fixture.server.start()
        val model = Voyage(
            fixture.httpClient(),
            VoyageProviderSettings { baseURL("https://voyage.test/v1") },
        ).embedding(ModelId("voyage-4"))
        val error = assertFailsWith<WireDecodeException> {
            model.embed(
                EmbeddingModelCallParams {
                    values(listOf("value"))
                    providerOptions(
                        ProviderOptions.Raw(
                            JsonObject(
                                mapOf(
                                    "voyage" to buildJsonObject {
                                        put("outputDtype", outputDtype)
                                        put("encodingFormat", JsonPrimitive("base64"))
                                    },
                                ),
                            ),
                        ),
                    )
                },
            )
        }
        return error to fixture
    }
}
