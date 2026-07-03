package ai.torad.aisdk

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal const val DEFAULT_MAX_PARALLEL_CALLS: Int = 8

internal object BoundedParallel {
    suspend fun <T, R> map(
        values: List<T>,
        maxParallelCalls: Int,
        transform: suspend (T) -> R,
    ): List<R> {
        require(maxParallelCalls > 0) { "maxParallelCalls must be > 0" }
        return if (values.isEmpty()) {
            emptyList()
        } else {
            val parallelism = maxParallelCalls.coerceAtMost(values.size)
            if (parallelism == 1) {
                values.map { transform(it) }
            } else {
                val workers = List(parallelism) { mutableListOf<IndexedValue<T>>() }
                values.forEachIndexed { index, value ->
                    workers[index % parallelism] += IndexedValue(index, value)
                }
                coroutineScope {
                    workers
                        .map { worker ->
                            async {
                                worker.map { indexed ->
                                    indexed.index to transform(indexed.value)
                                }
                            }
                        }
                        .awaitAll()
                        .flatten()
                        .sortedBy { it.first }
                        .map { it.second }
                }
            }
        }
    }
}
