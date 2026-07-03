package ai.torad.aisdk.testing

import app.cash.turbine.Event
import app.cash.turbine.test
import kotlinx.coroutines.flow.Flow

internal object FlowDrain {
    suspend fun <T> drainAllItems(flow: Flow<T>): List<T> {
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
}
