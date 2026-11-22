# MolidoVPN AI — Remaining Work Audit and Execution Plan

Audit basis: source reviewed through commit `169fa6ffd9deab0070c19f8ba61b5eb1e3f511ba`, master acceptance checklist, architecture decisions and CI runs #217–#221 as observed on 2026-11-21.
Audit date: 2026-11-21.
This is a verified work register, not a claim that blocked features are implemented.

## 1. Verified progress

- PR #4 remains Draft, open and unmerged.
- CI run #220 succeeded on commit `4019b4f2666d1b870bb9b397f3dc18be030cf333` (NOT the current head):
  - Android source validation: unit tests, lint and release assembly succeeded.
  - Backend: 13 tests passed.
  - API 35 x86_64 emulator: 15 instrumentation tests started and finished successfully.
  - Signed-release job skipped because the run was for a pull request.
- The KVM permission gate was added in `4019b4f`, then accidentally reverted by a stale-tree documentation commit `4206558`. It was restored and corrected in `169fa6f` (KVM access check, `-accel on`, valid multiline diagnostics). A completed CI run on the restored workflow is still pending; do not claim current-head CI green.
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
Latest completed CI #220 (commit `4019b4f`): PASS for source validation, 13 backend tests and 15 emulator instrumentation tests. This is historical evidence for that SHA, not a pass on latest head `169fa6f`. Run #221 was in progress on intermediate SHA `4206558`; a completed run for `169fa6f` is required.
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


## 6. Latest verified status — 2026-11-22

Audit update basis: branch `feature/wireguard-runtime-diagnostics`, head commit `61d707c673d29fe9933520eaed65705ff55a09ee`, CI run #355 (`45004213465`).

- CI #355 completed with overall conclusion **success** on the current branch head.
- Android source validation: PASS — Gradle retry-classification tests, Android unit tests, Android lint, and release-variant assembly all completed successfully.
- Android Keystore instrumentation on API 35 emulator: PASS — emulator boot, hardware-acceleration/KVM gate, and connected instrumentation task completed successfully.
- Self-hosted control-plane tests: PASS — bug-fix-agent classification tests and backend pytest suite completed successfully.
- Pinned WireGuard Go-only source build: PASS.
- Signed-release verification: SKIPPED because this was a pull-request event. No signed production APK or certificate fingerprint was produced by this run.
- Autonomous CI bug-fix agent: SKIPPED; this workflow job is not evidence of autonomous source-code repair.
- The preceding CI #353 failed on an invalid Kotlin `const val IntRange` declaration. Commit `61d707c` changes it to a runtime `private val`; CI #355 confirms the corrected head passes the configured checks.

### Current implementation boundary

- The only registered runtime engine remains WireGuard.
- WireGuard still uses the upstream merged `GoBackend.VpnService`; the app-owned central `MolidoVpnService`, descriptor broker, authenticated engine IPC, and service-owned TUN lifecycle are **not implemented**.
- The exact pinned upstream Go entry point accepts an externally supplied TUN FD, but the published AAR's public Android backend API does not expose it. A pinned source-built wrapper and its failure-path audit are required; do not substitute reflection/private AAR hooks.
- Existing coordinator unit tests cover contention, generation tokens, wrong PID, process death, cleanup acknowledgement and fail-closed lease retention. They do not prove real Binder FD transfer or kernel descriptor lifecycle.
- PR #4 remains open, Draft and unmerged. Do not merge or release while P0/P1/P2 and physical-device acceptance gates remain open.

### Next execution order

1. Complete the pinned WireGuard source-wrapper design and reviewed patch set, including nullable/idempotent native cleanup and socket-protection failure paths.
2. Implement the app-owned central VPN service and authenticated, versioned Binder protocol with generation/session/UID/PID checks.
3. Add real descriptor-handoff instrumentation, exact-once close, cancellation, partial-start, Binder death, stale-message and concurrent start/stop tests.
4. Migrate WireGuard atomically to the source-built backend; assert the merged manifest contains exactly one app-owned VPN service.
5. Repeat all CI checks and then run authorized physical-device data-plane, DNS, IPv4/IPv6, leak and network-handover acceptance.
6. Complete SBOM/license/dependency/native ABI review and signed artifact verification on the approved base-branch workflow.

The CI pass raises confidence in the current source and emulator checks; it does not change the architecture or production release gates above.


## 7. Superseding status — 2026-11-22 09:46 UTC

This section supersedes the earlier section 6 where its head/CI claims differ.

- Latest PR head observed: `492a99486100674796aa9a30d1d04ec99c50c23e`.
- Commit `492a994` only changes `docs/REMAINING_WORK_AUDIT.md` (+33 lines); it does not change Android source or workflow code.
- The last completed CI run visible for this branch is run #357, ID `45009130042`, associated with commit `10925259a6bb74639a314aa0ca48efa7465ac232`; conclusion: **cancelled**.
- GitHub reports zero jobs for run #357. Therefore no test failure is evidenced by that run, but it also provides no passing validation for commit `10925259a` or the later head `492a994`.
- The prior successful run #355 / ID `45004213465` passed on commit `61d707c673d29fe9933520eaed65705ff55a09ee` only. It must not be presented as CI success for later documentation commits.
- No evidence currently proves signed-release verification, physical Android device acceptance, real WireGuard server/data-plane acceptance, Binder descriptor handoff, or central app-owned TUN ownership.
- The Android implementation still uses the published `com.wireguard.android:tunnel:1.0.20260102` dependency and `GoBackend`; the app manifest explicitly documents that the AAR contributes `GoBackend.VpnService`. The custom source backend and central VPN service remain NOT IMPLEMENTED.
- PR #4 remains open, Draft, and unmerged. Do not merge or publish based on the historical CI pass.

### Next actions in strict priority

1. Re-run a complete CI workflow for the exact current PR head; investigate why the preceding PR run was cancelled before any job was created. Do not mark current-head CI green until a completed run is observed.
2. Start P0 source-backend work from the pinned, verified upstream WireGuard source. Implement in a small reviewable patch series; preserve notices and license inventory; do not remove the AAR until a tested replacement is ready.
3. Implement central `VpnService` and authenticated Binder `ParcelFileDescriptor` handoff only after the custom backend accepts an externally owned descriptor and socket-protection callback.
4. Add instrumentation for actual FD duplication/closure, wrong UID, stale generation, Binder death, partial startup, cancellation and competing engines.
5. Migrate WireGuard atomically, assert exactly one merged VPN service, and rerun full CI.
6. Only then perform authorized physical-device/server data-plane, DNS, IPv4/IPv6, leak, handover and sustained-stability acceptance.

No root cause is assigned to the cancellation because GitHub returned no jobs or logs for run #357. Do not speculate that it was caused by a source-code defect.


## 7. Verified status update — 2026-11-22, CI #373

Audit basis: branch `feature/wireguard-runtime-diagnostics`, commit `106858dde98441b0413cf08e97b05853f333ac51`, GitHub Actions run #373 (`45010985231`).

### CI evidence on this exact commit

- Overall workflow conclusion: **success**.
- Android source validation: **success**. The workflow completed the Gradle retry-classification tests, `:android:app:testDebugUnitTest`, Android lint, and `:android:app:assembleRelease`. Release assembly here is CI validation only; it is not a distributed or signed production release.
- Android Keystore instrumentation: **success**. API 35 emulator booted with hardware acceleration and all 24 connected instrumentation tests completed successfully.
- Pinned WireGuard Go-only source build: **success**.
- Self-hosted control-plane tests and CI retry-classification tests: **success**.
- Signed Android release verification: **skipped** on the pull-request event. No production signing or certificate-fingerprint proof is implied.
- Autonomous CI bug-fix agent: **skipped**; classification tests do not prove autonomous source repair.
- Earlier CI #366 on an intermediate commit failed 5 of 120 unit tests. Those failures exposed lifecycle races in STOPPING, cleanup retry, and late engine-failure callbacks. Follow-up commits were added, and CI #373 on the final head passed the configured source, lint, release-assembly, backend, native-build and emulator checks.

### Lifecycle fixes since the prior audit

- Process death after descriptor handoff no longer lets the session supervisor manufacture service-close acknowledgement.
- Startup rollback refuses to release a descriptor-handoff lease while cleanup is uncertain; the central service must independently close its retained descriptor.
- A service cleanup completed before the session callback is accepted only for the exact same generation.
- A failed stop remains retryable; asynchronous engine-failure callbacks during STOPPING are ignored by the controller.
- Repeated stop requests for the same generation are covered as an idempotency case.
- Regression coverage was added for process death during startup/running, delayed failure callbacks, retry after failed stop, and service-close ordering.

### Architecture boundary remains unchanged

The successful CI does **not** mean the central VPN architecture is complete. Source review at this commit confirms:

- `WireGuardEngineAdapter` still creates upstream `GoBackend(context.applicationContext)`; upstream `GoBackend.VpnService` remains the TUN owner.
- `NativeEngineProcessService` is a status-only `NOT_IMPLEMENTED` scaffold. It does not accept a descriptor, authenticate engine commands, or run packet I/O.
- The manifest still relies on the AAR-merged upstream `GoBackend.VpnService`; there is no app-owned `MolidoVpnService` yet.
- The in-process `TunOwnershipCoordinator` is a lifecycle guard, not a real FD broker or cross-process authority.
- Only WireGuard is registered. The other five approved engine slots remain unavailable/blocked as recorded above.
- Physical-device data-plane acceptance (authorized endpoint, bidirectional traffic, IPv4/IPv6, DNS, leak checks, network handover and sustained stability) is not performed by this CI run.
- Signed-release verification remains skipped; no production APK is approved.

### Next execution order after CI #373

1. Pin and audit official WireGuard Android source provenance, submodules, licenses and build inputs.
2. Implement a source-built backend that consumes the central service's TUN descriptor through a documented API; do not use reflection or private AAR hooks.
3. Add the app-owned `MolidoVpnService` and ensure it is the only `VpnService` and only caller of `Builder.establish()`.
4. Add authenticated/versioned Binder protocol with UID, PID, engine slot, session, generation and one-time capability checks.
5. Add real descriptor handoff/close instrumentation and manifest assertion; exercise cancellation, partial start, wrong identity, stale generation, Binder death and simultaneous start/stop.
6. Migrate WireGuard atomically to the source-built backend, then repeat all CI checks.
7. Only after the above, run authorized physical-device data-plane and leak acceptance.

PR #4 remains open, Draft and unmerged. No release, signing-key rotation, external infrastructure, paid service or production deployment is authorized by this audit.
