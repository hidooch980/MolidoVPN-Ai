# Delivery Status

Branch under active review: `feature/wireguard-runtime-diagnostics`
Base: `development/molido-vpn-ai-from-scratch`
Latest branch head at this status update: `0affc855ae78717a93757e6dff3098b9d1d4afcb`

## Release decision

**Stable Production: BLOCKED.** No Stable APK is approved or distributed. The central app-owned TUN service, descriptor handoff, custom WireGuard backend, physical-device acceptance, production signing verification, and remaining engine/legal gates are incomplete.

## Latest CI evidence

- [Run #44942055091](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/44942055091), commit `6f0b0cf20ed23bc2ed8fb69ff45991b18285bd6a`: Android source validation, backend tests, and Android Keystore emulator instrumentation all passed. Signed release was skipped for the pull-request event.
- [Run #44944204445](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/44944204445), commit `4fb11089a4cb6a78c01a15d4e0c484672f3ace5`: completed successfully on the first version of the manifest instrumentation test; the next commit only removed an unused import.
- [Run #44944220579](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/44944220579), commit `f0dfd741a0186ba1be0c3bc356f3b4a217772865`: backend and Android source validation passed, but emulator instrumentation failed before tests ran because Gradle could not resolve `org.jetbrains.kotlin:kotlin-reflect:2.0.20` and `org.jetbrains:annotations:13.0` from the configured repositories. This is a dependency-resolution failure, not evidence that the new manifest assertion failed.
- Workflow commit `3e4611db278cf0a52b2c3c075a1e1e5d2e291e60` adds bounded retries **only** for recognizable repository/dependency transport failures. Compilation, assertion, install, and instrumentation failures remain hard failures.
- Run [#44945055377](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/44945055377) was executing on that workflow commit when this status was updated. Run [#44945289340](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/44945289340) was queued for current head `0affc855ae78717a93757e6dff3098b9d1d4afcb`. **CI is not yet confirmed green on the current branch head.**

The added `SingleVpnServiceManifestTest` inspects the installed merged APK manifest and asserts exactly one service has `BIND_VPN_SERVICE`, currently the upstream `GoBackend$VpnService`. This is a regression guard only; it does not prove Molido owns TUN centrally.

## Recent architecture/provenance corrections

- `SINGLE_TUN_OWNER_MIGRATION.md` now accurately records that `SessionSupervisor` reserves the shared generation-scoped lease and passes it to WireGuard; the adapter validates it and does not independently acquire a second lease. Session and lease remain separate state ledgers, and the Android VPN service is still upstream-owned.
- Official WireGuard tag `1.0.20260315` was inspected: annotated tag object `31ba2dbd3ac793f2e2bb386310d837bdc0cd0bfd` is reported by GitHub as PGP-signature verified and peels to `0856e2ecf7f3cce0e9c9014db3ad7665669adce5`. The peeled commit is not independently signed. This is newer than the currently consumed AAR `1.0.20260102`, so it is a candidate only, not an approved drop-in.
- The upstream source review confirms the current backend calls `VpnService.Builder.establish()`, detaches the FD into private native `wgTurnOn(..., int tunFd, ...)`, and protects the resulting IPv4/IPv6 sockets through its own service. A supported public AAR API for injecting an externally owned TUN FD was not found.
- The upstream source tree pins `wireguard-tools` (GPL-2) and `termux-elf-cleaner` (GPL-3.0-or-later). The latter is a build-time tool; the former is compiled into native outputs. The complete tunnel/native build must not be described as Apache-2.0-only. Legal/distribution compatibility review is required before integrating or redistributing a source-built backend.

Non-blocking warnings previously observed in CI:
- Deprecated Android system UI APIs in `MainActivity.kt`.
- Unchecked `SpinnerAdapter` to `ArrayAdapter<String>` cast.
- Native WireGuard libraries could not be stripped and were packaged unchanged.
- GitHub Actions warns that several configured actions target Node.js 20 while the runner is transitioning to Node.js 24.

## Product decisions

- No human end-user accounts, registration, login, or password-reset flows.
- Machine/device authentication remains separate; admin secrets never belong in the APK.
- Local-first dashboard; unknown values remain unknown. No fabricated public IP, country, ping, speed, handshake, server or provider state.
- Exactly six engine slots and eight complementary cores; no seventh engine or ninth core.
- GitHub Actions is CI, not a permanent VPN server, relay, or backend runtime.
- No paid infrastructure or external service without explicit owner approval.

## Eight-core status

| Core | Current status | Remaining acceptance |
|---|---|---|
| Session & TUN Supervisor | Source Implemented; CI Tested | Replace split session/lease coordination with one authoritative service-owned TUN lifecycle; real descriptor handoff and cleanup acknowledgement |
| Network Intelligence | Source Implemented | Physical Android callback, stale/lost network, Wi-Fi/mobile transition and route behavior |
| Adaptive Path & Policy | Partially Source Implemented | Validated measured path selection; no real Smart/automatic optimization evidence |
| Security & Privacy | Partially Source Implemented | Full threat-model review, secret lifecycle, IPC authentication, dependency/SBOM/license and leak validation |
| Resilience & Recovery | Partially Source Implemented | Process-death/Binder-death recovery, safe retry scheduler, crash and network-switch tests |
| Diagnostics & Observability | Partially Source Implemented | Device evidence, redaction review, actionable diagnostics; telemetry remains disabled by default |
| Control Plane & Configuration | Partially Source Implemented; Server Deployment Required | Deployment source, operational backup/restore, authentication/key operations and real integration; no live server is asserted |
| Update & Integrity | Partially Source Implemented | Download transport, signed manifest/key lifecycle, physical PackageInstaller install/rollback and downgrade/replay tests |

## Six-engine matrix

| Engine | Current status | Remaining gate |
|---|---|---|
| WireGuard | Source Implemented; CI Tested | Migrate from AAR-owned GoBackend.VpnService to reviewed source backend accepting central TUN FD; then device/data-plane validation |
| AmneziaWG | Not Implemented | Official source pin, recursive submodules, reproducible native build, adapter and shared TUN tests |
| V2Ray/Xray | Not Implemented | Pinned official libXray/API v3, isolated Go runtime adapter, IPC/FD lifecycle and traffic tests |
| Psiphon | Blocked | GPL-3.0-compliant distribution decision or written commercial permission, then official library integration |
| Tor | Not Implemented | Official daemon, SOCKS lifecycle, audited TUN-to-SOCKS bridge, DNS/UDP limitations and device tests |
| MASQUE/WARP-compatible | Blocked | Supported public Android embedding/API and terms must be established; no private endpoint reverse engineering |

Only WireGuard is registered in the runtime capability catalog. A registered source adapter is not a verified working VPN. No provider-supplied country inventory is available, so country selection remains unavailable.

## TUN ownership — critical blocker

The app manifest still receives `GoBackend.VpnService` from `com.wireguard.android:tunnel:1.0.20260102`. The app does not yet declare its own central `MolidoVpnService`. The current `TunOwnershipCoordinator` is an in-process lease/state coordinator, not an Android VPN service or FD broker. `NativeEngineProcessService` is status-only process-isolation scaffolding: it does not receive an FD, start a native engine, or acknowledge descriptor closure.

The session supervisor and TUN coordinator are separate state machines but share one coordinator instance; the supervisor reserves the lease and WireGuard validates the exact token. The coordinator is not an Android VPN service or FD broker. Do not add a second VPN service or use reflection/private JNI hooks. Follow the reviewed migration decision: finish the pinned-source, submodule and license review; resolve distribution compatibility; then build a source-based backend that accepts a central-service FD and explicit socket-protection callback, migrate manifest ownership, and add authenticated versioned Binder/ParcelFileDescriptor IPC.

## Physical device and network acceptance

Not yet verified:
- Install/permission/foreground-service behavior on a physical Android phone.
- Authorized WireGuard endpoint handshake and bidirectional traffic.
- IPv4/IPv6 routes, DNS correctness, DNS/IP leaks and fail-closed behavior.
- Wi-Fi/mobile handover, captive portal, airplane mode, network loss and recovery.
- Repeated connect/disconnect, process death, cleanup and descriptor leak behavior.
- Battery, thermal, memory and long-duration stability.

No VPN server/VPS is assumed or requested as a current development dependency. Real endpoint acceptance is a later external prerequisite and must use an endpoint the owner is authorized to access.

## Signing and release

- PR CI release assembly is unsigned/CI validation only; signed-release job is skipped on pull requests.
- Permanent owner-controlled signing identity and matching APK certificate fingerprint have not been verified by a successful signed-release workflow on the approved base branch.
- No APK from any pull-request CI run is a Stable Production deliverable.
- Do not rotate or replace an established signing identity without explicit authorization and backup.

## Current PR state

PR #4 remains **Draft / open / unmerged**. Keep it Draft until the central TUN migration, engine/legal gates, physical-device acceptance, signed artifact verification and all release checks pass.
