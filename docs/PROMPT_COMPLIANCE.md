# Master Prompt Compliance Audit

Repository: hidooch980/MolidoVPN-Ai
Branch: development/molido-vpn-ai-from-scratch

This audit distinguishes source implementation, CI evidence, physical-device tests, server deployment and production verification. GitHub Actions is the live source of truth for commit-specific CI results; cancelled and in-progress runs are never counted as successful.

## Immutable architecture

- Exactly six approved engine slots and eight complementary cores.
- One TUN owner at a time; WireGuard upstream GoBackend owns the Android VPN service.
- WireGuard is the first engine. Five other engine slots remain inventory-only.
- Self-hosted architecture; GitHub Actions is CI/CD, not VPN infrastructure.
- No fabricated endpoint, network measurement, server status, handshake, traffic, or test result.
- No infrastructure or permanent signing identity is provisioned without owner approval.
- Stable Production only; no experimental APK distribution.

## Core status

| Core | Status | Evidence / boundary |
|---|---|---|
| Session & TUN Supervisor | Source Implemented | Exclusive controller and cleanup retry tests |
| Network Intelligence | Source Implemented | Passive default-network observation, timestamped assessment, stale/lost-network rejection; simulated controller tests cover offline/stale/validated transitions, physical Android callback behavior pending |
| Adaptive Path & Policy | Partially Source Implemented | Controller blocks start unless current measured network meets policy |
| Security & Privacy | Partially Source Implemented | Redaction, HTTPS-only bounded client, Android Keystore encrypted trust material; full security validation pending |
| Resilience & Recovery | Partially Source Implemented | Bounded backoff, explicit active-engine failure transition and safe cleanup preparation; failed cleanup retains TUN ownership and blocks replacement. No scheduler, concrete adapter failure callback or process-death recovery |
| Diagnostics & Observability | Partially Source Implemented | Disabled-by-default bounded local ring buffer |
| Control Plane & Configuration | Partially Source Implemented | Self-hosted FastAPI source, ECDSA config signatures, Android HTTPS client; server not deployed |
| Update & Integrity | Partially Source Implemented | Signed manifest/digest verification, encrypted rollback floor, PackageInstaller session staging, package identity/version checks, internal status callback, concurrent/duplicate/downgrade admission policy and PackageManager-confirmed success counter; download transport and device-level rollback validation pending |

## Engine status

| Slot | Status |
|---|---|
| WireGuard | Source Implemented; device/server validation pending |
| AmneziaWG | Not Implemented |
| V2Ray/Xray | Not Implemented |
| Psiphon | Not Implemented |
| Tor | Not Implemented |
| MASQUE/WARP-compatible | Not Implemented |

Do not add a seventh engine. Each remaining engine needs license, dependency, security and one-TUN compatibility review.

## Corrective audit changes

- Added `UpdateInstallAdmissionPolicy`, wired into `AndroidUpdateInstaller` before PackageInstaller session creation. JVM tests cover an existing pending session, equal/older version, invalid zero version and a strictly newer version.
- Added `VpnSessionController.reportActiveEngineFailure()` and `prepareRecovery()`. WireGuard's asynchronous unexpected DOWN transition now reports into the controller; intentional stop and late/duplicate notifications are ignored. Startup-racing failures are buffered until the session handle is retained, and cleared if startup rollback completes. The retry plan is calculated from the failed state; previous session cleanup must succeed before ownership is released. A failed cleanup preserves the owner and prevents another engine from starting.
- Added simulated controller tests for cleanup-before-retry, retained ownership after cleanup failure, waiting for validated connectivity, and rejecting unvalidated/stale network observations. This does not prove Android ConnectivityManager callbacks or real network handover.

- Backoff test corrected to test the configured retry ceiling without weakening production retry limits.
- Recovery planner now refuses retries for IDLE, RESERVED, RUNNING and STOPPING states.
- Session controller refuses recovery while an earlier session still owns resources awaiting cleanup.
- Android network observation moved from Activity lifecycle to Application lifecycle and clears a lost default-network observation.
- Backend readiness now rejects admin tokens shorter than 32 characters, matching protected admin operations.
- POSIX SQLite database file permissions are restricted to owner read/write; database connections fail closed unless the parent directory is service-owned and mode 0700, final-component symlinks are rejected where O_NOFOLLOW exists, and the database path is verified as a regular service-owned file. WAL/SHM are protected by the directory boundary. CI #104 validates the filesystem hardening and monotonic revision transaction tests.
- Added repository ignore protections for local environment files, private keys, keystores, databases and build outputs.

## Backend and client security boundaries

- Backend uses admin bearer authentication, per-device random bearer tokens stored as SHA-256 hashes, SQLite persistence, device revocation, expiring signed configuration and audit events.
- Android client requires HTTPS, rejects redirects, caps response size and verifies ECDSA signatures before advancing the local configuration revision.
- Trust public keys are pinned once and encrypted at rest with Android Keystore AES-GCM. Initial key pinning must be provisioned out of band by the owner.
- Update artifact verification checks ECDSA signature, expiry, monotonic version and SHA-256 digest. The Android client stages verified APKs with PackageInstaller, checks package identity and signed versionCode, forwards pending user confirmation, and advances the encrypted installed-version counter only after PackageInstaller STATUS_SUCCESS plus an exact PackageManager version match. Android enforces signing continuity with the installed package; duplicate success callbacks are idempotent.
- Local encrypted counters do not claim protection against rooted devices or reinstall-based state reset.
- No backend endpoint, token, signing key or public key is configured in this repository.

## Latest CI evidence

- [Android CI #106](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/44563299701), source commit `aef9eb3874dc350144be5aab9d4c20cd129a679b`: completed successfully for the previous source.
- [Android CI #108](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/44565006192), source commit `81cfd105515fef55c070f9419c657b17ff1b9e0a`: completed successfully; backend tests, Android unit tests, lint and release assembly passed. This does not include the later WireGuard callback wiring.
- WireGuard failure callback wiring and startup-race coverage are in source commit `6d14ef5667074fd05cd3f9d93c4767ab60897422`; CI must be confirmed for this source before it is labelled CI Tested.
- The backend audit also made signed configuration revision allocation transactional with SQLite `BEGIN IMMEDIATE` and added a regression test proving each successive publish receives a strictly increasing revision.
- CI success is source/build evidence only. It is not Android Device Tested, Server Deployment Verified, or Production Verified.

## Remaining release blockers

- Physical Android tests, authorized WireGuard server, DNS/IPv4/IPv6/leak tests.
- Production backend deployment, server hardening and backup/restore evidence.
- Owner-approved permanent signing identity.
- Production update download transport, physical PackageInstaller confirmation/install test, interrupted-install recovery and rollback verification.
- Five unimplemented engines.
- Automatic retry scheduling, process-death recovery and device-level validation of asynchronous WireGuard state callbacks.
- Full threat-model validation, dependency vulnerability scanning and penetration testing.
- Stable Production release gates.

No real-world behavior is inferred from source presence or CI.
