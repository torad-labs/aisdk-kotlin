package ai.torad.aisdk

internal object CancellationExceptions {
    fun asCancellationExceptionOrNull(
        throwable: Throwable,
    ): kotlin.coroutines.cancellation.CancellationException? =
        throwable as? kotlin.coroutines.cancellation.CancellationException
}
