# Provider capability matrix

This matrix is the beta hardening ledger for provider/protocol conformance. It is intentionally source-controlled (not generated) so capability decisions are reviewable. `tools/check-provider-capabilities.mjs` verifies that every public provider class in `src/commonMain/kotlin/ai/torad/aisdk/providers` has a row here and that each capability cell uses the fixed vocabulary below.

Capability values:

- `yes` — represented by the provider facade and expected to be covered by provider tests/goldens.
- `partial` — supported for some model families or via a compatibility adapter/synthetic stream.
- `no` — intentionally unsupported by this provider class.
- `n/a` — not applicable to this provider type.

| Provider class | Factory/package surface | Language generate/stream | Tools | Structured output | Images/files | Embeddings | Speech/transcription/video | Provider-executed tools | Response metadata/usage | Retry/error envelope | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| AlibabaProvider | Alibaba | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | no | yes | yes | no | yes | yes | Qwen chat plus DashScope embeddings and video. |
| AmazonBedrockProvider | AmazonBedrock | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | partial | yes | no | no | yes | yes | Converse plus Bedrock embeddings, image, and reranking adapters. |
| AnthropicAwsProvider | AnthropicAws | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | partial | no | no | partial | yes | yes | AWS-hosted Anthropic messages. |
| AnthropicProvider | Anthropic | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | partial | no | no | yes | yes | yes | Messages API with hosted tools, files, thinking, and MCP options. |
| AssemblyAIProvider | AssemblyAI | no | no | no | n/a | no | yes | no | yes | yes | Transcription-only provider. |
| AzureOpenAIProvider | AzureOpenAI | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | partial | yes | partial | yes | yes | yes | Azure OpenAI chat/responses plus media model routing. |
| BasetenProvider | Baseten | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | no | yes | no | no | yes | yes | OpenAI-compatible chat and embeddings. |
| BedrockAnthropicProvider | BedrockAnthropic | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | partial | no | no | partial | yes | yes | Bedrock-hosted Anthropic messages. |
| BedrockMantleProvider | BedrockMantle | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | no | no | no | no | yes | yes | Mantle OpenAI-compatible chat through Bedrock settings. |
| BlackForestLabsProvider | BlackForestLabs | no | no | no | yes | no | no | no | yes | yes | Image generation and editing provider. |
| ByteDanceProvider | ByteDance | no | no | no | partial | no | yes | no | yes | yes | Video task provider with image/video inputs. |
| CerebrasProvider | Cerebras | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | no | no | no | no | yes | yes | OpenAI-compatible chat facade. |
| CohereProvider | Cohere | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | partial | yes | no | no | yes | yes | Chat, embeddings, reranking, citations, and documents. |
| DeepInfraProvider | DeepInfra | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | partial | yes | no | no | yes | yes | OpenAI-compatible chat, embeddings, and image routing. |
| DeepSeekProvider | DeepSeek | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | no | no | no | no | yes | yes | OpenAI-compatible chat facade. |
| DeepgramProvider | Deepgram | no | no | no | n/a | no | yes | no | yes | yes | Speech and transcription provider. |
| ElevenLabsProvider | ElevenLabs | no | no | no | n/a | no | yes | no | yes | yes | Speech and transcription provider. |
| FalProvider | Fal | no | no | no | yes | no | yes | no | yes | yes | Image, speech, transcription, and video queues. |
| FireworksProvider | Fireworks | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | yes | yes | no | no | yes | yes | OpenAI-compatible chat, embeddings, and image backend. |
| GladiaProvider | Gladia | no | no | no | n/a | no | yes | no | yes | yes | Transcription upload/init/poll provider. |
| GoogleGenerativeAIProvider | GoogleGenerativeAI | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | yes | yes | yes | yes | yes | yes | Gemini, Imagen, Veo, embeddings, hosted tools, and interactions. |
| GoogleVertexAnthropicProvider | GoogleVertexAnthropic | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | partial | no | no | partial | yes | yes | Vertex rawPredict Anthropic adapter. |
| GoogleVertexMaasProvider | GoogleVertexMaas | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | no | no | no | no | yes | yes | Vertex MaaS OpenAI-compatible adapter. |
| GoogleVertexProvider | GoogleVertex | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | yes | yes | yes | yes | yes | yes | Vertex Gemini, embeddings, image/video, and hosted tools. |
| GoogleVertexXaiProvider | GoogleVertexXai | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | no | no | no | yes | yes | yes | Vertex xAI OpenAI-compatible adapter. |
| GroqProvider | Groq | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | no | no | partial | yes | yes | yes | OpenAI-compatible chat plus transcription and hosted tools. |
| HuggingFaceProvider | HuggingFace | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | partial | no | no | partial | yes | yes | Responses-style language adapter. |
| HumeProvider | Hume | no | no | no | n/a | no | yes | no | yes | yes | Speech provider. |
| KlingAIProvider | KlingAI | no | no | no | partial | no | yes | no | yes | yes | Video generation/edit/extend provider. |
| LMNTProvider | LMNT | no | no | no | n/a | no | yes | no | yes | yes | Speech provider. |
| LumaProvider | Luma | no | no | no | yes | no | no | no | yes | yes | Image generation/editing provider. |
| MistralProvider | Mistral | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | partial | yes | no | no | yes | yes | Chat, embeddings, reasoning, and multimodal input. |
| MoonshotAIProvider | MoonshotAI | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | no | no | no | no | yes | yes | OpenAI-compatible chat facade. |
| OpenAICompatibleProvider | OpenAICompatible | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | partial | yes | partial | partial | yes | yes | Generic OpenAI-compatible chat/completion/embed/media adapter. |
| OpenAIProvider | OpenAI | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | yes | yes | partial | yes | yes | yes | Chat/responses, hosted tools, embeddings, image, speech, transcription. |
| OpenResponsesProvider | OpenResponses | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | partial | no | no | yes | yes | yes | Responses API provider. |
| PerplexityProvider | Perplexity | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | no | no | no | no | yes | yes | OpenAI-compatible chat facade. |
| ProdiaProvider | Prodia | partial | partial | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | yes | no | yes | no | yes | yes | Language is job-backed with synthetic stream; image/video also supported. |
| QuiverAIProvider | QuiverAI | no | no | no | yes | no | no | no | yes | yes | Image/SVG provider. |
| ReplicateProvider | Replicate | no | no | no | yes | no | yes | no | yes | yes | Image and video prediction provider. |
| RevaiProvider | Revai | no | no | no | n/a | no | yes | no | yes | yes | Transcription provider. |
| TogetherAIProvider | TogetherAI | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | yes | no | no | no | yes | yes | OpenAI-compatible chat/image plus reranking. |
| VercelProvider | Vercel | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | no | no | no | no | yes | yes | OpenAI-compatible chat facade. |
| VoyageProvider | Voyage | no | no | no | n/a | yes | no | no | yes | yes | Embedding and reranking provider. |
| XaiProvider | Xai | yes | yes | [partial](wiki/structured-output.md#provider-strategy-and-native-json-schema) | yes | no | yes | yes | yes | yes | Chat/responses, hosted tools, image, and video. |
