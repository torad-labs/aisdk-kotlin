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

public class IdGeneratorBuilder internal constructor() {
    private var prefix: String? = null
    private var size: Int = 16
    private var alphabet: String = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private var separator: String = "-"
    private var random: Random = Random.Default

    public fun prefix(value: String?) {
        prefix = value
    }

    public fun size(value: Int) {
        size = value
    }

    public fun alphabet(value: String) {
        alphabet = value
    }

    public fun separator(value: String) {
        separator = value
    }

    public fun random(value: Random) {
        random = value
    }

    internal fun build(): IdGenerator =
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
