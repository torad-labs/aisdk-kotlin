#!/usr/bin/env node

import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';

const repoRoot = resolve(new URL('..', import.meta.url).pathname);
const referenceVersion = process.env.AI_SDK_REFERENCE_VERSION ?? '6.0.197';
const referenceRoot = join(repoRoot, '.reference', `vercel-ai-sdk-ai-${referenceVersion}`, 'packages');
const outputRoot = join(repoRoot, 'docs', 'parity');
const checkOnly = process.argv.includes('--check');

const packageStatus = new Map([
  ['ai', 'ported: generateText/streamText, structured Output, deprecated generateObject/streamObject compatibility shims, embeddings, image/speech/transcription/video generation, reranking, tool-loop Agent/ToolLoopAgent, tool approvals, provider registry/customProvider, middleware, telemetry, prompt/message conversion, prune/fix-json/smooth/simulated streams, UI message/transport/response primitives, test mocks, gateway re-exports, provider/provider-utils re-exports, and KMP-safe server/UI stream concepts are represented in the root module; framework and browser-specific hooks are mapped to the framework-neutral Kotlin ui package'],
  ['@ai-sdk/alibaba', 'ported: createAlibaba/alibaba, AlibabaProviderSettings, Alibaba chat aliases/options/cache-control/usage aliases, Alibaba OpenAI-compatible chat routing, Qwen thinking option mapping, parallel tool-call option mapping, cache-write usage correction, reasoning/tool-call parsing via the shared chat adapter, DashScope text-embedding (text-embedding-v3/v4) embedding model with text-type/dimension/output-type options and sparse-output rejection, DashScope video task creation/polling, T2V/I2V/R2V input mapping, video option mapping, URL video outputs, warnings, error handling, and provider metadata are represented as a Kotlin facade folded into the root module; VERSION is exposed as ALIBABA_VERSION until package modules are split'],
  ['@ai-sdk/amazon-bedrock', 'ported: createAmazonBedrock/bedrock, createBedrockAnthropic/bedrockAnthropic, createBedrockMantle/bedrockMantle, AmazonBedrockProviderSettings, Bedrock credentials/settings aliases, native SigV4 signing, Converse chat request/response mapping, multimodal message conversion, Bedrock toolConfig/tool-choice mapping, reasoning/service-tier/additionalModelRequestFields mapping, JSON and binary Smithy event-stream decoding, embedding/image/reranking runtime adapters, Mantle OpenAI-compatible chat routing, bearer-token auth, usage/provider metadata, and unsupported/default provider errors are represented as a Kotlin facade folded into the root module; VERSION is exposed as AMAZON_BEDROCK_VERSION until package modules are split'],
  ['@ai-sdk/angular', 'ported-as-kmp-ui: Angular Chat/Completion/StructuredObject runtime concepts are represented by the framework-neutral Kotlin ui package: Chat, ChatTransport, TextStreamChatTransport, UIMessage/UIMessagePart, streamToUiMessages, convertToModelMessages, text/UI message stream responses, tool state, and typed tool-part handler registry; Angular signal/component bindings are intentionally not emitted in the Kotlin runtime module'],
  ['@ai-sdk/anthropic', 'ported: createAnthropic/anthropic, AnthropicProviderSettings, Anthropic message model aliases/options/metadata aliases, Anthropic hosted tool descriptors, auth-token/api-key validation, Anthropic headers/beta/user-agent behavior, messages request conversion, multimodal image/PDF/text document blocks, cache-control/file citation metadata, thinking/adaptive-thinking options, structured output_config, metadata/MCP/container/context option mapping, function/provider tool mapping, response text/reasoning/source/tool parsing, usage/cache iteration accounting, SSE event mapping, container-id forwarding helper, and unsupported embedding/image model errors are represented as a Kotlin facade folded into the root module; VERSION is exposed as ANTHROPIC_VERSION until package modules are split'],
  ['@ai-sdk/anthropic-aws', 'ported: createAnthropicAws/anthropicAws, AnthropicAwsProviderSettings, AnthropicAwsCredentials alias, AWS-hosted Anthropic base URL mapping, workspace header, API-key auth, native SigV4 signing, Anthropic Messages generate/stream reuse, Anthropic hosted tool descriptors, unsupported embedding/image errors, and user-agent behavior are represented as a Kotlin facade folded into the root module; VERSION is exposed as ANTHROPIC_AWS_VERSION until package modules are split'],
  ['@ai-sdk/provider', 'ported: provider contracts for language/embedding/image/speech/transcription/reranking/video models, provider metadata/options, warnings, usage, request/response metadata, provider interfaces, V2/V3-compatible folded type aliases, and public AI SDK error classes are represented as Kotlin contracts folded into the root module'],
  ['@ai-sdk/provider-utils', 'ported: schema/asSchema/lazySchema/zodSchema/valibotSchema adapters, validation helpers, dynamicTool/tool/provider-executed tool factories, provider tool factories, executeTool, tool-name mapping, id generation, headers/user-agent helpers, parseJsonEventStream, base64/Uint8Array aliases, media/download URL validation, loadApiKey/loadSetting host-env helpers, provider option parsing, retry/delay-adjacent utilities, and test stream/mock helpers are represented as KMP utilities folded into the root module'],
  ['@ai-sdk/quiverai', 'ported: createQuiverAI/quiverai, QuiverAIProviderSettings, QuiverAIImageModelId, QuiverAIImageModelOptions, SVG generation, vectorization, reference image validation, option snake_case mapping, unsupported option warnings, SVG byte conversion, usage/provider metadata, and QuiverAI error parsing are represented as a Kotlin facade folded into the root module; VERSION is exposed as QUIVERAI_VERSION until package modules are split'],
  ['@ai-sdk/devtools', 'ported: the public devToolsMiddleware export is represented as a Kotlin-native recorder-backed middleware with run/step/result recording, generate and stream capture, raw request/response/chunk capture, usage/error recording, and production-environment guard folded into the root module'],
  ['@ai-sdk/assemblyai', 'ported: createAssemblyAI/assemblyai, AssemblyAIProviderSettings, AssemblyAITranscriptionModelOptions, upload/submit/poll transcription flow, provider-option snake_case mapping, transcript status errors, response headers/body, and segment parsing are represented as a Kotlin facade folded into the root module; VERSION is exposed as ASSEMBLYAI_VERSION until package modules are split'],
  ['@ai-sdk/azure', 'ported: createAzure/azure, AzureOpenAIProviderSettings, Azure OpenAI model aliases, responses/chat/completion/embedding/image/transcription/speech routing, api-key and tokenProvider authentication, v1 and deployment URL formats, api-version handling, Azure hosted OpenAI tools, per-call language headers, and OpenAI provider-option forwarding are represented as a Kotlin facade folded into the root module; VERSION is exposed as AZURE_VERSION until package modules are split'],
  ['@ai-sdk/baseten', 'ported: createBaseten/baseten, BasetenProviderSettings, BasetenChatModelId, BasetenEmbeddingModelOptions, BasetenErrorData, default Model API chat routing, custom /sync/v1 chat routing, /predict chat rejection, embedding modelURL validation, /sync to /sync/v1 embedding normalization, and Baseten auth/user-agent behavior are represented as a Kotlin OpenAI-compatible facade folded into the root module; VERSION is exposed as BASETEN_VERSION until package modules are split'],
  ['@ai-sdk/black-forest-labs', 'ported: createBlackForestLabs/blackForestLabs, BlackForestLabsProviderSettings, BlackForestLabsImageModelOptions/BlackForestLabsImageProviderOptions, async image submit/poll/download, x-key authentication, size-to-aspect-ratio warnings, input image and mask mapping, BFL provider-option snake_case mapping, poll timeout/interval controls, and provider metadata are represented as a Kotlin facade folded into the root module; VERSION is exposed as BLACK_FOREST_LABS_VERSION until package modules are split'],
  ['@ai-sdk/bytedance', 'ported: createByteDance/byteDance, ByteDanceProviderSettings, ByteDanceVideoModelId, ByteDanceVideoProviderOptions, video task creation, status polling, URL video output, resolution mapping, first/last frame and reference image/video/audio content role mapping, provider-option snake_case mapping, warnings, task metadata, and ByteDance error parsing are represented as a Kotlin facade folded into the root module; VERSION is exposed as BYTEDANCE_VERSION until package modules are split'],
  ['@ai-sdk/cerebras', 'ported: createCerebras/cerebras, chat provider settings, CerebrasErrorData, and chat model routing are represented as an OpenAI-compatible Kotlin facade folded into the root module; VERSION is exposed as CEREBRAS_VERSION until package modules are split'],
  ['@ai-sdk/codemod', 'not-applicable-runtime: upstream package exposes only the JavaScript codemod CLI and no public runtime library exports; Kotlin migration tooling is not shipped as part of the KMP runtime artifact'],
  ['@ai-sdk/cohere', 'ported: createCohere/cohere, CohereProviderSettings, Cohere chat/embedding/reranking model aliases, chat request mapping, multimodal image and document prompt conversion, JSON response format, tool choice/tool call mapping, citations, thinking options, Cohere auth/user-agent behavior, embedding options, reranking options, usage parsing, and unsupported image model errors are represented as a Kotlin facade folded into the root module; VERSION is exposed as COHERE_VERSION until package modules are split'],
  ['@ai-sdk/deepinfra', 'ported: createDeepInfra/deepinfra, provider settings, chat/completion/embedding/image routing, DeepInfraErrorData, and DeepInfra usage correction are represented as a Kotlin facade folded into the root module; VERSION is exposed as DEEPINFRA_VERSION until package modules are split'],
  ['@ai-sdk/deepgram', 'ported: createDeepgram/deepgram, DeepgramProviderSettings, DeepgramSpeechModel/DeepgramSpeechModelOptions, DeepgramSpeechCallOptions alias, DeepgramTranscriptionModelOptions, speech output-format and provider-option query mapping, transcription option query mapping, binary audio response parsing, and word segment parsing are represented as a Kotlin facade folded into the root module; VERSION is exposed as DEEPGRAM_VERSION until package modules are split'],
  ['@ai-sdk/deepseek', 'ported: createDeepSeek/deepseek, provider settings, language options, error data alias, and chat model routing are represented as an OpenAI-compatible Kotlin facade folded into the root module; VERSION is exposed as DEEPSEEK_VERSION until package modules are split'],
  ['@ai-sdk/elevenlabs', 'ported: createElevenLabs/elevenlabs, ElevenLabsProviderSettings, speech/transcription model id and option surfaces, speech query/body mapping, multipart transcription mapping, binary audio response parsing, and transcription segment parsing are represented as a Kotlin facade folded into the root module; VERSION is exposed as ELEVENLABS_VERSION until package modules are split'],
  ['@ai-sdk/fal', 'ported: createFal/fal, FalProviderSettings, Fal image/speech/transcription/video model aliases and option aliases, fal auth/user-agent behavior, direct image generation and image download, image-editing files/mask mapping, image option camelCase/snake_case handling with deprecation warnings, speech URL-audio generation and download, transcription queue submit/poll/chunk mapping, video queue submit/poll URL output mapping, provider metadata, queue progress handling, and unsupported language/embedding errors are represented as a Kotlin facade folded into the root module; VERSION is exposed as FAL_VERSION until package modules are split'],
  ['@ai-sdk/fireworks', 'ported: createFireworks/fireworks, provider settings, language/embedding option surfaces, FireworksErrorData, FireworksImageModel, chat/completion/embedding routing, and Fireworks image backend routing are represented as a Kotlin facade folded into the root module; VERSION is exposed as FIREWORKS_VERSION until package modules are split'],
  ['@ai-sdk/gateway', 'ported: createGateway/createGatewayProvider/gateway, GatewayProviderSettings, model aliases, gateway hosted tools, auth headers/API-key/OIDC method propagation, metadata caching, credits/spend/generation endpoints, Gateway error classes, and Ktor HTTP transport for language/embedding/image/video/reranking calls and SSE streams are represented as Kotlin facades folded into the root module'],
  ['@ai-sdk/gladia', 'ported: createGladia/gladia, GladiaProviderSettings, GladiaTranscriptionModelOptions, multipart upload, pre-recorded init, result polling, nested provider-option snake_case mapping, transcript failure/timeout handling, response headers/body, provider metadata, and utterance segment parsing are represented as a Kotlin facade folded into the root module; VERSION is exposed as GLADIA_VERSION until package modules are split'],
  ['@ai-sdk/google', 'ported: createGoogleGenerativeAI/google, GoogleGenerativeAIProviderSettings, Google model/option/metadata aliases, Google hosted tool descriptors, Gemini generateContent/streamGenerateContent request conversion, system/user/assistant/tool multimodal prompt mapping, function/provider tool mapping, JSON response format and generationConfig mapping, Gemini response text/reasoning/file/tool/source parsing, usage/provider metadata, SSE stream mapping, embedding single/batch payload mapping, Imagen and Gemini image generation, Veo long-running video polling, Interactions model/agent request mapping, Interactions response/step parsing, Interactions usage/provider metadata, Interactions SSE and background-agent stream synthesis, auth/user-agent behavior, and unsupported/error handling are represented as a Kotlin facade folded into the root module; VERSION is exposed as GOOGLE_VERSION until package modules are split'],
  ['@ai-sdk/google-vertex', 'ported: createVertex/vertex, createVertexAnthropic/vertexAnthropic, createVertexMaas/vertexMaas, createGoogleVertexXai/googleVertexXai, GoogleVertexProviderSettings and option aliases, Vertex publisher/express-mode base URL construction including global, regional, and eu/us REP hosts, Bearer/API-key/header auth behavior, Vertex Gemini language/embedding/image/video routing through the Google core adapter, Vertex hosted tool descriptors, Vertex Anthropic rawPredict/streamRawPredict routing with Vertex body/header transforms, Vertex MaAS OpenAI-compatible routing, Vertex xAI OpenAI-compatible routing with xAI request/usage transforms, unsupported model errors, and KMP host-injected credential boundaries are represented as Kotlin facades folded into the root module; VERSION is exposed as GOOGLE_VERTEX_VERSION until package modules are split'],
  ['@ai-sdk/groq', 'ported: createGroq/groq, Groq tools, chat/transcription routing, provider settings, and option surfaces are represented as an OpenAI-compatible Kotlin facade folded into the root module; VERSION is exposed as GROQ_VERSION until package modules are split'],
  ['@ai-sdk/huggingface', 'ported: createHuggingFace/huggingface, HuggingFaceProviderSettings, HuggingFace Responses model aliases/options/error alias, Responses request mapping, provider-option metadata/instructions/strict-json/reasoning handling, JSON schema format mapping, function tool/tool-choice mapping, unsupported parameter warnings, message/image conversion, response text/reasoning/source/function/MCP tool parsing, SSE stream mapping, usage parsing, auth/header behavior, and unsupported embedding/image model guidance are represented as a Kotlin facade folded into the root module; VERSION is exposed as HUGGINGFACE_VERSION until package modules are split'],
  ['@ai-sdk/hume', 'ported: createHume/hume, HumeProviderSettings, HumeSpeechModelOptions, speech routing, utterance/context request mapping, binary audio response parsing, and output-format warnings are represented as a Kotlin facade folded into the root module; VERSION is exposed as HUME_VERSION until package modules are split'],
  ['@ai-sdk/klingai', 'ported: createKlingAI/klingai, KlingAIProviderSettings, KlingAIVideoModelId, KlingAIVideoModelOptions/KlingAIVideoProviderOptions, HS256 JWT authentication, text-to-video/image-to-video/motion-control endpoint routing, model-name normalization, provider-option snake_case mapping, passthrough options, task polling, URL video outputs, warnings, failure/timeout handling, and provider metadata are represented as a Kotlin facade folded into the root module; VERSION is exposed as KLINGAI_VERSION until package modules are split'],
  ['@ai-sdk/langchain', 'ported: toBaseMessages, convertModelMessages, toUIMessageStream, StreamCallbacks, and LangSmithDeploymentTransport are represented as Kotlin-native UI/Flow adapters folded into the root module'],
  ['@ai-sdk/llamaindex', 'ported: toUIMessageStream is represented as a Kotlin Flow adapter over LlamaIndexEngineResponse, with callback lifecycle support folded into the root module'],
  ['@ai-sdk/lmnt', 'ported: createLMNT/lmnt, LMNTProviderSettings, LMNTSpeechModelOptions, speech routing, JSON request mapping, binary audio response parsing, and response-format warnings are represented as a Kotlin facade folded into the root module; VERSION is exposed as LMNT_VERSION until package modules are split'],
  ['@ai-sdk/luma', 'ported: createLuma/luma, LumaProviderSettings, LumaImageModelOptions/LumaImageProviderOptions, async image generation, polling, image download, seed/size warnings, provider-option passthrough, URL reference images, character/style/modify_image reference mapping, and mask/base64 rejection are represented as a Kotlin facade folded into the root module; VERSION is exposed as LUMA_VERSION until package modules are split'],
  ['@ai-sdk/mcp', 'ported: MCP JSON-RPC contracts, client handshake, capability-gated APIs, dynamic tool conversion, resources, prompts, elicitation, OAuth type surface plus authorization/protected-resource metadata discovery, resource validation/propagation, dynamic registration, PKCE authorization redirects, authorization-code exchange, refresh-token flow, awaited client authentication hooks, streamable HTTP transport, legacy SSE transport, and process-backed stdio transport for JVM/Android are represented as Kotlin facades folded into the root module; iOS stdio is an explicit unsupported platform boundary; VERSION is exposed as MCP_PACKAGE_VERSION until package modules are split'],
  ['@ai-sdk/mistral', 'ported: createMistral/mistral, MistralProviderSettings, MistralLanguageModelOptions, chat and embedding aliases, Mistral auth/user-agent behavior, random_seed mapping, provider-option snake_case mapping, reasoning extraction, usage parsing, embedding ordering, and unsupported image model errors are represented as a Kotlin OpenAI-compatible facade folded into the root module; VERSION is exposed as MISTRAL_VERSION until package modules are split'],
  ['@ai-sdk/moonshotai', 'ported: createMoonshotAI/moonshotai, provider settings, model id/options aliases, and chat model routing are represented as an OpenAI-compatible Kotlin facade folded into the root module'],
  ['@ai-sdk/open-responses', 'ported: createOpenResponses, OpenResponsesOptions, generate/stream response mapping, supported URL metadata, request option mapping, and fake HTTP tests are folded into the root module; VERSION is exposed as OPEN_RESPONSES_VERSION until package modules are split'],
  ['@ai-sdk/openai', 'ported: createOpenAI/openai facade, hosted OpenAI tool descriptors, OpenAI-prefixed provider-tool argument factories, default Responses API model routing, OpenAI Responses request option mapping, supported URL metadata, built-in Responses tool type mapping, Responses metadata/logprob output mapping, OpenAI/Azure file-id prefix handling, and fake HTTP tests are folded into the root module; VERSION is exposed as VERSION until package modules are split'],
  ['@ai-sdk/openai-compatible', 'ported: createOpenAICompatible/createOpenAICompatibleProvider, OpenAICompatibleProviderSettings, chat/completion/embedding/image/speech/transcription models, request URL/header/auth/query handling, structured-output controls, provider-option forwarding, request transforms, usage conversion hooks, response metadata, finish-reason/usage mapping, SSE chat/completion streaming, multimodal/tool message conversion, and facade reuse for compatible providers are represented as Kotlin adapters folded into the root module'],
  ['@ai-sdk/perplexity', 'ported: createPerplexity/perplexity and provider settings are represented as an OpenAI-compatible Kotlin facade folded into the root module; VERSION is exposed as PERPLEXITY_VERSION until package modules are split'],
  ['@ai-sdk/prodia', 'ported: createProdia/prodia, ProdiaProviderSettings, Prodia language/image/video model aliases, Prodia image provider option alias, JSON and multipart job request paths, multipart job/output response parsing, language text/image output mapping, image generation options, video generation options, provider metadata, Prodia auth/user-agent behavior, and unsupported embedding errors are represented as a Kotlin facade folded into the root module; VERSION is exposed as PRODIA_VERSION until package modules are split'],
  ['@ai-sdk/react', 'ported-as-kmp-ui: React Chat/useChat/useCompletion/useObject concepts are represented by the framework-neutral Kotlin ui package: Chat, ChatRequest, ChatTransport, TextStreamChatTransport, UIMessage/UIMessagePart, streamToUiMessages, convertToModelMessages, TextStreamResponse/UIMessageStreamResponse, createUiMessageStream, tool approval state, and typed tool-part handler registry; React hooks themselves are intentionally not emitted in the Kotlin runtime module'],
  ['@ai-sdk/revai', 'ported: createRevai/revai, RevaiProviderSettings, RevaiTranscriptionModelOptions, multipart media/config job submission, job status polling, transcript retrieval, Rev.ai config passthrough, transcript text reconstruction, word timing segment parsing, and failure/timeout handling are represented as a Kotlin facade folded into the root module; VERSION is exposed as REVAI_VERSION until package modules are split'],
  ['@ai-sdk/replicate', 'ported: createReplicate/replicate, ReplicateProviderSettings, image/video model id and option surfaces, versioned and unversioned prediction routing, prefer wait headers, image output downloads, Flux 2 multi-image warnings, data-URI file conversion, video polling, URL video outputs, prediction metadata, and Replicate error parsing are represented as a Kotlin facade folded into the root module; VERSION is exposed as REPLICATE_VERSION until package modules are split'],
  ['@ai-sdk/rsc', 'ported-as-kmp-server: RSC streamable-value/UI concepts are represented by Kotlin server/ui stream primitives: createTextStreamResponse, pipeTextStreamToResponse, createUiMessageStreamResponse, pipeUiMessageStreamToResponse, createUiMessageStream, UIMessageStreamWriter, read/merge/error stream handling via Flow, ChatTransport, and UIMessage state conversion; React Server Components runtime bindings and JSX UI streaming are intentionally not emitted in the Kotlin runtime module'],
  ['@ai-sdk/svelte', 'ported-as-kmp-ui: Svelte Chat/Completion/StructuredObject store concepts are represented by the framework-neutral Kotlin ui package: Chat, ChatTransport, TextStreamChatTransport, UIMessage/UIMessagePart, streamToUiMessages, convertToModelMessages, stream responses, and typed tool-part handler registry; Svelte stores/components are intentionally not emitted in the Kotlin runtime module'],
  ['@ai-sdk/test-server', 'ported: createTestServer, TestResponseController, UrlResponse, UrlHandler, and UrlHandlers are represented as a Kotlin-native in-memory server with a Ktor MockEngine bridge folded into the root module'],
  ['@ai-sdk/togetherai', 'ported: createTogetherAI/togetherai, provider settings, image/reranking option surfaces, TogetherAIErrorData, chat/completion/embedding/image routing, and TogetherAI reranking are represented as a Kotlin facade folded into the root module; VERSION is exposed as TOGETHERAI_VERSION until package modules are split'],
  ['@ai-sdk/valibot', 'ported: valibotSchema is represented as a Kotlin-native Schema adapter folded into the root module'],
  ['@ai-sdk/vercel', 'ported: createVercel/vercel, VercelProviderSettings, VercelErrorData, and chat-only routing are represented as an OpenAI-compatible Kotlin facade folded into the root module; VERSION is exposed as VERCEL_VERSION until package modules are split'],
  ['@ai-sdk/voyage', 'ported: createVoyage/voyage, VoyageProviderSettings, embedding/reranking option surfaces, embedding routing, reranking routing, usage parsing, and embedding input limits are represented as a Kotlin facade folded into the root module; VERSION is exposed as VOYAGE_VERSION until package modules are split'],
  ['@ai-sdk/vue', 'ported-as-kmp-ui: Vue Chat/useCompletion/useObject concepts are represented by the framework-neutral Kotlin ui package: Chat, ChatTransport, TextStreamChatTransport, UIMessage/UIMessagePart, streamToUiMessages, convertToModelMessages, stream responses, and typed tool-part handler registry; Vue refs/components are intentionally not emitted in the Kotlin runtime module'],
  ['@ai-sdk/xai', 'ported: createXai/xai, XaiProviderSettings, chat/responses/image/video model factories, hosted xAI tool descriptors, chat option snake_case mapping, citations, image generation/editing, image provider metadata, video generation/edit/extend/reference routing, video polling, warnings, URL video outputs, xAI auth/user-agent behavior, and unsupported embedding errors are represented as a Kotlin facade folded into the root module; VERSION is exposed as XAI_VERSION until package modules are split'],
]);

function main() {
  if (!existsSync(referenceRoot)) {
    fail(`Missing upstream reference root: ${relative(repoRoot, referenceRoot)}`);
  }

  const packages = listPackages().map(readPackageLedger);
  const files = new Map();
  files.set(join(outputRoot, 'README.md'), renderIndex(packages));
  for (const pkg of packages) {
    files.set(join(outputRoot, `${slugForPackage(pkg.name)}.md`), renderPackage(pkg));
  }

  if (checkOnly) {
    const failures = [];
    for (const [file, content] of files) {
      if (!existsSync(file)) {
        failures.push(`missing ${relative(repoRoot, file)}`);
        continue;
      }
      const actual = readFileSync(file, 'utf8');
      if (actual !== content) {
        failures.push(`stale ${relative(repoRoot, file)}`);
      }
    }
    if (failures.length > 0) {
      fail(`Parity ledgers are not current:\n${failures.map(item => `- ${item}`).join('\n')}`);
    }
    console.log(`Parity ledgers are current (${files.size} files).`);
    return;
  }

  mkdirSync(outputRoot, { recursive: true });
  for (const [file, content] of files) {
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, content);
  }
  console.log(`Wrote ${files.size} parity ledger files.`);
}

function listPackages() {
  return readdirSync(referenceRoot, { withFileTypes: true })
    .filter(entry => entry.isDirectory())
    .map(entry => join(referenceRoot, entry.name))
    .filter(dir => existsSync(join(dir, 'package.json')))
    .sort((left, right) => {
      const leftName = JSON.parse(readFileSync(join(left, 'package.json'), 'utf8')).name ?? '';
      const rightName = JSON.parse(readFileSync(join(right, 'package.json'), 'utf8')).name ?? '';
      return leftName.localeCompare(rightName);
    });
}

function readPackageLedger(packageDir) {
  const packageJson = JSON.parse(readFileSync(join(packageDir, 'package.json'), 'utf8'));
  const name = packageJson.name ?? relative(referenceRoot, packageDir);
  const entrypoints = resolveEntrypoints(packageDir, packageJson);
  const publicExports = new Map();
  const externalExports = new Set();
  const unresolvedExports = [];

  for (const entrypoint of entrypoints) {
    if (!entrypoint.sourcePath) {
      unresolvedExports.push(`${entrypoint.subpath}: no source entry found`);
      continue;
    }
    const parsed = parseExportSurface(entrypoint.sourcePath, packageDir);
    for (const item of parsed.exports) {
      const existing = publicExports.get(item.name);
      if (!existing) {
        publicExports.set(item.name, { ...item, entrypoints: [entrypoint.subpath] });
      } else if (!existing.entrypoints.includes(entrypoint.subpath)) {
        existing.entrypoints.push(entrypoint.subpath);
      }
    }
    for (const item of parsed.external) {
      externalExports.add(item);
    }
    for (const item of parsed.unresolved) {
      unresolvedExports.push(`${entrypoint.subpath}: ${item}`);
    }
  }

  return {
    dir: packageDir,
    name,
    version: packageJson.version ?? '',
    status: packageStatus.get(name) ?? 'missing: no Kotlin module or parity mapping exists yet',
    targetModule: targetModuleFor(name),
    entrypoints,
    exports: [...publicExports.values()].sort((left, right) => left.name.localeCompare(right.name)),
    externalExports: [...externalExports].sort(),
    unresolvedExports: [...new Set(unresolvedExports)].sort(),
  };
}

function resolveEntrypoints(packageDir, packageJson) {
  const exportsField = packageJson.exports;
  if (!exportsField || typeof exportsField !== 'object') {
    const binEntrypoints = Object.entries(packageJson.bin ?? {}).map(([name, target]) => ({
      subpath: `bin:${name}`,
      sourcePath: sourcePathForDistTarget(packageDir, target),
    }));
    if (binEntrypoints.length > 0) {
      return binEntrypoints;
    }
    return [
      {
        subpath: '.',
        sourcePath: firstExisting([
          join(packageDir, 'src', 'index.ts'),
          join(packageDir, 'index.ts'),
        ]),
      },
    ];
  }

  return Object.entries(exportsField)
    .filter(([subpath]) => subpath !== './package.json')
    .map(([subpath, value]) => {
      const typesPath = typeof value === 'string' ? value : value?.types ?? value?.import ?? value?.require;
      return {
        subpath,
        sourcePath: sourcePathForExport(packageDir, subpath, typesPath),
      };
    });
}

function sourcePathForExport(packageDir, subpath, typesPath) {
  const candidates = [];
  if (subpath === '.') {
    candidates.push(join(packageDir, 'src', 'index.ts'), join(packageDir, 'index.ts'));
  } else {
    const cleanSubpath = subpath.replace(/^\.\//, '');
    candidates.push(
      join(packageDir, 'src', cleanSubpath, 'index.ts'),
      join(packageDir, cleanSubpath, 'index.ts'),
      join(packageDir, 'src', `${cleanSubpath}.ts`),
      join(packageDir, `${cleanSubpath}.ts`),
    );
  }

  if (typeof typesPath === 'string') {
    const suffix = distTargetToSourceSuffix(typesPath);
    candidates.push(join(packageDir, 'src', suffix), join(packageDir, suffix));
    const basenameMatches = findFiles(packageDir, file => file.endsWith(suffix));
    candidates.push(...basenameMatches);
  }

  return firstExisting(candidates);
}

function sourcePathForDistTarget(packageDir, target) {
  if (typeof target !== 'string') return undefined;
  const suffix = distTargetToSourceSuffix(target);
  return firstExisting([
    join(packageDir, 'src', suffix),
    join(packageDir, suffix),
    ...findFiles(packageDir, file => file.endsWith(suffix)),
  ]);
}

function distTargetToSourceSuffix(target) {
  return target
    .replace(/^\.\//, '')
    .replace(/^dist\//, '')
    .replace(/\.d\.ts$/, '.ts')
    .replace(/\.js$/, '.ts');
}

function parseExportSurface(entryFile, packageDir, seen = new Set()) {
  const exports = new Map();
  const external = new Set();
  const unresolved = [];

  function addExport(item) {
    if (!exports.has(item.name)) {
      exports.set(item.name, item);
    }
  }

  function visit(file) {
    if (!file || seen.has(file)) return;
    seen.add(file);
    const source = stripComments(readFileSync(file, 'utf8'));

    for (const match of source.matchAll(/export\s+(?:type\s+)?\*\s+from\s+['"]([^'"]+)['"]/g)) {
      const target = match[1];
      if (!target.startsWith('.')) {
        external.add(`* from ${target}`);
        continue;
      }
      const resolved = resolveRelativeExport(file, target);
      if (resolved) {
        visit(resolved);
      } else {
        unresolved.push(`${relative(packageDir, file)} re-exports unresolved ${target}`);
      }
    }

    for (const match of source.matchAll(/export\s+(type\s+)?\{([\s\S]*?)\}\s+from\s+['"]([^'"]+)['"]/g)) {
      const exportKind = match[1] ? 'type' : 'value';
      const names = parseExportNames(match[2], exportKind);
      const target = match[3];
      if (!target.startsWith('.')) {
        for (const name of names) {
          addExport({ ...name, source: target });
        }
        continue;
      }
      const resolved = resolveRelativeExport(file, target);
      for (const name of names) {
        addExport({ ...name, source: resolved ? relative(packageDir, resolved) : target });
      }
      if (!resolved) {
        unresolved.push(`${relative(packageDir, file)} named export source unresolved ${target}`);
      }
    }

    for (const match of source.matchAll(/export\s+(?:declare\s+)?(?:async\s+)?(class|function|interface|type|const|let|var|enum)\s+([A-Za-z_$][\w$]*)/g)) {
      addExport({
        name: match[2],
        kind: declarationKind(match[1]),
        source: relative(packageDir, file),
      });
    }
  }

  visit(entryFile);
  return {
    exports: [...exports.values()],
    external: [...external],
    unresolved,
  };
}

function parseExportNames(rawNames, defaultKind) {
  return rawNames
    .split(',')
    .map(item => item.trim())
    .filter(Boolean)
    .map(item => item.replace(/\s+/g, ' '))
    .map(item => {
      let kind = defaultKind;
      let text = item;
      if (text.startsWith('type ')) {
        kind = 'type';
        text = text.slice('type '.length).trim();
      }
      const aliasMatch = text.match(/^([A-Za-z_$][\w$]*)\s+as\s+([A-Za-z_$][\w$]*)$/);
      const name = aliasMatch ? aliasMatch[2] : text;
      const original = aliasMatch ? aliasMatch[1] : text;
      return { name, original, kind };
    })
    .filter(item => /^[A-Za-z_$][\w$]*$/.test(item.name));
}

function resolveRelativeExport(fromFile, target) {
  const targetWithoutRuntimeExtension = target.replace(/\.js$/, '');
  const base = resolve(dirname(fromFile), targetWithoutRuntimeExtension);
  return firstExisting([
    `${base}.ts`,
    `${base}.tsx`,
    `${base}.svelte.ts`,
    `${base}.d.ts`,
    join(base, 'index.ts'),
    join(base, 'index.tsx'),
    join(base, 'index.d.ts'),
  ]);
}

function findFiles(root, predicate) {
  const result = [];
  const stack = [root];
  while (stack.length > 0) {
    const dir = stack.pop();
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const fullPath = join(dir, entry.name);
      if (entry.isDirectory()) {
        stack.push(fullPath);
      } else if (predicate(relative(root, fullPath).replaceAll('\\', '/'))) {
        result.push(fullPath);
      }
    }
  }
  return result;
}

function firstExisting(paths) {
  return paths.find(path => path && existsSync(path));
}

function stripComments(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1');
}

function declarationKind(kind) {
  return kind === 'interface' || kind === 'type' ? 'type' : 'value';
}

function targetModuleFor(packageName) {
  if (packageName === 'ai') return ':aisdk-core';
  if (packageName === '@ai-sdk/provider') return ':aisdk-provider';
  if (packageName === '@ai-sdk/provider-utils') return ':aisdk-provider-utils';
  if (packageName === '@ai-sdk/gateway') return ':aisdk-gateway';
  if (packageName === '@ai-sdk/openai-compatible') return ':aisdk-openai-compatible';
  if (packageName === '@ai-sdk/openai') return ':aisdk-openai';
  if (packageName === '@ai-sdk/open-responses') return ':aisdk-open-responses';
  if (packageName === '@ai-sdk/react') return ':aisdk-ui';
  if (packageName === '@ai-sdk/vue') return ':aisdk-ui';
  if (packageName === '@ai-sdk/svelte') return ':aisdk-ui';
  if (packageName === '@ai-sdk/angular') return ':aisdk-ui';
  if (packageName === '@ai-sdk/rsc') return ':aisdk-server';
  if (packageName === '@ai-sdk/mcp') return ':aisdk-mcp';
  if (packageName === '@ai-sdk/langchain') return ':aisdk-langchain';
  if (packageName === '@ai-sdk/llamaindex') return ':aisdk-llamaindex';
  if (packageName === '@ai-sdk/valibot') return ':aisdk-validation';
  if (packageName === '@ai-sdk/devtools') return ':aisdk-devtools';
  if (packageName === '@ai-sdk/test-server') return ':aisdk-test-server';
  if (packageName === '@ai-sdk/codemod') return ':aisdk-codemod';
  return `:aisdk-provider-${packageName.replace(/^@ai-sdk\//, '')}`;
}

function slugForPackage(packageName) {
  return packageName
    .replace(/^@/, '')
    .replace(/\//g, '-')
    .replace(/[^A-Za-z0-9._-]/g, '-');
}

function renderIndex(packages) {
  const totalExports = packages.reduce((sum, pkg) => sum + pkg.exports.length, 0);
  return [
    '# Vercel AI SDK v6 Parity Ledgers',
    '',
    `Generated from \`.reference/vercel-ai-sdk-ai-${referenceVersion}/packages\`.`,
    'Run `node tools/generate-parity-ledger.mjs` after refreshing the upstream reference.',
    'Run `node tools/generate-parity-ledger.mjs --check` in CI to verify this directory is current.',
    '',
    `- Packages: ${packages.length}`,
    `- Public exports discovered: ${totalExports}`,
    '',
    '| Package | Version | Parity area | Upstream exports | Status |',
    '|---|---:|---|---:|---|',
    ...packages.map(pkg => {
      const link = `${slugForPackage(pkg.name)}.md`;
      return `| [${pkg.name}](${link}) | ${pkg.version} | \`${pkg.targetModule}\` | ${pkg.exports.length} | ${pkg.status} |`;
    }),
    '',
  ].join('\n');
}

function renderPackage(pkg) {
  const lines = [
    `# ${pkg.name}`,
    '',
    `- Version: ${pkg.version}`,
    `- Upstream path: \`${relative(repoRoot, pkg.dir)}\``,
    `- Kotlin parity area: \`${pkg.targetModule}\``,
    `- Current parity status: ${pkg.status}`,
    '',
    '## Entrypoints',
    '',
    '| Subpath | Source | Export count |',
    '|---|---|---:|',
  ];

  for (const entrypoint of pkg.entrypoints) {
    const source = entrypoint.sourcePath ? `\`${relative(repoRoot, entrypoint.sourcePath)}\`` : 'unresolved';
    const count = pkg.exports.filter(item => item.entrypoints?.includes(entrypoint.subpath)).length;
    lines.push(`| \`${entrypoint.subpath}\` | ${source} | ${count} |`);
  }

  lines.push('', '## Public Exports', '', '| Export | Kind | Source | Entrypoints |', '|---|---|---|---|');
  for (const item of pkg.exports) {
    const source = item.source ? `\`${item.source}\`` : '';
    const entrypoints = item.entrypoints?.map(name => `\`${name}\``).join(', ') ?? '';
    lines.push(`| \`${item.name}\` | ${item.kind} | ${source} | ${entrypoints} |`);
  }

  if (pkg.externalExports.length > 0) {
    lines.push('', '## External Star Re-exports', '');
    for (const item of pkg.externalExports) {
      lines.push(`- \`${item}\``);
    }
  }

  if (pkg.unresolvedExports.length > 0) {
    lines.push('', '## Unresolved Export Sources', '');
    for (const item of pkg.unresolvedExports) {
      lines.push(`- ${item}`);
    }
  }

  lines.push('');
  return lines.join('\n');
}

function fail(message) {
  console.error(message);
  process.exit(1);
}

main();
