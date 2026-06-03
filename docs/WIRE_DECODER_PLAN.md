# Wire Decoder Leverage Plan

## Decision

The SDK needs one strict wire boundary between external provider/MCP/tool JSON and internal domain types. Provider code can still map provider-specific shapes, but malformed wire data must cross a shared decoder that fails loudly and consistently.

## Design Exploration

1. Central helper functions only: low migration cost, but leaves parsing scattered.
2. Full provider DTO rewrite: strongest end state, but too much blast radius for one unsafe sweep.
3. Strict boundary plus staged migration: introduce one decoder/error type, migrate high-risk seams first, and make future migrations incremental.
4. Runtime schema engine for every provider response: powerful, but redundant with Kotlin serialization and harder to maintain.

Chosen: strict boundary plus staged migration. It gives the leverage point a real API now without hiding a massive rewrite inside one diff.

## Acceptance Criteria

1. A shared `WireDecoder` exists in `commonMain`.
2. Wire failures throw a typed `WireDecodeException` with provider, operation, and path context.
3. Embedding vector values use the shared decoder, not local silent coercion.
4. JSON-RPC parsing uses the shared decoder and rejects malformed envelopes.
5. Tool result wire parsing uses the shared decoder for known tagged wire shapes.
6. Tests prove missing/malformed values fail rather than becoming `0`, `""`, `emptyList`, or dropped events.
7. `./gradlew clean check --no-build-cache` passes.

## Migration Rule

When moving provider parsing behind the boundary, the provider may still accept documented optional fields. It must not use missing/malformed required wire fields as default domain values. Optional means the provider protocol says optional, not "the parser was convenient."

## Next Migration Targets

1. Gateway stream event parsing in `KtorGatewayTransport`.
2. OpenAI-compatible chat/tool-call parsing.
3. Google and Anthropic stream block parsing.
4. Media generation response parsers for image, video, speech, and transcription.
