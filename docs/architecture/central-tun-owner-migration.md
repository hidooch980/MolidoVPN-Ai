# Central TUN owner migration — implementation gate

Status: **NOT IMPLEMENTED / BLOCKING**  
Decision date: 2026-11-20

## Required end state

MolidoVPN has exactly one Android `VpnService` implementation. It is the only component allowed to call `VpnService.Builder.establish()`. It owns the original `ParcelFileDescriptor` for the lifetime of a VPN session and serializes all engine leases through `TunOwnershipCoordinator`.

An engine process receives only a duplicated descriptor for the active lease. The handoff is bound to an unguessable session token and monotonically increasing generation. The receiving process must validate the calling UID, expected engine slot, generation, and one-time lease state before accepting the descriptor. No profile/private key is sent in a status or liveness message.

Shutdown ordering is mandatory:

1. Central service stops accepting packets/new engine work.
2. It sends a stop request for the exact engine slot + generation.
3. Engine stops its packet loop/native tunnel and closes its descriptor duplicate.
4. Engine returns a cleanup acknowledgement containing the same lease generation and its process identity.
5. Central service closes its original TUN descriptor and verifies closure.
6. Only then does the coordinator release the lease and permit another engine.

A process death is handled as a cleanup event, not a successful stop acknowledgement. The central service must close its own descriptor and confirm that the dead process can no longer retain its duplicate before releasing the lease. A timeout or missing acknowledgement retains the lease and blocks all other engines.

## Verified blocker in the current WireGuard dependency

The app currently depends on:

`com.wireguard.android:tunnel:1.0.20260102`

At that exact upstream tag, `GoBackend.setStateInternal(UP)` does all of the following internally:

- starts `GoBackend.VpnService` by its concrete nested class;
- calls `service.getBuilder()`;
- calls `builder.establish()` itself;
- detaches the resulting descriptor and passes it directly to private native `wgTurnOn(...)`;
- calls `service.protect(...)` for the WireGuard UDP sockets.

The AAR manifest contributes `com.wireguard.android.backend.GoBackend$VpnService` as a VPN service. The app adapter currently calls `GoBackend.setState`, so the app does **not** own the TUN descriptor and cannot safely hand it to another process.

The upstream API does not expose a supported injection point for an externally established TUN descriptor or a replaceable service class. Merely adding another app `VpnService`, declaring it as central, or sending an FD over Messenger would leave the old backend creating its own TUN and violate the single-owner invariant.

Source reviewed at the pinned dependency tag:
- `https://github.com/WireGuard/wireguard-android/blob/1.0.20260102/tunnel/src/main/java/com/wireguard/android/backend/GoBackend.java`
- `https://github.com/WireGuard/wireguard-android/blob/1.0.20260102/tunnel/src/main/AndroidManifest.xml`

## Approved implementation path

Do not use reflection to reach private native methods or mutate `GoBackend` internals. Do not run two VPN services during migration.

Before enabling the central service in the manifest:

1. Create and review a pinned, license-compliant WireGuard Android backend fork/adapter that preserves the upstream config parsing and userspace protocol behavior but accepts a TUN FD supplied by the central service.
2. Ensure the engine can protect its UDP sockets through the central service's `VpnService.protect(fd)` before those sockets are used.
3. Remove the dependency's manifest contribution for `GoBackend.VpnService` only after the fork no longer starts or depends on that concrete service. Verify the merged manifest contains exactly one `android.net.VpnService` component.
4. Register the central service with `android.permission.BIND_VPN_SERVICE`, `android:exported="false"`, and the `android.net.VpnService` intent filter.
5. Implement authenticated Binder IPC with `ParcelFileDescriptor` duplication; close every local/remote duplicate on all error paths. Do not pass a raw integer FD across processes.
6. Implement engine stop/cleanup acknowledgements and Binder death handling against `TunOwnershipCoordinator`.
7. Keep Xray, AmneziaWG, Psiphon, Tor, and all other engines non-selectable until their adapter passes the same contract.

## Validation gates

- Unit tests: simultaneous competing leases, stale generation, duplicate acquire, wrong PID/UID, missing acknowledgement, cleanup failure, process death, idempotent close.
- Instrumentation: actual Binder FD duplication, receiving-process FD validity, service-side close, Binder death, and no descriptor leaks.
- Merged manifest assertion: exactly one VPN service and it is the central service.
- Device acceptance: connect, route IPv4/IPv6, DNS and leak tests, verify actual data-plane traffic, stop, reconnect with another engine only after cleanup, and repeat under abrupt process termination.
- CI emulator success is not physical-device acceptance. No production readiness until the physical-device gates pass.

## Current code boundary

`TunOwnershipCoordinator` is a platform-neutral, fail-closed lease state machine with unit tests. It is not yet wired to Android `VpnService`, Binder, `ParcelFileDescriptor`, or the WireGuard backend. Its presence must not be displayed as real TUN ownership or production readiness.

Until the pinned WireGuard backend migration is complete, the existing WireGuard AAR remains the only VPN service/TUN provider and all isolated engine processes remain liveness-only scaffolding.
