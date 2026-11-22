package ai.molido.vpn.engine.wireguard

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Delivers an unexpected DOWN transition exactly once, including the narrow race where
 * the backend reports DOWN between its final state check and startup completion.
 *
 * Failure observers are external callbacks: their exceptions must never escape into
 * WireGuard's backend/network callback thread or interfere with tunnel cleanup.
 */
internal class EngineStartupFailureSignal(
    private val onFailure: () -> Unit
) {
    private val startupComplete = AtomicBoolean(false)
    private val downObservedDuringStartup = AtomicBoolean(false)
    private val failureDelivered = AtomicBoolean(false)

    fun onDown(intentionalStop: Boolean) {
        if (intentionalStop) return
        if (!startupComplete.get()) {
            downObservedDuringStartup.set(true)
        }
        if (startupComplete.get()) {
            deliverFailureOnce()
        }
    }

    fun markStartupComplete() {
        startupComplete.set(true)
        if (downObservedDuringStartup.get()) {
            deliverFailureOnce()
        }
    }

    private fun deliverFailureOnce() {
        if (failureDelivered.compareAndSet(false, true)) {
            try {
                onFailure()
            } catch (_: Exception) {
                // Observers cannot be allowed to crash backend callback threads.
            }
        }
    }
}
