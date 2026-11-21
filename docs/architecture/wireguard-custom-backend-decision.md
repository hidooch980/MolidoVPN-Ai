# WireGuard custom backend decision record

Status: **SELECTED DESIGN — IMPLEMENTATION NOT YET STARTED**
Decision date: 2026-11-20
Applies to: Android client, WireGuard engine, central TUN ownership migration

## Decision

Build a pinned, source-based Molido WireGuard backend from the official WireGuard Android tunnel module and its Apache-2.0-licensed source, with a narrow local patch that removes the backend's ownership of Android VPN service creation. The backend must accept a TUN descriptor created by Molido's one central `VpnService`.

Do not keep the published `com.wireguard.android:tunnel` AAR as an operational dependency after migration. Do not add a second VPN service beside its merged `GoBackend.VpnService`. Do not use reflection, private API mutation, or raw integer file-descriptor transfer.

## Exact source baseline matching the current AAR

A second upstream check identified the source tag that exactly matches the currently consumed AAR version:

- Tag: `1.0.20260102`; annotated tag object `3831cab2da844319291459308a6e535d36dde4b3`.
- Peeled commit: `09b75c2bd37f749e2a8c85876394854113c74be7`.
- GitHub reports the annotated tag PGP signature as **verified: true** (tagger Jason A. Donenfeld; verified 2026-01-02). The peeled commit object is not independently signed.
- The pinned upstream tree contains the same two native submodule gitlinks recorded below: `wireguard-tools` at `e2ecaaa739144997ccff89d6ad6ec81698ea6ced` and `elf-cleaner` at `7efc05090675ec6161b7def862728086a26c3b1f`.
- Upstream `libwg-go` declares Go `1.23.1`; its Go module dependencies are pinned in the upstream `go.mod` / `go.sum`.

The source build offers a potential way to avoid shipping the GPL native command-line tools, subject to a reproducible proof:
- `GoBackend` loads `wg-go` and calls the Go native API; its current implementation creates the TUN through its own service.
- `WgQuickBackend` uses `ToolsInstaller` and the `wg` / `wg-quick` executables. Molido's registered adapter currently uses `GoBackend`, not `WgQuickBackend`.
- Upstream CMake declares `libwg-go.so`, `libwg.so`, and `libwg-quick.so`, and builds the latter two from the GPL-2 `wireguard-tools` submodule; `elf-cleaner` is used as a post-build helper for those two outputs.
- A narrow Molido fork may be able to build/package only `libwg-go.so`, while retaining parser/backend Java code and omitting the CLI binaries, GPL submodule and ELF-cleaner. This must be demonstrated by a clean multi-ABI build, APK/AAB native-library inventory, dependency/license scan, and tests proving no app path invokes `WgQuickBackend` or expects the omitted binaries. Do not assume the legal concern is resolved until that evidence and a qualified license review exist.

This exact-version tag is the correct source baseline to review first; it is not yet integrated. Toolchain compatibility is a material migration item: this upstream source uses AGP `8.13.2`, Kotlin `2.3.0`, and a Gradle wrapper distribution `9.1.0`; the Molido app currently uses AGP `8.10.1`, Kotlin `2.2.21`, and Gradle `8.11.1`. Do not copy upstream Gradle files wholesale or upgrade the app toolchain without a compatibility change plan and full CI validation.

## Upstream provenance and API review (2026-11-21)

The official upstream GitHub API was inspected for candidate tag `1.0.20260315`:

- Annotated tag object: `31ba2dbd3ac793f2e2bb386310d837bdc0cd0bfd`.
- Peeled commit: `0856e2ecf7f3cce0e9c9014db3ad7665669adce5`.
- GitHub reports the annotated tag signature as **verified: true** (valid PGP signature; tagger Jason A. Donenfeld; verified 2026-03-15).
- The peeled commit itself is reported unsigned. The verified signed tag authenticates the tag-to-commit reference; do not describe the commit object as independently signed.
- The currently consumed Maven AAR is `1.0.20260102`, so candidate source tag `1.0.20260315` is a newer upstream revision and is **not yet approved as a drop-in replacement**. Review intervening source, native toolchain, submodule revisions, and dependency/license changes before selecting it.

The candidate `GoBackend.java` confirms the integration boundary:
- `wgTurnOn(String ifName, int tunFd, String settings)` is a private native method.
- The current UP path builds the TUN with `VpnService.Builder.establish()`, detaches its descriptor into `wgTurnOn`, then calls `service.protect()` for the WireGuard IPv4 and IPv6 sockets.
- The public backend API does not expose caller-supplied TUN FD injection. A Molido-owned service therefore requires a source-level, reviewed backend/JNI change and an explicit socket-protection callback/path; it cannot be achieved by merely passing a descriptor to the existing AAR API.


The recursive upstream tree also contains these pinned gitlinks:

| Path | Pinned revision | Observed license / role | Distribution note |
|---|---|---|---|
| `tunnel/tools/wireguard-tools` | `e2ecaaa739144997ccff89d6ad6ec81698ea6ced` | `COPYING` is GNU GPL version 2; CMake compiles its sources into `libwg.so` and `libwg-quick.so` | These native outputs are part of the tunnel build; include matching source and complete GPL notices when distributing binaries. Compatibility with the overall app distribution model requires legal review. |
| `tunnel/tools/elf-cleaner` | `7efc05090675ec6161b7def862728086a26c3b1f` | Upstream README identifies GPL-3.0-or-later; used as a build-time ELF post-processing tool | Not a runtime engine; include license/source notices if source bundle or build environment redistributes it. |

This means the tunnel module's Apache-2.0 POM/COPYING does **not** describe every component used by its native build. The GPL-2 and GPL-3-or-later components must remain separately inventoried; do not claim the complete native bundle is Apache-2.0-only. No legal compatibility conclusion is made here.

No upstream source, dependency, manifest, or runtime code was changed by this provenance/API review.

## Why this path

The currently pinned AAR's `GoBackend.setStateInternal(UP)` starts its nested `GoBackend.VpnService`, calls `VpnService.Builder.establish()`, detaches that descriptor, and passes it to the native `wgTurnOn` entry point. The AAR manifest contributes that nested class as an Android VPN service. The supported public API does not accept a descriptor established by an external service.

The upstream tunnel module is identified as Apache License 2.0. The fork must preserve upstream copyright and license notices, include the complete Apache-2.0 license and applicable NOTICE material, record the exact upstream commit/tag, and document every Molido patch. Re-check every transitive/native component's license independently; the module's Apache-2.0 status does not automatically relicense unrelated dependencies.

## Fork boundary and native ABI

1. Pin the official `wireguard-android` source to the exact reviewed tag/commit matching the currently consumed release, rather than tracking a moving branch.
2. Build the tunnel module from source as a local Gradle module (or a pinned, auditable source submodule). Remove the Maven AAR dependency only in the same change that introduces the replacement module.
3. Retain the upstream config parser and userspace WireGuard implementation. Refactor the Java backend so its native start method accepts a caller-owned TUN FD and never starts `GoBackend.VpnService`, never calls `Builder.establish()`, and never detaches an internally created TUN.
4. Keep the JNI class/package/method ABI aligned with the exact pinned native bridge, or deliberately update and rebuild the Go/JNI exports together. Do not rename Java classes casually: JNI symbol names are part of the integration contract.
5. Replace the upstream manifest contribution so the merged application manifest contains exactly one `android.net.VpnService` component: Molido's central service. Assert this in CI by inspecting the merged manifest for each build variant.
6. Native socket protection must be delegated to the central service's `protect(fd)` before the WireGuard UDP sockets are used. A backend reporting UP is not proof that sockets are protected or traffic is passing.

## Runtime ownership and FD handoff

The central service is the sole creator and owner of the original `ParcelFileDescriptor`. It acquires a `TunOwnershipCoordinator` lease before creating or handing off a descriptor. It sends a duplicated `ParcelFileDescriptor` through authenticated Binder IPC to the isolated engine process. The receiver validates Binder caller UID, engine slot, session nonce, generation, and one-time transfer state before accepting it.

Never transmit a raw FD integer. Close every descriptor copy on every rejected, exceptional, cancellation, and shutdown path. The engine must report its PID and exact generation; the central service must not trust a PID supplied only in an unauthenticated message.

Shutdown is fail-closed:
- stop accepting new work and stop the native packet loop;
- request stop for the exact engine/generation;
- require the engine to close its duplicate before sending cleanup acknowledgement;
- central service closes its original descriptor;
- coordinator releases only after matching cleanup acknowledgement (or confirmed engine process death) and central descriptor closure.

Binder death/process death is a cleanup signal, not an ACK. Missing ACK, timeout, uncertain process identity, or failed descriptor close leaves the lease held and blocks all competing engines. Do not attempt a second engine as a recovery shortcut.

## Implementation sequence

1. Add the pinned upstream source and license inventory; build the unmodified source in CI to establish a reproducible baseline.
2. Patch the backend to accept an externally supplied TUN FD and a protect-socket callback; add native start/stop tests and verify no implicit VPN service startup remains.
3. Remove the upstream VPN service manifest contribution and add the central service; add a merged-manifest exactly-one-service test.
4. Integrate `TunOwnershipCoordinator` into the central service and implement Binder `ParcelFileDescriptor` transfer with UID/nonce/generation checks.
5. Implement engine cleanup acknowledgement, Binder death recipient, process-death cleanup, idempotent close, and retained-lease failure behavior.
6. Add Android instrumentation tests for descriptor validity across Binder, closure semantics, wrong UID/slot/generation, concurrent engine contention, failed cleanup, process kill, and descriptor leak detection.
7. Only after these gates pass, route the WireGuard adapter through the new backend. Keep every other engine non-selectable until it implements the same contract.

## Required acceptance evidence

- Unit tests: two engines racing for a lease; duplicate/stale lease; wrong PID/UID/slot; no cleanup ACK; failed native stop; abrupt engine process death; idempotent close; no release before both descriptor sides are safe.
- Instrumentation: actual Binder FD duplication and use, engine-side close ACK, service-side original close, Binder death, forced process kill, and no FD leak.
- Manifest: exactly one declared VPN service and it is Molido's central service; no merged `GoBackend$VpnService`.
- Runtime: IPv4 and IPv6 route checks, DNS resolution, DNS/IPv6 leak checks, traffic counters and independent external reachability checks, reconnect after clean stop, and forced process death while connected.
- Physical device: repeat on the user's Android handset and real network. Emulator/CI success is not physical-device or production acceptance.

## Current state

This record selects the implementation path only. The repository still uses the published WireGuard tunnel AAR and `GoBackend`; `TunOwnershipCoordinator` is still a platform-neutral state machine and is not connected to Android `VpnService`, Binder, `ParcelFileDescriptor`, or a custom backend. The central service, secure FD handoff, custom backend, and physical-device data-plane validation remain **NOT IMPLEMENTED**.
