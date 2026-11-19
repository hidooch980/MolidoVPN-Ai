package ai.molido.vpn.core

import org.junit.Assert.*
import org.junit.Test

class EngineStartExceptionTest {
    private class RecordingSession : EngineSession {
        var stops = 0
        override fun stop() { stops += 1 }
    }

    @Test
    fun retainsCleanupHandleAndCause() {
        val session = RecordingSession()
        val cause = IllegalStateException("startup")
        val error = EngineStartException("cleanup pending", session, cause)
        assertSame(session, error.cleanupSession)
        assertSame(cause, error.cause)
        error.cleanupSession.stop()
        assertEquals(1, session.stops)
    }
}
