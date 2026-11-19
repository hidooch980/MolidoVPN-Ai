package ai.molido.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Explicit, non-exported receiver used only as PackageInstaller's commit callback. */
class UpdateInstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val application = context.applicationContext as? MolidoVpnApplication ?: return
        application.updateInstaller.handleInstallStatus(intent)
    }
}
