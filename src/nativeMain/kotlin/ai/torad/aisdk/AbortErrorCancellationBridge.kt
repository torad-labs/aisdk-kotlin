package ai.torad.aisdk

internal actual object AbortErrorCancellationBridge {
    actual fun AbortError.asCoroutineCancellation(): kotlinx.coroutines.CancellationException = this
}
