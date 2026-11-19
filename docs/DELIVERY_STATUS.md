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
| Network Intelligence | Source Implemented | Passive default-network observation; background observation while process is alive; stale/lost-network handling |
| Adaptive Path & Policy | Partially Source Implemented | Fresh Android-observed connectivity gates start; no multi-engine ranking or handover |
| Security & Privacy | Partially Source Implemented | In-memory profile flow, redaction, HTTPS-only client, Android Keystore-protected trust material; full threat-model validation pending |
| Resilience & Recovery | Partially Source Implemented | Bounded exponential planner; retries only from FAILED; pending cleanup blocks replacement; no scheduler/process-death recovery |
| Diagnostics & Observability | Partially Source Implemented | Bounded, disabled-by-default, process-local ring buffer; no telemetry or persistent logs |
| Control Plane & Configuration | Partially Source Implemented; Server Deployment Required | FastAPI/SQLite enrollment, revocation and ECDSA-signed config source; Android verifies signed config; server not deployed |
| Update & Integrity | Partially Source Implemented | ECDSA manifest and SHA-256 verification; downloader, installer, successful-install callback and rollback UX not integrated |

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

Latest fully successful CI run: [Android CI #104](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/44558369381), source commit `4afe8436b29d9e457ccdb7a7bcca190ac6a9513e`. Backend tests, Android unit tests, Android lint and release-variant assembly all passed. This run includes the SQLite private-directory/symlink hardening and the new atomic configuration-revision allocation using `BEGIN IMMEDIATE`, with a monotonic revision regression test. The later documentation-only commits do not alter the tested source. CI success proves only the checks actually executed; it does not prove VPN handshake, packet routing, DNS, leak prevention or production readiness.

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
- Recovery computes bounded plans only; it does not automatically restart a tunnel.
- Diagnostics are disabled by default, process-local and not uploaded.
- Five engines are not implemented.
- No permanent signing key was generated or changed.
- Dependency vulnerability scanning, full penetration testing and physical-device testing are not yet evidenced. Database directory/symlink hardening and atomic configuration-revision allocation have passed CI #104, but they are not a substitute for production host validation.

## Stable Production release gates

Stable Production remains blocked until all relevant tests and approvals above pass. CI release-variant assembly is not a signed owner release. No Debug, Alpha, Beta, Preview or Test APK is distributed.
