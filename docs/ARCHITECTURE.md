# Architecture and Trust Boundaries

## Logical topology

```text
Android UI
   |
Connection State / User Policy
   |
Session & TUN Supervisor  <--- sole TUN owner
   |            |
   |            +---- Eight complementary platform cores
   |
Engine Adapter Registry (exactly six approved slots)
   |
Selected validated engine adapter
   |
Android VpnService / TUN
   |
Encrypted transport
   |
Self-hosted server endpoint (deployment required)
```

## Ownership rules
- UI requests actions; it never manipulates TUN directly.
- The supervisor serializes connect, disconnect, restart, and handover operations.
- Exactly one adapter may be active for a session.
- An adapter exposes lifecycle and packet-transport interfaces; it cannot independently call `VpnService.Builder.establish()`.
- Switching engines requires stopping the previous data plane, confirming TUN ownership is released, then starting the next adapter. If ownership cannot be proven released, fail closed.
- Engine failure must not silently fall back to plaintext traffic when tunnel-required policy is active.

## Adapter contract
Each engine adapter must declare a stable engine identifier mapped to one of the six fixed slots; upstream project and exact version/commit; license and attribution; supported Android API range and ABI; configuration schema and validation; lifecycle operations and cancellation behavior; packet I/O contract; DNS and IPv4/IPv6 behavior; error taxonomy and recovery semantics; resource limits and security assumptions; automated and real-device test evidence.

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
