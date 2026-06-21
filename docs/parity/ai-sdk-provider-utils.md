# @ai-sdk/provider-utils

- Version: 4.0.30
- Upstream path: `.reference/vercel-ai-sdk-ai-6.0.208/packages/provider-utils`
- Kotlin parity area: `:aisdk-provider-utils`
- Current parity status: ported: schema/asSchema/lazySchema/zodSchema/valibotSchema adapters, validation helpers, dynamicTool/tool/provider-executed tool factories, provider tool factories, executeTool, tool-name mapping, id generation, headers/user-agent helpers, parseJsonEventStream, base64/Uint8Array aliases, media/download URL validation, loadApiKey/loadSetting host-env helpers, provider option parsing, retry/delay-adjacent utilities, and test stream/mock helpers are represented as KMP utilities folded into the root module; v6.0.204 adds the SSRF helpers fetchWithValidatedRedirects and isSameOrigin (download/redirect URL validation, adjacent to the existing validateDownloadUrl/isUrlSupported surface) plus isBrowserRuntime, a browser-JS runtime probe not applicable to the JVM/Android/Native/iOS targets

## Entrypoints

| Subpath | Source | Export count |
|---|---|---:|
| `.` | `.reference/vercel-ai-sdk-ai-6.0.208/packages/provider-utils/src/index.ts` | 108 |
| `./test` | `.reference/vercel-ai-sdk-ai-6.0.208/packages/provider-utils/src/test/index.ts` | 7 |

## Public Exports

| Export | Kind | Source | Entrypoints |
|---|---|---|---|
| `asSchema` | value | `src/schema.ts` | `.` |
| `AssistantContent` | type | `src/types/assistant-model-message.ts` | `.` |
| `AssistantModelMessage` | type | `src/types/assistant-model-message.ts` | `.` |
| `cancelResponseBody` | value | `src/cancel-response-body.ts` | `.` |
| `combineHeaders` | value | `src/combine-headers.ts` | `.` |
| `convertArrayToAsyncIterable` | value | `src/test/convert-array-to-async-iterable.ts` | `./test` |
| `convertArrayToReadableStream` | value | `src/test/convert-array-to-readable-stream.ts` | `./test` |
| `convertAsyncIterableToArray` | value | `src/test/convert-async-iterable-to-array.ts` | `./test` |
| `convertAsyncIteratorToReadableStream` | value | `src/convert-async-iterator-to-readable-stream.ts` | `.` |
| `convertBase64ToUint8Array` | value | `src/uint8-utils.ts` | `.` |
| `convertImageModelFileToDataUri` | value | `src/convert-image-model-file-to-data-uri.ts` | `.` |
| `convertReadableStreamToArray` | value | `src/test/convert-readable-stream-to-array.ts` | `./test` |
| `convertResponseStreamToArray` | value | `src/test/convert-response-stream-to-array.ts` | `./test` |
| `convertToBase64` | value | `src/uint8-utils.ts` | `.` |
| `convertToFormData` | value | `src/convert-to-form-data.ts` | `.` |
| `convertUint8ArrayToBase64` | value | `src/uint8-utils.ts` | `.` |
| `createBinaryResponseHandler` | value | `src/response-handler.ts` | `.` |
| `createEventSourceResponseHandler` | value | `src/response-handler.ts` | `.` |
| `createIdGenerator` | value | `src/generate-id.ts` | `.` |
| `createJsonErrorResponseHandler` | value | `src/response-handler.ts` | `.` |
| `createJsonResponseHandler` | value | `src/response-handler.ts` | `.` |
| `createProviderToolFactory` | value | `src/provider-tool-factory.ts` | `.` |
| `createProviderToolFactoryWithOutputSchema` | value | `src/provider-tool-factory.ts` | `.` |
| `createStatusCodeErrorResponseHandler` | value | `src/response-handler.ts` | `.` |
| `createToolNameMapping` | value | `src/create-tool-name-mapping.ts` | `.` |
| `DataContent` | type | `src/types/data-content.ts` | `.` |
| `DEFAULT_MAX_DOWNLOAD_SIZE` | value | `src/read-response-with-size-limit.ts` | `.` |
| `delay` | value | `src/delay.ts` | `.` |
| `DelayedPromise` | value | `src/delayed-promise.ts` | `.` |
| `downloadBlob` | value | `src/download-blob.ts` | `.` |
| `DownloadError` | value | `src/download-error.ts` | `.` |
| `dynamicTool` | value | `src/types/tool.ts` | `.` |
| `EventSourceMessage` | type | `eventsource-parser/stream` | `.` |
| `EventSourceParserStream` | value | `eventsource-parser/stream` | `.` |
| `executeTool` | value | `src/types/execute-tool.ts` | `.` |
| `extractResponseHeaders` | value | `src/extract-response-headers.ts` | `.` |
| `FetchFunction` | type | `src/fetch-function.ts` | `.` |
| `fetchWithValidatedRedirects` | value | `src/fetch-with-validated-redirects.ts` | `.` |
| `FilePart` | type | `src/types/content-part.ts` | `.` |
| `FlexibleSchema` | type | `src/schema.ts` | `.` |
| `generateId` | value | `src/generate-id.ts` | `.` |
| `getErrorMessage` | value | `src/get-error-message.ts` | `.` |
| `getFromApi` | value | `src/get-from-api.ts` | `.` |
| `getRuntimeEnvironmentUserAgent` | value | `src/get-runtime-environment-user-agent.ts` | `.` |
| `IdGenerator` | type | `src/generate-id.ts` | `.` |
| `ImagePart` | type | `src/types/content-part.ts` | `.` |
| `InferSchema` | type | `src/schema.ts` | `.` |
| `InferToolInput` | type | `src/types/tool.ts` | `.` |
| `InferToolOutput` | type | `src/types/tool.ts` | `.` |
| `injectJsonInstructionIntoMessages` | value | `src/inject-json-instruction.ts` | `.` |
| `isAbortError` | value | `src/is-abort-error.ts` | `.` |
| `isBrowserRuntime` | value | `src/is-browser-runtime.ts` | `.` |
| `isNodeVersion` | value | `src/test/is-node-version.ts` | `./test` |
| `isNonNullable` | value | `src/is-non-nullable.ts` | `.` |
| `isParsableJson` | value | `src/parse-json.ts` | `.` |
| `isSameOrigin` | value | `src/is-same-origin.ts` | `.` |
| `isUrlSupported` | value | `src/is-url-supported.ts` | `.` |
| `jsonSchema` | value | `src/schema.ts` | `.` |
| `lazySchema` | value | `src/schema.ts` | `.` |
| `LazySchema` | type | `src/schema.ts` | `.` |
| `loadApiKey` | value | `src/load-api-key.ts` | `.` |
| `loadOptionalSetting` | value | `src/load-optional-setting.ts` | `.` |
| `loadSetting` | value | `src/load-setting.ts` | `.` |
| `MaybePromiseLike` | type | `src/maybe-promise-like.ts` | `.` |
| `mediaTypeToExtension` | value | `src/media-type-to-extension.ts` | `.` |
| `mockId` | value | `src/test/mock-id.ts` | `./test` |
| `ModelMessage` | type | `src/types/model-message.ts` | `.` |
| `normalizeHeaders` | value | `src/normalize-headers.ts` | `.` |
| `parseJSON` | value | `src/parse-json.ts` | `.` |
| `parseJsonEventStream` | value | `src/parse-json-event-stream.ts` | `.` |
| `parseProviderOptions` | value | `src/parse-provider-options.ts` | `.` |
| `ParseResult` | type | `src/parse-json.ts` | `.` |
| `postFormDataToApi` | value | `src/post-to-api.ts` | `.` |
| `postJsonToApi` | value | `src/post-to-api.ts` | `.` |
| `postToApi` | value | `src/post-to-api.ts` | `.` |
| `ProviderOptions` | type | `src/types/provider-options.ts` | `.` |
| `ProviderToolFactory` | type | `src/provider-tool-factory.ts` | `.` |
| `ProviderToolFactoryWithOutputSchema` | type | `src/provider-tool-factory.ts` | `.` |
| `readResponseWithSizeLimit` | value | `src/read-response-with-size-limit.ts` | `.` |
| `ReasoningPart` | type | `src/types/content-part.ts` | `.` |
| `removeUndefinedEntries` | value | `src/remove-undefined-entries.ts` | `.` |
| `Resolvable` | type | `src/resolve.ts` | `.` |
| `resolve` | value | `src/resolve.ts` | `.` |
| `ResponseHandler` | type | `src/response-handler.ts` | `.` |
| `safeParseJSON` | value | `src/parse-json.ts` | `.` |
| `safeValidateTypes` | value | `src/validate-types.ts` | `.` |
| `Schema` | type | `src/schema.ts` | `.` |
| `stripFileExtension` | value | `src/strip-file-extension.ts` | `.` |
| `SystemModelMessage` | type | `src/types/system-model-message.ts` | `.` |
| `TextPart` | type | `src/types/content-part.ts` | `.` |
| `tool` | value | `src/types/tool.ts` | `.` |
| `Tool` | type | `src/types/tool.ts` | `.` |
| `ToolApprovalRequest` | type | `src/types/tool-approval-request.ts` | `.` |
| `ToolApprovalResponse` | type | `src/types/tool-approval-response.ts` | `.` |
| `ToolCall` | type | `src/types/tool-call.ts` | `.` |
| `ToolCallOptions` | type | `src/types/index.ts` | `.` |
| `ToolCallPart` | type | `src/types/content-part.ts` | `.` |
| `ToolContent` | type | `src/types/tool-model-message.ts` | `.` |
| `ToolExecuteFunction` | type | `src/types/tool.ts` | `.` |
| `ToolExecutionOptions` | type | `src/types/tool.ts` | `.` |
| `ToolModelMessage` | type | `src/types/tool-model-message.ts` | `.` |
| `ToolNameMapping` | type | `src/create-tool-name-mapping.ts` | `.` |
| `ToolNeedsApprovalFunction` | type | `src/types/tool.ts` | `.` |
| `ToolResult` | type | `src/types/tool-result.ts` | `.` |
| `ToolResultOutput` | type | `src/types/content-part.ts` | `.` |
| `ToolResultPart` | type | `src/types/content-part.ts` | `.` |
| `UserContent` | type | `src/types/user-model-message.ts` | `.` |
| `UserModelMessage` | type | `src/types/user-model-message.ts` | `.` |
| `validateDownloadUrl` | value | `src/validate-download-url.ts` | `.` |
| `validateTypes` | value | `src/validate-types.ts` | `.` |
| `ValidationResult` | type | `src/schema.ts` | `.` |
| `VERSION` | value | `src/version.ts` | `.` |
| `withoutTrailingSlash` | value | `src/without-trailing-slash.ts` | `.` |
| `withUserAgentSuffix` | value | `src/with-user-agent-suffix.ts` | `.` |
| `zodSchema` | value | `src/schema.ts` | `.` |

## External Star Re-exports

- `* from @standard-schema/spec`
