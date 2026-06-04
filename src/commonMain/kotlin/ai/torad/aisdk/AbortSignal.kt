package ai.torad.aisdk

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * Cancellation primitive matching the Vercel AI SDK v6 `AbortSignal` shape.
 *
 * Per port DECISION 3 (cancellation model): we expose a custom interface
 * that internally wraps Kotlin coroutines `Job.cancel()`. This gives v6
 * ergonomics (a propagatable signal threaded through tool execution
 * contexts and subagent calls — invariant I-10) while staying native to
 * Kotlin's structured concurrency.
 *
 * Idiomatic use: bind to the calling [CoroutineScope]'s job. When the
 * scope cancels, the signal aborts, and any subagent/tool execution
 * observing [throwIfAborted] or [register] surfaces the cancellation.
 */
interface AbortSignal {
    /** True once any cancellation source has fired. */
    val isAborted: Boolean

    /** Throws [AbortError] if [isAborted]. Cheap to call repeatedly. */
    fun throwIfAborted()

    /**
     * Register a callback that fires exactly once on abort. If already
     * aborted, fires synchronously. Returns a handle to deregister.
     */
    fun register(onAbort: () -> Unit): AbortRegistration

    interface AbortRegistration {
        fun cancel()
    }
}

/** A signal that is never aborted. Useful as a default. */
val AbortSignalNever: AbortSignal = object : AbortSignal {
    override val isAborted: Boolean = false
    override fun throwIfAborted() = Unit
    override fun register(onAbort: () -> Unit): AbortSignal.AbortRegistration =
        object : AbortSignal.AbortRegistration { override fun cancel() = Unit }
}

/**
 * Mutable abort source — the v6 equivalent of `AbortController`. Hold the
 * controller, hand the [signal] to the agent, call [abort] from the UI's
 * stop button.
 */
class AbortController {
    private val backing: CompletableJob = SupervisorJob()
    private val callbacks = mutableListOf<() -> Unit>()

    val signal: AbortSignal = SignalImpl()

    fun abort() {
        if (backing.isCancelled) return
        backing.cancel()
        // Snapshot to avoid concurrent-modification if a callback registers
        // a new callback during invocation.
        val snapshot = callbacks.toList()
        callbacks.clear()
        for (cb in snapshot) {
            runCatching { cb() }
        }
    }

    private inner class SignalImpl : AbortSignal {
        override val isAborted: Boolean get() = backing.isCancelled

        override fun throwIfAborted() {
            if (backing.isCancelled) throw AbortError()
        }

        override fun register(onAbort: () -> Unit): AbortSignal.AbortRegistration {
            if (backing.isCancelled) {
                runCatching { onAbort() }
                return object : AbortSignal.AbortRegistration { override fun cancel() = Unit }
            }
            callbacks.add(onAbort)
            return object : AbortSignal.AbortRegistration {
                override fun cancel() {
                    callbacks.remove(onAbort)
                }
            }
        }
    }
}

/** Thrown from [AbortSignal.throwIfAborted] when the signal has fired. */
class AbortError(message: String = "operation aborted") : kotlin.coroutines.cancellation.CancellationException(message)

/**
 * Bind an abort signal to a [Job] so the signal fires when the job
 * completes (cancelled or otherwise). Lets a parent scope's lifetime
 * automatically cancel anything observing the signal.
 */
fun abortSignalFromJob(job: Job): AbortSignal {
    val controller = AbortController()
    job.invokeOnCompletion { controller.abort() }
    return controller.signal
}

fun Job.asAbortSignal(): AbortSignal = abortSignalFromJob(this)

fun CoroutineScope.asAbortSignal(): AbortSignal =
    coroutineContext[Job]?.asAbortSignal() ?: AbortSignalNever

fun combineAbortSignals(vararg signals: AbortSignal): AbortSignal {
    val active = signals.filterNot { it === AbortSignalNever }
    if (active.isEmpty()) return AbortSignalNever
    if (active.size == 1) return active.single()

    val controller = AbortController()
    val registrations = active.map { signal ->
        signal.register { controller.abort() }
    }
    controller.signal.register {
        registrations.forEach { it.cancel() }
    }
    return controller.signal
}
