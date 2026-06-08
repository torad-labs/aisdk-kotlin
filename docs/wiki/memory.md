# Memory

The SDK does not own durable memory. Hosts decide what to store, how to secure
it, and which slice to feed back into a model or agent.

## What To Store

Common memory categories:

- validated `UIMessage` history,
- `ModelMessage` history for agent services,
- summaries of long conversations,
- retrieved documents and citations,
- user preferences,
- tool decisions and approval records,
- embeddings and vector ids,
- audit events and usage metadata.

Store the smallest useful representation. Rich UI data and model-visible
summaries often have different shapes.

## Conversation Memory

```kotlin
val checked = safeValidateUIMessages(session.state.value.messages)

if (checked is SafeValidateUIMessagesResult.Success) {
    chatStore.save(chatId, checked.messages)
}
```

On reload:

```kotlin
val messages = chatStore.load(chatId)
session.setMessages(messages)
```

Convert to model messages only when calling the model:

```kotlin
val modelMessages = convertToModelMessages(messages)
```

## Summary Memory

Summaries keep long conversations manageable:

```kotlin
val summary = generateText(
    model = model,
    system = "Summarize durable facts and unresolved tasks.",
    messages = oldMessages,
).text

memoryStore.saveSummary(userId, summary)
```

Feed summaries back through `system`, `messages`, or `prepareCall`.

## Retrieval Memory

Use embeddings for recall:

```kotlin
val embedded = embedMany(
    model = embeddingModel,
    values = notes.map { it.text },
    maxParallelCalls = 4,
)

embedded.embeddings.zip(notes).forEach { (vector, note) ->
    vectorStore.upsert(note.id, vector)
}
```

At answer time, retrieve and rerank before generation.

## Memory Tools

Expose memory as explicit tools when the model should decide what to read or
write:

```kotlin
val rememberPreference = tool<PreferenceInput, PreferenceResult, AppContext>(
    name = "rememberPreference",
    description = "Store a user preference after approval.",
    needsApproval = { _, _ -> true },
) { input ->
    preferences.save(context.userId, input.key, input.value)
    PreferenceResult(saved = true)
}
```

Use approval for memory writes that affect future behavior.

## Context Injection

Use `prepareCall` for request-specific memory:

```kotlin
prepareCall = {
    val profile = memory.loadProfile(options!!.userId)
    AgentSettings(
        instructions = instructions + "\nKnown user profile:\n$profile",
    )
}
```

Keep sensitive memory out of prompts unless the current task requires it.

## Tips

- Validate UI messages before storing.
- Store approval records with tool-call ids.
- Summarize old turns before they dominate context.
- Keep memory writes explicit, auditable, and reversible.
- Use retrieval tools when memory may be large.

## Related

- [Agents](agents.md)
- [Chatbots](chatbots.md)
- [Prompts And Messages](prompts-and-messages.md)
- [Model Families](model-families.md)
- [Application Patterns](application-patterns.md)
