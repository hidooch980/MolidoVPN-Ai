# MolidoVPN AI — Remaining Work Audit and Execution Plan

Audit basis: PR #4 head `4019b4f2666d1b870bb9b397f3dc18be030cf333`, repository source, master acceptance checklist, architecture decisions and Android CI run #220.
Audit date: 2026-11-21.
This is a verified work register, not a claim that blocked features are implemented.

## 1. Verified progress

- PR #4 remains Draft, open and unmerged.
- CI run #220 on the current head succeeded:
  - Android source validation: unit tests, lint and release assembly succeeded.
  - Backend: 13 tests passed.
  - API 35 x86_64 emulator: 15 instrumentation tests started and finished successfully.
  - Signed-release job skipped because the run was for a pull request.
- The CI KVM permission problem seen in run #219 is addressed by the new explicit KVM permission check and hardware-acceleration requirement.
- Runtime catalog contains exactly six engine slots and eight platform cores. Only WireGuard is registered.
- The native engine process test proves separate process PIDs and NOT_IMPLEMENTED status; it does not prove engine startup, FD transfer or TUN ownership.
- MasterDnsVPN remains NO-GO / research-only and must not be integrated or counted as an additional engine.

## 2. Critical path — must be completed in this order

### P0 — WireGuard provenance and custom backend
Status: NOT IMPLEMENTED.
1. Verify the official upstream WireGuard Android tag/commit, annotated-tag PGP signature, submodule SHAs, dependency provenance and complete license/NOTICE set.
2. Create a pinned source-based local backend from the reviewed official source. Preserve upstream notices and record every Molido patch.
3. Remove dependence on the published AAR only in the same atomic change that supplies the tested source replacement.
4. Refactor the Java/JNI/native boundary so native WireGuard accepts a TUN FD supplied by the central service and uses central-service socket protection.
5. Keep upstream parsing and protocol behavior; do not use reflection, private JNI hooks or an undocumented API.
6. Build and test the source backend reproducibly across supported Android ABIs.

Acceptance: source provenance, signature, licenses, build hashes and native ABI are recorded; no old backend service remains in the merged manifest.

### P1 — One authoritative Android TUN owner
Status: NOT IMPLEMENTED; architecture blocker.
1. Implement app-owned `MolidoVpnService` as the only Android `VpnService` and only caller of `VpnService.Builder.establish()`.
2. Remove the AAR-merged `GoBackend.VpnService` only after the custom backend no longer depends on it.
3. Make the service/coordinator the sole authority for TUN ownership; remove/reduce duplicate authoritative ownership ledgers in `SessionSupervisor` and `TunOwnershipCoordinator`.
4. Define immutable `EngineStartContext`, generation-scoped lease, cancellation, state transitions and exact ownership of original and duplicated descriptors.
5. Implement authenticated, versioned Binder IPC: caller UID, expected engine slot, session ID, generation, protocol version, one-time token and process identity.
6. Transfer descriptors only with `ParcelFileDescriptor`; never pass raw integer FDs.
7. Enforce shutdown ordering: stop packet loop -> engine closes duplicate -> matching cleanup acknowledgement -> central service closes original FD -> exact lease release.
8. Treat Binder death/process death as cleanup-required, not as successful acknowledgement. Timeouts retain ownership and block engine switching.
9. Make start/stop/cancel idempotent and safe under simultaneous requests, stale messages and partial startup.

Acceptance: merged-manifest assertion finds exactly one VPN service (the central service); instrumentation proves real FD handoff, descriptor closure, wrong UID/PID rejection, stale-generation rejection, Binder death recovery and no FD leaks.

### P2 — WireGuard runtime and data-plane acceptance
Status: source adapter CI-tested; real device NOT TESTED.
1. Validate profile parser bounds and reject malformed key, peer, endpoint, route and DNS input.
2. Confirm Android VPN permission and foreground service lifecycle.
3. Verify fresh handshake and increasing RX/TX in both directions.
4. Verify actual routed traffic independently; backend UP/handshake/counters alone are not proof of internet.
5. Verify IPv4, IPv6, DNS, MTU, keepalive, roaming and route policy.
6. Test kill-switch/fail-closed behavior and no direct traffic leakage.
7. Test Wi-Fi/mobile transitions, lost network, captive portal and explicit recovery.
8. Test at least repeated connect/disconnect cycles, force-stop, app relaunch, service recreation, crash and cleanup.
9. Record device model/Android version, exact commit, APK hash and redacted evidence.

Acceptance: authorized endpoint, physical Android device, real bidirectional traffic, route/DNS/leak checks and cleanup all pass. No fabricated endpoint or network measurements.

### P3 — Six engines (exact inventory; one at a time)
Status: only WireGuard adapter source exists.
- **WireGuard:** complete P0–P2 first.
- **AmneziaWG:** pin official Android source, recursive submodules, Go/CMake/NDK toolchain and license inventory; reproducible native build; isolated runtime; shared FD lifecycle; protocol/data-plane tests.
- **V2Ray/Xray:** pin official libXray API v3 and dependencies; isolate its Go runtime in its own process; implement validated config/API, IPC, central FD and stop/cleanup; test supported transports and DNS.
- **Psiphon:** remain BLOCKED until GPL-3.0 distribution compliance or written commercial exception is approved. Then review official Mobile Library, source-offer obligations, dependency notices and lifecycle.
- **Tor:** review official daemon/library and license; implement daemon/SOCKS lifecycle plus a reviewed TUN-to-SOCKS bridge; document TCP/UDP/QUIC limitations, DNS and circuit readiness; no second VpnService.
- **MASQUE/WARP-compatible:** remain BLOCKED until a supported public Android SDK/API and terms for the exact consumer use case are documented. Do not reverse-engineer private endpoints or conflate consumer WARP, Zero Trust, MASQUE and WireGuard enrollment.

Admission rule: one engine at a time, only after the previous engine's stop/cleanup has been proven under the same central TUN contract. No seventh engine.

### P4 — Eight platform cores

1. **Session & TUN Supervisor:** consolidate ownership authority; full state machine; serialization; cleanup and engine switching tests.
2. **Network Intelligence:** real Android callback tests, timestamp freshness, lost-network handling, Wi-Fi/mobile/captive portal behavior; never fabricate signal.
3. **Adaptive Path & Policy:** Smart selection only from measured and fresh data; explainable policy; Gaming/Streaming/restriction profiles remain unavailable until meaningful measurements and engine paths exist.
4. **Security & Privacy:** threat model, secure profile/key lifecycle, IPC auth, memory/log redaction, least privilege, dependency/SBOM/license scans, vulnerability review and leak tests.
5. **Resilience & Recovery:** bounded backoff, safe explicit retry, Binder/process death recovery, stale session cleanup, network handover; do not schedule reconnect until cleanup is confirmed.
6. **Diagnostics & Observability:** local redacted diagnostics, event correlation by session/generation, no secrets, telemetry disabled by default; distinguish backend state from data-plane health.
7. **Control Plane & Configuration:** keep self-hosted backend source; configuration schema/versioning, signature verification, device revocation, RBAC/audit, backup/restore and deployment docs. Actual server deployment is separate and not required to perform source/CI work.
8. **Update & Integrity:** signed metadata, trusted key lifecycle, hash/signature verification, downgrade/replay prevention, download transport, PackageInstaller install result, rollback and recovery tests. No release without owner-approved signing identity.

### P5 — Product/UI acceptance
Status: source UI and CI compile/lint tested; physical interaction not tested.
- Keep dark/teal dashboard and no-account decision.
- Display real session state and actual network observations only.
- Only registered/validated engines can be selected.
- Country/server lists must come from a trusted, real provider inventory; currently none are available.
- Smart, Single, Country, Protocol modes must map to real supported behavior; unavailable choices remain disabled with reason.
- Gaming Plus, Streaming/Download, anti-interference, dual-hop, Double WARP, HTTP/3 masking and Shard are policies/transport options, not extra engine slots. Each requires exact protocol definition, license review, architecture fit, real metrics and acceptance tests before activation.
- Check accessibility, Persian/RTL, screen sizes, touch targets, loading/error states, permission prompts and battery/foreground notifications on a real phone.

### P6 — CI, supply chain and release
Current CI #220: PASS for source validation, backend tests and 15 emulator instrumentation tests.
Remaining:
- Add merged-manifest assertion for exactly one VpnService.
- Add dependency locking/provenance, SBOM, license and vulnerability checks for all native artifacts.
- Add tests for every central TUN/IPC failure path.
- Resolve current Kotlin/Android deprecation and unchecked-cast warnings.
- Review GitHub Actions Node 20 deprecation and pin audited action versions/SHAs.
- Verify native library packaging/ABI and stripping expectations.
- Run signed-release workflow only on approved base-branch event with existing owner-approved key; verify APK signature and certificate SHA-256 matches keystore.
- Verify APK SHA-256, artifact provenance and update manifest. No Stable APK from PR CI.

## 3. Engine and platform feature ideas — decision gates

- **Accountless provider enrollment / WARP-style enrollment:** only lawful, documented and reproducible public flow; user consent, encrypted credential lifecycle, revocation, rate-limit handling and no hidden account bypass.
- **Smart mode:** deterministic first; measured and fresh inputs only; clear reason for selected path.
- **Country/server selection:** disabled until real provider inventory exists. Never manufacture countries, server names, IP, ping or load.
- **Gaming / Streaming / Download:** policy profiles based on measured latency, jitter, loss and throughput; do not promise optimization without measurement.
- **Anti-interference:** use only supported transports and disclose that total loss of all usable routes cannot be bypassed by an app.
- **Dual-hop / Double WARP:** only with two supported, independently verified hops, explicit route/DNS topology, loop prevention and failure tests. Otherwise BLOCKED.
- **HTTP/3 masking:** define exact protocol and upstream implementation; obfuscation is not encryption and stealth claims require tests.
- **Shard:** identify exact project/protocol and license before considering it; never add another engine slot.
- **MasterDnsVPN:** NO-GO / research-only. Do not prototype or integrate unless every separate remediation gate is completed and the architecture board explicitly accepts it within an existing slot.

## 4. CI warnings and known defects to clear

- Deprecated `statusBarColor`, `navigationBarColor`, `systemUiVisibility` API use in MainActivity.
- Unchecked cast from SpinnerAdapter to ArrayAdapter.
- Native WireGuard .so files could not be stripped by current Android toolchain; confirm expected symbols/packaging and ABI.
- GitHub Actions actions using Node 20 are under deprecation transition to Node 24.
- CI #217 had an instrumentation process crash with 0/0 tests; latest run #220 passed 15 tests. Keep #217 in historical evidence; do not claim its root cause is known without diagnostics.

## 5. Release gates — all required

Stable Production remains blocked until:
1. One central TUN service is the sole VPN owner.
2. WireGuard source backend and descriptor lifecycle are integrated and tested.
3. All required unit, integration and emulator tests pass on the latest commit.
4. Physical-device authorized endpoint tests prove data plane, DNS, routes, leaks and cleanup.
5. Security, license, dependency and update integrity gates pass.
6. Owner-approved permanent signing key and APK fingerprint are verified.
7. The release artifact is reproducible, signed, verified and explicitly approved.

Do not merge PR #4 or publish any APK before all gates pass. No VPS, dedicated server, PC, paid service or unapproved infrastructure is assumed for the current GitHub-only source/CI work.
