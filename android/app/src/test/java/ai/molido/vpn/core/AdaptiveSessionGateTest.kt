package ai.molido.vpn.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveSessionGateTest {
    private class Session : EngineSession { override fun stop() = Unit }
    private class Adapter : EngineAdapter {
        override val slot = EngineSlot.WIREGUARD
        var starts = 0
        override fun start(configuration: String): EngineSession { starts++; return Session() }
    }

    @Test fun unvalidatedNetworkBlocksBeforeEngineStart() {
        val adapter = Adapter()
        val controller = VpnSessionController(
            SessionSupervisor(), listOf(adapter),
            networkAssessmentProvider = { NetworkAssessment(NetworkAssessmentStatus.NOT_VALIDATED) }
        )
        assertFalse(controller.connect(EngineSlot.WIREGUARD, "config"))
        assertTrue(adapter.starts == 0)
    }

    @Test fun unknownNetworkBlocksBeforeEngineStart() {
        val adapter = Adapter()
        val controller = VpnSessionController(
            SessionSupervisor(), listOf(adapter),
            networkAssessmentProvider = { NetworkAssessment(NetworkAssessmentStatus.UNKNOWN) }
        )
        assertFalse(controller.connect(EngineSlot.WIREGUARD, "config"))
        assertTrue(adapter.starts == 0)
    }

    @Test fun validatedNetworkAllowsEngineStart() {
        val adapter = Adapter()
        val controller = VpnSessionController(
            SessionSupervisor(), listOf(adapter),
            networkAssessmentProvider = { NetworkAssessment(NetworkAssessmentStatus.VALIDATED, NetworkTransport.WIFI, false) },
            networkPolicyProvider = { NetworkPolicy.WIFI_ONLY }
        )
        assertTrue(controller.connect(EngineSlot.WIREGUARD, "config"))
        assertTrue(adapter.starts == 1)
    }
}
