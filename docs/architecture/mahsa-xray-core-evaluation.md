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

## Explicit non-goals

- No new engine slot and no seventh engine.
- No copying MahsaNG UI, account/login, server subscription, private backend, or application service orchestration.
- No second `VpnService`, parallel TUN, or independent VPN lifecycle.
- No hard-coded/fabricated countries, endpoints, IPs, ping, or server availability.
- No automatic production activation, release, PR merge, paid service, hosting, or external runtime.
- No claim that MASQUE is equivalent to Cloudflare WARP or that the APK was device-tested.

## Current MolidoVPN status after this evaluation

- Xray: **NOT_IMPLEMENTED / NOT_SELECTABLE**. Existing process isolation is scaffolding only.
- MASQUE: **BLOCKED** pending supported public integration path, terms, and end-to-end tests.
- TUN FD: **REFERENCE DESIGN IDENTIFIED**; Molido's secure handoff and cleanup ACK are not implemented.
- WireGuard profile parsing: **candidate test cases only**; no runtime code copied.
- PR #4 remains Draft and unmerged. This evaluation must not relax any existing release gate.
