# Delivery Status

Last updated: 2026-11-19

Reviewed source baseline: `3b8f537ea774e38726e0d92b511e125fec0409c6`.
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
| Controller/lifecycle unit tests | CI Tested | CI run #43 passed unit tests on commit `627fa1d21698caf17de9c8fa320908ecda67f75c` |
| Android lint | CI Tested | CI run #43 passed Android lint |
| Release-variant assembly | CI Built | CI run #43 passed release-variant assembly; CI-only output, not signed owner release or distribution |
| Latest CI run | Blocked (in progress) | Run #44, ID `44473125681`, commit `3b8f537ea774e38726e0d92b511e125fec0409c6`; unit tests and Android lint passed, release assembly was still running at last inspection |
| Physical Android validation | Not Implemented | No device test evidence |
| Self-hosted backend/server | Server Deployment Required | No VPS/server has been provisioned |
| Stable signed APK | Blocked | Permanent signing identity not confirmed by owner |
| Production verification | Blocked | Requires device, real endpoint, and complete release gates |

CI run #43 (completed successfully on parent commit): https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/44472058510

Latest CI run #44 (in progress at last inspection): https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/44473125681

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
| Network Intelligence | Not Implemented | No real network measurement pipeline |
| Adaptive Path & Policy | Not Implemented | No validated adaptive selection; no fabricated measurements |
| Security & Privacy | Source Implemented | Initial privacy boundaries and in-memory profile flow; full threat model/security verification remains |
| Resilience & Recovery | Source Implemented | Lifecycle cleanup retry behavior exists; device/network recovery is unverified |
| Diagnostics & Observability | Not Implemented | No production diagnostics pipeline or telemetry service |
| Control Plane & Configuration | Not Implemented | No backend/control-plane implementation |
| Update & Integrity | Not Implemented | No signed update metadata, verified updater, or rollback implementation |

## WireGuard and Android technical boundary

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
