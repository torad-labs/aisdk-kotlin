package ai.torad.aisdk

/**
 * Cancellation recognition for `catch (t: Throwable)` sites that must not swallow cancellation.
 *
 * IMPORTANT: [asCancellationExceptionOrNull] is a plain `as?` cast. It does NOT walk `cause`, so it
 * is exactly equivalent to a preceding `catch (ce: CancellationException) { throw ce }` clause — the
 * two forms are interchangeable, and neither one rethrows a non-cancellation wrapper whose cause
 * happens to be a `CancellationException`.
 *
 * That is stated this loudly because the name reads like it unwraps: two separate review passes read
 * it that way and reported the same non-defect — that a `catch (ce: CancellationException)` site
 * "differs from" a site using this helper. They do not differ. If cause-unwrapping is ever wanted it
 * has to be added here deliberately, and every call site re-checked, because swallowing vs
 * propagating cancellation is a behavioural change in the agent loop.
 */
internal object CancellationExceptions {
    /** The throwable itself when it IS a [kotlin.coroutines.cancellation.CancellationException]. */
    fun asCancellationExceptionOrNull(
        throwable: Throwable,
    ): kotlin.coroutines.cancellation.CancellationException? =
        throwable as? kotlin.coroutines.cancellation.CancellationException
}
