# Native engine integration gates and implementation sequence

Status: engineering decision record; this document does not mark any additional engine as implemented.

## Shared runtime and TUN constraints

- `VpnSessionController` remains the only application-level authority that may start or stop an engine session. It must reserve the session before any engine starts and retain ownership until cleanup succeeds.
- Only one engine may own an active Android VPN/TUN session at a time. A successful native-core start is not proof of a working tunnel.
- The current WireGuard adapter uses the upstream WireGuard Android GoBackend. Xray's official `libXray` Android wrapper explicitly warns that only one Go runtime may be loaded per process. Independently built Go-based engines therefore must not be loaded together into the same Android process.
- Before adding Xray, AmneziaWG, or Psiphon, implement and test an isolated engine-process/IPC lifecycle (or a single combined native Go build, if technically supportable). A separate process per Go runtime is the default safe direction. Do not assume that merely adding multiple AARs is safe.
- The VPN service process must be the sole owner of the active TUN descriptor. Stop/cleanup must be acknowledged before another engine can start. The main process must never report a session as released while the remote engine still owns TUN resources.
- Engine readiness is never inferred from CI build success, a service process being alive, or a VPN interface being UP. Track handshake, traffic counters, DNS, data-plane reachability, and physical-device verification separately.

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

## Proposed implementation order

1. Finish WireGuard physical-device verification and cleanup/diagnostics.
2. Implement the shared isolated engine-process lifecycle and remote session cleanup contract.
3. Prototype Tor daemon + TUN-to-SOCKS bridge without a second active VPN owner.
4. Integrate Xray using pinned official libXray in its isolated process; validate configuration import and runtime TUN FD.
5. Build AmneziaWG from a pinned official upstream source revision and isolate its Go runtime.
6. Resolve Psiphon GPL-3.0 distribution/licensing and host-service constraints before implementation.
7. Revisit MASQUE/WARP only after a documented supported consumer integration route is available.

No engine may be promoted to selectable until its adapter is registered, its configuration is validated, and its runtime lifecycle passes automated tests. No engine may be marked Android-device-tested or production-verified without the corresponding real-device evidence.
