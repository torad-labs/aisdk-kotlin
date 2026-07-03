package ai.torad.aisdk.samples.jvmchat

import ai.torad.aisdk.GenerationInput
import ai.torad.aisdk.LanguageModel
import ai.torad.aisdk.TextGenerator
import ai.torad.aisdk.providers.MockLanguageModelTextOnly
import ai.torad.aisdk.providers.OpenAICompatible
import ai.torad.aisdk.providers.OpenAICompatibleProviderSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

public fun main(args: Array<String>) {
    runBlocking {
        val prompt = args.joinToString(" ").ifBlank {
            "Explain what AI SDK Kotlin provides."
        }
        val client = createOpenAIClientOrNull()
        try {
            val model = client?.let(::openAICompatibleModel)
                ?: MockLanguageModelTextOnly("AI SDK Kotlin provides agents, tools, streaming, and provider adapters.")
            runGenerateAndStream(model, prompt)
        } finally {
            client?.close()
        }
    }
}

private suspend fun runGenerateAndStream(model: LanguageModel, prompt: String) {
    val generator = TextGenerator(model)

    val generated = generator.generate(GenerationInput.Prompt(prompt)).first()
    println("generate:")
    println(generated.text)

    println()
    println("stream:")
    generator.streamResult(GenerationInput.Prompt(prompt))
        .textStream
        .collect { print(it) }
    println()
}

private fun createOpenAIClientOrNull(): HttpClient? =
    if (System.getenv("AISDK_SAMPLE_PROVIDER") == "openai") HttpClient(CIO) else null

private fun openAICompatibleModel(client: HttpClient): LanguageModel {
    val apiKey = requireNotNull(System.getenv("OPENAI_API_KEY")) {
        "OPENAI_API_KEY is required for the OpenAI-compatible sample path"
    }
    val baseUrl = System.getenv("OPENAI_BASE_URL") ?: "https://api.openai.com/v1"
    val modelId = System.getenv("OPENAI_MODEL") ?: "gpt-4o-mini"
    val provider = OpenAICompatible(
        client = client,
        settings = OpenAICompatibleProviderSettings {
            name("openai-compatible")
            baseUrl(baseUrl)
            apiKey(apiKey)
        },
    )
    return provider.chatModel(modelId)
}
