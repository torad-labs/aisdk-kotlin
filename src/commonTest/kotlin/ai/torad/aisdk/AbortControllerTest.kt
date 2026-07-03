package ai.torad.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AbortControllerTest {
    @Test
    fun `abort callback failures are logged and do not block remaining callbacks`() {
        val logger = RecordingLogger()
        val controller = AbortController(logger)
        val callbacks = mutableListOf<String>()

        controller.signal.register {
            callbacks += "first"
            throw CallbackFailure("first failed")
        }
        controller.signal.register {
            callbacks += "second"
        }

        controller.abort()

        assertEquals(listOf("first", "second"), callbacks)
        assertEquals(1, logger.warnings.size)
        assertTrue(logger.warnings.single().message.contains("AbortSignal callback threw"))
        assertIs<CallbackFailure>(logger.warnings.single().throwable)
    }

    @Test
    fun `already aborted registration logs callback failure synchronously`() {
        val logger = RecordingLogger()
        val controller = AbortController(logger)
        controller.abort()

        controller.signal.register {
            throw CallbackFailure("late failed")
        }

        assertEquals(1, logger.warnings.size)
        assertIs<CallbackFailure>(logger.warnings.single().throwable)
    }

    private class CallbackFailure(message: String) : RuntimeException(message)

    private data class Warning(val message: String, val throwable: Throwable?)

    private class RecordingLogger : Logger {
        val warnings = mutableListOf<Warning>()

        override fun warn(message: String, throwable: Throwable?) {
            warnings += Warning(message, throwable)
        }

        override fun info(message: String) = Unit

        override fun debug(message: String) = Unit
    }
}
