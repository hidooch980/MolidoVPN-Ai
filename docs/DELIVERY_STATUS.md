# Delivery Status

Last updated: 2026-11-19

Reviewed source baseline: `ea34e01c0f70e3a4067732f66ae485990f352b44`.
This document distinguishes repository/CI evidence from Android-device and production evidence. A green CI run is not proof of a working VPN connection.

## Current delivery decision

**Stable Production: BLOCKED.** No owner-approved permanent signing identity, physical-device test evidence, or deployed self-hosted endpoint is available. No APK is being distributed. Do not label this repository production-ready.

## Repository and CI evidence

| Item | Status | Evidence / boundary |
|---|---|---|
| Dedicated from-scratch development branch | Source Implemented | `development/molido-vpn-ai-from-scratch` |
| Master specification and architecture | Source Implemented | `docs/MASTER_SPEC.md`, `docs/ARCHITECTURE.md` |
| Android native application scaffold | Source Implemented | `android/app`; native Android UI |
| Fixed six-engine / eight-core inventory | Source Implemented | `ArchitectureInventory.kt`; inventory does not imply implementation |
| Session lifecycle coordinator | Source Implemented | `SessionSupervisor.kt`; exclusive claim, explicit states, retained ownership |
| Central engine orchestration | Source Implemented | `VpnSessionController.kt`; one app-level adapter invocation path |
| WireGuard upstream Android dependency | Source Implemented | `com.wireguard.android:tunnel:1.0.20260102`; Apache-2.0 |
| WireGuard adapter | Source Implemented | In-memory config parsing and upstream `GoBackend` integration |
| Controller/lifecycle unit tests | CI Tested | CI run #46 passed unit tests on commit `60d3bfb9548767713dcc4009232d98b21c6f61da` |
| Android lint | CI Tested | CI run #46 passed Android lint |
| Release-variant assembly | CI Built | CI run #46 passed release-variant assembly; CI-only output, not signed owner release or distribution |
| Latest completed CI run | CI Tested | Run #46, ID `44473841493`, commit `60d3bfb9548767713dcc4009232d98b21c6f61da`; exact-six/exact-eight inventory guard tests, unit tests, Android lint, and release-variant assembly all passed |
| Physical Android validation | Not Implemented | No device test evidence |
| Self-hosted backend/server | Server Deployment Required | No VPS/server has been provisioned |
| Stable signed APK | Blocked | Permanent signing identity not confirmed by owner |
| Production verification | Blocked | Requires device, real endpoint, and complete release gates |

CI run #43 (completed successfully on parent commit): https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/44472058510

CI run #44 (completed successfully): https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/44473125681

Latest completed CI run #46: https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/44473841493

Master prompt compliance audit: `docs/PROMPT_COMPLIANCE.md`.

## Six-engine implementation matrix

| Approved engine slot | Status | Evidence / next gate |
|---|---|---|
| WireGuard | Source Implemented | Upstream GoBackend adapter exists; Android packet-flow and server tests remain outstanding |
| AmneziaWG | Not Implemented | Inventory slot only |
| V2Ray/Xray | Not Implemented | Inventory slot only |
| Psiphon | Not Implemented | Inventory slot only |
| Tor | Not Implemented | Inventory slot only |
| MASQUE/WARP-compatible | Not Implemented | Inventory slot only |

No seventh engine is authorized. A slot is not an implementation or a promise of compatibility.

## Eight-core implementation matrix

| Complementary core | Status | Evidence / next gate |
|---|---|---|
| Session & TUN Supervisor | Source Implemented | Exclusive lifecycle ownership and controller tests |
| Network Intelligence | Source Implemented | Timestamped evaluator plus lifecycle-bound Android default-network callback; local observation only, no active probes or telemetry; physical-device validation pending |
| Adaptive Path & Policy | Partially Source Implemented | Deterministic policy gate for validated, fresh observations and measured Wi-Fi/metered requirements; no multi-engine path selection or handover |
| Security & Privacy | Partially Source Implemented | Initial privacy boundaries and in-memory profile flow; full threat model/security verification remains |
| Resilience & Recovery | Partially Source Implemented | Bounded exponential-backoff planning and offline wait logic; scheduler, reconnect orchestration and device recovery remain unimplemented |
| Diagnostics & Observability | Partially Source Implemented | Disabled-by-default bounded in-memory diagnostic ring buffer with basic secret/IP redaction; no persistence or telemetry |
| Control Plane & Configuration | Partially Source Implemented; Server Deployment Required | Self-hosted FastAPI source for enrollment/revocation and ECDSA-signed per-device config; Android network client, TLS deployment, key management and operations remain |
| Update & Integrity | Partially Source Implemented | Canonical ECDSA manifest and SHA-256 artifact verification primitive with expiry/rollback checks; no trusted production key, downloader, installer or persistent rollback store |

## Network Intelligence implementation boundary\n\n- `NetworkIntelligenceCore` evaluates only caller-supplied, timestamped local observations.\n- Missing measurements remain null/unknown; stale and future observations are not used.\n- The evaluator performs no network probes, throughput estimates, engine selection, or telemetry transmission.\n- `AndroidNetworkObservationCollector` reads active-network capabilities without probing or telemetry; `ACCESS_NETWORK_STATE` is declared. UI/session integration, callback-driven refresh, and physical-device validation remain outstanding.\n\n## Newly added core boundaries\n\n- Android network monitoring uses the default-network callback while the activity is active; no active probing or telemetry is performed.\n- Adaptive policy only allows paths when required measurements are fresh and validated; this does not yet select among VPN engines.\n- Recovery currently calculates bounded retry plans; no automatic reconnect scheduler is connected.\n- Diagnostics are process-local, bounded, redacted and disabled by default.\n- Self-hosted backend source is in `backend/`; it requires operator-provisioned admin token, external ECDSA private key and TLS reverse proxy. No deployment is running.\n- Update integrity verifies signed canonical metadata and artifact digest only; it does not install APKs or provide persistent anti-rollback state.\n\n## WireGuard and Android technical boundary

- The WireGuard adapter delegates VPN interface creation and packet routing to upstream `GoBackend`.
- The upstream manifest-merged `GoBackend.VpnService` is the sole intended VPN service/TUN provider. Do not add a second service or create a second TUN.
- The app-level `VpnSessionController` is the only application entry point for adapter start/stop.
- WireGuard configuration is parsed in memory with a 64 KiB input limit; this UI flow does not persist or log the profile.
- A successful backend start in source is not evidence of peer handshake, internet traffic, DNS correctness, IPv4/IPv6 routing, or leak prevention.
- Five other engine slots and most complementary cores remain unimplemented.

## Release gates — all required

1. Owner confirms a permanent signing identity and securely backs it up. Never generate or replace it without explicit approval.
2. A production-only release workflow is reviewed; no Debug/Beta/Alpha/Preview/Test APK is distributed to the owner.
3. Static analysis, dependency/license/security review, unit and integration tests, artifact integrity, SBOM, checksums, and release notes are complete.
4. A physical Android device validates permission flow, connection, real traffic, DNS, IPv4, IPv6, leak prevention, kill-switch policy, reconnect, process death, and recovery.
5. An authorized real self-hosted endpoint is deployed; ownership, key rotation, backup, monitoring, upgrade, and rollback are documented.
6. Stable release signing, installation/update behavior, and rollback are verified end-to-end.

Until every applicable gate is evidenced, keep Stable Production blocked. GitHub Actions is CI/CD only; it is not a VPN server, relay, backend, or data-plane endpoint.

## Next execution order

1. Obtain an authorized WireGuard profile and endpoint from the project owner/operator.
2. Run the Android app on a physical device and record real connection, traffic, DNS, IPv4/IPv6, leak, kill-switch, and recovery results.
3. Fix any observed device-level defects before adding more engines.
4. Implement the remaining cores and engine slots one at a time with license/security review and reproducible tests.
5. Build and deploy the self-hosted control plane/server on explicitly approved infrastructure.
6. Complete signing and release gates, then publish only Stable Production.

No infrastructure, paid service, domain, VPS, private key, or signing identity is created by this repository update.

## Current blockers that cannot be truthfully cleared in repository-only work

- Physical-device validation is blocked until an Android device is available for hands-on testing.
- Data-plane validation is blocked until an authorized WireGuard profile and reachable endpoint are supplied and deployed.
- Stable signing is blocked until the owner supplies/approves the permanent signing identity and its secure backup.
- The self-hosted backend source, five remaining engine integrations, and unimplemented complementary cores are product implementation work; they are not completed by CI or documentation changes.
- No external infrastructure, credentials, endpoints, or signing material may be fabricated or provisioned without owner authorization.
