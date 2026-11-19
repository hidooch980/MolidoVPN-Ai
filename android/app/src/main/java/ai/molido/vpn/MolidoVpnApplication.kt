package ai.molido.vpn

import android.app.Application
import ai.molido.vpn.core.SessionSupervisor
import ai.molido.vpn.core.VpnSessionController
import ai.molido.vpn.engine.wireguard.WireGuardEngineAdapter

/** Owns the process-wide VPN controller and the single registered WireGuard adapter. */
class MolidoVpnApplication : Application() {
    lateinit var vpnSessions: VpnSessionController
        private set

    override fun onCreate() {
        super.onCreate()
        vpnSessions = VpnSessionController(
            supervisor = SessionSupervisor(),
            adapters = listOf(WireGuardEngineAdapter(this))
        )
    }
}
