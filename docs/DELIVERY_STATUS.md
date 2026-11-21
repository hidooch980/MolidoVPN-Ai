# Delivery Status

Branch under active review: `feature/wireguard-runtime-diagnostics`
Base: `development/molido-vpn-ai-from-scratch`
Latest branch head at this status update: `0affc855ae78717a93757e6dff3098b9d1d4afcb`

## Release decision

**Stable Production: BLOCKED.** No Stable APK is approved or distributed. The central app-owned TUN service, descriptor handoff, custom WireGuard backend, physical-device acceptance, production signing verification, and remaining engine/legal gates are incomplete.

## Latest CI evidence

- [Run #44945055377](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/44945055377), commit `3e4611db278cf0a52b2c3c075a1e1e5d2e291e60`: Android source validation, backend tests, and Android Keystore emulator instrumentation passed. The emulator log reports **16 tests started, 16 finished, BUILD SUCCESSFUL**. Signed release was skipped for the pull-request event.
- [Run #44945404147](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/44945404147), commit `afc1f61623bea79c6ef81138d0e5fc51ad6d71c0`: completed successfully on the same Android/backend/emulator validation workflow.
- [Run #44945698971](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/44945698971), commit `c2e3abae677cb1390051cc654e13945f21d97747`: Android source validation, backend tests, and all 16 emulator tests passed. The newly added WireGuard source-build job failed during Android SDK package setup because the action received a newline-separated package list as one invalid package argument; no upstream build was attempted.
- Workflow commit `93036fe118d6d9f7a23d00e8bee66aa2410f03a9` corrected the SDK package list to the action's space-separated format. [Run #44945829405](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/44945829405) is running on that correction. Its source-build job has passed SDK/JDK/Go setup and is executing the pinned upstream Go-only build; final result is not yet known.
- The instrumentation manifest test asserts exactly one service has `BIND_VPN_SERVICE`, currently upstream `GoBackend$VpnService`. This is a regression guard only; it does not prove Molido owns TUN centrally.
- The CI workflow now retries only recognizable transient Gradle repository/dependency resolution failures. Compilation, assertion, installation, and instrumentation failures remain hard failures.

CI is **not yet confirmed green on the current branch head**, and the new source-build gate has not yet passed.

## Recent architecture and source review

- `SINGLE_TUN_OWNER_MIGRATION.md` now accurately records that `SessionSupervisor` reserves the shared generation-scoped lease and passes it to WireGuard; the adapter validates it and does not independently acquire a second lease. Session and lease remain separate state ledgers, and Android VPN service ownership remains upstream.
- The exact current AAR source tag `1.0.20260102` was inspected: annotated tag object `3831cab2da844319291459308a6e535d36dde4b3` is reported by GitHub as PGP-signature verified and peels to `09b75c2bd37f749e2a8c85876394854113c74be7`. The peeled commit is not independently signed.
- The source review confirms the current backend creates TUN with `VpnService.Builder.establish()`, detaches the descriptor into private native `wgTurnOn(..., int tunFd, ...)`, and protects its IPv4/IPv6 sockets through its own service. No supported public AAR API for injecting an externally owned TUN FD was found.
- The source tree also pins `wireguard-tools` (GPL-2) and `termux-elf-cleaner` (GPL-3.0-or-later). A Go-only build may avoid packaging these unused command-line tools; this must be proven by the new source-build job and an APK/AAR native-library inventory/license scan. Do not describe the complete native bundle as Apache-2.0-only.
- The new `scripts/ci/build-wireguard-go-only.sh` checks the exact upstream tag object and peeled commit, builds only `libwg-go.so` without initializing the GPL submodules, and rejects AAR output containing `libwg.so` or `libwg-quick.so`. This is a build smoke test, not the production backend migration.

Non-blocking warnings previously observed in CI:
- Deprecated Android system UI APIs in `MainActivity.kt`.
- Unchecked `SpinnerAdapter` to `ArrayAdapter<String>` cast.
- Native WireGuard libraries could not be stripped and were packaged unchanged in the earlier AAR-based build.
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
