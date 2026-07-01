package ai.torad.aisdk

/**
 * Port-side log sink for non-fatal warnings and informational events.
 * Mirrors v6's `logger/log-warnings.ts` (per historical parity gap #31)
 * — the agent loop, middleware, and tool dispatch surface warnings
 * here so consumers can route them to Logcat / Crashlytics / their own
 * telemetry without the port hard-coding a logging dependency.
 *
 * Default impl is [NoopLogger] — the loop never breaks for missing
 * loggers. Production hosts inject their own implementation, such as
 * an Android Logcat adapter or a server-side structured logger.
 *
 * Three severities:
 *  - [warn] — recoverable issue worth surfacing (e.g., a tool-call
 *    failed JSON decode and the repair function ran successfully —
 *    the agent recovered, but consumers may want to count the rate).
 *  - [info] — lifecycle notes (e.g., `simulateStreamingMiddleware`
 *    fell back to synthesizing a stream because the underlying model
 *    didn't support streaming directly).
 *  - [debug] — verbose tracing. Production hosts typically drop
 *    these unless a debug-mode toggle is on.
 *
 * Errors are NOT routed here — those crash the loop via
 * [StreamEvent.Error] / `AgentEvent.Errored`, which is the typed channel
 * consumers should listen on.
 */
/** @since 0.3.0-beta01 */
public interface Logger {
    /** @since 0.3.0-beta01 */
    public fun warn(message: String, throwable: Throwable? = null)
    /** @since 0.3.0-beta01 */
    public fun info(message: String)
    /** @since 0.3.0-beta01 */
    public fun debug(message: String)
}

/**
 * Drop-everything logger. Default when no DI-injected impl is wired.
 * @since 0.3.0-beta01
 */
public data object NoopLogger : Logger {
    override fun warn(message: String, throwable: Throwable?): Unit = Unit
    override fun info(message: String): Unit = Unit
    override fun debug(message: String): Unit = Unit
}
