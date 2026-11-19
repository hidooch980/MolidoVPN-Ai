# MolidoVPN AI Threat Model

## Scope and status

This is an initial source-level threat model for the Android client and self-hosted control-plane source. It is not a penetration-test report, certification, or proof of production security. No production server or physical-device test is currently evidenced.

## Assets

- WireGuard profile text, peer private keys and preshared keys while imported or used.
- Per-device bearer tokens and administrator bearer token.
- Control-plane and update-signing private keys held by the operator.
- Owner-pinned control-plane and update public keys on Android.
- Signed configuration payloads, revision counters and update metadata.
- SQLite device records, token hashes, configuration payloads and audit events.
- VPN session ownership state and network observations.
- Release artifacts and the owner-controlled Android signing identity.

## Trust boundaries

1. Android UI to local application/session controller.
2. Session controller to the single engine adapter and upstream WireGuard backend.
3. Android client to an operator-controlled HTTPS control plane.
4. Operator deployment boundary: host, filesystem, database, TLS termination, admin secret and signing key.
5. Build and dependency supply chain: GitHub Actions, Gradle/Maven dependencies and Python packages.
6. Owner release boundary: permanent APK signing identity and update trust key.

## Threats and current controls

| Threat | Current control | Residual risk |
|---|---|---|
| Profile/private-key leakage through app logs | Profile kept in memory; adapter does not log config; diagnostics redaction | Memory inspection/rooted device is not prevented |
| Concurrent or duplicate TUN ownership | SessionSupervisor, controller serialization, one upstream GoBackend service | Must be verified on physical devices and across process/service failure |
| Retry starts a second tunnel while old resources remain | Retry only from FAILED; controller blocks while cleanup session exists | No automatic recovery scheduler or process-death recovery |
| Stale network facts allow a connection | Timestamped assessment, default-network monitor, stale and lost-network clearing | OS callback behavior still needs physical-device validation |
| Weak administrator credential | Backend rejects admin tokens shorter than 32 characters; readiness fails closed | Entropy and secure provisioning remain operator responsibilities |
| Device-token database disclosure | Only SHA-256 token hashes stored; POSIX DB file mode 0600 and service-owned parent directory mode 0700, exact mode enforced at each connection | Parent directory is enforced as service-owned mode 0700; WAL/SHM inherit that boundary; backups and host access must still be secured by operator |
| Network attacker modifies configuration | HTTPS platform TLS validation, bounded response, ECDSA signature, expiry and monotonic revision | Trust-key provisioning and real endpoint deployment are not yet exercised |
| Redirect or credential leakage to another host | HTTPS-only client, rejects URL credentials/query/fragment and redirects | Endpoint must be owner-approved and correctly provisioned |
| Malicious or downgraded update | Signed manifest, artifact SHA-256, expiry and monotonic version check | Downloader, PackageInstaller, successful-install callback and rollback are not integrated |
| Rooted device or app-data rollback | Android Keystore-backed encryption and local monotonic counters | Explicitly not resistant to root, app-data deletion or reinstall |
| Dependency or build supply-chain compromise | Pinned direct dependency versions and CI build/test | Full dependency vulnerability scanning, action SHA pinning and reproducible-build verification remain |
| Backend host compromise | Self-hosted deployment target, external signing key and admin secret | Host compromise can expose database/configuration and misuse service credentials |

## Security invariants

- Exactly six engine slots and eight platform cores; no additional engine or core.
- Exactly one active TUN owner.
- No plaintext fallback when a tunnel-required policy is enforced.
- No secrets, private keys, production profiles or signing keystores committed to source control.
- No fabricated server inventory, endpoint health, latency, throughput or handshake claims.
- No telemetry by default; diagnostics remain local, bounded and disabled by default.
- No production infrastructure or permanent signing identity without owner approval.
- No experimental APK distribution; Stable Production only after all release gates.

## Required validation before production

1. Review Android merged manifest and foreground-service behavior on supported Android versions.
2. Test WireGuard handshake, bidirectional traffic, DNS, IPv4/IPv6 routes, leak behavior, network handover, disconnect and process death on physical devices.
3. Test cleanup failure and retry against the real GoBackend/TUN service.
4. Deploy the backend on approved self-hosted infrastructure with TLS, firewall, non-root account, private data directory, protected WAL/SHM and encrypted backups.
5. Test enrollment, revocation, config expiry, signature failure, replay/rollback and restore.
6. Complete dependency vulnerability/license review and independent security testing.
7. Integrate and test signed download, PackageInstaller success/failure, anti-rollback persistence and rollback/recovery.
8. Obtain owner approval for permanent signing identity and verify secure backup.

## Incident response

If a device token is suspected compromised, revoke that device through the operator control plane and re-enroll it after investigation. If the administrator token or signing private key is compromised, stop configuration publication, rotate credentials/keys through an owner-controlled migration, and re-pin the corresponding public key only through a separately authenticated owner-approved app update. Do not silently replace pinned trust keys.
