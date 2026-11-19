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
| Central engine orchestration | Source Implemented | `VpnSessionController.kt`; single adapter invocation path |
| WireGuard Android dependency | Source Implemented | Upstream `com.wireguard.android:tunnel:1.0.20260102`, Apache-2.0 |
| WireGuard engine adapter | Source Implemented | `WireGuardEngineAdapter.kt`; parses in-memory wg-quick config and delegates to upstream GoBackend |
| Single VPN service declaration | Source Implemented | Manifest merges upstream `GoBackend.VpnService`; custom placeholder VPN service removed from manifest |
| Controller lifecycle tests | Source Implemented | Five controller tests plus supervisor/inventory tests; current revision awaits CI |
| GitHub Actions Android CI workflow | Source Implemented | `.github/workflows/android-ci.yml` |
| Last verified CI execution | CI Tested | Run #11 passed unit tests and Android lint on commit `f04b253d6526ba17276503cd3978260c72aa71f8` |
| WireGuard packet data plane | Source Implemented | Upstream GoBackend integration is wired; real packet flow not yet verified on a device |
| Android TUN integration | Source Implemented | Delegated to the single manifest-merged upstream GoBackend VPN service; device validation pending |
| Other five engine data planes | Not Implemented | Slots only; no other engine claimed ready |
| Self-hosted backend/server | Server Deployment Required | No server is provisioned |
| Stable signed APK | Blocked | Permanent signing identity and release evidence absent |
| Physical Android device verification | Not Implemented | No device test evidence |
| Production verification | Blocked | Requires real device and deployed server evidence |

## Branch tracking

Current source revision is the branch head shown by GitHub. All product development remains on this branch.

## Release gates

A Stable Production release is blocked until all are true:
1. Permanent signing identity is confirmed and securely backed up by the owner.
2. Release workflow builds only the production variant and never distributes debug/test variants.
3. Static analysis, dependency/license checks, unit/integration tests, and artifact integrity checks pass for the release candidate.
4. Physical Android device validates connection, traffic flow, DNS, IPv4, IPv6, leak prevention, kill-switch policy, reconnect, and recovery.
5. A real self-hosted endpoint is deployed and its operational ownership is documented.
6. Release notes, SBOM/dependency inventory, checksums, and rollback guidance are prepared.

## Reporting rule

A CI green check means only that the named automated checks passed for the named commit. It does not imply Android Device Tested, Server Deployment Required completion, or Production Verified.
