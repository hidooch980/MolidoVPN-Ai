# MolidoVPN AI — end-to-end readiness and release gate

This is the master execution tracker. It distinguishes work that exists in source from work that has passed CI, real-device testing, server verification, and release signing. A checkbox is checked only when evidence for that exact gate is available.

## Current verified snapshot (2026-11-22)

- [x] Six-engine catalog is capability-driven; only adapters actually registered at runtime can be selected.
- [x] WireGuard is the only registered engine adapter.
- [x] Temporary UTF-8 WireGuard profile bytes are wiped after parsing.
- [x] Manual WireGuard diagnostics expose RX/TX counters and latest peer handshake time; these are not treated as proof of internet access.
- [x] Native process-isolation scaffolds exist for Xray, AmneziaWG, and Psiphon; they report `NOT_IMPLEMENTED`, do not create a TUN, and are not selectable.
- [x] Psiphon remains blocked pending GPL-3.0 compliance or written commercial exception.
- [x] MASQUE/WARP remains blocked pending a documented supported public embedding path and terms.
- [x] Physical-device and native-engine acceptance procedures are documented.
- [x] Full automated CI passed on the exact current PR head `61d707c673d29fe9933520eaed65705ff55a09ee`, run [45004213465](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/45004213465) on 2026-11-22. Android unit tests, Android lint, release-variant assembly (CI validation only), Android Keystore instrumentation on emulator, backend/control-plane tests, retry-classifier tests, and pinned WireGuard Go-only source build all completed successfully.
- [ ] Signed-release verification: the `Build and verify signed Android release` job was skipped in run 45004213465. No signed release artifact or signer fingerprint is proven by that run.
- [ ] Autonomous CI bug-fix agent job: skipped in run 45004213465; do not describe the system as an autonomous code-repair agent.
- [ ] Real Android phone acceptance evidence recorded.
- [ ] Compatible WireGuard server/provider deployment and endpoint tested.

Historical note: earlier runs on 2026-11-21 had an emulator PackageManager startup failure during a slow TCG boot. The emulator workflow was subsequently hardened to prefer host acceleration and wait for PackageManager readiness. That earlier failure is historical and is not the result of the current PR-head run.

## Workstream A — WireGuard end-to-end

- [ ] Obtain a valid authorized server/profile outside source control; never place private keys or full profiles in issues, logs, or commits.
- [ ] Verify Android VPN permission, start, backend UP/DOWN transitions, and exact single-session ownership.
- [ ] Verify peer handshake is fresh and corresponds to the intended peer.
- [ ] Generate TCP and UDP traffic in both directions; verify RX and TX deltas.
- [ ] Verify public egress against the authorized server route.
- [ ] Verify IPv4 routes and IPv6 routes; record intentionally excluded routes/apps.
- [ ] Run two independent DNS leak checks, including IPv6 DNS behavior.
- [ ] Test fail-closed behavior by interrupting the VPN endpoint in a controlled test; no silent physical-network fallback when policy requires blocking.
- [ ] Test disconnect, descriptor/resource cleanup, reconnect, and at least five repeated cycles.
- [ ] Test Wi-Fi-to-mobile and mobile-to-Wi-Fi handover. If seamless recovery is unsupported, require a safe disconnected state and explicit reconnect.
- [ ] Run a sustained 30-minute traffic session; inspect crashes, ANRs, memory, file descriptors, threads, battery and thermal behavior.
- [ ] Record exact commit SHA, APK SHA-256, phone model, Android version, server version, handshake age, RX/TX deltas, route/DNS/leak results, handover results, and cleanup outcome.

**Important:** the current adapter uses upstream WireGuard GoBackend and its manifest-merged VPN service. The app-level controller and supervisor arbitrate lifecycle, but a dedicated app-owned VPNService with explicit descriptor handoff/remote cleanup acknowledgement has not yet replaced this provider. Do not activate another engine until that architecture is implemented and tested.

## Workstream B — single TUN owner and shared engine runtime

- [ ] Designate exactly one Android `VpnService` as the only component allowed to call `Builder.establish()`.
- [ ] Define descriptor ownership, duplication, close semantics, startup timeout, stop acknowledgement, process-death handling and cleanup retry.
- [ ] Make the central session controller retain ownership until every remote engine confirms descriptor closure and stop completion.
- [ ] Add IPC request/response contracts with versioning, bounded messages, authentication to the app UID, explicit error codes and timeout behavior.
- [ ] Add instrumentation tests proving no two engines can own the TUN concurrently.
- [ ] Add process-death and cleanup-failure tests proving a replacement engine cannot start while the old engine may still hold the descriptor.
- [ ] Migrate WireGuard to the shared owner and prove no second VPN service/TUN is created.
- [ ] Only after the shared owner passes tests, integrate other engines one at a time.

## Workstream C — six engine implementation gates

| Engine | Current status | Gate before it can be selectable |
|---|---|---|
| WireGuard | Adapter registered; CI source tests exist; real device/server acceptance pending | Full lifecycle, data-plane, DNS/routes/leaks, cleanup, physical phone and server evidence |
| AmneziaWG | Process scaffold only; not selectable | Pinned reproducible upstream build, license audit, isolated runtime, Amnezia config validation, shared TUN handoff and all device gates |
| V2Ray/Xray | Process scaffold only; not selectable | Pinned official libXray build, isolated Go runtime, bounded config import, documented TUN FD handoff, DNS lifecycle and all device gates |
| Psiphon | Blocked | Explicit GPL-3.0-compliant distribution decision or written commercial exception, then host-service/TUN integration and all device gates |
| Tor | Not implemented | Tor daemon + tested TUN-to-SOCKS bridge, DNS/UDP/IPv6 behavior, shared TUN ownership and all device gates |
| MASQUE/WARP-compatible | Blocked | Supported public integration API/SDK and terms, or a separately reviewed lawful implementation; no private endpoint reverse engineering |

No generic country selector is allowed. Country choices may be shown only when the selected provider supplies a verified country inventory and supports user selection.

## Workstream D — automated validation

- [ ] Unit tests: profile parsing/validation, state machine, policy decisions, ownership, failure and retry.
- [ ] Instrumentation tests: secure storage, Android VPN permission/service lifecycle, IPC, process death and TUN descriptor cleanup.
- [ ] Integration tests: real native engine with deterministic local test endpoints and bidirectional TCP/UDP.
- [ ] Security tests: malformed/oversized config, secret redaction, encrypted storage, DNS/IPv4/IPv6 leakage, fail-closed routing and socket protection.
- [ ] Stability tests: repeated connect/disconnect, network handover, sustained traffic, resource growth and crash/ANR review.
- [x] Current PR-head CI: unit tests, lint, release assembly, Keystore emulator instrumentation, backend/control-plane tests, retry-classifier tests and pinned WireGuard Go-only source build passed on SHA `61d707c673d29fe9933520eaed65705ff55a09ee` (run 45004213465). This does not satisfy the physical-device, real-server, descriptor-handoff or signed-release gates.
- [ ] CI must pass on the exact PR head before review/merge. CI build success alone does not imply device or production readiness.

## Workstream E — signed release and security review

- [ ] Keep signing material only in GitHub Actions secrets; never commit keystore/password files.
- [ ] Run the signed-release job only from the intended development branch after PR review and merge.
- [ ] Verify APK signature with `apksigner` and compare the signer SHA-256 fingerprint to the configured keystore certificate.
- [ ] Record APK SHA-256, version code/name, commit SHA, signer fingerprint and build run.
- [ ] Install the signed APK on the physical test phone and repeat the release smoke tests.
- [ ] Review Android manifest/exported components, permissions, backup policy, network security, dependency versions/licenses, ProGuard/R8 behavior, secret handling, update trust and installer flow.
- [ ] Produce a release note with tested devices, supported engines, server requirements and known limitations. Never label a debug APK as stable.

## Promotion rules

- `SOURCE_IMPLEMENTED`: implementation exists; no claim of runtime success.
- `CI_BUILT`: exact source revision builds.
- `CI_TESTED`: required automated tests pass.
- `ANDROID_DEVICE_TESTED`: physical device, data plane, DNS, routes, security and stability gates pass with evidence.
- `SERVER_DEPLOYMENT_REQUIRED`: client gates pass but compatible server/provider deployment remains unverified.
- `PRODUCTION_VERIFIED`: supported client/server, operational monitoring, security review and production-like end-to-end evidence pass.
- `BLOCKED`: a legal, provider, upstream, architecture or safety prerequisite prevents integration.

A UI state, process PID, VPN icon, interface UP state, successful native-core start, handshake, RX/TX counters, or APK build is never sufficient by itself to promote an engine.
