# Delivery Status

Last updated: 2026-11-19

## Repository baseline

| Item | Status | Evidence |
|---|---|---|
| Dedicated from-scratch branch | Source Implemented | `development/molido-vpn-ai-from-scratch` |
| Master specification and architecture docs | Source Implemented | `docs/MASTER_SPEC.md`, `docs/ARCHITECTURE.md` |
| Android Gradle application scaffold | Source Implemented | `android/app`, Gradle Kotlin DSL |
| Fixed six-engine/eight-core inventory | Source Implemented | `ArchitectureInventory.kt` |
| Session lifecycle coordinator | Source Implemented | `SessionSupervisor.kt`; IDLE/RESERVED/RUNNING/STOPPING/FAILED |
| Central engine orchestration | Source Implemented | `VpnSessionController.kt`; one adapter invocation path |
| Startup cleanup retention | Source Implemented | `EngineStartException.kt`; failed startup retains cleanup handle |
| WireGuard Android dependency | Source Implemented | `com.wireguard.android:tunnel:1.0.20260102`, Apache-2.0 |
| WireGuard engine adapter | Source Implemented | `WireGuardEngineAdapter.kt`; parses in-memory config and uses upstream GoBackend |
| Single VPN service declaration | Source Implemented | Upstream GoBackend VPN service is the only intended TUN provider |
| Controller and lifecycle tests | Source Implemented | Unit tests authored; current revision awaits CI |
| CI dependency retry | Source Implemented | Three bounded attempts for test, lint, and release assembly |
| Last verified green CI | CI Tested | Run #11 passed unit tests and lint on `f04b253d6526ba17276503cd3978260c72aa71f8` |
| Latest pre-fix CI attempt | Blocked | Run #25 failed during Gradle dependency resolution before compilation; multiple Maven artifacts were reported missing |
| WireGuard packet data plane | Source Implemented | Upstream GoBackend integration is present; no device packet-flow proof |
| Android TUN integration | Source Implemented | Delegated to upstream GoBackend VPN service; device validation pending |
| Other five engine data planes | Not Implemented | Inventory slots only |
| Self-hosted backend/server | Server Deployment Required | No server is provisioned |
| Stable signed APK | Blocked | No owner-confirmed permanent signing identity and no release evidence |
| Physical Android device verification | Not Implemented | No device test evidence |
| Production verification | Blocked | Requires a real device and deployed endpoint |

## Important technical boundary

The WireGuard adapter uses the upstream GoBackend, which internally establishes the Android VPN interface and owns packet routing. The app-level controller is the only caller of the adapter. The application must not add a second VpnService or create another TUN. This source integration is not proof of a successful handshake or traffic flow.

## Release gates

A Stable Production release is blocked until all are true:
1. Permanent signing identity is confirmed and securely backed up by the owner.
2. Release workflow builds the production variant and never distributes debug/test variants.
3. Static analysis, dependency/license checks, unit/integration tests, and artifact integrity checks pass.
4. Physical Android device validates connection, traffic flow, DNS, IPv4, IPv6, leak prevention, kill-switch policy, reconnect, and recovery.
5. A real self-hosted endpoint is deployed and its operational ownership is documented.
6. Release notes, SBOM/dependency inventory, checksums, and rollback guidance are prepared.

A green CI check proves only the named automated checks for the exact commit. It does not imply Android Device Tested or Production Verified.
