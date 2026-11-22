package ai.molido.vpn.engine.wireguard

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineStartupFailureSignalTest {
    @Test
    fun downDuringStartupIsDeliveredAfterStartupCompletes() {
        var failures = 0
        val signal = EngineStartupFailureSignal { failures += 1 }

        signal.onDown(intentionalStop = false)
        assertEquals(0, failures)

        signal.markStartupComplete()
        assertEquals(1, failures)
    }

    @Test
    fun downAfterStartupIsDeliveredImmediately() {
        var failures = 0
        val signal = EngineStartupFailureSignal { failures += 1 }

        signal.markStartupComplete()
        signal.onDown(intentionalStop = false)

        assertEquals(1, failures)
    }

    @Test
    fun duplicateDownCallbacksAreDeliveredOnlyOnce() {
        var failures = 0
        val signal = EngineStartupFailureSignal { failures += 1 }

        signal.markStartupComplete()
        signal.onDown(intentionalStop = false)
        signal.onDown(intentionalStop = false)

        assertEquals(1, failures)
    }

    @Test
    fun intentionalStopDoesNotReportUnexpectedFailure() {
        var failures = 0
        val signal = EngineStartupFailureSignal { failures += 1 }

        signal.markStartupComplete()
        signal.onDown(intentionalStop = true)

        assertEquals(0, failures)
    }

    @Test
    fun throwingFailureObserverDoesNotEscapeCallbackThread() {
        val signal = EngineStartupFailureSignal {
            throw IllegalStateException("observer failure")
        }

        signal.markStartupComplete()
        signal.onDown(intentionalStop = false)
        // No exception escapes; exactly-once delivery remains consumed.
        signal.onDown(intentionalStop = false)
    }

    @Test
    fun downRacingStartupCompletionIsNeverLost() {
        repeat(100) {
            var failures = 0
            val signal = EngineStartupFailureSignal { failures += 1 }
            val down = Thread { signal.onDown(intentionalStop = false) }
            val complete = Thread { signal.markStartupComplete() }

            down.start()
            complete.start()
            down.join()
            complete.join()

            assertEquals(1, failures)
        }
    }
}
