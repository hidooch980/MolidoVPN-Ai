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
| Network Intelligence | Source Implemented | Passive default-network observation, timestamped assessment, stale/lost-network rejection |
| Adaptive Path & Policy | Partially Source Implemented | Controller blocks start unless current measured network meets policy |
| Security & Privacy | Partially Source Implemented | Redaction, HTTPS-only bounded client, Android Keystore encrypted trust material; full security validation pending |
| Resilience & Recovery | Partially Source Implemented | Bounded backoff; only FAILED state can retry; pending cleanup prevents replacement; no scheduler |
| Diagnostics & Observability | Partially Source Implemented | Disabled-by-default bounded local ring buffer |
| Control Plane & Configuration | Partially Source Implemented | Self-hosted FastAPI source, ECDSA config signatures, Android HTTPS client; server not deployed |
| Update & Integrity | Partially Source Implemented | Signed manifest/digest verification and encrypted local counters; installer and end-to-end rollback not implemented |

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
- Update artifact verification checks ECDSA signature, expiry, monotonic version and SHA-256 digest. Installed version advances only after a future installer reports success; that installer integration is not present.
- Local encrypted counters do not claim protection against rooted devices or reinstall-based state reset.
- No backend endpoint, token, signing key or public key is configured in this repository.

## Latest CI evidence

- [Android CI #104](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/44558369381), source commit `4afe8436b29d9e457ccdb7a7bcca190ac6a9513e`: completed successfully. Backend tests, Android unit tests, Android lint and release-variant assembly all passed.
- The backend audit also made signed configuration revision allocation transactional with SQLite `BEGIN IMMEDIATE` and added a regression test proving each successive publish receives a strictly increasing revision.
- CI success is source/build evidence only. It is not Android Device Tested, Server Deployment Verified, or Production Verified.

## Remaining release blockers

- Physical Android tests, authorized WireGuard server, DNS/IPv4/IPv6/leak tests.
- Production backend deployment, server hardening and backup/restore evidence.
- Owner-approved permanent signing identity.
- Downloader, PackageInstaller integration, rollback and recovery verification.
- Five unimplemented engines.
- Automatic reconnect/process-death recovery.
- Full threat-model validation, dependency vulnerability scanning and penetration testing.
- Stable Production release gates.

No real-world behavior is inferred from source presence or CI.
