package ai.molido.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RecoveryAttemptGateTest {
    @Test
    fun onlyOneAttemptCanBeInFlightAndLimitIsStrict() {
        val gate = RecoveryAttemptGate(maxAttempts = 2)
        val first = gate.tryStart()
        assertNotNull(first)
        assertNull(gate.tryStart())
        assertTrue(gate.complete(first!!, connected = false))
        val second = gate.tryStart()
        assertNotNull(second)
        assertTrue(gate.complete(second!!, connected = false))
        assertNull(gate.tryStart())
        assertEquals(2, gate.attemptsStarted())
    }

    @Test
    fun staleOrDuplicateCompletionCannotUnlockAnotherAttempt() {
        val gate = RecoveryAttemptGate(maxAttempts = 3)
        val first = gate.tryStart()!!
        assertTrue(gate.complete(first, connected = false))
        val second = gate.tryStart()!!
        assertFalse(gate.complete(first, connected = true))
        assertTrue(gate.isInFlight())
        assertTrue(gate.complete(second, connected = true))
        assertEquals(0, gate.attemptsStarted())
    }

    @Test
    fun successfulReconnectResetsBoundedRetryBudget() {
        val gate = RecoveryAttemptGate(maxAttempts = 1)
        val token = gate.tryStart()!!
        assertTrue(gate.complete(token, connected = true))
        assertNotNull(gate.tryStart())
    }

    @Test
    fun concurrentCallersCannotReserveTwoAttempts() {
        val gate = RecoveryAttemptGate(maxAttempts = 5)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (0 until 8).map {
                pool.submit<RecoveryAttemptToken?> {
                    ready.countDown()
                    start.await(2, TimeUnit.SECONDS)
                    gate.tryStart()
                }
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            start.countDown()
            val winners = results.count { it.get(2, TimeUnit.SECONDS) != null }
            assertEquals(1, winners)
            assertEquals(1, gate.attemptsStarted())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun processDeathWithPendingAttemptFailsClosedAcrossGateRecreation() {
        val store = InMemoryRecoveryStateStore()
        val firstProcess = RecoveryAttemptGate(maxAttempts = 3, store = store)
        assertNotNull(firstProcess.tryStart())
        val afterProcessDeath = RecoveryAttemptGate(maxAttempts = 3, store = store)
        assertTrue(afterProcessDeath.isInterrupted())
        assertEquals(3, afterProcessDeath.attemptsStarted())
        assertNull(afterProcessDeath.tryStart())
    }

    @Test
    fun persistenceFailurePreventsStartingOrCompletingAnAttempt() {
        val store = InMemoryRecoveryStateStore().apply { failWrites = true }
        val gate = RecoveryAttemptGate(maxAttempts = 2, store = store)
        assertNull(gate.tryStart())
        assertFalse(gate.isInFlight())
    }

    @Test
    fun explicitOperatorResetClearsProcessDeathLockout() {
        val store = InMemoryRecoveryStateStore()
        val first = RecoveryAttemptGate(maxAttempts = 2, store = store)
        assertNotNull(first.tryStart())
        val restarted = RecoveryAttemptGate(maxAttempts = 2, store = store)
        assertTrue(restarted.isInterrupted())
        assertTrue(restarted.resetAfterExplicitUserAction())
        assertNotNull(restarted.tryStart())
    }

    private class InMemoryRecoveryStateStore : RecoveryAttemptStateStore {
        var state = PersistedRecoveryAttemptState()
        var failWrites = false
        override fun read() = state
        override fun write(state: PersistedRecoveryAttemptState): Boolean {
            if (failWrites) return false
            this.state = state
            return true
        }
    }

}
