# Single TUN Owner Migration — Architecture Decision and Execution Gates

Status: REQUIRED BEFORE ADDING A SECOND ENGINE
Decision scope: Android VPN lifecycle and exclusive TUN descriptor ownership in MolidoVPN AI.

## 1. Verified implementation baseline
- `VpnSessionController` serializes session commands through `SessionSupervisor` and separately retains the active `EngineSession` / owner handle for cleanup retries.
- `MolidoVpnApplication` creates one `TunOwnershipCoordinator` and passes that same instance to both `SessionSupervisor` and `WireGuardEngineAdapter`.
- `SessionSupervisor.claim()` reserves the `BACKEND_MANAGED` lease before adapter startup and supplies the exact generation-scoped token through `tunLeaseFor()`; `WireGuardEngineAdapter` validates that token and reports backend UP/DOWN confirmation. The adapter does **not** independently acquire a second lease.
- The session state machine and TUN lease state machine remain separate ledgers, coordinated synchronously by the controller/supervisor and guarded by the coordinator's generation token. This is stronger than an unshared reservation, but it is not yet one Android service-owned authority and does not create or broker a TUN descriptor.
- The manifest currently relies on the WireGuard AAR merged `GoBackend.VpnService` as the VPN service/TUN provider.
- The private Xray, AmneziaWG, and Psiphon services are process/IPC scaffolding only; they are not `VpnService` implementations and do not own or receive a TUN descriptor.

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

## 8. Verified upstream WireGuard API and native FD feasibility (2026-11-22)

Reviewed the exact official `WireGuard/wireguard-android` dependency tag `1.0.20260102`; signed annotated tag object `3831cab2da844319291459308a6e535d36dde4b3`, peeled commit `09b75c2bd37f749e2a8c85876394854113c74be7` (upstream tag signature verified by GitHub).

The native integration blocker is narrower than “WireGuard cannot accept an external TUN FD”:

- Upstream `libwg-go/api-android.go` exports `wgTurnOn(interfaceName, tunFd, settings)` and attaches the supplied descriptor with `tun.CreateUnmonitoredTUNFromFD`.
- On attach failure the Go entry point closes the supplied FD. After successful attach, the Go TUN device owns that descriptor and `wgTurnOff(handle)` closes the device.
- The Android `GoBackend` wrapper currently hides this capability: `GoBackend.java` (blob `196836d278c92ac86db49a131d6f27b27b445403`) obtains its own `VpnService`, calls `Builder.establish()`, detaches that descriptor, invokes `wgTurnOn`, and protects the returned IPv4/IPv6 UDP socket descriptors. Its `wgTurnOn(String, int, String)` JNI declaration is private; the public `Backend` interface has no externally supplied TUN-FD parameter.
- The JNI bridge is `tunnel/tools/libwg-go/api-android.go` (blob `d47c5d76cfdef368ae1cdacf805c53acc553a023`) and `tunnel/tools/libwg-go/jni.c` (blob `7ad94d350a9f1953ab4c5de8dcd374f9512612b9`). It forwards the supplied FD to the Go `wgTurnOn` symbol. Thus the Go entry point is reusable in a pinned source build, but the published AAR API does not expose it for Molido-owned TUN injection.
- Therefore the supported engineering route is a pinned source-built backend wrapper around the existing native entry point, not reflection or a guessed public AAR API. Keep the native ABI and package/JNI symbols aligned to the exact pinned source revision.

### Required wrapper contract

1. Central `MolidoVpnService` creates and retains the original `ParcelFileDescriptor`.
2. It passes a duplicated descriptor through authenticated Binder IPC. Never pass the original service-owned descriptor or a raw integer FD.
3. The isolated WireGuard process invokes the source-built wrapper, which detaches/attaches only its duplicate to the existing native `wgTurnOn` entry point.
4. The wrapper obtains the WireGuard UDP socket FDs and requests protection from the central service. A missing/invalid socket FD or failed `VpnService.protect(fd)` is a hard startup failure; no unprotected fallback is allowed.
5. On stop, `wgTurnOff` must return after closing the Go TUN device and its duplicate. Only then may the engine send the matching generation/PID cleanup acknowledgement.
6. The central service closes its retained original descriptor, verifies close completion, and only then releases the TUN lease.
7. Every attach-failure, Binder-rejection, cancellation, process-death, and partial-start path must close the descriptor copy it owns exactly once. The central original remains owned by the service until its own close.

### Scope and non-claims

This source inspection establishes a feasible native FD entry point; it does **not** establish that Molido's wrapper, central service, Binder authentication, socket protection, or cleanup protocol has been implemented. The pinned source tag matching the current AAR remains the required baseline unless a separately reviewed upgrade is approved. No second VPN service may be declared during the transition, and the AAR dependency must not be removed until the replacement is built and tested atomically.

Phase A's upstream FD API inspection is now source-verified. Phase B/C implementation and all runtime/device acceptance gates remain open.

## 9. Additional upstream failure-path and concurrency audit (2026-11-22)

Reviewed the complete api-android.go and GoBackend.java files at the exact pinned upstream commit 09b75c2bd37f749e2a8c85876394854113c74be7. These are source-level findings; no runtime patch is claimed here.

### Confirmed findings requiring a reviewed source-fork patch

1. **Nil uapiFile close on startup failure:** ipc.UAPIOpen(name) can return an error before assigning a usable file. The later device.Up() failure branch unconditionally calls uapiFile.Close(). If the earlier open failed, this can panic while handling the original startup failure. The same unconditional close appears in the handle-exhaustion branch. The fork must use explicit nullable ownership and one idempotent cleanup path.
2. **Partial cleanup after IpcSet failure:** after tun.CreateUnmonitoredTUNFromFD succeeds, device.NewDevice(...) is created. If device.IpcSet(settings) fails, upstream directly calls unix.Close(tunFd) and returns without calling device.Close(). A fork must audit the exact tun.Device/device.Device close semantics and ensure all device resources are released exactly once; do not add a second close blindly because that can create a double-close/FD-reuse hazard.
3. **Unsynchronized global handle registry:** tunnelHandles is a package-global Go map accessed by wgTurnOn, wgTurnOff, wgGetSocketV4, wgGetSocketV6, and wgGetConfig without a mutex. Concurrent JNI start/stop/diagnostic calls can race map reads/writes. The fork must serialize lifecycle and handle operations or protect the registry with a mutex, and add race-focused tests where the build environment supports them.
4. **Socket protection result ignored:** GoBackend.java calls service.protect() for both socket families without checking the returned booleans, and passes the values returned by wgGetSocketV4/V6 without first rejecting invalid negative descriptors. The Molido wrapper must validate each available socket FD, check each protection result, fail closed, stop the partially started handle, and retain the TUN lease until cleanup is confirmed.
5. **Error-path ownership contract:** wgTurnOn closes the supplied FD on TUN attach failure and transfers ownership to the Go TUN device on successful attach; the IpcSet failure path also closes the FD directly. The custom JNI/Java wrapper must encode these outcomes explicitly so that Java, Binder, and Go do not each believe they own the same descriptor. Every path must close each descriptor copy exactly once.

### Required tests before accepting the fork

- UAPI open failure followed by device.Up() failure must return a controlled error without panic.
- UAPI listen failure, device.Up() failure, handle exhaustion, and IpcSet failure must leave no live device, listener, or leaked descriptor.
- Concurrent start/stop/socket-query/config-query operations must be serialized or pass Go race detection without concurrent map access.
- Missing IPv4/IPv6 socket descriptors and protect(fd) == false must fail closed with verified native shutdown and descriptor cleanup.
- Repeated start/stop, cancellation during startup, process death, and Binder rejection must preserve generation ownership and never release the central TUN lease before cleanup acknowledgement.

These fixes belong in a minimal pinned-source fork and wrapper review. Do not patch the upstream AAR through reflection, do not introduce a second VpnService, and do not remove the current AAR until the replacement path is built and acceptance-tested.

## 10. UAPI listener descriptor ownership audit (2026-11-22)

Reviewed pinned upstream `wireguard-go` commit `f333402bd9cb`, file `ipc/uapi_linux.go` (blob `fddded0c99dd7fdd59843eb88e7d7a8bacd517b4`), together with Android `api-android.go`.

### Confirmed descriptor leak on successful UAPI listener creation

- `ipc.UAPIListen(name, file)` calls Go standard-library `net.FileListener(file)`. That API creates a listener using a duplicated descriptor; the caller retains ownership of the original `*os.File` and must close it.
- `wgTurnOn` retains the original `uapiFile` only as a local variable. On successful listener creation, it does not close `uapiFile` or store it in `TunnelHandle`.
- Normal `wgTurnOff` closes `handle.uapi`, which closes the listener's descriptor, but it has no reference to the original `uapiFile`. Therefore a successful start/stop cycle leaks the original UAPI file descriptor.
- This is separate from the previously recorded nil-pointer failure path. Fixing only the nil guard does not fix this leak.

### Required minimal fix and regression coverage

1. After `UAPIListen` succeeds, close the original `uapiFile` immediately and set the local to nil; the returned listener owns its independent descriptor.
2. If closing the original file reports an error, close the newly created listener, close the WireGuard device, and fail startup without registering a handle.
3. Audit every `UAPIListen` failure branch in the pinned `ipc/uapi_linux.go`: once `net.FileListener` has succeeded, later inotify setup failures must close the listener and any created inotify descriptor before returning.
4. On `device.Up()` failure and handle exhaustion, close the listener (when present), close any still-owned original file (when present), and close the device exactly once.
5. Add repeated start/stop FD-count regression tests, plus fault-injection tests for `net.FileListener`, inotify init/watch, and device startup failures. A passing Go unit test alone does not replace Android process FD-count instrumentation.

No upstream runtime code has been patched by this audit. The WireGuard fork remains unimplemented, and this finding must be fixed and tested before claiming descriptor-clean lifecycle or central TUN readiness.
