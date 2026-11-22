package ai.molido.vpn.core

/**
 * Distinguishes a live PackageInstaller session from a stale persisted marker.
 * A stale marker may be cleared; a live session must continue blocking another update.
 */
object PendingInstallRecoveryPolicy {
    fun isSessionActive(pendingSessionId: Int, activeSessionIds: Collection<Int>): Boolean =
        pendingSessionId >= 0 && activeSessionIds.any { it == pendingSessionId }
}
