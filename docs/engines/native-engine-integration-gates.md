# Native engine integration gates and implementation sequence

Status: engineering decision record; this document does not mark any additional engine as implemented.

## Shared runtime and TUN constraints

- `VpnSessionController` remains the only application-level authority that may start or stop an engine session. It must reserve the session before any engine starts and retain ownership until cleanup succeeds.
- Only one engine may own an active Android VPN/TUN session at a time. A successful native-core start is not proof of a working tunnel.
- The current WireGuard adapter uses the upstream WireGuard Android GoBackend. Xray's official `libXray` Android wrapper explicitly warns that only one Go runtime may be loaded per process. Independently built Go-based engines therefore must not be loaded together into the same Android process.
- Before adding Xray, AmneziaWG, or Psiphon, implement and test an isolated engine-process/IPC lifecycle (or a single combined native Go build, if technically supportable). A separate process per Go runtime is the default safe direction. Do not assume that merely adding multiple AARs is safe.
- The VPN service process must be the sole owner of the active TUN descriptor. Stop/cleanup must be acknowledged before another engine can start. The main process must never report a session as released while the remote engine still owns TUN resources.
- Engine readiness is never inferred from CI build success, a service process being alive, or a VPN interface being UP. Track handshake, traffic counters, DNS, data-plane reachability, and physical-device verification separately.

## Native process-isolation foundation (scaffold only)

The Android manifest now declares private :engine_xray, :engine_amneziawg, and :engine_psiphon service processes. Their shared Messenger endpoint reports its engine slot, process ID, and explicit NOT_IMPLEMENTED readiness. Instrumentation tests assert that each service PID differs from the app PID.

This is only a process-isolation and IPC-liveness scaffold:
- These services are not VPNServices and do not create or own a TUN descriptor.
- They do not load native libraries, accept profiles, or start an engine.
- No adapter is registered for Xray, AmneziaWG, or Psiphon; all remain unavailable in the UI.
- The next engineering gate is a single designated VPNService/TUN owner with safe descriptor handoff and acknowledged remote stop/cleanup. That requires a deliberate migration away from relying on WireGuard GoBackend's manifest-merged VPNService as the sole TUN provider before the other engines can be activated.
## Engine-specific gates

### WireGuard (existing engine; hardening in progress)

- Uses upstream `com.wireguard.android:tunnel` GoBackend.
- Profile size is bounded; the temporary UTF-8 configuration byte buffer is wiped after parsing.
- Manual diagnostics expose RX/TX counters and the latest peer handshake timestamp. These counters are evidence only; they do not prove public internet reachability.
- Remaining acceptance: real Android phone test, server endpoint reachability, fresh handshake, bidirectional traffic, DNS/leak checks, disconnect cleanup and retry.

### Xray / V2Ray

- Preferred candidate: official XTLS `libXray` Android artifact, pinned to a reviewed release (research snapshot: `v26.11.9`).
- Official libXray exposes a structured JSON `Invoke` API and supports runtime TUN FD through the Xray config root `env.xray.tun.fd`.
- The upstream README warns against loading more than one Go runtime in a process. Isolate libXray from WireGuard's GoBackend unless a single combined Go build is proven.
- Build a strict allowlist parser for supported URI/config formats, validate the generated Xray JSON before starting, redact credentials, and never execute arbitrary imported config directives without bounds.
- Acceptance: arm64 and x86_64 artifacts, isolated process startup/stop, TUN FD ownership, DNS leak tests, TCP+UDP tests, malformed config tests, physical Android device.

### Tor

- Preferred candidate: Guardian Project `tor-android` native Tor library and control interface.
- Tor daemon provides a local SOCKS endpoint; this alone does not route all device traffic. A separately tested TUN-to-SOCKS packet bridge is required for full-device VPN mode.
- Do not embed Orbot as a second VPN owner or create a second concurrently active TUN.
- Acceptance: Tor bootstrap/circuit-ready signal, SOCKS proxy test, UDP behavior documented, TUN bridge tests, DNS handling, leak checks, bridge configuration UX, physical Android device.

### AmneziaWG

- Upstream Android project is Apache-2.0 and builds its tunnel module from source with native Go/CMake components and submodules.
- Upstream issue tracker research found no official Maven Central tunnel artifact at the time of review. Do not silently depend on an unofficial or unmaintained fork.
- Build a pinned upstream source revision reproducibly, inspect every submodule license, and isolate its Go runtime from WireGuard's GoBackend.
- Preserve the Amnezia-specific obfuscation parameters and validate server compatibility; standard WireGuard profiles are not automatically AmneziaWG profiles.
- Acceptance: reproducible native builds for supported ABIs, server-side compatibility, exclusive TUN ownership, handshake/data-plane/DNS tests, physical Android device.

### Psiphon

- Psiphon publishes an Android Mobile Library, but its tunnel-core repository is GPL-3.0.
- Do not embed or distribute the GPL-3.0 core until the product's distribution model and source-offer obligations have been reviewed and explicitly accepted. Obtain a suitable commercial license/exception if the intended distribution cannot meet GPL obligations.
- Its Android host integration requires lifecycle and service callbacks; confirm that the SDK can operate under Molido's single-active-TUN ownership model before writing the adapter.
- Acceptance: license approval, supported SDK version pinned, host-service lifecycle, no credential/diagnostic leakage, reconnect/stop cleanup, physical Android device.

### MASQUE / WARP-compatible

- Cloudflare's official consumer WARP documentation describes traffic tunneling over MASQUE.
- The consumer service does not provide user-selectable country egress; do not expose a fake country selector.
- No supported public consumer embedding SDK/API has been established by this review. Do not reverse-engineer private endpoints or ship an undocumented enrollment protocol.
- Keep this engine blocked until a documented supported integration path and terms are confirmed. Accountless WireGuard enrollment is a separate optional provider path and must not be misrepresented as a full MASQUE implementation.
- Acceptance: documented supported API/SDK and terms, explicit external-provider disclosure and consent, local key handling, provider refusal/rate-limit handling, TUN/DNS/data-plane/device tests.

## Updated upstream review (2026-11-20)

### Tor daemon and TUN-to-SOCKS bridge

- Guardian Project `tor-android` provides an Android `TorService` and native `libtor.so`; its README documents the Guardian Project Maven repository and `jtorctl`. The Tor daemon is a SOCKS endpoint, not a full-device VPN by itself.
- `TorService` exposes explicit START/STOP actions, status broadcasts, a bindable `TorControlConnection`, and a per-app `torrc` path. It also documents process-global Tor state and a single active `tor_run_main()` per process. Keep Tor in its own dedicated process and never instantiate multiple Tor runtimes there.
- For the packet bridge, the reviewed candidate is upstream `heiher/hev-socks5-tunnel` (MIT). Its Android AAR exposes `hev.htproxy.TProxyService.TProxyStartService(config_path, fd)`, `TProxyStopService()`, `TProxyIsRunning()`, and `TProxyGetStats()`. It supports IPv4/IPv6, TCP and UDP forwarding. Pin a signed upstream release and verify its asset digest before vendoring or consuming the AAR.
- Still required: a dedicated `VpnService` that alone calls `Builder.establish()`, passes a duplicated/owned TUN descriptor to the bridge, and waits for bridge-stop acknowledgement before closing the descriptor. The bridge must point only to Tor's loopback SOCKS listener. Test UDP ASSOCIATE support, DNS mapping, IPv6, MTU, socket protection, and fail-closed behavior. No Tor VPN adapter is registered until these checks pass.

### Xray / libXray current integration contract

- Official `XTLS/libXray` currently documents `Invoke(requestJSON)` with `apiVersion: 3`; `runXray`, `stopXray`, `getXrayState`, validation and conversion methods are exposed through the structured API. Pin a verified release/tag and its matching Android artifact; do not assume API stability.
- The official README explicitly prohibits loading independently built Go runtimes in one process. The isolated `:engine_xray` process is therefore mandatory. The Android process must own the TUN descriptor and hand a duplicated descriptor to Xray using the documented `env.xray.tun.fd` mechanism; Xray closes only its duplicate.
- libXray's `SetDNS`/`ResetDNS` alter the process-wide Go resolver. Call `SetDNS` before start and `ResetDNS` only after a confirmed `stopXray`; serialize this with engine lifecycle. `runXray` success means the core started, not that the tunnel routes traffic.
- A production adapter still needs ABI-pinned AAR builds, JNI/Java API binding verified against the selected release, bounded configuration parsing, response parsing, start/stop timeouts, remote cleanup acknowledgement, and TCP/UDP/DNS/leak/device tests.

### AmneziaWG official source build

- The official `amnezia-vpn/amneziawg-android` repository is Apache-2.0 and its README builds with `git clone --recurse-submodules` followed by `./gradlew assembleRelease`.
- The tunnel module compiles native targets `libwg-go.so`, `libwg.so`, and `libwg-quick.so` through CMake. Its `.gitmodules` includes `amneziawg-tools` and `termux-elf-cleaner`; the tunnel build also depends on the `libwg-go` source tree. Pin the parent commit and every recursive submodule commit and audit each license independently.
- Do not copy the upstream UI or create a second VPN service. Extract/build only the tunnel component in a controlled, reproducible CI job, then integrate it behind the dedicated process and single TUN-owner contract. Preserve Amnezia-specific obfuscation parameters; ordinary WireGuard profiles are not interchangeable.

### Psiphon license decision

- The official `Psiphon-Labs/psiphon-tunnel-core` repository identifies GPL-3.0 and includes the GPLv3 license text. This is a confirmed license constraint, not a pending guess.
- Integration remains blocked until the product owner explicitly chooses either (a) GPL-3.0-compliant distribution, including complete corresponding source and notices for the covered combined work as legally applicable, or (b) a written commercial license/exception from the rights holder. This engineering note is not legal advice.
- If neither option is accepted, do not bundle the core. Keep the adapter unregistered and the engine unavailable.

### MASQUE / Cloudflare WARP

- Current official Cloudflare docs describe the consumer WARP app using MASQUE and/or WireGuard, while Cloudflare One documents its own client daemon, device enrollment, orchestration and policy model.
- No supported public third-party Android embedding SDK/API for the consumer client was established in this review. The existence of official WARP apps and public protocol documentation does not grant permission to impersonate the client or use private enrollment/orchestration endpoints.
- Revisit only if Cloudflare publishes an embedding SDK/API and terms that explicitly permit this use, or if a separately reviewed independent implementation can lawfully interoperate under published specifications and service terms. Do not present an unofficial client as Cloudflare-supported.

## Proposed implementation order

1. Finish WireGuard physical-device verification and cleanup/diagnostics.
2. Implement the shared isolated engine-process lifecycle and remote session cleanup contract.
3. Prototype Tor daemon + TUN-to-SOCKS bridge without a second active VPN owner.
4. Integrate Xray using pinned official libXray in its isolated process; validate configuration import and runtime TUN FD.
5. Build AmneziaWG from a pinned official upstream source revision and isolate its Go runtime.
6. Resolve Psiphon GPL-3.0 distribution/licensing and host-service constraints before implementation.
7. Revisit MASQUE/WARP only after a documented supported consumer integration route is available.

No engine may be promoted to selectable until its adapter is registered, its configuration is validated, and its runtime lifecycle passes automated tests. No engine may be marked Android-device-tested or production-verified without the corresponding real-device evidence.
