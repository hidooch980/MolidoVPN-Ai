package ai.molido.vpn.engine.wireguard

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import ai.molido.vpn.core.TunLeaseAwareEngineAdapter
import ai.molido.vpn.core.EngineSession
import ai.molido.vpn.core.EngineSlot
import ai.molido.vpn.core.EngineStartException
import ai.molido.vpn.core.EngineFailureAwareAdapter
import ai.molido.vpn.core.TunLeaseToken
import ai.molido.vpn.core.TunOwnershipCoordinator
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WireGuard userspace adapter backed by the upstream embeddable Android tunnel library.
 * GoBackend.VpnService is the sole Android VPN service/TUN provider. The shared
 * TunOwnershipCoordinator arbitrates every engine before this backend is allowed to start.
 */
data class WireGuardRuntimeStatistics(
    val receivedBytes: Long,
    val transmittedBytes: Long,
    val latestHandshakeEpochMillis: Long
)

class WireGuardEngineAdapter(
    context: Context,
    private val tunOwnership: TunOwnershipCoordinator
) : TunLeaseAwareEngineAdapter, EngineFailureAwareAdapter {
    override val slot: EngineSlot = EngineSlot.WIREGUARD

    private val lock = Any()
    private val backend: GoBackend by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GoBackend(context.applicationContext)
    }
    private var activeTunnel: Tunnel? = null
    private var activeLease: TunLeaseToken? = null
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

    override fun start(configuration: String, lease: TunLeaseToken): EngineSession = synchronized(lock) {
        check(activeTunnel == null && activeLease == null) { "WireGuard session is already active" }
        require(configuration.isNotBlank()) { "WireGuard configuration is empty" }
        val encoded = configuration.toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= MAX_CONFIG_BYTES) { "WireGuard configuration exceeds size limit" }

        val parsed = try {
            Config.parse(ByteArrayInputStream(encoded)).also {
                require(it.peers.isNotEmpty()) { "WireGuard configuration must contain at least one peer" }
            }
        } catch (failure: Throwable) {
            throw EngineStartException(
                message = "WireGuard configuration rejected before backend startup",
                cleanupSession = null,
                cause = failure,
                cleanupConfirmed = true
            )
        } finally {
            encoded.fill(0)
        }

        check(lease.engine == slot && lease.transport == ai.molido.vpn.core.TunLeaseTransport.BACKEND_MANAGED &&
            tunOwnership.isReserved(lease)
        ) { "WireGuard received an invalid or stale shared TUN lease" }

        val intentionalStop = AtomicBoolean(false)
        val startupSignal = EngineStartupFailureSignal { failureListener?.invoke() }
        val tunnel = object : Tunnel {
            override fun getName(): String = TUNNEL_NAME
            override fun onStateChange(newState: Tunnel.State) {
                if (newState == Tunnel.State.DOWN) {
                    startupSignal.onDown(intentionalStop.get())
                }
            }
        }

        val session = object : EngineSession {
            private var stopped = false

            override fun stop() = synchronized(lock) {
                if (stopped) return@synchronized
                check(activeTunnel === tunnel && activeLease == lease) {
                    "WireGuard session ownership mismatch"
                }
                check(tunOwnership.beginStop(lease)) {
                    "Shared TUN coordinator refused WireGuard stop transition"
                }
                intentionalStop.set(true)
                try {
                    val resultingState = backend.setState(tunnel, Tunnel.State.DOWN, null)
                    check(resultingState == Tunnel.State.DOWN &&
                        backend.getState(tunnel) == Tunnel.State.DOWN
                    ) { "WireGuard backend did not confirm DOWN state" }
                    check(tunOwnership.confirmBackendStopped(lease)) {
                        "Shared TUN coordinator refused backend cleanup confirmation"
                    }
                    activeTunnel = null
                    activeLease = null
                    stopped = true
                } catch (failure: Throwable) {
                    intentionalStop.set(false)
                    throw failure
                }
            }
        }

        activeTunnel = tunnel
        activeLease = lease
        try {
            val resultingState = backend.setState(tunnel, Tunnel.State.UP, parsed)
            check(resultingState == Tunnel.State.UP &&
                backend.getState(tunnel) == Tunnel.State.UP
            ) { "WireGuard backend did not confirm UP state" }
            check(tunOwnership.markBackendActive(lease)) {
                "Shared TUN coordinator refused WireGuard activation"
            }
            startupSignal.markStartupComplete()
        } catch (failure: Throwable) {
            var cleanupFailure: Throwable? = null
            try {
                session.stop()
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
            throw EngineStartException(
                message = "WireGuard startup failed; backend cleanup was confirmed",
                cleanupSession = null,
                cause = failure,
                cleanupConfirmed = true
            )
        }

        session
    }

    private companion object {
        const val TUNNEL_NAME = "molido"
        const val MAX_CONFIG_BYTES = 64 * 1024
    }
}
