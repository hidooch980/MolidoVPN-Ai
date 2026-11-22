package ai.molido.vpn.core

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VpnSessionHealthConcurrencyTest {
    @Test
    fun disconnectDoesNotWaitForSlowNativeHealthReadAndStaleSampleIsDropped() {
        val healthStarted = CountDownLatch(1)
        val releaseHealth = CountDownLatch(1)
        val adapter = object : EngineAdapter, TunnelHealthAwareAdapter {
            override val slot = EngineSlot.WIREGUARD
            override fun start(configuration: String): EngineSession = object : EngineSession {
                override fun stop() = Unit
            }
            override fun runtimeHealth(nowEpochMillis: Long): TunnelHealthAssessment {
                healthStarted.countDown()
                check(releaseHealth.await(3, TimeUnit.SECONDS))
                return TunnelHealthAssessment(
                    TunnelHealthState.HANDSHAKE_RECENT,
                    peerActivityObserved = true,
                    explanation = "late sample"
                )
            }
        }
        val controller = VpnSessionController(SessionSupervisor(), listOf(adapter))
        assertTrue(controller.connect(EngineSlot.WIREGUARD, "profile"))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val sample = pool.submit<TunnelHealthAssessment?> { controller.observeActiveTunnelHealth() }
            assertTrue(healthStarted.await(2, TimeUnit.SECONDS))
            val stopped = pool.submit<Boolean> { controller.disconnect() }
            assertTrue("disconnect must not block behind native telemetry", stopped.get(1, TimeUnit.SECONDS))
            releaseHealth.countDown()
            assertNull("a sample from a stopped session must be discarded", sample.get(2, TimeUnit.SECONDS))
        } finally {
            releaseHealth.countDown()
            pool.shutdownNow()
        }
    }
}
