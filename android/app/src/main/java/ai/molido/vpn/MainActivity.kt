package ai.molido.vpn

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val state = (application as MolidoVpnApplication).vpnSessions.snapshot().state
        val status = TextView(this).apply {
            text = buildString {
                append("MolidoVPN AI\n\n")
                append("WireGuard engine: integrated\n")
                append("Session state: ")
                append(state.name)
                append("\n")
                append("VPN connection: not connected\n")
                append("A valid server profile and real device verification are still required.")
            }
            textSize = 18f
            setPadding(32, 48, 32, 32)
        }
        setContentView(status)
    }
}
