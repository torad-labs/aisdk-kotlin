# Vercel AI SDK v6 Parity Ledgers

Generated from `.reference/vercel-ai-sdk-ai-6.0.195/packages`.
Run `node tools/generate-parity-ledger.mjs` after refreshing the upstream reference.
Run `node tools/generate-parity-ledger.mjs --check` in CI to verify this directory is current.

- Packages: 56
- Public exports discovered: 1162

| Package | Version | Target module | Upstream exports | Status |
|---|---:|---|---:|---|
| [@ai-sdk/alibaba](ai-sdk-alibaba.md) | 1.0.25 | `:aisdk-provider-alibaba` | 13 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/amazon-bedrock](ai-sdk-amazon-bedrock.md) | 4.0.112 | `:aisdk-provider-amazon-bedrock` | 21 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/angular](ai-sdk-angular.md) | 2.0.196 | `:aisdk-ui` | 5 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/anthropic](ai-sdk-anthropic.md) | 3.0.81 | `:aisdk-provider-anthropic` | 15 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/anthropic-aws](ai-sdk-anthropic-aws.md) | 1.0.3 | `:aisdk-provider-anthropic-aws` | 6 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/assemblyai](ai-sdk-assemblyai.md) | 2.0.33 | `:aisdk-provider-assemblyai` | 6 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/azure](ai-sdk-azure.md) | 3.0.69 | `:aisdk-provider-azure` | 13 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/baseten](ai-sdk-baseten.md) | 1.0.51 | `:aisdk-provider-baseten` | 8 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/black-forest-labs](ai-sdk-black-forest-labs.md) | 1.0.34 | `:aisdk-provider-black-forest-labs` | 9 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/bytedance](ai-sdk-bytedance.md) | 1.0.14 | `:aisdk-provider-bytedance` | 7 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/cerebras](ai-sdk-cerebras.md) | 2.0.54 | `:aisdk-provider-cerebras` | 6 | ported: createCerebras/cerebras, chat provider settings, CerebrasErrorData, and chat model routing are represented as an OpenAI-compatible Kotlin facade folded into the root module; VERSION is exposed as CEREBRAS_VERSION until package modules are split |
| [@ai-sdk/codemod](ai-sdk-codemod.md) | 3.0.6 | `:aisdk-codemod` | 0 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/cohere](ai-sdk-cohere.md) | 3.0.36 | `:aisdk-provider-cohere` | 10 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/deepgram](ai-sdk-deepgram.md) | 2.0.33 | `:aisdk-provider-deepgram` | 10 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/deepinfra](ai-sdk-deepinfra.md) | 2.0.52 | `:aisdk-provider-deepinfra` | 6 | ported: createDeepInfra/deepinfra, provider settings, chat/completion/embedding/image routing, DeepInfraErrorData, and DeepInfra usage correction are represented as a Kotlin facade folded into the root module; VERSION is exposed as DEEPINFRA_VERSION until package modules are split |
| [@ai-sdk/deepseek](ai-sdk-deepseek.md) | 2.0.35 | `:aisdk-provider-deepseek` | 8 | ported: createDeepSeek/deepseek, provider settings, language options, error data alias, and chat model routing are represented as an OpenAI-compatible Kotlin facade folded into the root module; VERSION is exposed as DEEPSEEK_VERSION until package modules are split |
| [@ai-sdk/devtools](ai-sdk-devtools.md) | 0.0.18 | `:aisdk-devtools` | 1 | in-progress: devToolsMiddleware is represented as a Kotlin-native recorder-backed middleware folded into the root module; DB viewer/server storage remains a platform/tooling module concern |
| [@ai-sdk/elevenlabs](ai-sdk-elevenlabs.md) | 2.0.33 | `:aisdk-provider-elevenlabs` | 9 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/fal](ai-sdk-fal.md) | 2.0.34 | `:aisdk-provider-fal` | 12 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/fireworks](ai-sdk-fireworks.md) | 2.0.53 | `:aisdk-provider-fireworks` | 13 | ported: createFireworks/fireworks, provider settings, language/embedding option surfaces, FireworksErrorData, FireworksImageModel, chat/completion/embedding routing, and Fireworks image backend routing are represented as a Kotlin facade folded into the root module; VERSION is exposed as FIREWORKS_VERSION until package modules are split |
| [@ai-sdk/gateway](ai-sdk-gateway.md) | 3.0.123 | `:aisdk-gateway` | 27 | in-progress: gateway facade and Ktor transport are currently folded into the root module |
| [@ai-sdk/gladia](ai-sdk-gladia.md) | 2.0.33 | `:aisdk-provider-gladia` | 6 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/google](ai-sdk-google.md) | 3.0.80 | `:aisdk-provider-google` | 30 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/google-vertex](ai-sdk-google-vertex.md) | 4.0.140 | `:aisdk-provider-google-vertex` | 25 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/groq](ai-sdk-groq.md) | 3.0.39 | `:aisdk-provider-groq` | 9 | ported: createGroq/groq, Groq tools, chat/transcription routing, provider settings, and option surfaces are represented as an OpenAI-compatible Kotlin facade folded into the root module; VERSION is exposed as GROQ_VERSION until package modules are split |
| [@ai-sdk/huggingface](ai-sdk-huggingface.md) | 1.0.50 | `:aisdk-provider-huggingface` | 7 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/hume](ai-sdk-hume.md) | 2.0.33 | `:aisdk-provider-hume` | 6 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/klingai](ai-sdk-klingai.md) | 3.0.18 | `:aisdk-provider-klingai` | 7 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/langchain](ai-sdk-langchain.md) | 2.0.202 | `:aisdk-langchain` | 6 | ported: toBaseMessages, convertModelMessages, toUIMessageStream, StreamCallbacks, and LangSmithDeploymentTransport are represented as Kotlin-native UI/Flow adapters folded into the root module |
| [@ai-sdk/llamaindex](ai-sdk-llamaindex.md) | 2.0.195 | `:aisdk-llamaindex` | 1 | ported: toUIMessageStream is represented as a Kotlin Flow adapter over LlamaIndexEngineResponse, with callback lifecycle support folded into the root module |
| [@ai-sdk/lmnt](ai-sdk-lmnt.md) | 2.0.33 | `:aisdk-provider-lmnt` | 6 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/luma](ai-sdk-luma.md) | 2.0.33 | `:aisdk-provider-luma` | 8 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/mcp](ai-sdk-mcp.md) | 1.0.45 | `:aisdk-mcp` | 28 | in-progress: MCP JSON-RPC contracts, client handshake, capability-gated APIs, dynamic tool conversion, elicitation, OAuth type surface, and stdio API shape are currently folded into the root module; HTTP/SSE/stdio platform transports remain open |
| [@ai-sdk/mistral](ai-sdk-mistral.md) | 3.0.37 | `:aisdk-provider-mistral` | 6 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/moonshotai](ai-sdk-moonshotai.md) | 2.0.23 | `:aisdk-provider-moonshotai` | 7 | ported: createMoonshotAI/moonshotai, provider settings, model id/options aliases, and chat model routing are represented as an OpenAI-compatible Kotlin facade folded into the root module |
| [@ai-sdk/open-responses](ai-sdk-open-responses.md) | 1.0.16 | `:aisdk-open-responses` | 3 | in-progress: createOpenResponses, OpenResponsesOptions, generate/stream response mapping, and fake HTTP tests are folded into the root module; VERSION is exposed as OPEN_RESPONSES_VERSION until package modules are split |
| [@ai-sdk/openai](ai-sdk-openai.md) | 3.0.67 | `:aisdk-openai` | 72 | in-progress: createOpenAI/openai facade and hosted OpenAI tool descriptors are folded into the root module; Responses-specific model transport remains open |
| [@ai-sdk/openai-compatible](ai-sdk-openai-compatible.md) | 2.0.48 | `:aisdk-openai-compatible` | 26 | in-progress: OpenAI-compatible Ktor adapter is currently folded into the root module |
| [@ai-sdk/perplexity](ai-sdk-perplexity.md) | 3.0.33 | `:aisdk-provider-perplexity` | 5 | ported: createPerplexity/perplexity and provider settings are represented as an OpenAI-compatible Kotlin facade folded into the root module; VERSION is exposed as PERPLEXITY_VERSION until package modules are split |
| [@ai-sdk/prodia](ai-sdk-prodia.md) | 1.0.31 | `:aisdk-provider-prodia` | 12 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/provider](ai-sdk-provider.md) | 3.0.10 | `:aisdk-provider` | 122 | in-progress: provider contracts are currently folded into the root module |
| [@ai-sdk/provider-utils](ai-sdk-provider-utils.md) | 4.0.27 | `:aisdk-provider-utils` | 111 | in-progress: provider utility subset is currently folded into the root module |
| [@ai-sdk/quiverai](ai-sdk-quiverai.md) | 1.0.0 | `:aisdk-provider-quiverai` | 7 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/react](ai-sdk-react.md) | 3.0.197 | `:aisdk-ui` | 9 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/replicate](ai-sdk-replicate.md) | 2.0.33 | `:aisdk-provider-replicate` | 10 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/revai](ai-sdk-revai.md) | 2.0.33 | `:aisdk-provider-revai` | 6 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/rsc](ai-sdk-rsc.md) | 2.0.195 | `:aisdk-server` | 29 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/svelte](ai-sdk-svelte.md) | 4.0.195 | `:aisdk-ui` | 8 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/test-server](ai-sdk-test-server.md) | 1.0.5 | `:aisdk-test-server` | 5 | ported: createTestServer, TestResponseController, UrlResponse, UrlHandler, and UrlHandlers are represented as a Kotlin-native in-memory server with a Ktor MockEngine bridge folded into the root module |
| [@ai-sdk/togetherai](ai-sdk-togetherai.md) | 2.0.53 | `:aisdk-provider-togetherai` | 10 | ported: createTogetherAI/togetherai, provider settings, image/reranking option surfaces, TogetherAIErrorData, chat/completion/embedding/image routing, and TogetherAI reranking are represented as a Kotlin facade folded into the root module; VERSION is exposed as TOGETHERAI_VERSION until package modules are split |
| [@ai-sdk/valibot](ai-sdk-valibot.md) | 2.0.28 | `:aisdk-validation` | 1 | ported: valibotSchema is represented as a Kotlin-native Schema adapter folded into the root module |
| [@ai-sdk/vercel](ai-sdk-vercel.md) | 2.0.50 | `:aisdk-provider-vercel` | 6 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/voyage](ai-sdk-voyage.md) | 1.0.4 | `:aisdk-provider-voyage` | 7 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/vue](ai-sdk-vue.md) | 3.0.195 | `:aisdk-ui` | 6 | missing: no Kotlin module or parity mapping exists yet |
| [@ai-sdk/xai](ai-sdk-xai.md) | 3.0.93 | `:aisdk-provider-xai` | 22 | missing: no Kotlin module or parity mapping exists yet |
| [ai](ai.md) | 6.0.195 | `:aisdk-core` | 328 | in-progress: current root module covers the core KMP surface; full package ecosystem parity remains open |
