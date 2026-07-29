package ai.torad.aisdk
import ai.torad.aisdk.providers.VOYAGE_VERSION
import ai.torad.aisdk.providers.Voyage
import ai.torad.aisdk.providers.VoyageProviderSettings
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VoyageProviderTest {
    @Test
    fun `embedding model sends voyage request shape and parses usage`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://voyage.test/v1/embeddings" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"data":[{"embedding":[0.1,0.2],"index":1},{"embedding":[0.3,0.4],"index":0}],"usage":{"total_tokens":7}}""",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val model = Voyage(
            fixture.httpClient(),
            VoyageProviderSettings {
                apiKey("key")
                baseURL("https://voyage.test/v1")
            },
        ).embedding(ModelId("voyage-4"))

        val result = model.embed(
            EmbeddingModelCallParams {
                values(listOf("first", "second"))
                providerOptions(
                    ProviderOptions.Raw(
                        JsonObject(
                            mapOf(
                                "voyage" to buildJsonObject {
                                    put("inputType", JsonPrimitive("document"))
                                    put("truncation", JsonPrimitive(true))
                                    put("outputDimension", JsonPrimitive(256))
                                    put("outputDtype", JsonPrimitive("int8"))
                                },
                            )
                        )
                    )
                )
            },
        )

        assertEquals("voyage.embedding", model.provider)
        assertEquals(1000, model.maxEmbeddingsPerCall)
        assertEquals(true, model.supportsParallelCalls)
        assertEquals(listOf(listOf(0.1f, 0.2f), listOf(0.3f, 0.4f)), result.embeddings)
        assertEquals(7, result.usage.tokens)
        assertVoyageRepresentation(
            result = result,
            rawOutputDtype = JsonPrimitive("int8"),
            effectiveOutputDtype = "int8",
            packing = "none",
            logicalDimension = 256,
            storedElementCounts = listOf(2, 2),
        )
        val request = fixture.calls.single()
        assertEquals("POST", request.requestMethod)
        assertEquals("https://voyage.test/v1/embeddings", request.requestUrl)
        assertEquals("Bearer key", request.requestHeaders.headerValue(HttpHeaders.Authorization))
        assertTrue(request.requestUserAgent.orEmpty().contains("ai-sdk/voyage/$VOYAGE_VERSION"))
        val body = request.requestBodyJson.jsonObject
        assertEquals("voyage-4", body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("first", body["input"]?.jsonArray?.first()?.jsonPrimitive?.contentOrNull)
        assertEquals("document", body["input_type"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, body["truncation"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(256, body["output_dimension"]?.jsonPrimitive?.intOrNull)
        assertEquals("int8", body["output_dtype"]?.jsonPrimitive?.contentOrNull)
    }

    private val voyageBase64DtypeCases: List<VoyageBase64EmbeddingCase> = listOf(
        VoyageBase64EmbeddingCase(null, "AACAPwAAIMA=", listOf(1f, -2.5f), "none", 2),
        VoyageBase64EmbeddingCase("int8", "gH8=", listOf(-128f, 127f), "none", 2),
        VoyageBase64EmbeddingCase("uint8", "AP8=", listOf(0f, 255f), "none", 2),
        // binary/ubinary are BIT-PACKED: 8 single-bit dimensions per stored byte, so two bytes
        // decode to 16 values, matching the logicalDimension the metadata reports. `binary` is
        // offset binary — the wire int8 is (packed_uint8 - 128) — so 0x80 is packed 0 (all bits
        // clear) and 0x7F is packed 255 (all bits set); ubinary uses the byte directly.
        VoyageBase64EmbeddingCase("binary", "gH8=", List(8) { 0f } + List(8) { 1f }, "bit-packed", 16),
        VoyageBase64EmbeddingCase("ubinary", "AP8=", List(8) { 0f } + List(8) { 1f }, "bit-packed", 16),
        // Voyage's own documented worked example, which pins BOTH the bit order (MSB first) and the
        // offset: bits 0,1,0,0,1,1,0,1 pack to 01001101 = 77, reported as ubinary 77 and as binary
        // -51 (77 - 128). The uniform bytes above cannot catch a reversed bit order.
        VoyageBase64EmbeddingCase("ubinary", "TQ==", listOf(0f, 1f, 0f, 0f, 1f, 1f, 0f, 1f), "bit-packed", 8),
        VoyageBase64EmbeddingCase("binary", "zQ==", listOf(0f, 1f, 0f, 0f, 1f, 1f, 0f, 1f), "bit-packed", 8),
    )

    private val voyageJsonDtypeCases: List<VoyageJsonEmbeddingCase> = listOf(
        VoyageJsonEmbeddingCase("float", "[1.5,-2.25]", listOf(1.5f, -2.25f), "none", 2),
        VoyageJsonEmbeddingCase("int8", "[-128,127]", listOf(-128f, 127f), "none", 2),
        VoyageJsonEmbeddingCase("uint8", "[0,255]", listOf(0f, 255f), "none", 2),
        // A JSON-array response carries the same packed integers as base64, so the bit-packed
        // dtypes expand identically here: `binary` -128 is packed 0, 127 is packed 255.
        VoyageJsonEmbeddingCase("binary", "[-128,127]", List(8) { 0f } + List(8) { 1f }, "bit-packed", 16),
        VoyageJsonEmbeddingCase("ubinary", "[0,255]", List(8) { 0f } + List(8) { 1f }, "bit-packed", 16),
        // Voyage's documented example again, over the JSON-array path: ubinary 77 / binary -51 both
        // unpack to bits 0,1,0,0,1,1,0,1. Pins that both encodings agree.
        VoyageJsonEmbeddingCase("ubinary", "[77]", listOf(0f, 1f, 0f, 0f, 1f, 1f, 0f, 1f), "bit-packed", 8),
        VoyageJsonEmbeddingCase("binary", "[-51]", listOf(0f, 1f, 0f, 0f, 1f, 1f, 0f, 1f), "bit-packed", 8),
    )

    @Test
    fun `embedding model preserves all known JSON array dtypes and describes storage`() = runTest {
        for (case in voyageJsonDtypeCases) {
            val responseBody = Json.parseToJsonElement(
                """{"data":[{"embedding":${case.rowJson}}]}""",
            )
            val (result, fixture) = executeVoyageEmbedding(
                responseBody = responseBody,
                voyageOptions = buildJsonObject {
                    put("outputDtype", JsonPrimitive(case.outputDtype))
                },
            )

            assertEquals(listOf(case.expected), result.embeddings, case.outputDtype)
            assertEquals(
                case.outputDtype,
                fixture.calls.single().requestBodyJson.jsonObject["output_dtype"]?.jsonPrimitive?.contentOrNull,
            )
            assertVoyageRepresentation(
                result = result,
                rawOutputDtype = JsonPrimitive(case.outputDtype),
                effectiveOutputDtype = case.outputDtype,
                packing = case.packing,
                logicalDimension = case.logicalDimension,
                storedElementCounts = listOf(
                    if (case.packing == "bit-packed") case.expected.size / 8 else case.expected.size,
                ),
            )
        }
    }

    @Test
    fun `embedding model preserves present-invalid dtypes for numeric arrays with honest metadata`() = runTest {
        val cases = listOf(
            VoyagePresentInvalidDtypeCase("number", JsonPrimitive(8), requestedOutputDimension = 256),
            VoyagePresentInvalidDtypeCase("boolean", JsonPrimitive(true)),
            VoyagePresentInvalidDtypeCase(
                "object",
                buildJsonObject { put("kind", JsonPrimitive("invalid")) },
            ),
            VoyagePresentInvalidDtypeCase(
                "array",
                JsonArray(listOf(JsonPrimitive("invalid"))),
            ),
            VoyagePresentInvalidDtypeCase("null", JsonNull),
        )

        for (case in cases) {
            val (result, fixture) = executeVoyageEmbedding(
                responseBody = Json.parseToJsonElement(
                    """{"data":[{"embedding":[1,-2.5]}]}""",
                ),
                voyageOptions = buildJsonObject {
                    put("outputDtype", case.rawOutputDtype)
                    case.requestedOutputDimension?.let {
                        put("outputDimension", JsonPrimitive(it))
                    }
                },
            )

            assertEquals(listOf(listOf(1f, -2.5f)), result.embeddings, case.name)
            assertEquals(1, fixture.calls.size, case.name)
            val requestBody = fixture.calls.single().requestBodyJson.jsonObject
            assertEquals(case.rawOutputDtype, requestBody["output_dtype"], case.name)
            assertEquals(
                case.requestedOutputDimension,
                (requestBody["output_dimension"] as? JsonPrimitive)?.intOrNull,
                case.name,
            )
            assertVoyageRepresentation(
                result = result,
                rawOutputDtype = case.rawOutputDtype,
                effectiveOutputDtype = null,
                packing = "unknown",
                logicalDimension = case.requestedOutputDimension,
                storedElementCounts = listOf(2),
            )
        }
    }

    @Test
    fun `embedding model decodes all base64 storage classes and preserves the raw response`() = runTest {
        for (case in voyageBase64DtypeCases) {
            val responseBody = Json.parseToJsonElement(
                """{"data":[{"embedding":"${case.base64}"}],"usage":{"total_tokens":1}}""",
            )
            val (result, fixture) = executeVoyageEmbedding(
                responseBody = responseBody,
                voyageOptions = buildJsonObject {
                    case.outputDtype?.let { put("outputDtype", JsonPrimitive(it)) }
                    put("encodingFormat", JsonPrimitive("base64"))
                },
            )

            assertEquals(listOf(case.expected), result.embeddings, case.outputDtype ?: "default float")
            assertEquals(responseBody, result.response.body)
            assertEquals(1, fixture.calls.size)
            val requestBody = fixture.calls.single().requestBodyJson.jsonObject
            assertEquals("base64", requestBody["encoding_format"]?.jsonPrimitive?.contentOrNull)
            assertEquals(case.outputDtype?.let(::JsonPrimitive), requestBody["output_dtype"])
            if (case.outputDtype == null) {
                assertTrue("output_dtype" !in requestBody)
            }
            assertVoyageRepresentation(
                result = result,
                rawOutputDtype = case.outputDtype?.let(::JsonPrimitive),
                effectiveOutputDtype = case.outputDtype ?: "float",
                packing = case.packing,
                logicalDimension = case.logicalDimension,
                // Stored count is the WIRE element count: one eighth of the decoded length for
                // the bit-packed dtypes, equal for the byte-per-value ones.
                storedElementCounts = listOf(
                    if (case.packing == "bit-packed") case.expected.size / 8 else case.expected.size,
                ),
            )
        }
    }

    @Test
    fun `embedding model preserves custom JSON dtype with unknown packing`() = runTest {
        val result = executeVoyageEmbedding(
            responseBody = Json.parseToJsonElement(
                """{"data":[{"embedding":[1,2.5]}]}""",
            ),
            voyageOptions = buildJsonObject {
                put("outputDtype", JsonPrimitive("float16"))
            },
        ).first

        assertEquals(listOf(listOf(1f, 2.5f)), result.embeddings)
        assertVoyageRepresentation(
            result = result,
            rawOutputDtype = JsonPrimitive("float16"),
            effectiveOutputDtype = "float16",
            packing = "unknown",
            logicalDimension = null,
            storedElementCounts = listOf(2),
        )
    }

    @Test
    fun `embedding model records irregular rows without rejecting count or dimension mismatches`() = runTest {
        val result = executeVoyageEmbedding(
            responseBody = Json.parseToJsonElement(
                """{"data":[{"embedding":[1,2]},{"embedding":[]},{"embedding":[3,4,5]}]}""",
            ),
            values = listOf("first", "second"),
            voyageOptions = buildJsonObject {
                put("outputDtype", JsonPrimitive("int8"))
            },
        ).first

        assertEquals(listOf(listOf(1f, 2f), emptyList(), listOf(3f, 4f, 5f)), result.embeddings)
        assertVoyageRepresentation(
            result = result,
            rawOutputDtype = JsonPrimitive("int8"),
            effectiveOutputDtype = "int8",
            packing = "none",
            logicalDimension = null,
            storedElementCounts = listOf(2, 0, 3),
        )
    }

    @Test
    fun `embedding model records empty response data without inventing a dimension`() = runTest {
        val result = executeVoyageEmbedding(
            responseBody = Json.parseToJsonElement("""{"data":[]}"""),
            voyageOptions = buildJsonObject {
                put("outputDtype", JsonPrimitive("float"))
            },
        ).first

        assertEquals(emptyList(), result.embeddings)
        assertVoyageRepresentation(
            result = result,
            rawOutputDtype = JsonPrimitive("float"),
            effectiveOutputDtype = "float",
            packing = "none",
            logicalDimension = null,
            storedElementCounts = emptyList(),
        )
    }

    @Test
    fun `embedding model forwards snake encoding format and gives camel spelling precedence`() = runTest {
        val cases = listOf(
            buildJsonObject {
                put("encoding_format", JsonPrimitive("base64"))
            },
            buildJsonObject {
                put("encoding_format", JsonPrimitive("float"))
                put("encodingFormat", JsonPrimitive("base64"))
            },
        )

        for (voyageOptions in cases) {
            val (_, fixture) = executeVoyageEmbedding(
                responseBody = Json.parseToJsonElement(
                    """{"data":[{"embedding":"AACAPwAAIMA="}]}""",
                ),
                voyageOptions = voyageOptions,
            )
            assertEquals(
                "base64",
                fixture.calls.single().requestBodyJson.jsonObject["encoding_format"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    @Test
    fun `reranking model sends voyage request shape and maps ranking`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://voyage.test/v1/rerank" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"data":[{"index":1,"relevance_score":0.9},{"index":0,"relevance_score":0.2}],"usage":{"total_tokens":11}}""",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val model = Voyage(
            fixture.httpClient(),
            VoyageProviderSettings {
                apiKey("key")
                baseURL("https://voyage.test/v1")
            },
        ).reranking(ModelId("rerank-2.5"))

        val result = model.rerank(
            RerankingParams {
                query("best")
                documents(listOf("alpha", "beta"))
                topN(1)
                providerOptions(
                    ProviderOptions.Raw(
                        JsonObject(
                            mapOf(
                                "voyage" to buildJsonObject {
                                    put("returnDocuments", JsonPrimitive(false))
                                    put("truncation", JsonPrimitive(true))
                                },
                            )
                        )
                    )
                )
            },
        )

        assertEquals("voyage.reranking", model.provider)
        assertEquals("beta", result.results.first().value)
        assertEquals(0.9f, result.results.first().score)
        assertEquals(11, result.usage.promptTokens)
        val body = fixture.calls.single().requestBodyJson.jsonObject
        assertEquals("rerank-2.5", body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("best", body["query"]?.jsonPrimitive?.contentOrNull)
        assertEquals("alpha", body["documents"]?.jsonArray?.first()?.jsonPrimitive?.contentOrNull)
        assertEquals(1, body["top_k"]?.jsonPrimitive?.intOrNull)
        assertEquals(false, body["return_documents"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, body["truncation"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `embedding model accepts 1000 values and rejects 1001 before transport`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://voyage.test/v1/embeddings" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"data":[]}""")),
                ),
            ),
        )
        fixture.server.start()
        val model = Voyage(
            fixture.httpClient(),
            VoyageProviderSettings { baseURL("https://voyage.test/v1") },
        ).embedding(ModelId("voyage-4"))

        val accepted = model.embed(
            EmbeddingModelCallParams {
                values(List(1000) { "value-$it" })
            },
        )
        assertTrue(accepted.embeddings.isEmpty())
        assertEquals(1, fixture.calls.size)

        val error = assertFailsWith<InvalidArgumentError> {
            model.embed(
                EmbeddingModelCallParams {
                    values(List(1001) { "value-$it" })
                },
            )
        }

        assertTrue(error.message.orEmpty().contains("1000 values"))
        assertEquals(1, fixture.calls.size)
    }

    private suspend fun executeVoyageEmbedding(
        responseBody: JsonElement,
        voyageOptions: JsonObject = JsonObject(emptyMap()),
        values: List<String> = listOf("value"),
    ): Pair<EmbeddingModelResult, CreatedTestServer> {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://voyage.test/v1/embeddings" to UrlHandler(
                    UrlResponse.JsonValue(responseBody),
                ),
            ),
        )
        fixture.server.start()
        val model = Voyage(
            fixture.httpClient(),
            VoyageProviderSettings { baseURL("https://voyage.test/v1") },
        ).embedding(ModelId("voyage-4"))
        val result = model.embed(
            EmbeddingModelCallParams {
                values(values)
                if (voyageOptions.isNotEmpty()) {
                    providerOptions(
                        ProviderOptions.Raw(
                            JsonObject(mapOf("voyage" to voyageOptions)),
                        ),
                    )
                }
            },
        )
        return result to fixture
    }

    private fun assertVoyageRepresentation(
        result: EmbeddingModelResult,
        rawOutputDtype: JsonElement?,
        effectiveOutputDtype: String?,
        packing: String,
        logicalDimension: Int?,
        storedElementCounts: List<Int>,
    ) {
        val voyageMetadata = result.providerMetadata.toMap().getValue("voyage").jsonObject
        assertEquals(setOf("embeddingRepresentation"), voyageMetadata.keys)
        val representation = voyageMetadata.getValue("embeddingRepresentation").jsonObject
        assertEquals(
            setOf(
                "rawOutputDtype",
                "effectiveOutputDtype",
                "packing",
                "logicalDimension",
                "storedElementCounts",
            ),
            representation.keys,
        )
        assertEquals(rawOutputDtype ?: JsonNull, representation["rawOutputDtype"])
        assertEquals(
            effectiveOutputDtype?.let(::JsonPrimitive) ?: JsonNull,
            representation["effectiveOutputDtype"],
        )
        assertEquals(JsonPrimitive(packing), representation["packing"])
        assertEquals(logicalDimension?.let(::JsonPrimitive) ?: JsonNull, representation["logicalDimension"])
        assertEquals(
            JsonArray(storedElementCounts.map(::JsonPrimitive)),
            representation["storedElementCounts"],
        )
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private class VoyageJsonEmbeddingCase(
        val outputDtype: String,
        val rowJson: String,
        val expected: List<Float>,
        val packing: String,
        val logicalDimension: Int,
    )

    private class VoyagePresentInvalidDtypeCase(
        val name: String,
        val rawOutputDtype: JsonElement,
        val requestedOutputDimension: Int? = null,
    )

    private class VoyageBase64EmbeddingCase(
        val outputDtype: String?,
        val base64: String,
        val expected: List<Float>,
        val packing: String,
        val logicalDimension: Int,
    )
}
