package ai.torad.aisdk

internal object CollectionOps {

    fun <T> splitArray(values: List<T>, chunkSize: Int): List<List<T>> {
        require(chunkSize > 0) { "chunkSize must be > 0" }
        return values.chunked(chunkSize)
    }

    fun <T> asArray(value: T): List<T> = listOf(value)

    fun <T> asArray(value: Iterable<T>): List<T> = value.toList()
}
