# Single TUN Owner Migration — Architecture Decision and Execution Gates

Status: REQUIRED BEFORE ADDING A SECOND ENGINE
Decision scope: Android VPN lifecycle and exclusive TUN descriptor ownership in MolidoVPN AI.

## 1. Verified implementation baseline
- `VpnSessionController` currently owns a `SessionSupervisor` reservation and separately tracks `activeSession` / `activeOwner`.
- `WireGuardEngineAdapter` independently acquires a `TunOwnershipCoordinator` lease using `BACKEND_MANAGED` and starts/stops upstream `GoBackend`.
- `MolidoVpnApplication` creates one `TunOwnershipCoordinator`, passes it to WireGuard, and separately creates `SessionSupervisor` for `VpnSessionController`.
- The manifest currently relies on the WireGuard AAR merged `GoBackend.VpnService` as the VPN service/TUN provider.
- The private Xray, AmneziaWG, and Psiphon services are process/IPC scaffolding only; they are not `VpnService` implementations and do not own or receive a TUN descriptor.
- Therefore the session reservation and TUN lease are distinct state machines. Their current coordination is by call ordering, not one atomic ownership authority.

## 2. Required invariant
At any instant, at most one engine may own the Android VPN session and the TUN data path. A replacement engine must not start until the previous engine has stopped, all duplicated descriptor copies are closed or the backend has positively confirmed DOWN, and the central owner has released the exact generation-scoped lease.

Timeout, process death notification, Binder death, backend UP/DOWN intent, handshake, interface presence, or byte counters alone are not sufficient proof of cleanup or working internet.

## 3. Target architecture
- Introduce one app-owned `MolidoVpnService` as the only declared Android `VpnService` and the only creator/owner of the Android TUN descriptor.
- The service owns a single `TunOwnershipCoordinator` and accepts generation-scoped commands from the session controller.
- Refactor `EngineAdapter.start` to receive an immutable start context containing the already-reserved lease token and a narrowly scoped TUN transport capability. Adapters must not independently acquire a second lease.
- WireGuard must migrate from backend-managed `GoBackend.VpnService` ownership to a supported descriptor-handoff path only if upstream APIs permit it safely. If upstream cannot accept the app-owned descriptor without unsupported hooks, stop the migration and document the blocker; do not run two VPN services.
- Isolated native engine processes receive only the descriptor/IPC capability required for their generation. They must acknowledge shutdown only after closing their descriptor copy.
- The central service closes its descriptor and confirms closure before the coordinator releases the lease.
- `VpnSessionController` remains the serialized policy/lifecycle facade. The service/coordinator is the sole authority for TUN ownership; duplicate independent ownership ledgers must be removed or reduced to non-authoritative observations.

## 4. Migration phases
### Phase A — contract and tests (no runtime behavior change)
- [ ] Define `EngineStartContext`, lease lifecycle, descriptor ownership, cancellation, and cleanup acknowledgement contracts.
- [ ] Add tests for stale generation tokens, duplicate acquire, invalid state transitions, failed startup cleanup, stop timeout, process death, descriptor handoff, and owner-switch serialization.
- [ ] Add tests proving a failed cleanup prevents all subsequent engine starts.
- [ ] Document the exact supported WireGuard backend API needed for externally supplied TUN descriptors; pin the dependency and inspect upstream source/API before coding.

### Phase B — central service foundation
- [ ] Add the app-owned `VpnService` with correct manifest declaration, foreground-service notification/type requirements for supported Android versions, permission flow, and lifecycle handling.
- [ ] Make service startup idempotent and bind session commands to generation-scoped authenticated IPC.
- [ ] Implement TUN creation and descriptor close in one place; expose no raw descriptor outside the authorized engine process.
- [ ] Add instrumentation tests for service recreation, revoke, process death, descriptor closure, and cleanup acknowledgement.

### Phase C — WireGuard migration
- [ ] Integrate WireGuard with the central descriptor path using documented upstream APIs only.
- [ ] Remove reliance on the merged `GoBackend.VpnService` only after the new path is proven; ensure the manifest resolves to exactly one VPN service.
- [ ] Verify start/stop, route installation, DNS, MTU, IPv4/IPv6 policy, cancellation, crash recovery, and repeated switching.
- [ ] Run real authorized server acceptance with bidirectional traffic and independent DNS/IP leak checks on a physical Android device.

### Phase D — second-engine admission
- [ ] Only after Phase C passes, admit one additional engine adapter behind the same start context and service-owned TUN.
- [ ] Prove engine process isolation, IPC authentication, descriptor handoff, stop acknowledgement, and cleanup before considering the next engine.
- [ ] Repeat the full regression suite for every new adapter.

## 5. Non-goals and safety boundaries
- Do not add a second `VpnService` for any engine.
- Do not infer TUN ownership from a process being alive or a network interface being present.
- Do not release a lease on timeout alone.
- Do not expose an engine as selectable until its adapter, lifecycle, data plane, and cleanup tests pass.
- Do not claim that a central service or descriptor handoff exists until implemented and verified.
- Do not use undocumented backend internals or reverse-engineered private APIs to force descriptor injection.

## 6. Current decision
Migration is NOT IMPLEMENTED. WireGuard remains the only registered adapter and uses the upstream backend-managed VPN service. The second-engine admission gate remains closed. PR #4 must remain Draft until the migration and physical-device acceptance gates pass.

## 7. Evidence required to close this decision
- Architecture commit and dependency/API review.
- Unit and integration test results for lease/state-machine invariants.
- Manifest inspection proving exactly one app-owned VPN service after migration.
- Instrumentation evidence for descriptor lifecycle and cleanup.
- Physical-device logs and authorized endpoint observations for real bidirectional traffic, DNS, IPv4/IPv6, network handover, stop, and restart.
- Exact app commit, device model/Android version, server version, test timestamps, and redacted evidence bundle.