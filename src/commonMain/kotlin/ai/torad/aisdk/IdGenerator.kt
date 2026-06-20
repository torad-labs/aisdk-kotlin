package ai.torad.aisdk

import kotlin.random.Random

public data class IdGenerator(
    val prefix: String? = null,
    val size: Int = 16,
    val alphabet: String = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
    val separator: String = "-",
    val random: Random = Random.Default,
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
            IdGenerator(prefix = prefix, random = random).generate()
    }
}
