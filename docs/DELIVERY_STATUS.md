# Delivery Status

Last updated: 2026-11-19
Branch: \`development/molido-vpn-ai-from-scratch\`

## Release decision

**Stable Production: BLOCKED.** Repository work cannot substitute for an authorized VPN endpoint, hands-on Android device validation, owner-approved permanent signing identity, or operator-approved server deployment. No APK is distributed.

## Verified source implementation

| Area | Current status | Boundary |
|---|---|---|
| Six-engine / eight-core architecture | Source Implemented | Exact inventory guarded by unit tests; inventory does not imply engine implementation |
| Session & TUN Supervisor | Source Implemented | Exclusive owner, lifecycle state machine, cleanup retry |
| WireGuard | Source Implemented | Upstream Android GoBackend adapter; no device/server data-plane proof |
| Network Intelligence | Source Implemented | Default-network callback; timestamped observations are reassessed for staleness at decision time |
| Adaptive Path & Policy | Partially Source Implemented | Controller blocks start unless fresh Android-observed connectivity satisfies configured policy; no multi-engine ranking/handover |
| Security & Privacy | Partially Source Implemented | In-memory profile flow, redaction, HTTPS-only control-plane client, Android Keystore AES-GCM protected trust material; full threat-model review pending |
| Resilience & Recovery | Partially Source Implemented | Bounded retry planner exposed by controller; no automatic reconnect scheduler or process-death recovery |
| Diagnostics & Observability | Partially Source Implemented | Bounded, disabled-by-default, process-local ring buffer; no telemetry or persistent logs |
| Control Plane & Configuration | Partially Source Implemented; Server Deployment Required | FastAPI/SQLite enrollment, revocation and ECDSA-signed config source; HTTPS client verifies signatures and advances encrypted local revision counter; server not deployed |
| Update & Integrity | Partially Source Implemented | ECDSA manifest and SHA-256 verification; Android Keystore trust key and encrypted monotonic version counter; downloader, installer, successful-install callback and rollback UX not integrated |

## Six-engine matrix

| Engine slot | Status |
|---|---|
| WireGuard | Source Implemented; physical data-plane validation pending |
| AmneziaWG | Not Implemented |
| V2Ray/Xray | Not Implemented |
| Psiphon | Not Implemented |
| Tor | Not Implemented |
| MASQUE/WARP-compatible | Not Implemented |

No seventh engine is allowed. The five remaining engines require individual upstream license, dependency, native-code, privacy, routing and single-TUN compatibility reviews before implementation. A slot or adapter stub is not a working engine.

## CI evidence

- Backend dependency installation and backend tests passed in the backend job of CI run \`44480442687\` on commit \`dce9d71a634afda74781ff5ecc596f1a370d0e98\`.
- The Android job for that run was still in progress at the last check; it was not counted as a completed Android validation.
- The subsequent branch commits require their own completed CI run. Check [GitHub Actions](https://github.com/hidooch980/MolidoVPN-Ai/actions).

A green CI run proves only the checks it actually executed. It does not prove a working VPN, handshake, packet routing, DNS, IPv4/IPv6 behavior, leak prevention or production readiness.

## Real-world validation still required

1. Physical Android device and authorized WireGuard profile.
2. Reachable, operator-controlled WireGuard endpoint.
3. Verify permission, handshake, bidirectional traffic, DNS, IPv4 and IPv6 routes, leak behavior, network changes, reconnect, process death and cleanup.
4. Deploy the backend only on explicitly approved self-hosted infrastructure, with TLS, firewall, protected admin token, private signing key, backups and restore test.
5. Owner-approved permanent APK signing identity and secure backup.
6. Verify update download, package installation, post-install version persistence and rollback/recovery on device.

## Security and operational limitations

- Android trust keys are pinned once and encrypted at rest using an Android Keystore AES-GCM key. Initial pinning must occur through a trusted owner-controlled channel; there is no trust-on-first-use over the network.
- Encrypted local counters provide app-level rollback detection but are not claimed to resist rooted-device tampering, app-data deletion or reinstall.
- The HTTPS client uses Android platform TLS validation, rejects non-HTTPS URLs and redirects, bounds response size and keeps device tokens in caller memory. No live server URL or token is configured.
- The backend requires operator-provisioned admin token and ECDSA private key outside the repository. No production server is running.
- Recovery computes bounded retry plans only; it does not automatically restart a tunnel.
- Diagnostics are disabled by default, process-local and not uploaded.
- Five engines are not implemented.
- No permanent signing key was generated or changed.

## Stable Production release gates

Stable Production remains blocked until all relevant tests and approvals above pass. CI release-variant assembly is not a signed owner release. No Debug, Alpha, Beta, Preview or Test APK is distributed.
