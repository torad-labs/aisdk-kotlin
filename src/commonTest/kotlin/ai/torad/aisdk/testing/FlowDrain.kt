package ai.torad.aisdk.testing

import app.cash.turbine.Event
import app.cash.turbine.test
import kotlinx.coroutines.flow.Flow

/**
 * Drain every emission of a FINITE cold flow into a list via Turbine.
 *
 * Replaces the deprecated `flow.toList()` shape (banned by
 * §tests-flow-via-turbine — hangs forever on StateFlow/SharedFlow). For
 * cold flows whose body completes, this is the explicit-collection
 * equivalent: the Turbine `.test {}` block handles cancellation if the
 * flow somehow doesn't complete, and `awaitEvent()` surfaces both items
 * and the terminal completion / error.
 *
 * Top-level function used by stream tests.
 */
internal suspend fun <T> drainAllItems(flow: Flow<T>): List<T> {
    val items = mutableListOf<T>()
    flow.test {
        while (true) {
            when (val event = awaitEvent()) {
                is Event.Item -> items += event.value
                is Event.Complete -> return@test
                is Event.Error -> throw event.throwable
            }
        }
    }
    return items
}
