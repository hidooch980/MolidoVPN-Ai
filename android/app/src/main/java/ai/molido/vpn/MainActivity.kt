package ai.molido.vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ai.molido.vpn.core.EngineSlot
import ai.molido.vpn.core.SessionState
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Minimal profile-import and connection surface.
 * WireGuard profiles are read from the Android document provider into memory only.
 */
class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var profileConfiguration: String? = null
    private var pendingConnectAfterAuthorization = false

    private lateinit var statusView: TextView
    private lateinit var importButton: Button
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button

    private val sessions
        get() = (application as MolidoVpnApplication).vpnSessions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        renderStatus()
    }

    private fun buildUi() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        statusView = TextView(this).apply {
            textSize = 17f
            text = "MolidoVPN AI"
        }
        importButton = Button(this).apply {
            text = "Import WireGuard profile"
            setOnClickListener { openProfilePicker() }
        }
        connectButton = Button(this).apply {
            text = "Connect"
            setOnClickListener { requestVpnAuthorizationAndConnect() }
        }
        disconnectButton = Button(this).apply {
            text = "Disconnect"
            setOnClickListener { disconnect() }
        }

        layout.addView(statusView)
        layout.addView(importButton)
        layout.addView(connectButton)
        layout.addView(disconnectButton)
        setContentView(layout)
    }

    private fun openProfilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_PROFILE)
    }

    private fun requestVpnAuthorizationAndConnect() {
        if (profileConfiguration == null) {
            statusView.text = "Import a WireGuard .conf profile first."
            return
        }
        val authorizationIntent = VpnService.prepare(this)
        if (authorizationIntent != null) {
            pendingConnectAfterAuthorization = true
            startActivityForResult(authorizationIntent, REQUEST_VPN_AUTHORIZATION)
        } else {
            startConnect()
        }
    }

    @Deprecated("Activity result is retained for compatibility with the minimal Activity shell")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_PROFILE -> {
                if (resultCode != RESULT_OK || data?.data == null) return
                try {
                    val loaded = contentResolver.openInputStream(data.data!!)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_PROFILE_BYTES) { "Profile exceeds the 64 KiB limit" }
                            output.write(buffer, 0, count)
                        }
                        String(output.toByteArray(), StandardCharsets.UTF_8)
                    } ?: error("Unable to open selected profile")
                    require(loaded.isNotBlank()) { "Selected profile is empty" }
                    profileConfiguration = loaded
                    statusView.text = "WireGuard profile loaded in memory. No profile content is written to app storage."
                } catch (_: Exception) {
                    profileConfiguration = null
                    statusView.text = "Profile could not be read. Check that it is a valid, readable WireGuard configuration."
                }
            }
            REQUEST_VPN_AUTHORIZATION -> {
                val shouldConnect = pendingConnectAfterAuthorization
                pendingConnectAfterAuthorization = false
                if (resultCode == RESULT_OK && shouldConnect) startConnect()
                else statusView.text = "VPN permission was not granted. No tunnel was started."
            }
        }
    }

    private fun startConnect() {
        val configuration = profileConfiguration ?: return
        setBusy(true)
        statusView.text = "Starting WireGuard tunnel…"
        worker.execute {
            try {
                val started = sessions.connect(EngineSlot.WIREGUARD, configuration)
                runOnUiThread {
                    if (started) profileConfiguration = null
                    setBusy(false)
                    statusView.text = if (started) {
                        "WireGuard tunnel interface started. Peer handshake, internet access, DNS and leak behavior are not yet device-verified."
                    } else {
                        "A different session currently owns the VPN slot. No second tunnel was started."
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    setBusy(false)
                    statusView.text = "WireGuard startup failed. The session supervisor retained or safely released ownership according to cleanup evidence."
                }
            }
        }
    }

    private fun disconnect() {
        setBusy(true)
        worker.execute {
            try {
                val stopped = sessions.disconnect()
                runOnUiThread {
                    setBusy(false)
                    statusView.text = if (stopped) {
                        "VPN session stopped and ownership released."
                    } else {
                        "VPN session could not be safely released. Another engine remains blocked."
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    setBusy(false)
                    statusView.text = "Cleanup is incomplete. The active engine remains reserved; retry disconnect after the error is resolved."
                }
            }
        }
    }

    private fun renderStatus() {
        val state = sessions.snapshot().state
        statusView.text = when (state) {
            SessionState.IDLE -> "MolidoVPN AI\nNo active VPN session. Import a WireGuard profile to begin."
            SessionState.RESERVED -> "VPN session is reserved."
            SessionState.RUNNING -> "WireGuard tunnel interface is running. Peer handshake and traffic are not verified."
            SessionState.STOPPING -> "VPN session is stopping."
            SessionState.FAILED -> "VPN session failed. Ownership is retained until cleanup completes."
        }
    }

    private fun setBusy(busy: Boolean) {
        importButton.isEnabled = !busy
        connectButton.isEnabled = !busy
        disconnectButton.isEnabled = !busy
    }

    override fun onDestroy() {
        super.onDestroy()
        // Do not stop a user-requested VPN merely because the UI Activity is recreated.
        // The Application-scoped controller and upstream VpnService own the live session.
    }

    private companion object {
        const val REQUEST_PROFILE = 4101
        const val REQUEST_VPN_AUTHORIZATION = 4102
        const val MAX_PROFILE_BYTES = 64 * 1024
    }
}
