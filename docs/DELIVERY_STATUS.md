# Delivery Status

Branch: development/molido-vpn-ai-from-scratch

## Release decision

**Stable Production: BLOCKED.** Repository work cannot substitute for an authorized VPN endpoint, hands-on Android device validation, owner-approved permanent signing identity, or operator-approved server deployment. No APK is distributed.

## Latest CI evidence

[Android CI #122](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/44576617166), commit `86bc0c62d655fab4a3d34a1cad62ba7ddd37755e`, completed successfully:
- Self-hosted control-plane backend tests: success
- Android unit tests: success
- Android lint: success
- Release variant assembly (CI validation only): success

This is **CI Tested**, not Android Device Tested or Production Verified. No APK was distributed.

## Product decisions

- No human end-user accounts, registration, login, or password-reset flows. See [ADR: No End-User Accounts](ARCHITECTURE_DECISIONS.md).
- Machine/device authentication remains separate and optional to a deployed control plane. Admin secrets never belong in the APK.
- Dashboard is local-first and uses actual Android/session observations. Unknown values remain unknown; no invented public IP, country, ping, speed, handshake, or server state.

## Android dashboard

The native dashboard now uses a dark layered/glass visual system with teal accent surfaces, raised controls, live session-state observation, network assessment, profile import, network-policy controls, and all six engine slots. Only WireGuard is selectable because it is the only implemented adapter. The other five are explicitly marked Not Implemented. This source UI has passed CI compilation/lint/unit checks; visual behavior and touch layout still require a physical Android device review.

## Verified source implementation

| Area | Status | Boundary |
|---|---|---|
| Six-engine / eight-core architecture | Source Implemented | Inventory guarded by unit tests; inventory does not imply engine implementation |
| Session & TUN Supervisor | Source Implemented | Exclusive owner, lifecycle state machine, cleanup retry |
| WireGuard | Source Implemented | Upstream Android GoBackend adapter; no device/server data-plane proof |
| Network Intelligence | Source Implemented | Passive default-network observation; stale/lost-network handling; UI reports OS-observed facts only |
| Adaptive Path & Policy | Partially Source Implemented | Fresh Android-observed connectivity gates start; local ANY_VALIDATED/UNMETERED_ONLY/WIFI_ONLY policy |
| Security & Privacy | Partially Source Implemented | In-memory profile flow, redaction, HTTPS-only client, Android Keystore-protected trust material; full threat-model validation pending |
| Resilience & Recovery | Partially Source Implemented | Bounded recovery planner and explicit cleanup action; no automatic scheduler or process-death recovery |
| Diagnostics & Observability | Partially Source Implemented | Bounded, disabled-by-default, process-local ring buffer; no telemetry or persistent logs |
| Control Plane & Configuration | Partially Source Implemented; Server Deployment Required | FastAPI/SQLite device enrollment/revocation and ECDSA-signed config source; server not deployed |
| Update & Integrity | Partially Source Implemented | Signed manifest/hash verification and PackageInstaller staging; download transport and physical install/rollback validation pending |

## Six-engine matrix

| Engine slot | Status |
|---|---|
| WireGuard | Source Implemented; physical data-plane validation pending |
| AmneziaWG | Not Implemented |
| V2Ray/Xray | Not Implemented |
| Psiphon | Not Implemented |
| Tor | Not Implemented |
| MASQUE/WARP-compatible | Not Implemented |

No seventh engine is allowed. Remaining engines require individual license, dependency, native-code, privacy, routing and single-TUN compatibility reviews.

## Recovery and network boundaries

- Recovery remains user/caller initiated: cleanup of a retained FAILED session is offered from the dashboard. A returned retry plan is advisory; there is no automatic reconnect scheduler.
- Profiles remain in memory only. Process-death recovery is not implemented and must not be approximated by storing secrets in WorkManager data.
- Network metrics are Android-reported link capability estimates, not measured VPN throughput. Public IP, country, ping and peer handshake are not shown without authoritative measurements.
- Physical testing is still required for permission flow, handshake, bidirectional traffic, DNS, IPv4/IPv6 routes, leak behavior, network transitions, recovery and process death.

## Security and operational limitations

- Android trust keys are pinned once and encrypted at rest using Android Keystore AES-GCM. Initial pinning must occur through a trusted owner-controlled channel; there is no trust-on-first-use.
- Backend requires operator-provisioned admin token and ECDSA private key outside the repository. No production server is running.
- Diagnostics are disabled by default, process-local and not uploaded.
- Five engines are not implemented.
- No permanent signing key was generated or changed.
- Stable Production remains blocked until device tests, approved endpoint, approved signing identity, server deployment (if used), update transport and security gates pass.
