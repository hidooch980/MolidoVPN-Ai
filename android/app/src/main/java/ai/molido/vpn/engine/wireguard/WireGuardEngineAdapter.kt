package ai.molido.vpn.engine.wireguard

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import ai.molido.vpn.core.EngineAdapter
import ai.molido.vpn.core.EngineSession
import ai.molido.vpn.core.EngineSlot
import ai.molido.vpn.core.EngineStartException
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * WireGuard userspace adapter backed by the upstream embeddable Android tunnel library.
 * GoBackend's manifest-merged GoBackend.VpnService is the one VPN service/TUN provider.
 * VpnSessionController is the sole application-level caller of this adapter.
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
            override fun getName(): String = TUNNEL_NAME
            override fun onStateChange(newState: Tunnel.State) = Unit
        }

        val session = object : EngineSession {
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

        // Reserve adapter-local ownership before native startup so cleanup can be retried.
        activeTunnel = tunnel
        try {
            val resultingState = backend.setState(tunnel, Tunnel.State.UP, parsed)
            check(resultingState == Tunnel.State.UP && backend.getState(tunnel) == Tunnel.State.UP) {
                "WireGuard backend did not enter UP state"
            }
        } catch (failure: Exception) {
            var cleanupFailure: Exception? = null
            try {
                val down = backend.setState(tunnel, Tunnel.State.DOWN, null)
                check(down == Tunnel.State.DOWN && backend.getState(tunnel) == Tunnel.State.DOWN) {
                    "WireGuard backend cleanup did not reach DOWN state"
                }
                activeTunnel = null
            } catch (cleanup: Exception) {
                cleanupFailure = cleanup
            }
            if (cleanupFailure != null) {
                val wrapped = EngineStartException(
                    message = "WireGuard startup failed and cleanup remains pending",
                    cleanupSession = session,
                    cause = failure
                )
                wrapped.addSuppressed(cleanupFailure)
                throw wrapped
            }
            throw failure
        }

        session
    }

    private companion object {
        const val TUNNEL_NAME = "molido"
        const val MAX_CONFIG_BYTES = 64 * 1024
    }
}
