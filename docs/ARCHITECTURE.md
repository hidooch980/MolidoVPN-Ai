# Architecture and Trust Boundaries

## Logical topology

```text
Android UI / Configuration Input
   |
VpnSessionController (only app-level engine start/stop authority)
   |
SessionSupervisor (exclusive owner + lifecycle state machine)
   |
Engine Adapter Registry (exactly six approved slots)
   |
Selected validated engine adapter
   |
Single designated Android VpnService / TUN provider
   |
Encrypted transport
   |
Self-hosted server endpoint (deployment required)
```

## Ownership rules
- UI requests actions; it never manipulates TUN directly.
- `VpnSessionController` is the only application-level entry point allowed to invoke engine adapters.
- `SessionSupervisor` serializes reservation, startup, running, stopping, failure, and release.
- Exactly one adapter may be active for a session.
- The Android application must declare/use exactly one designated VPN service/TUN provider. No adapter may create a second VPN service or establish an independent TUN.
- The initial WireGuard implementation uses the upstream `GoBackend` and its manifest-merged `GoBackend.VpnService` as that single provider. The supervisor/controller delegates the start/stop operation to this backend; no parallel `MolidoVpnService` is declared.
- Future engines may be registered only after their data plane is shown to work through the same single-TUN ownership boundary. If they cannot, they are not integrated.
- Switching engines requires stopping the previous data plane, confirming backend DOWN and service/TUN release, then starting the next adapter. If release cannot be proven, fail closed.
- Engine failure must not silently fall back to plaintext traffic when tunnel-required policy is active.

## Adapter contract
Each engine adapter must declare a stable engine identifier mapped to one of the six fixed slots; upstream project and exact version/commit; license and attribution; supported Android API range and ABI; configuration schema and validation; lifecycle operations and cancellation behavior; single-TUN integration plan; DNS and IPv4/IPv6 behavior; error taxonomy and recovery semantics; resource limits and security assumptions; automated and real-device test evidence.

## Network intelligence and selection
Only measured, timestamped, provenance-labelled observations may influence adaptive selection. Missing measurements remain unknown; they must not be replaced with fabricated defaults. Selection must be explainable and bounded by user policy. Do not continuously probe or transmit telemetry without a documented privacy and battery budget.

## Self-hosted control plane
The Android client must remain safe when the control plane is unreachable. Cached configuration must be authenticated, have explicit expiry/rollback semantics, and never silently weaken local security policy. The backend is deployable by the operator on infrastructure they control; no such infrastructure is currently assumed to exist.

## Failure boundaries
- No route/internet: report unavailable/offline; preserve local UI and safe recovery.
- Backend unreachable: use valid cached configuration or report control-plane unavailable.
- Engine crash: supervisor owns teardown and recovery.
- TUN ownership uncertain: fail closed; do not start another engine.
- Invalid update/signature: reject update and preserve current installation.
