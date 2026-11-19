package ai.molido.vpn.vpn

import android.net.VpnService
import android.content.Intent
import android.os.IBinder

/**
 * VPN service lifecycle boundary.
 *
 * This scaffold deliberately does not establish a TUN or claim connectivity.
 * TUN creation will be implemented only behind the single SessionSupervisor
 * after the WireGuard packet data plane and lifecycle contract are reviewed.
 */
class MolidoVpnService : VpnService() {
    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
