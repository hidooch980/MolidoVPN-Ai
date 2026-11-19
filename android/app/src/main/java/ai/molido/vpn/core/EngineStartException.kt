package ai.molido.vpn.core

/**
 * Startup failed after the adapter acquired resources that could not be proven released.
 * The controller retains exclusive session ownership and retries cleanup via disconnect().
 */
class EngineStartException(
    message: String,
    val cleanupSession: EngineSession,
    cause: Throwable
) : Exception(message, cause)
