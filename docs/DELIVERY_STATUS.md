# Delivery Status

Branch: development/molido-vpn-ai-from-scratch

## Release decision

**Stable Production: BLOCKED.** Repository work cannot substitute for an authorized VPN endpoint, hands-on Android device validation, owner-approved permanent signing identity, or operator-approved server deployment. No APK is distributed.

## Verified source implementation

| Area | Status | Boundary |
|---|---|---|
| Six-engine / eight-core architecture | Source Implemented | Inventory guarded by unit tests; inventory does not imply engine implementation |
| Session & TUN Supervisor | Source Implemented | Exclusive owner, lifecycle state machine, cleanup retry |
| WireGuard | Source Implemented | Upstream Android GoBackend adapter; no device/server data-plane proof |
| Network Intelligence | Source Implemented | Passive default-network observation; background observation while process is alive; stale/lost-network handling. Unit tests cover UNKNOWN/NOT_VALIDATED/STALE/VALIDATED assessments and controller preflight; Android callback behavior still requires device validation |
| Adaptive Path & Policy | Partially Source Implemented | Fresh Android-observed connectivity gates start; no multi-engine ranking or handover |
| Security & Privacy | Partially Source Implemented | In-memory profile flow, redaction, HTTPS-only client, Android Keystore-protected trust material; full threat-model validation pending |
| Resilience & Recovery | Partially Source Implemented | Bounded exponential planner; explicit active-engine failure transition; `prepareRecovery` computes a bounded plan and releases the prior owner only after cleanup succeeds; cleanup failure retains exclusive ownership. No automatic scheduler, adapter failure callback wiring, or process-death recovery |
| Diagnostics & Observability | Partially Source Implemented | Bounded, disabled-by-default, process-local ring buffer; no telemetry or persistent logs |
| Control Plane & Configuration | Partially Source Implemented; Server Deployment Required | FastAPI/SQLite enrollment, revocation and ECDSA-signed config source; Android verifies signed config; server not deployed |
| Update & Integrity | Partially Source Implemented | ECDSA manifest and SHA-256 verification; PackageInstaller session staging, internal status receiver, user-confirmation forwarding, package identity/version checks, duplicate/concurrent/downgrade admission policy, and PackageManager-confirmed monotonic success callback. Download transport and physical install/rollback validation remain pending |

## Six-engine matrix

| Engine slot | Status |
|---|---|
| WireGuard | Source Implemented; physical data-plane validation pending |
| AmneziaWG | Not Implemented |
| V2Ray/Xray | Not Implemented |
| Psiphon | Not Implemented |
| Tor | Not Implemented |
| MASQUE/WARP-compatible | Not Implemented |

No seventh engine is allowed. The five remaining engines require individual upstream license, dependency, native-code, privacy, routing and single-TUN compatibility reviews before implementation.

## Recent corrective audit

- Added pure update-install admission policy and tests for concurrent pending sessions, duplicate version, downgrade, invalid version and strictly newer candidates; the Android installer now applies the policy before creating a PackageInstaller session.
- Added `reportActiveEngineFailure()` and `prepareRecovery()`: a caller can report a failed active session, obtain a bounded retry/wait/stop plan, and clean up the prior engine before any replacement. A failed cleanup retains the previous owner and blocks all other engines. No automatic retry scheduler or concrete adapter failure callback is wired yet.
- Added controller tests for exclusive recovery, cleanup failure/retry, network-unavailable waiting, and start rejection for unvalidated or stale observations. Network state transition tests are simulated JVM tests, not Android-device callback tests.

- Backoff test now respects the configured retry ceiling.
- Retry planning is restricted to FAILED sessions.
- Controller blocks recovery while previous session cleanup is pending.
- Network observation remains active while the app process is alive, including when the Activity is backgrounded.
- Loss of the observed default network clears the observation instead of retaining stale network facts.
- Backend readiness enforces the same 32-character admin-token minimum as protected admin operations.\n- Configuration revision allocation now takes a SQLite `BEGIN IMMEDIATE` write lock before reading the previous revision, preventing concurrent publishers from issuing the same next revision; a regression test verifies monotonic successive publishes.
- SQLite database files are restricted to POSIX owner-only permissions; the service now fails readiness closed unless the database parent directory is owned by the service account and exact mode 0700 (not merely absence of group/world access), rejects unsafe final-component symlinks where O_NOFOLLOW is available, and verifies the database is a regular service-owned file. WAL/SHM sidecars inherit the private directory boundary.
- Added repository ignores for local secrets, databases, keystores and build outputs.

## CI evidence

GitHub Actions is the live source of truth for the latest commit and workflow results: https://github.com/hidooch980/MolidoVPN-Ai/actions

Latest fully successful CI run before this change: [Android CI #106](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/44563299701), source commit `aef9eb3874dc350144be5aab9d4c20cd129a679b`. The current source audit commit `81cfd105515fef55c070f9419c657b17ff1b9e0a` is being validated by [Android CI #108](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/44565006192); do not treat it as CI-tested until all jobs complete successfully. CI #106 validated the previous source, not the new recovery/admission tests. CI success proves only the checks actually executed; it does not prove VPN handshake, packet routing, DNS, leak prevention or production readiness.

## Real-world validation still required

1. Physical Android device and authorized WireGuard profile.
2. Reachable, operator-controlled WireGuard endpoint.
3. Verify permission, handshake, bidirectional traffic, DNS, IPv4/IPv6 routes, leak behavior, network changes, reconnect, process death and cleanup.
4. Deploy the backend only on explicitly approved self-hosted infrastructure, with TLS, firewall, protected admin token, private signing key, backups and restore test.
5. Owner-approved permanent APK signing identity and secure backup.
6. Verify update download, package installation, post-install version persistence and rollback/recovery on device.

## Security and operational limitations

- Android trust keys are pinned once and encrypted at rest using Android Keystore AES-GCM. Initial pinning must occur through a trusted owner-controlled channel; there is no trust-on-first-use over the network.
- Encrypted local counters provide app-level rollback detection but are not claimed to resist rooted-device tampering, app-data deletion or reinstall.
- The HTTPS client uses Android platform TLS validation, rejects non-HTTPS URLs and redirects, bounds response size and keeps device tokens in caller memory. No live server URL or token is configured.
- The backend requires operator-provisioned admin token and ECDSA private key outside the repository. No production server is running.
- Recovery is caller-driven: the controller exposes failure reporting and safe cleanup preparation, but no WorkManager scheduler, concrete engine failure callback, process-death recovery, or automatic reconnect is implemented.
- Android update installation now uses a full-install PackageInstaller session. The candidate must pass the pinned ECDSA manifest and SHA-256 check, match this app's package name and signed versionCode, and is handed to Android for signature-continuity enforcement. PackageInstaller status is received by a non-exported receiver; pending user confirmation is forwarded to the system installer UI. The encrypted installed-version counter advances only after STATUS_SUCCESS and an exact match with PackageManager's installed version; duplicate callbacks are idempotent.
- The update installer accepts a verified APK byte array; a production download transport/control-plane artifact delivery path is not yet integrated. Unit tests cover pending/concurrent admission and duplicate/downgrade rejection; device-level confirmation, interrupted-install recovery, and rollback behavior remain unverified.
- Diagnostics are disabled by default, process-local and not uploaded.
- Five engines are not implemented.
- No permanent signing key was generated or changed.
- Dependency vulnerability scanning, full penetration testing and physical-device testing are not yet evidenced. Database directory/symlink hardening and atomic configuration-revision allocation have passed CI #104, but they are not a substitute for production host validation.

## Stable Production release gates

Stable Production remains blocked until all relevant tests and approvals above pass. CI release-variant assembly is not a signed owner release. No Debug, Alpha, Beta, Preview or Test APK is distributed.
