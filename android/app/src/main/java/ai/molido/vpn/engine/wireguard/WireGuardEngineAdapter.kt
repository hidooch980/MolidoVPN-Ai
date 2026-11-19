package ai.molido.vpn.engine.wireguard

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import ai.molido.vpn.core.EngineAdapter
import ai.molido.vpn.core.EngineSession
import ai.molido.vpn.core.EngineSlot
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * WireGuard userspace adapter backed by the upstream embeddable Android tunnel library.
 *
 * GoBackend's manifest-merged GoBackend.VpnService is the one VPN service/TUN provider
 * for this initial data plane. The app-level VpnSessionController is the sole caller of
 * this adapter. Do not add another VpnService or call Builder.establish() elsewhere.
 */
class WireGuardEngineAdapter(context: Context) : EngineAdapter {
    override val slot: EngineSlot = EngineSlot.WIREGUARD

    private val lock = Any()
    private val backend = GoBackend(context.applicationContext)
    private var activeTunnel: Tunnel? = null

    override fun start(configuration: String): EngineSession = synchronized(lock) {
        check(activeTunnel == null) { "WireGuard session is already active" }
        require(configuration.isNotBlank()) { "WireGuard configuration is empty" }
        val encoded = configuration.toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= MAX_CONFIG_BYTES) { "WireGuard configuration exceeds size limit" }

        val parsed = Config.parse(ByteArrayInputStream(encoded))
        require(parsed.peers.isNotEmpty()) { "WireGuard configuration must contain at least one peer" }

        val tunnel = object : Tunnel {
            @Volatile private var state: Tunnel.State = Tunnel.State.DOWN

            override fun getName(): String = TUNNEL_NAME

            override fun onStateChange(newState: Tunnel.State) {
                state = newState
            }

            fun currentState(): Tunnel.State = state
        }

        try {
            val resultingState = backend.setState(tunnel, Tunnel.State.UP, parsed)
            check(resultingState == Tunnel.State.UP && backend.getState(tunnel) == Tunnel.State.UP) {
                "WireGuard backend did not enter UP state"
            }
            activeTunnel = tunnel
        } catch (failure: Exception) {
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
            } catch (cleanupFailure: Exception) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }

        object : EngineSession {
            private var stopped = false

            override fun stop() = synchronized(lock) {
                if (stopped) return@synchronized
                check(activeTunnel === tunnel) { "WireGuard session ownership mismatch" }
                val resultingState = backend.setState(tunnel, Tunnel.State.DOWN, null)
                check(resultingState == Tunnel.State.DOWN && backend.getState(tunnel) == Tunnel.State.DOWN) {
                    "WireGuard backend did not enter DOWN state"
                }
                activeTunnel = null
                stopped = true
            }
        }
    }

    private companion object {
        const val TUNNEL_NAME = "molido"
        const val MAX_CONFIG_BYTES = 64 * 1024
    }
}
