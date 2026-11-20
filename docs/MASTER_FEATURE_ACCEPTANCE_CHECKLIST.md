# MolidoVPN AI — Master Feature & Acceptance Checklist

This checklist is a delivery gate, not a claim that the listed features already exist. Every item must be linked to implementation, tests, evidence, and a commit. Never mark a feature complete from UI presence, dependency inclusion, CI compilation alone, or a simulated result.

## Status vocabulary
- [ ] Not Implemented
- [ ] Source Implemented
- [ ] CI Built
- [ ] CI Tested
- [ ] Android Device Tested
- [ ] Server Deployment Required
- [ ] Production Verified
- [ ] Blocked (with reason and next decision)

A feature may carry multiple scoped statuses. Record commit SHA, test run, Android model/version, Android build, and authorized endpoint/server version where applicable.

## A. Product inventory and architecture
- [ ] Preserve exactly six official engine slots: WireGuard, AmneziaWG, V2Ray/Xray, Psiphon, Tor, MASQUE/WARP-compatible.
- [ ] Preserve exactly eight complementary cores: Session & TUN Supervisor; Network Intelligence; Adaptive Path & Policy; Security & Privacy; Resilience & Recovery; Diagnostics & Observability; Control Plane & Configuration; Update & Integrity.
- [ ] One and only one Android VpnService/TUN owner at any instant.
- [ ] All engine adapters use the central session supervisor and a documented adapter contract.
- [ ] Explicit lifecycle state machine: idle, preparing, connecting, connected, reconnecting, disconnecting, blocked/error, offline/unavailable.
- [ ] Capability registry exposes only implemented, validated adapters; no fake enabled buttons, countries, servers, or endpoints.
- [ ] Engine switching is serialized; old engine stop and TUN release must be acknowledged before a new engine acquires ownership.
- [ ] Define configuration schema/versioning, migration, rollback, and compatibility policy.
- [ ] Document architecture decisions, data flows, trust boundaries, failure modes, and dependency/license inventory.

## B. Six engine slots — implementation gates
### B1. WireGuard
- [ ] Profile import and strict validation; reject malformed keys, routes, DNS, endpoint and unsupported options.
- [ ] Secure key generation/import/storage, redaction, revoke and deletion.
- [ ] Real GoBackend lifecycle integrated with the sole TUN supervisor.
- [ ] Deterministic stop, resource release, cleanup acknowledgement and safe retry.
- [ ] Endpoint validation and supported endpoint selection; no fabricated public endpoints.
- [ ] Handshake freshness plus bidirectional data-plane verification; handshake alone is not connectivity proof.
- [ ] IPv4/IPv6 routing, DNS, MTU, keepalive and roaming behavior verified.
- [ ] WARP-style accountless provider enrollment researched and implemented only through a lawful, supported, reproducible flow; explicit consent, fail-closed behavior, schema validation, rate-limit handling, revocation and encrypted persistence.
- [ ] Enrollment/provider failure must never be reported as a connected tunnel.

### B2. AmneziaWG
- [ ] Upstream source, license, maintenance and Android build provenance reviewed.
- [ ] Native/runtime adapter and configuration validation implemented.
- [ ] Shared TUN ownership and descriptor/lifecycle integration proven.
- [ ] Compatibility, handshake, bidirectional traffic, DNS and cleanup tests pass.

### B3. V2Ray/Xray
- [ ] Select and pin a maintained, legally compatible Android runtime/artifact.
- [ ] Verify ABI, provenance, signature/hash, API contract and dependency licenses.
- [ ] Implement supported protocol/config parsing and strict validation.
- [ ] Integrate through the single TUN owner without a competing VpnService.
- [ ] Process isolation, IPC, crash handling, descriptor ownership and shutdown proven.
- [ ] Real bidirectional traffic, DNS, routing and leak tests pass.

### B4. Psiphon
- [ ] Resolve GPL-3.0 compliance/source-offer or obtain documented commercial permission before distribution.
- [ ] Review official Android Mobile Library, maintenance, terms and security.
- [ ] Implement only after legal gate; no proprietary-code copying or license bypass.
- [ ] Integrate lifecycle and traffic path under sole TUN owner.
- [ ] Validate real connectivity, DNS, cleanup and failure behavior.

### B5. Tor
- [ ] Review supported Tor Android daemon/library and license.
- [ ] Implement daemon lifecycle and local SOCKS transport.
- [ ] Implement and audit TUN-to-SOCKS packet bridge; do not start a second competing VPNService.
- [ ] Handle DNS, UDP limitations, isolation, circuit readiness, timeouts and graceful shutdown.
- [ ] Verify traffic path and leak boundaries on device.

### B6. MASQUE/WARP-compatible
- [ ] Establish supported public Android embedding/API and applicable terms before integration.
- [ ] Distinguish consumer WARP enrollment, WireGuard-based enrollment, MASQUE transport and Zero Trust APIs; do not conflate them.
- [ ] Do not reverse-engineer private endpoints or rely on undocumented endpoints.
- [ ] Implement only a supported transport with consent, credentials lifecycle, routing and single-TUN integration.
- [ ] Verify real data plane, DNS, failure and revocation behavior.

## C. Connection options and user-facing modes
These are policies/workflows over validated engines, not extra engine slots. Each mode must map to a real tested policy or remain unavailable.
- [ ] Automatic / Smart Select: choose only among currently validated paths using measured, fresh signals and explainable rules.
- [ ] Gaming Plus: latency/jitter/loss-oriented policy; no invented ping or speed.
- [ ] Severe-Restriction / Anti-Interference: policy using supported transports only; never promise bypass under total route loss.
- [ ] Streaming & Download: stability/throughput policy based on measurements, with user controls.
- [ ] Dual-hop / Double-chain: two independently supported hops, ordered routing, failure handling, DNS protection and loop prevention.
- [ ] Double WARP: implement only if a supported, lawful topology exists; otherwise show Blocked with reason, not a fake option.
- [ ] HTTP/3 masking/transport: define exact protocol and upstream implementation first; distinguish obfuscation from encryption and do not claim stealth without tests.
- [ ] Shard: identify exact protocol/project and licensing before classifying; do not create a seventh engine slot or expose an unverified option.
- [ ] User-selected engine/profile and safe fallback policy.
- [ ] Mode descriptions disclose limitations, battery/data trade-offs and whether the mode is actually available.

## D. Nodes, profiles and provider enrollment
- [ ] Import/export supported configuration formats with strict parsing and size limits.
- [ ] Encrypt secrets at rest with Android Keystore-backed keys; no secrets in logs, analytics, backups or crash reports.
- [ ] Profile list, rename, duplicate, edit, validate, select, revoke and securely delete.
- [ ] Public/community node sources must be documented, lawful, attributable and checked for freshness; no fabricated inventory.
- [ ] Verify node endpoint, certificate/key identity, protocol compatibility and actual health before selection.
- [ ] Automatic node ranking uses measured latency, loss, availability and recent successful data-plane checks.
- [ ] Clearly label stale, unverified, unavailable and user-supplied nodes.
- [ ] Provider enrollment requires informed consent, minimal disclosure, rate-limit handling and revocation.
- [ ] Do not assume a free public relay/server exists; server-dependent features remain Server Deployment Required until verified.

## E. DNS, routing and leak protection
- [ ] DNS modes: tunnel-provided, user-selected encrypted DNS where supported, and explicit system/default mode.
- [ ] DNS without VPN is a separate, explicit policy with a clear leak/privacy warning; never silently bypass the tunnel.
- [ ] DNS requests follow selected routing policy; prevent resolver fallback outside the tunnel when kill-switch policy is enabled.
- [ ] IPv4 and IPv6 route correctness; no silent IPv6 bypass.
- [ ] Kill-switch/fail-closed behavior when required by policy and supported by Android.
- [ ] Split tunneling per-app include/exclude with conflict validation and clear semantics.
- [ ] Route exclusions, local LAN access and captive-portal behavior documented and tested.
- [ ] MTU discovery/adaptation bounded and measured; prevent fragmentation black holes.
- [ ] Verify public IP, DNS resolver path, IPv4/IPv6, WebRTC/browser exposure where testable; report exact test scope.
- [ ] No traffic leaves an unintended physical interface during transition or tunnel failure.

## F. Network intelligence and adaptive policy
- [ ] Observe Android-reported network type, validation, metering, transport and network changes.
- [ ] Detect stale/lost network observations and avoid acting on stale measurements.
- [ ] Measure candidate path latency, jitter, packet loss and throughput only with real probes and disclose probe limits.
- [ ] Handle Wi-Fi/mobile handover, airplane mode, captive portal and no-route/offline states.
- [ ] Deterministic explainable selection first; AI claims require datasets, evaluation, reproducibility and safe fallback.
- [ ] User policy controls: automatic, Wi-Fi-only, unmetered-only and permitted network constraints.
- [ ] Respect Android background execution limits; no unimplemented WorkManager/auto-reconnect claims.

## G. Security, privacy and resilience
- [ ] Threat model, trust boundaries, abuse cases and security review.
- [ ] Keystore-backed encryption, key rotation/revocation and secure deletion boundaries documented.
- [ ] HTTPS/TLS validation and trust bootstrap through an owner-controlled trusted channel; no unsafe trust-on-first-use.
- [ ] Redact private keys, tokens, credentials, profile content and sensitive endpoint data.
- [ ] Minimize permissions; document foreground service, notification and battery behavior.
- [ ] Dependency pinning, SBOM, license audit and vulnerability scanning.
- [ ] No telemetry by default or strictly minimized anonymous opt-in telemetry with documented retention.
- [ ] Bounded retry/backoff, cancellation, timeouts, cleanup and crash recovery.
- [ ] No reconnect loop, resource leak, duplicate service or orphaned TUN after failure/process death.
- [ ] Signed configuration/update metadata, integrity verification, anti-downgrade and rollback-safe recovery.

## H. Android app and UX
- [ ] Connection dashboard reflects observed session state only.
- [ ] Every engine/mode has accurate availability and readiness state.
- [ ] Clear connect, disconnect, cancel, retry, switch, profile and permission flows.
- [ ] Explain VPN permission, foreground notification, battery and data usage.
- [ ] Diagnostics screen with redacted logs, export consent and actionable error codes.
- [ ] Settings for DNS, routing, per-app split tunnel, network policy, privacy and update behavior.
- [ ] Accessible layouts, touch targets, screen sizes, dark/light behavior and localization.
- [ ] Persian, English, Arabic and Turkish localization where included in product scope; RTL verified.
- [ ] No fake country selector or server map; show only provider-supported, verified inventory.
- [ ] UI never labels a planned, blocked or untested engine as active/connected.

## I. Self-hosted control plane and operations
- [ ] Reproducible self-hosted deployment source; no dependency on GitHub Actions as a VPN server/relay.
- [ ] Device enrollment/authentication, revocation and least-privilege administration.
- [ ] Signed configuration distribution and key lifecycle.
- [ ] Actual server inventory and health checks, only after operator deployment.
- [ ] Audit trail without browsing history or secret material.
- [ ] Health/readiness endpoints, backup/restore, migration, upgrade and rollback.
- [ ] Secrets supplied through secure deployment configuration, never committed or embedded in APK.
- [ ] Label all server-dependent functions Server Deployment Required until deployed and verified.

## J. Test matrix — execute after feature implementation
- [ ] Unit tests for state machines, parsers, policy, crypto wrappers, storage, redaction and adapters.
- [ ] Integration tests for every engine adapter, supervisor, TUN lease, IPC and cleanup.
- [ ] Instrumentation tests for Android Keystore, profile persistence/revoke and permission lifecycle.
- [ ] CI build, lint, static analysis, dependency/license and security scans.
- [ ] Emulator tests for reproducible lifecycle and storage cases (not a substitute for device/network tests).
- [ ] Physical Android device tests across supported Android versions and representative vendors.
- [ ] Real authorized endpoint tests for bidirectional traffic and independent data-plane reachability.
- [ ] IPv4, IPv6, DNS, WebRTC and route leak tests.
- [ ] Wi-Fi to mobile, mobile to Wi-Fi, network loss/return, airplane mode and captive portal tests.
- [ ] Start/stop/restart, rapid switching, cancellation, process death, force-stop and reboot tests.
- [ ] Malformed profiles, invalid keys, oversized input, provider refusal, timeout, rate limit and corrupted response tests.
- [ ] Battery, memory, CPU, thermal, sustained traffic and long-duration stability tests.
- [ ] Security tests: secret redaction, storage, TLS/trust, downgrade/replay, permissions and dependency vulnerabilities.
- [ ] Regression suite after every bug fix and release candidate.
- [ ] Record exact pass/fail, skipped reason, environment and evidence; never count skipped as passed.

## K. Defect closure and release gates
- [ ] Central defect register with severity, reproduction steps, owner, fix commit and regression test.
- [ ] Resolve all Critical/High defects; no known data leak, key exposure, TUN ownership race or crash.
- [ ] Re-run full relevant suite after fixes; no unexplained flaky or skipped critical tests.
- [ ] Permanent owner-approved signing identity securely backed up; no key material in repository.
- [ ] Verify release signature, package identity, versioning, artifact hashes and reproducible build inputs.
- [ ] Verify install, upgrade, rollback and uninstall behavior on supported devices.
- [ ] Publish release notes, privacy disclosure, permissions, limitations, known issues and recovery guide.
- [ ] Stable Production only after all required gates pass; keep PR Draft and release blocked beforehand.
- [ ] Archive test evidence and final delivery report tied to exact commit and APK hash.

## L. Current known baseline at checklist creation
- [ ] Re-check live CI run 44771401137 before relying on its result; at last inspection Android Keystore emulator job was still booting.
- [ ] Last inspected PR #4 head: 91008dc884e3cbee3d9d6318df94e537f0b603e7; PR was open and Draft.
- [ ] Last inspected source inventory: WireGuard is the only registered adapter; the other five engine slots are not implemented.
- [ ] Last inspected WARP enrollment document is a plan, not an enrollment client or provisioned tunnel.
- [ ] Physical Android device and real authorized endpoint acceptance remain outstanding.