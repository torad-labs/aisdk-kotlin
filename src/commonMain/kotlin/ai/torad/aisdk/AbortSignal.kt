package ai.torad.aisdk

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.coroutineContext

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
/** @since 0.3.0-beta01 */
public interface AbortSignal {
    /**
     * True once any cancellation source has fired.
     * @since 0.3.0-beta01
     */
    public val isAborted: Boolean

    /**
     * Throws [AbortError] if [isAborted]. Cheap to call repeatedly.
     * @since 0.3.0-beta01
     */
    public fun throwIfAborted()

    /**
     * Register a callback that fires exactly once on abort. If already
     * aborted, fires synchronously. Returns a handle to deregister.
     * @since 0.3.0-beta01
     */
    public fun register(onAbort: () -> Unit): AbortRegistration

    /** @since 0.3.0-beta01 */
    public interface AbortRegistration {
        /** @since 0.3.0-beta01 */
        public fun cancel()
    }
}

/**
 * A signal that is never aborted. Useful as a default.
 * @since 0.3.0-beta01
 */
public val AbortSignalNever: AbortSignal = object : AbortSignal {
    override val isAborted: Boolean = false
    override fun throwIfAborted() = Unit
    override fun register(onAbort: () -> Unit): AbortSignal.AbortRegistration =
        object : AbortSignal.AbortRegistration { override fun cancel() = Unit }
}

/**
 * Mutable abort source — the v6 equivalent of `AbortController`. Hold the
 * controller, hand the [signal] to the agent, call [abort] from the UI's
 * stop button.
 * @since 0.3.0-beta01
 */
@OptIn(ExperimentalAtomicApi::class)
public class AbortController {
    private val backing: CompletableJob = SupervisorJob()

    // Copy-on-write callback list via atomic CAS. register/cancel/abort may be
    // called from different threads (a UI stop button vs a background tool
    // coroutine), so a plain mutableListOf would race — and is UB on Native.
    private val callbacks = AtomicReference<List<() -> Unit>>(emptyList())

    /** @since 0.3.0-beta01 */
    public val signal: AbortSignal = SignalImpl()

    /** @since 0.3.0-beta01 */
    public fun abort() {
        if (backing.isCancelled) return
        backing.cancel()
        // Atomically drain so a firing callback can't observe a half-cleared
        // list, and so abort() is idempotent under races.
        val snapshot = callbacks.exchange(emptyList())
        for (cb in snapshot) {
            runCatching { cb() }
        }
    }

    private fun addCallback(onAbort: () -> Unit) {
        while (true) {
            val current = callbacks.load()
            if (callbacks.compareAndSet(current, current + onAbort)) return
        }
    }

    private fun removeCallback(onAbort: () -> Unit) {
        while (true) {
            val current = callbacks.load()
            val next = current - onAbort
            if (next.size == current.size) return // already removed
            if (callbacks.compareAndSet(current, next)) return
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
                return NoopRegistration
            }
            addCallback(onAbort)
            // abort() may have drained between the isCancelled check and the
            // add; if so this callback was missed — fire any stragglers now.
            if (backing.isCancelled) {
                for (cb in callbacks.exchange(emptyList())) {
                    runCatching { cb() }
                }
            }
            return object : AbortSignal.AbortRegistration {
                override fun cancel() = removeCallback(onAbort)
            }
        }
    }

    private object NoopRegistration : AbortSignal.AbortRegistration {
        override fun cancel() = Unit
    }
}

/**
 * Thrown from [AbortSignal.throwIfAborted] when the signal has fired.
 * @since 0.3.0-beta01
 */
public class AbortError(
    message: String = "operation aborted"
) : kotlin.coroutines.cancellation.CancellationException(message)

internal object AbortSignalRuntime {
    suspend fun <T> withAbortCancellation(signal: AbortSignal, block: suspend () -> T): T = coroutineScope {
        signal.throwIfAborted()
        val job = coroutineContext[Job]
        val registration = signal.register { job?.cancel(AbortError()) }
        try {
            block()
        } finally {
            registration.cancel()
        }
    }
}

/**
 * Bind an abort signal to a [Job] so the signal fires when the job
 * completes (cancelled or otherwise). Lets a parent scope's lifetime
 * automatically cancel anything observing the signal.
 * @since 0.3.0-beta01
 */
public fun AbortSignalFromJob(job: Job): AbortSignal {
    val controller = AbortController()
    job.invokeOnCompletion { controller.abort() }
    return controller.signal
}

/**
 * Member-extensions converting coroutine handles into [AbortSignal]s.
 * @since 0.3.0-beta01
 */
public object AbortSignals {
    /** @since 0.3.0-beta01 */
    public fun Job.asAbortSignal(): AbortSignal = AbortSignalFromJob(this)

    /** @since 0.3.0-beta01 */
    public fun CoroutineScope.asAbortSignal(): AbortSignal =
        coroutineContext[Job]?.asAbortSignal() ?: AbortSignalNever
}

// FunctionNaming/ReturnCount: PascalCase factory (matches AbortSignalFromJob) with intentional
// early returns for the empty / single / already-aborted fast paths — long-standing, baselined.
@OptIn(ExperimentalAtomicApi::class)
@Suppress("FunctionNaming", "ReturnCount")
/** @since 0.3.0-beta01 */
public fun CombineAbortSignals(vararg signals: AbortSignal): AbortSignal {
    val active = signals.filterNot { it === AbortSignalNever }
    if (active.isEmpty()) return AbortSignalNever
    if (active.size == 1) return active.single()

    val controller = AbortController()
    // If a source has already fired, abort now and skip wiring — registering
    // forwards onto an already-aborted source fires them synchronously and
    // would leak the child registrations.
    if (active.any { it.isAborted }) {
        controller.abort()
        return controller.signal
    }

    // Copy-on-write via AtomicReference (the AbortController idiom): the wiring loop CAS-appends
    // while the teardown reads an immutable snapshot via exchange. A plain MutableList raced here —
    // a source firing from another thread mid-wiring iterated the list while the loop mutated it
    // (ConcurrentModificationException on JVM, memory-unsafe on Native).
    val registrations = AtomicReference<List<AbortSignal.AbortRegistration>>(emptyList())
    // Attach teardown BEFORE the forwards, so a source that aborts
    // synchronously mid-wiring still tears down everything registered so far.
    controller.signal.register { registrations.exchange(emptyList()).forEach { it.cancel() } }
    for (signal in active) {
        val registration = signal.register { controller.abort() }
        while (true) {
            val current = registrations.load()
            if (registrations.compareAndSet(current, current + registration)) break
        }
        // If that source fired synchronously, the teardown already ran (before
        // this registration was listed). Cancel it explicitly and stop wiring
        // the remaining sources — the controller is already terminal.
        if (controller.signal.isAborted) {
            registration.cancel()
            break
        }
    }
    return controller.signal
}
