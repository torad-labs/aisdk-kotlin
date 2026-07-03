package ai.torad.aisdk

internal object ErrorMessages {

    fun of(error: Throwable?): String = error?.message ?: "unknown error"

    fun of(error: Any?): String = when (error) {
        null -> "unknown error"
        is String -> error
        is Throwable -> of(error)
        else -> error.toString()
    }
}
