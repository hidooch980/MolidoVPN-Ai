package ai.molido.vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import ai.molido.vpn.core.NetworkAssessmentStatus
import ai.molido.vpn.core.SessionStatusText
import ai.molido.vpn.core.SessionState
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Minimal functional client shell.
 * Profiles remain in memory only; secrets are neither logged nor persisted by this screen.
 */
class MainActivity : ComponentActivity() {
    private lateinit var statusView: TextView
    private lateinit var profileView: TextView
    private lateinit var importButton: Button
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private var profileText: String? = null
    private var connectAfterConsent = false
    private val molidoApp get() = application as MolidoVpnApplication

    private val profilePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                try {
                    profileText = contentResolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_PROFILE_BYTES) { "Profile exceeds size limit" }
                            output.write(buffer, 0, count)
                        }
                        output.toString(StandardCharsets.UTF_8.name())
                    } ?: error("Unable to read selected profile")
                    require(!profileText.isNullOrBlank()) { "Selected profile is empty" }
                    profileView.text = "Profile loaded in memory (not saved)"
                    statusView.text = "Profile loaded. Connection requires Android VPN permission and a reachable WireGuard server."
                    connectButton.isEnabled = true
                } catch (_: Exception) {
                    profileText = null
                    profileView.text = "No profile loaded"
                    statusView.text = "Could not load this profile. Check that it is a valid text configuration under 64 KiB."
                    connectButton.isEnabled = false
                }
            }
        }
    }

    private val vpnConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (connectAfterConsent && result.resultCode == Activity.RESULT_OK) {
            connectAfterConsent = false
            connectUsingLoadedProfile()
        } else {
            connectAfterConsent = false
            statusView.text = "VPN permission was not granted. No tunnel was started."
            setControlsEnabled(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        renderState()
    }

    override fun onStart() {
        super.onStart()
        molidoApp.onNetworkAssessmentChanged = { runOnUiThread { renderState() } }
        renderState()
    }

    override fun onStop() {
        molidoApp.onNetworkAssessmentChanged = null
        super.onStop()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 56, 40, 40)
        }
        val title = TextView(this).apply {
            text = "MolidoVPN AI"
            textSize = 26f
        }
        statusView = TextView(this).apply {
            textSize = 16f
            setPadding(0, 32, 0, 24)
        }
        profileView = TextView(this).apply {
            text = "No profile loaded"
            textSize = 14f
            setPadding(0, 0, 0, 24)
        }
        importButton = Button(this).apply {
            text = "Import WireGuard profile"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(
                        Intent.EXTRA_MIME_TYPES,
                        arrayOf("text/plain", "application/octet-stream", "application/x-wireguard-config")
                    )
                }
                profilePicker.launch(intent)
            }
        }
        connectButton = Button(this).apply {
            text = "Connect"
            isEnabled = false
            setOnClickListener { requestVpnConsentAndConnect() }
        }
        disconnectButton = Button(this).apply {
            text = "Disconnect"
            setOnClickListener { disconnectTunnel() }
        }

        root.addView(title)
        root.addView(statusView)
        root.addView(profileView)
        root.addView(importButton)
        root.addView(connectButton)
        root.addView(disconnectButton)
        setContentView(root)
    }

    private fun requestVpnConsentAndConnect() {
        if (profileText.isNullOrBlank()) {
            statusView.text = "Import a WireGuard profile first."
            return
        }
        val consentIntent = VpnService.prepare(this)
        if (consentIntent == null) {
            connectUsingLoadedProfile()
        } else {
            connectAfterConsent = true
            setControlsEnabled(false)
            statusView.text = "Waiting for Android VPN permission…"
            vpnConsent.launch(consentIntent)
        }
    }

    private fun connectUsingLoadedProfile() {
        val configuration = profileText ?: return
        setControlsEnabled(false)
        statusView.text = "Starting WireGuard backend…"
        molidoApp.vpnExecutor.execute {
            try {
                val started = molidoApp.vpnSessions.connect(
                    ai.molido.vpn.core.EngineSlot.WIREGUARD, configuration
                )
                runOnUiThread {
                    setControlsEnabled(true)
                    val detail = if (started) {
                        "WireGuard backend is active. Peer handshake, internet reachability, DNS and leak protection still require real-device verification."
                    } else {
                        molidoApp.vpnSessions.latestPathDecision()?.reason
                            ?: "The session could not be started. Check the selected engine and current session state."
                    }
                    renderState(detail)
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    setControlsEnabled(true)
                    renderState("WireGuard startup failed. If cleanup is pending, the session remains reserved until disconnect succeeds.")
                }
            }
        }
    }

    private fun disconnectTunnel() {
        setControlsEnabled(false)
        statusView.text = "Stopping VPN session…"
        molidoApp.vpnExecutor.execute {
            try {
                val stopped = molidoApp.vpnSessions.disconnect()
                runOnUiThread {
                    setControlsEnabled(true)
                    val detail = if (stopped) "VPN session stopped."
                    else "Session cleanup is not complete. Ownership remains reserved."
                    renderState(detail)
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    setControlsEnabled(true)
                    renderState("Disconnect failed. Session ownership is retained to prevent a second TUN from starting.")
                }
            }
        }
    }

    private fun renderState(detail: String? = null) {
        val snapshot = molidoApp.vpnSessions.snapshot()
        val network = molidoApp.latestNetworkAssessment()
        val networkText = when (network.status) {
            NetworkAssessmentStatus.VALIDATED -> "Network: validated (${network.transport ?: "unknown transport"})"
            NetworkAssessmentStatus.NOT_VALIDATED -> "Network: not validated by Android"
            NetworkAssessmentStatus.STALE -> "Network: measurement is stale"
            NetworkAssessmentStatus.UNKNOWN -> "Network: measurements unavailable"
        }
        statusView.text = SessionStatusText.render(snapshot, detail) + "\n" + networkText
        disconnectButton.visibility =
            if (snapshot.state == SessionState.IDLE) View.GONE else View.VISIBLE
    }

    private fun setControlsEnabled(enabled: Boolean) {
        importButton.isEnabled = enabled
        connectButton.isEnabled = enabled && !profileText.isNullOrBlank()
        disconnectButton.isEnabled = enabled
    }

    private companion object {
        const val MAX_PROFILE_BYTES = 64 * 1024
    }
}
