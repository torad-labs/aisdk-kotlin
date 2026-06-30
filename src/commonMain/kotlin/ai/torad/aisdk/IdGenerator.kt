package ai.torad.aisdk

import kotlin.random.Random

public class IdGenerator internal constructor(
    public val prefix: String? = null,
    public val size: Int = 16,
    public val alphabet: String = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
    public val separator: String = "-",
    public val random: Random = Random.Default,
) {
    public fun generate(): String {
        require(size > 0) { "size must be > 0" }
        require(alphabet.isNotEmpty()) { "alphabet must not be empty" }
        require(separator !in alphabet) {
            "The separator \"$separator\" must not be part of the alphabet \"$alphabet\"."
        }
        val suffix = buildString {
            repeat(size) {
                append(alphabet[random.nextInt(alphabet.length)])
            }
        }
        return prefix?.let { "$it$separator$suffix" } ?: suffix
    }

    public companion object {
        public fun generate(prefix: String? = null, random: Random = Random.Default): String =
            IdGenerator {
                prefix(prefix)
                random(random)
            }.generate()
    }
}

public class IdGeneratorBuilder {
    private var prefix: String? = null
    private var size: Int = 16
    private var alphabet: String = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private var separator: String = "-"
    private var random: Random = Random.Default

    public fun prefix(value: String?): IdGeneratorBuilder {
        prefix = value
        return this
    }

    public fun size(value: Int): IdGeneratorBuilder {
        size = value
        return this
    }

    public fun alphabet(value: String): IdGeneratorBuilder {
        alphabet = value
        return this
    }

    public fun separator(value: String): IdGeneratorBuilder {
        separator = value
        return this
    }

    public fun random(value: Random): IdGeneratorBuilder {
        random = value
        return this
    }

    public fun build(): IdGenerator =
        IdGenerator(
            prefix = prefix,
            size = size,
            alphabet = alphabet,
            separator = separator,
            random = random,
        )
}

public fun IdGenerator(block: IdGeneratorBuilder.() -> Unit = {}): IdGenerator =
    IdGeneratorBuilder().apply(block).build()
