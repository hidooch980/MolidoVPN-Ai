package ai.molido.vpn

import android.app.Application
import ai.molido.vpn.core.SessionSupervisor
import ai.molido.vpn.core.VpnSessionController
import ai.molido.vpn.engine.wireguard.WireGuardEngineAdapter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Owns the process-wide VPN controller and serializes blocking engine operations off the UI thread. */
class MolidoVpnApplication : Application() {
    lateinit var vpnSessions: VpnSessionController
        private set

    lateinit var vpnExecutor: ExecutorService
        private set

    override fun onCreate() {
        super.onCreate()
        vpnSessions = VpnSessionController(
            supervisor = SessionSupervisor(),
            adapters = listOf(WireGuardEngineAdapter(this))
        )
        vpnExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "molido-vpn-control").apply { isDaemon = true }
        }
    }

    override fun onTerminate() {
        vpnExecutor.shutdown()
        super.onTerminate()
    }
}
