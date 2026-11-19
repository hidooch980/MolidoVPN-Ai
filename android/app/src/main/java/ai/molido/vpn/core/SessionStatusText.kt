package ai.molido.vpn.core

/** Produces truthful UI status while preserving the result of the latest user operation. */
object SessionStatusText {
    fun render(snapshot: SessionSnapshot, detail: String? = null): String {
        val stateLabel = when (snapshot.state) {
            SessionState.IDLE -> "Idle — no VPN session"
            SessionState.RESERVED -> "Reserved — startup in progress"
            SessionState.RUNNING -> "Backend active — peer handshake not verified"
            SessionState.STOPPING -> "Stopping — cleanup in progress"
            SessionState.FAILED -> "Failed — cleanup may be pending"
        }
        return if (detail.isNullOrBlank()) stateLabel else "$stateLabel\n$detail"
    }
}
