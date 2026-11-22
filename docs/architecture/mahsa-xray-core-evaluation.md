# MahsaNG / GFW-knocker Xray-core — technical evaluation for MolidoVPN

Status: **EVALUATED CANDIDATE — NOT INTEGRATED, NOT SELECTABLE**
Review date: 2026-11-22
Target: MolidoVPN Android, existing Xray/V2Ray engine slot only

## Decision summary

Reuse only the technically relevant, auditable Xray-core ideas as a candidate for the existing Xray engine slot. Do not import MahsaNG as an application, do not add a seventh engine, do not add a second Android VpnService, and do not add a runtime dependency until the pinned source builds reproducibly and passes license, security, and device gates.

The highest-value findings are:

1. An Android TUN-FD input path in the fork's Go TUN implementation.
2. A MASQUE outbound implementation (HTTP/3 + QUIC datagrams, with an HTTP/2 fallback using DATAGRAM capsules).
3. A reusable WireGuard URI/profile parser pattern and additional profile fields, which can inform parser tests only.
4. Explicit MTU, DNS, keepalive, certificate/public-key pinning, and tunnel-chaining considerations.

This is an evaluation record, not a claim that any feature works in MolidoVPN.

## Upstream source pin and provenance

Repository: https://github.com/GFW-knocker/Xray-core

- Reviewed default-branch commit: `6310f4a2220d09282a9fafd115e4d5d33d527a58` (2026-10-30).
- Reviewed `proxy/tun/tun_android.go` blob: `6887fd88ddc72cb58e08f64690388f607ee16b8c`.
- Reviewed `proxy/masque/config.proto` blob: `430f9bb0cd4e4ffb5db49289631b9242dedd50af`.
- Reviewed `proxy/masque/config.go` blob: `6c335e053da72dcb9ab5797672f6a50a6efd0f64`.
- Root license file identifies Mozilla Public License 2.0 (MPL-2.0).
- The branch is moving; the commit above is the review baseline. Do not build from `main` or a floating tag. Re-fetch and verify this exact commit before any vendoring/build, then review later upstream commits separately.

Related repositories:
- https://github.com/GFW-knocker/MahsaNG
- https://github.com/GFW-knocker/AndroidLibXrayLite

MahsaNG itself is not the integration target. Its Android app contains its own VPN service(s), app-specific configuration and service orchestration. Copying those app services would conflict with Molido's single-TUN-owner requirement. Its release APK was not installed or dynamically tested as part of this review.

## Finding 0 — AndroidLibXrayLite startup and embedding boundary

Reviewed repository: https://github.com/GFW-knocker/AndroidLibXrayLite

- Reviewed branch: `main2`; reviewed commit: `48f35be868ee247e53a8036e284a8c2f97c80068`.
- Reviewed files: `README.md`, `libv2ray_main.go`, `libv2ray_support.go`, `go.mod`, `.github/workflows/main.yml`, and `gen_assets.sh`.
- The repository is a Go `gomobile bind` wrapper that produces an Android AAR. Its `go.mod` pins Xray as `github.com/GFW-knocker/Xray-core v1.26.10-mahsa-r2` and declares Go `1.26.7`. The wrapper's root license is LGPL-3.0; its complete dependency/license inventory is still required.
- The public Go API exposes `NewV2RayPoint`, `RunLoop`, `StopLoop`, `InitV2Env`, and a `V2RayVPNServiceSupportsSet` callback interface (`Prepare`, `Setup`, `Shutdown`, `Protect(fd)`, `OnEmitStatus`). `Protect(fd)` protects Xray-created outbound sockets from being routed back into the VPN. It is **not** the TUN descriptor handoff.
- `InitV2Env` sets the asset path and XUDP key only. The reviewed wrapper does not expose a TUN-FD setter or an authenticated Binder/ParcelFileDescriptor handoff API. The reviewed repository is Go source; the Android-side callback implementation and service lifecycle must be reviewed separately before reuse.
- The wrapper installs an alternative system dialer globally in the Go process. Keep it isolated from other native engines and do not assume it is safe to share a process with unrelated Go runtimes.
- Lifecycle concerns in the reviewed code: `pointloop()` sets `IsRunning` and starts the Xray instance, then calls `Prepare()` and `Setup("")` but ignores their integer return codes before emitting `Running`. If `Vpoint.Start()` fails, the shown path resets `IsRunning` but does not visibly close and clear the newly created instance. Normal `StopLoop()` closes the core and emits `Closed`, but does not itself call `SupportSet.Shutdown()` (that callback is called on the asynchronous DNS-failure path). These semantics are not acceptable as-is for Molido's fail-closed start/stop contract; a reviewed wrapper fork or adapter must check callback results, close partial startup resources, and make shutdown acknowledgement explicit.
- The repository's current build workflow is not reproducible enough for Molido: it installs `gomobile@latest`, runs `go mod tidy`, downloads latest GeoIP/geosite data via `gen_assets.sh`, uses floating `ubuntu-latest`, and uploads an AAR to a user-supplied GitHub Release tag. The asset script resolves mutable latest releases and the repository tree at the reviewed commit has no checked-in `data/` assets. Do not run this release workflow or import its output. A Molido build must pin source commit, Go/JDK/SDK/NDK, gomobile module version, module graph, and both data-asset URLs plus SHA-256; it must produce an artifact only (no release upload).
- No ARM64 AAR was built or tested in this review. The README's generic `gomobile bind` command does not constitute evidence of an ARM64-only reproducible build.

**Decision:** AndroidLibXrayLite is useful as a source/API reference, but is not ready to be consumed as a binary dependency. Build a controlled Molido-owned wrapper from pinned source only after pinning data assets and resolving the lifecycle defects above.

## Finding 0.1 — How MahsaNG actually runs Xray on Android

Reviewed MahsaNG Android sources at its `master` branch (the application repository is GPL-3.0):
- `V2rayNG/app/src/main/kotlin/com/v2ray/ang/service/V2RayServiceManager.kt`
- `V2rayNG/app/src/main/kotlin/com/v2ray/ang/service/V2RayVpnService.kt`
- `V2rayNG/app/src/main/kotlin/com/v2ray/ang/service/V2RayProxyOnlyService.kt`

The inspected VPN-mode path is **not** a direct transfer of the Android TUN FD into the Xray Go runtime:

1. `V2RayServiceManager` creates `Libv2ray.newV2RayPoint(V2RayCallback(), ...)`, sets the asset path with `initV2Env`, loads a generated Xray configuration, and calls `runLoop()`.
2. The Xray callback's `setup()` starts the Android VPN service. `protect(fd)` delegates to Android `VpnService.protect(fd)` for Xray's outbound sockets.
3. `V2RayVpnService` creates the one VPN interface with `VpnService.Builder.establish()`, configures addresses/routes/DNS/MTU, and launches a separate native `libtun2socks.so` process.
4. The VPN service passes the TUN file descriptor to that tun2socks process over a filesystem `LocalSocket` using `setFileDescriptorsForSend()` (SCM_RIGHTS). tun2socks forwards packets to Xray's local SOCKS listener.
5. Network callbacks update the VPN service's underlying network; shutdown destroys tun2socks, stops Xray, stops the service, and closes the interface.

This is a working architectural reference for a **VpnService + tun2socks + local SOCKS** design, but it is not evidence that AndroidLibXrayLite consumes the TUN FD itself. It adds another native helper process, socket IPC, a local SOCKS listener, and a separate lifecycle/cleanup protocol. The inspected `sendFd()` retries the local-socket handoff but does not show an explicit receiver cleanup acknowledgement. The callback implementation also returns `true` from `protect()` when the service reference is missing; Molido must not copy this fail-open behavior.

**Molido decision:** do not copy MahsaNG's service or tun2socks orchestration. Prefer a single central Molido VpnService and a directly authenticated descriptor contract to the isolated Xray engine if the pinned Xray TUN implementation can be safely embedded. If that direct path proves impractical, any tun2socks helper must remain subordinate to the same central service, have authenticated descriptor transfer and explicit shutdown ACK, and must not establish another VPN interface. Either option remains unimplemented until tested.

## Finding A — TUN FD input in Xray Android build

At the reviewed source baseline, `proxy/tun/tun_android.go`:

- Reads the descriptor number using `xray.tun.fd` (normalized environment variable: `XRAY_TUN_FD`).
- Calls `unix.SetNonblock(fd, true)`.
- Duplicates the descriptor using `unix.F_DUPFD_CLOEXEC`.
- Builds its Android TUN reader around the duplicate; `Close()` closes the duplicate through the internal FD device.
- The source comment says the application retains the original descriptor.

Important integration caveat: `SetNonblock` is called on the received descriptor **before** duplication. File status flags may be shared by duplicated descriptors, so this may affect the central service's original open-file description. Before adopting this code, verify Android/Linux semantics on supported API levels and either make the flag change intentional/documented or patch the source to avoid surprising the owner.

This is a promising boundary for the existing `TunOwnershipCoordinator`, but it does **not** implement Molido's Binder protocol, caller-UID authentication, generation/nonce checks, service-side FD lifecycle, or cleanup acknowledgement. The environment-variable descriptor number is not itself a secure IPC protocol. Do not pass raw FD integers across processes or Binder; transfer a `ParcelFileDescriptor`, validate the caller/session, duplicate at the receiving boundary, and retain the coordinator lease until both engine-side and service-side closure are confirmed.

Before integration, prove:
- How the Android wrapper sets `XRAY_TUN_FD` for the actual embedded Go runtime (not just a shell process).
- Whether the FD survives the chosen JNI/gomobile startup path and is not accidentally closed or reused.
- That the TUN is configured with the exact address/route/MTU/DNS contract expected by Molido.
- That Xray protects/binds all required upstream sockets outside the VPN and fails closed on protection failure.
- That stop closes the Xray duplicate and all goroutines/sockets before returning a cleanup acknowledgement.
- That process death, partial startup, duplicate start, stale generation, and timeout retain the lease safely.

## Finding B — MASQUE outbound

The reviewed `proxy/masque` package registers an Xray outbound. Its configuration supports:
- HTTP/3 over QUIC datagrams (default).
- HTTP/2 fallback using DATAGRAM capsules (with TCP head-of-line blocking trade-offs).
- Endpoint, authority, path, CONNECT protocol token, addresses, DNS, domain strategy, MTU, keepalive, and certificate public-key pinning.
- Optional UDP noise and packet sizing for chained tunnels.

The implementation creates an internal userspace netstack for the MASQUE outbound. It is not a second Android `VpnService` and must not call Android `VpnService.Builder.establish()`. If ever integrated, it belongs behind the existing Xray engine as an outbound/protocol feature; it does not become another Molido engine.

**Provider/legal gate remains BLOCKED.** The reviewed config defaults explicitly reference Cloudflare consumer MASQUE behavior (authority/path/protocol/SNI and public-key pinning), and the config describes a private key enrolled with the edge. This source review does not establish a supported public Android embedding SDK/API, service terms, account enrollment route, or permission to rely on private consumer endpoints. Do not enable Cloudflare/WARP connectivity, reverse-engineer private endpoints, ship hard-coded endpoint assumptions, or advertise MASQUE/WARP as operational until a supported public integration path and applicable terms are documented. Generic RFC support in code is not proof of service interoperability.

## Finding C — WireGuard profile parser and noise fields

MahsaNG's `WireguardFmt` parser shows URI fields such as public key, address, MTU, keepalive, reserved bytes, and noise settings. These may inform additional parser fixtures and validation cases in Molido's existing WireGuard profile import path.

Do not copy the implementation blindly: parsing should reject malformed ports, duplicate/ambiguous query keys, invalid reserved-byte ranges, invalid IP/prefix values, missing public keys, and oversized inputs. Secrets must be redacted from logs and temporary byte buffers wiped. No change to the WireGuard runtime is authorized by this evaluation alone.

## License and supply-chain gate

- Xray-core root license: MPL-2.0. Modifications to MPL-covered files and distribution of executable forms trigger source-availability and notice obligations under the license. Keep the source, modifications, notices, and exact upstream provenance auditable.
- MahsaNG application repository: GPL-3.0; do not copy application code into Molido without a separate compatibility/distribution review.
- AndroidLibXrayLite and its embedded/native/transitive dependencies require an independent license inventory. Do not infer their license solely from Xray-core's root LICENSE.
- Generate an SBOM/license report for the exact selected commit and Android build output; review all Go modules, native libraries, generated code, and build tools before packaging.
- This document is not legal advice and makes no final license-compatibility determination.

## Integration plan — gated and additive

1. **Source audit (now):** preserve this pinned evaluation; inspect AndroidLibXrayLite startup, environment propagation, JNI exports, ABI/toolchain, and all license notices.
2. **Reproducible build:** create a separate, pinned Xray build job for Android ABIs supported by the app. No floating branch, no downloaded prebuilt binary without provenance, and no runtime dependency until artifact hashes and SBOM are recorded.
3. **TUN contract:** implement the central-service `ParcelFileDescriptor` handoff and authenticated session contract. Xray receives only a duplicated FD. No second VPN service.
4. **Engine adapter:** implement Xray start/stop/readiness only after descriptor ownership and stop-ack semantics are tested. Register Xray only after actual native runtime capability is available; until then it remains `NOT_IMPLEMENTED` and unselectable.
5. **MASQUE experiment:** behind a disabled development test profile and a controlled, authorized endpoint only after provider/API/terms gate passes. Verify both H3 and H2, IPv4/IPv6, DNS, UDP, MTU/fragmentation, keepalive, reconnect, and fail-closed behavior. Never expose it in production UI before acceptance.
6. **Regression/security/device tests:** malformed configuration, key redaction, socket protection, DNS and IPv6 leaks, network handover, process death, FD leaks, repeated start/stop, and sustained bidirectional traffic on a physical Android phone.

## Required Xray acceptance matrix (must all pass before registration)

| Gate | Required evidence | Failure behavior |
|---|---|---|
| ARM64 artifact | Pinned wrapper/Xray/module graph; pinned Go, gomobile, JDK, SDK and NDK; pinned GeoIP/geosite assets with SHA-256; AAR contains ARM64 only; archived build-input manifest and artifact hash | Do not import or register Xray |
| Secure TUN handoff | Binder caller UID validation; per-session nonce and generation; `ParcelFileDescriptor` transfer; receiver duplicates FD; no raw integer sent over IPC; central service retains original | Reject start; retain lease if ownership is uncertain |
| Startup | Core creation, config validation, outbound socket protection, and all service callbacks return success before session reports RUNNING | Close partial core/resources; report failure; no RUNNING status |
| IPv4 / IPv6 | Real routed traffic for IPv4 and IPv6, correct address/routes/MTU, network transition tests; if IPv6 cannot be supported, explicitly block IPv6 traffic rather than leak it | Fail closed; do not claim connected |
| DNS | Real A and AAAA lookups through configured DNS path; no plaintext/system-resolver leak; resolver failure and timeout tested | Fail closed; do not silently fall back to unapproved DNS |
| Transport | Real TCP and UDP bidirectional traffic through a controlled authorized test endpoint; handshake/data-plane proof, not merely core-start status | Do not mark engine connected |
| Reconnect | Wi-Fi/mobile handover, loss/restore, stale session, duplicate start, partial startup and process death | Old generation cannot reclaim or release a newer lease |
| Cleanup | Xray closes its duplicate and sockets; explicit cleanup ACK; central service closes original; lease released only after confirmed closure; repeated start/stop shows no FD/thread growth | Keep lease in cleanup-required state; block another engine |
| Stability | At least 30-minute bidirectional run plus repeated reconnect/stop cycles on a physical Android device | No device-tested or production status |

CI/emulator success can prove build and policy behavior only. It cannot replace physical-device VPN routing, DNS-leak, network-handover, or sustained data-plane tests.

## Explicit non-goals

- No new engine slot and no seventh engine.
- No copying MahsaNG UI, account/login, server subscription, private backend, or application service orchestration.
- No second `VpnService`, parallel TUN, or independent VPN lifecycle.
- No hard-coded/fabricated countries, endpoints, IPs, ping, or server availability.
- No automatic production activation, release, PR merge, paid service, hosting, or external runtime.
- No claim that MASQUE is equivalent to Cloudflare WARP or that the APK was device-tested.

## Current MolidoVPN status after this evaluation

- Xray: **NOT_IMPLEMENTED / NOT_SELECTABLE**. Existing process isolation is scaffolding only; AndroidLibXrayLite has been source-audited but is not yet a Molido dependency.
- ARM64 build: a pinned CI validation job has been added to the PR. It is configured to build an AAR and upload build evidence only; no run/result is recorded for the latest commit yet.
- MASQUE: **BLOCKED** pending supported public integration path, terms, and end-to-end tests.
- TUN FD: **REFERENCE DESIGN IDENTIFIED**; Molido's secure handoff and cleanup ACK are not implemented.
- WireGuard profile parsing: **candidate test cases only**; no runtime code copied.
- PR #4 remains Draft and unmerged. This evaluation must not relax any existing release gate.
