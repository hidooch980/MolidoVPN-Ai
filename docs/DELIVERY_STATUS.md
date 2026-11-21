# Delivery Status

Branch under active review: `feature/wireguard-runtime-diagnostics`
Base: `development/molido-vpn-ai-from-scratch`
Latest workflow correction: `169fa6ffd9deab0070c19f8ba61b5eb1e3f511ba`

## Release decision

**Stable Production: BLOCKED.** CI #220 passed on its own commit, but no full CI result has yet been confirmed for the latest corrected PR head, but this is not proof of real VPN data-plane operation. The central app-owned TUN service, descriptor handoff, custom WireGuard backend, physical-device acceptance, production signing verification, and external engine gates remain incomplete. No Stable APK is approved or distributed.

## Latest completed CI evidence — run #220 (not the latest PR head)

[Android CI #220](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/44940225549), commit `4019b4f2666d1b870bb9b397f3dc18be030cf333`, completed successfully:

- Android source validation: success (unit tests, lint, release-variant assembly).
- Backend tests: **13 passed**.
- Android Keystore instrumentation: **15 tests started, 15 finished, BUILD SUCCESSFUL** on API 35 x86_64 emulator with KVM.
- Signed-release job: **skipped**, as expected for a pull-request event.

Run #220 passed after the KVM gate was added in commit `4019b4f`. A subsequent documentation commit was mistakenly created from a stale base tree and reverted that workflow change. The KVM gate and diagnostics were restored in commit `169fa6f`; the restored workflow has not yet been verified by a completed CI run. Run #221 was still executing against the intermediate commit `4206558` when this status was checked. Therefore CI is NOT yet confirmed green on the latest PR head. It does not resolve the central TUN ownership architecture or establish real-device connectivity. Earlier run #217's 0/0 instrumentation process crash remains historical evidence; run #220 passed the current instrumentation suite.

Non-blocking warnings observed in CI:
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

The session supervisor and TUN coordinator are still separate state machines. Do not add a second VPN service or use reflection/private JNI hooks. Follow the reviewed migration decision: pin and verify official WireGuard source provenance/signature/submodules, build a narrow Apache-2.0-compliant local backend that accepts a central-service FD, then migrate manifest ownership and add authenticated versioned Binder/ParcelFileDescriptor IPC.

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
- No APK from CI #220 is a Stable Production deliverable.
- Do not rotate or replace an established signing identity without explicit authorization and backup.

## Current PR state

PR #4 remains **Draft / open / unmerged**. Keep it Draft until the central TUN migration, engine/legal gates, physical-device acceptance, signed artifact verification and all release checks pass.
