package ai.molido.vpn.engine.wireguard

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import ai.molido.vpn.core.EngineAdapter
import ai.molido.vpn.core.EngineSession
import ai.molido.vpn.core.EngineSlot
import ai.molido.vpn.core.EngineStartException
import ai.molido.vpn.core.EngineFailureAwareAdapter
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WireGuard userspace adapter backed by the upstream embeddable Android tunnel library.
 * GoBackend's manifest-merged GoBackend.VpnService is the one VPN service/TUN provider.
 * VpnSessionController is the sole application-level caller of this adapter.
 */
data class WireGuardRuntimeStatistics(
    val receivedBytes: Long,
    val transmittedBytes: Long,
    val latestHandshakeEpochMillis: Long
)

class WireGuardEngineAdapter(context: Context) : EngineAdapter, EngineFailureAwareAdapter {
    override val slot: EngineSlot = EngineSlot.WIREGUARD

    private val lock = Any()
    private val backend: GoBackend by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GoBackend(context.applicationContext)
    }
    private var activeTunnel: Tunnel? = null
    @Volatile private var failureListener: (() -> Unit)? = null

    override fun setFailureListener(listener: (() -> Unit)?) {
        failureListener = listener
    }

    /** Snapshot counters; these do not by themselves prove internet reachability. */
    fun runtimeStatistics(): WireGuardRuntimeStatistics? = synchronized(lock) {
        val tunnel = activeTunnel ?: return@synchronized null
        val statistics = backend.getStatistics(tunnel)
        val latestHandshake = statistics.peers()
            .mapNotNull { peer -> statistics.peer(peer)?.latestHandshakeEpochMillis() }
            .maxOrNull() ?: 0L
        WireGuardRuntimeStatistics(
            receivedBytes = statistics.totalRx(),
            transmittedBytes = statistics.totalTx(),
            latestHandshakeEpochMillis = latestHandshake
        )
    }

    override fun start(configuration: String): EngineSession = synchronized(lock) {
        check(activeTunnel == null) { "WireGuard session is already active" }
        require(configuration.isNotBlank()) { "WireGuard configuration is empty" }
        val encoded = configuration.toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= MAX_CONFIG_BYTES) { "WireGuard configuration exceeds size limit" }

        // Config text contains the private key. Wipe the temporary UTF-8 copy
        // immediately after parsing, including when parsing rejects the profile.
        val parsed = try {
            Config.parse(ByteArrayInputStream(encoded)).also {
                require(it.peers.isNotEmpty()) { "WireGuard configuration must contain at least one peer" }
            }
        } finally {
            encoded.fill(0)
        }

        // These flags cross the backend callback boundary, so use atomic visibility.
        val startupComplete = AtomicBoolean(false)
        val intentionalStop = AtomicBoolean(false)
        val tunnel = object : Tunnel {
            override fun getName(): String = TUNNEL_NAME
            override fun onStateChange(newState: Tunnel.State) {
                if (newState == Tunnel.State.DOWN &&
                    startupComplete.get() &&
                    !intentionalStop.get()
                ) {
                    failureListener?.invoke()
                }
            }
        }

        val session = object : EngineSession {
            private var stopped = false

            override fun stop() = synchronized(lock) {
                if (stopped) return@synchronized
                check(activeTunnel === tunnel) { "WireGuard session ownership mismatch" }
                intentionalStop.set(true)
                try {
                    val resultingState = backend.setState(tunnel, Tunnel.State.DOWN, null)
                    check(resultingState == Tunnel.State.DOWN && backend.getState(tunnel) == Tunnel.State.DOWN) {
                        "WireGuard backend did not enter DOWN state"
                    }
                    activeTunnel = null
                    stopped = true
                } catch (failure: Throwable) {
                    intentionalStop.set(false)
                    throw failure
                }
            }
        }

        // Reserve adapter-local ownership before native startup so failed cleanup can be retried.
        activeTunnel = tunnel
        try {
            val resultingState = backend.setState(tunnel, Tunnel.State.UP, parsed)
            check(resultingState == Tunnel.State.UP && backend.getState(tunnel) == Tunnel.State.UP) {
                "WireGuard backend did not enter UP state"
            }
            startupComplete.set(true)
        } catch (failure: Throwable) {
            var cleanupFailure: Throwable? = null
            try {
                val down = backend.setState(tunnel, Tunnel.State.DOWN, null)
                check(down == Tunnel.State.DOWN && backend.getState(tunnel) == Tunnel.State.DOWN) {
                    "WireGuard backend cleanup did not reach DOWN state"
                }
                activeTunnel = null
            } catch (cleanup: Throwable) {
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
