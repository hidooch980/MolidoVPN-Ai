# Delivery Status

Branch under active review: `feature/wireguard-runtime-diagnostics`
Base: `development/molido-vpn-ai-from-scratch`
Active branch: `feature/wireguard-runtime-diagnostics`

## Release decision

**Stable Production: BLOCKED.** No Stable APK is approved or distributed. The central app-owned TUN service, descriptor handoff, custom WireGuard backend, physical-device acceptance, production signing verification, and remaining engine/legal gates are incomplete.

## Latest CI evidence

- [Run #44945055377](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/44945055377), commit `3e4611db278cf0a52b2c3c075a1e1e5d2e291e60`: Android source validation, backend tests, and Android Keystore emulator instrumentation passed. The emulator log reports **16 tests started, 16 finished, BUILD SUCCESSFUL**. Signed release was skipped for the pull-request event.
- [Run #44945404147](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/44945404147), commit `afc1f61623bea79c6ef81138d0e5fc51ad6d71c0`: completed successfully on the same Android/backend/emulator validation workflow.
- [Run #44945698971](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/44945698971), commit `c2e3abae677cb1390051cc654e13945f21d97747`: Android source validation, backend tests, and all 16 emulator tests passed. The newly added WireGuard source-build job failed during Android SDK package setup because the action received a newline-separated package list as one invalid package argument; no upstream build was attempted.
- Workflow commit `93036fe118d6d9f7a23d00e8bee66aa2410f03a9` corrected the SDK package list to the action's space-separated format. [Run #44945829405](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/44945829405) is running on that correction. Its source-build job has passed SDK/JDK/Go setup and is executing the pinned upstream Go-only build; final result is not yet known.
- The instrumentation manifest test asserts exactly one service has `BIND_VPN_SERVICE`, currently upstream `GoBackend$VpnService`. This is a regression guard only; it does not prove Molido owns TUN centrally.
- The CI workflow now retries only recognizable transient Gradle repository/dependency resolution failures. Compilation, assertion, installation, and instrumentation failures remain hard failures.

CI is **not yet confirmed green on the current branch head**, and the new source-build gate has not yet passed.

## Recent architecture and source review

- `SINGLE_TUN_OWNER_MIGRATION.md` now accurately records that `SessionSupervisor` reserves the shared generation-scoped lease and passes it to WireGuard; the adapter validates it and does not independently acquire a second lease. Session and lease remain separate state ledgers, and Android VPN service ownership remains upstream.
- The exact current AAR source tag `1.0.20260102` was inspected: annotated tag object `3831cab2da844319291459308a6e535d36dde4b3` is reported by GitHub as PGP-signature verified and peels to `09b75c2bd37f749e2a8c85876394854113c74be7`. The peeled commit is not independently signed.
- The source review confirms the current backend creates TUN with `VpnService.Builder.establish()`, detaches the descriptor into private native `wgTurnOn(..., int tunFd, ...)`, and protects its IPv4/IPv6 sockets through its own service. No supported public AAR API for injecting an externally owned TUN FD was found.
- The source tree also pins `wireguard-tools` (GPL-2) and `termux-elf-cleaner` (GPL-3.0-or-later). A Go-only build may avoid packaging these unused command-line tools; this must be proven by the new source-build job and an APK/AAR native-library inventory/license scan. Do not describe the complete native bundle as Apache-2.0-only.
- The new `scripts/ci/build-wireguard-go-only.sh` checks the exact upstream tag object and peeled commit, builds only `libwg-go.so` without initializing the GPL submodules, and rejects AAR output containing `libwg.so` or `libwg-quick.so`. This is a build smoke test, not the production backend migration.

Non-blocking warnings previously observed in CI:
- Deprecated Android system UI APIs in `MainActivity.kt`.
- Unchecked `SpinnerAdapter` to `ArrayAdapter<String>` cast.
- Native WireGuard libraries could not be stripped and were packaged unchanged in the earlier AAR-based build.
- GitHub Actions warns that several configured actions target Node.js 20 while the runner is transitioning to Node.js 24.

## Product decisions

- No human end-user accounts, registration, login, or password-reset flows.
- Machine/device authentication remains separate; admin secrets never belong in the APK.
- Local-first dashboard; unknown values remain unknown. No fabricated public IP, country, ping, speed, handshake, server or provider state.
- Exactly six engine slots and eight complementary cores; no seventh engine or ninth core.
- GitHub Actions is CI, not a permanent VPN server, relay, or backend runtime.
- No paid infrastructure or external service without explicit owner approval.

## Eight-core status

| Core | Current status | Remaining acceptance |
|---|---|---|
| Session & TUN Supervisor | Source Implemented; CI Tested | Replace split session/lease coordination with one authoritative service-owned TUN lifecycle; real descriptor handoff and cleanup acknowledgement |
| Network Intelligence | Source Implemented | Physical Android callback, stale/lost network, Wi-Fi/mobile transition and route behavior |
| Adaptive Path & Policy | Partially Source Implemented | Validated measured path selection; no real Smart/automatic optimization evidence |
| Security & Privacy | Partially Source Implemented | Full threat-model review, secret lifecycle, IPC authentication, dependency/SBOM/license and leak validation |
| Resilience & Recovery | Partially Source Implemented | Process-death/Binder-death recovery, safe retry scheduler, crash and network-switch tests |
| Diagnostics & Observability | Partially Source Implemented | Device evidence, redaction review, actionable diagnostics; telemetry remains disabled by default |
| Control Plane & Configuration | Partially Source Implemented; Server Deployment Required | Deployment source, operational backup/restore, authentication/key operations and real integration; no live server is asserted |
| Update & Integrity | Partially Source Implemented | Download transport, signed manifest/key lifecycle, physical PackageInstaller install/rollback and downgrade/replay tests |

## Six-engine matrix

| Engine | Current status | Remaining gate |
|---|---|---|
| WireGuard | Source Implemented; CI Tested | Migrate from AAR-owned GoBackend.VpnService to reviewed source backend accepting central TUN FD; then device/data-plane validation |
| AmneziaWG | Not Implemented | Official source pin, recursive submodules, reproducible native build, adapter and shared TUN tests |
| V2Ray/Xray | Not Implemented | Pinned official libXray/API v3, isolated Go runtime adapter, IPC/FD lifecycle and traffic tests |
| Psiphon | Blocked | GPL-3.0-compliant distribution decision or written commercial permission, then official library integration |
| Tor | Not Implemented | Official daemon, SOCKS lifecycle, audited TUN-to-SOCKS bridge, DNS/UDP limitations and device tests |
| MASQUE/WARP-compatible | Blocked | Supported public Android embedding/API and terms must be established; no private endpoint reverse engineering |

Only WireGuard is registered in the runtime capability catalog. A registered source adapter is not a verified working VPN. No provider-supplied country inventory is available, so country selection remains unavailable.

## TUN ownership — critical blocker

The app manifest still receives `GoBackend.VpnService` from `com.wireguard.android:tunnel:1.0.20260102`. The app does not yet declare its own central `MolidoVpnService`. The current `TunOwnershipCoordinator` is an in-process lease/state coordinator, not an Android VPN service or FD broker. `NativeEngineProcessService` is status-only process-isolation scaffolding: it does not receive an FD, start a native engine, or acknowledge descriptor closure.

The session supervisor and TUN coordinator are separate state machines but share one coordinator instance; the supervisor reserves the lease and WireGuard validates the exact token. The coordinator is not an Android VPN service or FD broker. Do not add a second VPN service or use reflection/private JNI hooks. Follow the reviewed migration decision: finish the pinned-source, submodule and license review; resolve distribution compatibility; then build a source-based backend that accepts a central-service FD and explicit socket-protection callback, migrate manifest ownership, and add authenticated versioned Binder/ParcelFileDescriptor IPC.

## Physical device and network acceptance

Not yet verified:
- Install/permission/foreground-service behavior on a physical Android phone.
- Authorized WireGuard endpoint handshake and bidirectional traffic.
- IPv4/IPv6 routes, DNS correctness, DNS/IP leaks and fail-closed behavior.
- Wi-Fi/mobile handover, captive portal, airplane mode, network loss and recovery.
- Repeated connect/disconnect, process death, cleanup and descriptor leak behavior.
- Battery, thermal, memory and long-duration stability.

No VPN server/VPS is assumed or requested as a current development dependency. Real endpoint acceptance is a later external prerequisite and must use an endpoint the owner is authorized to access.

## Signing and release

- PR CI release assembly is unsigned/CI validation only; signed-release job is skipped on pull requests.
- Permanent owner-controlled signing identity and matching APK certificate fingerprint have not been verified by a successful signed-release workflow on the approved base branch.
- No APK from any pull-request CI run is a Stable Production deliverable.
- Do not rotate or replace an established signing identity without explicit authorization and backup.

## Current PR state

PR #4 remains **Draft / open / unmerged**. Keep it Draft until the central TUN migration, engine/legal gates, physical-device acceptance, signed artifact verification and all release checks pass.


## Latest verified CI update — 2026-11-22 (supersedes older run summary above)

- Current PR head at verification: `18b85d3a99f02d79bfbace6f04b06d52be9e4273`.
- [Android CI run 45017363826](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/45017363826), run #411: **success**.
- Passed jobs: Android Keystore instrumentation on CI emulator; patched Xray ARM64 AAR build validation; self-hosted control-plane tests; pinned WireGuard Go-only source build; Android source validation (unit tests, lint, release-variant assembly validation, and one-VPN-service manifest guard).
- The Xray job explicitly passed wrapper patch-transformer tests, the Molido-scoped wrapper lifecycle Go tests, ARM64 AAR build, ELF inspection, and dependency license report generation. It does not represent full upstream integration/network tests.
- Artifact ID `14752028831`; ZIP SHA-256 `6ad24b7e404888c1500204bbb729a7b1b7eae799b4e1660642ab4932236d2966`; AAR SHA-256 `b5ddb1314247b5020c3205a2f634871fbe7cf4571bfbf5fcc59d5caa34fb14cb`; extracted ARM64 ELF SHA-256 `e8dec785c9fb2055637d316f395748df5a5ddc115690702a3c89ade749edf028`.
- License inventory: 62 dependency rows, including 2 GPL-3.0, 2 LGPL-3.0, and 2 MPL-2.0 entries. This requires a documented distribution/legal review; no license clearance is claimed. See `architecture/mahsa-xray-core-evaluation.md`, Finding E.
- Release decision remains **BLOCKED**. PR #4 remains Draft/open/unmerged. Xray remains **NOT_IMPLEMENTED / NOT_SELECTABLE**; no physical-device test or real DNS/IP/TCP/UDP/network-handover test has been evidenced.
- The CI artifact expires 2026-11-29. Preserve it only under the project's approved artifact policy; its existence does not authorize release or integration.


## Latest CI and device-test artifact update — 2026-11-22

- PR #4 head: `39f7f44bc04f7b6ba620e761093c83dfa8972601`; remains Draft/open/unmerged.
- [Android CI run 45034998265](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/45034998265): completed **success**. Android source validation, WireGuard Go-only source build, control-plane tests, Xray ARM64 AAR build validation, and Android Keystore emulator instrumentation all passed. Keystore instrumentation log: **24 tests started, 24 finished, BUILD SUCCESSFUL**.
- The signed-release job was **skipped by design**, not failed: the run event is `pull_request` and the branch is `feature/wireguard-runtime-diagnostics`; the workflow only permits signed release on a non-PR event for `development/molido-vpn-ai-from-scratch`.
- A debug-only physical-device test APK was built, verified with APK Signature Scheme v2, and uploaded as artifact `molido-vpn-android-device-test-debug` (artifact ID `14758889913`). APK SHA-256: `897f8c883cfbfb1e6c4af95db7f6f28369a9d805d92ce0674f42f923c23c43cd`. Debug certificate SHA-256: `4fc378e6bd331d1cc1a7d7b7f713f8a20d8d0635e8595fb597f7e10ec17c3840`. This is not a Stable/Release APK.
- Xray dependency license audit is recorded in `docs/security/XRAY_ANDROID_AAR_LICENSE_AUDIT_2026-11-22.md`. The 62-row report contains 2 GPL-3.0, 2 LGPL-3.0 and 2 MPL-2.0 entries. No distribution clearance is claimed.
- The central app-owned `VpnService` and Binder/ParcelFileDescriptor handoff remain **NOT IMPLEMENTED**. Do not add a second VPN service while the WireGuard AAR still merges `GoBackend.VpnService`. The pinned-source WireGuard fork must first accept the central FD and provide verified socket-protection-before-traffic semantics, robust native cleanup and concurrency protection.
- Physical-device traffic tests have **not** been run. Emulator success is not a substitute. The debug artifact is available for a manual test with an authorized WireGuard profile; record DNS, IPv4/IPv6, bidirectional traffic, leak/fail-closed behavior, network handover and repeated cleanup before assigning Android Device Tested.
- Stable Production remains **BLOCKED**. No merge or release was performed.


- Additional pinned-source audit finding: `ipc.UAPIListen` calls `net.FileListener`, which duplicates the listener FD; the original `uapiFile` is not closed or retained on successful WireGuard startup. A normal stop therefore leaks the original UAPI descriptor. This is documented with the upstream blob and required regression tests in `docs/architecture/SINGLE_TUN_OWNER_MIGRATION.md` section 10. The WireGuard fork patch must close the original after successful listener creation and audit all partial inotify failure cleanup paths. No runtime fix is claimed.


## Latest pinned WireGuard UAPI cleanup patch CI — 2026-11-22

- PR head `4e9db4326017c0f8670b8d3001e76976b0033d65`.
- [Push run 45040345839](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/45040345839) and [PR run 45040353154](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/45040353154): all required jobs completed successfully.
- Both runs passed strict Android `api-android.go` patch transformer tests, pinned `ipc/uapi_linux.go` cleanup transformer tests, and pinned WireGuard Go-only native source build.
- Both runs passed Android source validation, manifest single-VPN-service guard, release-variant assembly validation, debug test APK build/signature verification, Xray ARM64 AAR build validation, control-plane tests, and Android Keystore emulator instrumentation. Emulator logs confirm 24 tests started, 24 finished, BUILD SUCCESSFUL.
- Signed release job was skipped by design for pull-request events. No Stable APK was produced or approved.
- UAPI patch closes the returned listener on inotify initialization failure; closes inotify FD and listener on watch-registration failure; and closes inotify FD and listener on RWCancel initialization failure. This is source-transformer and native-build CI evidence only; no Android runtime FD-count or repeated start/stop test has been performed.
- No Molido JNI wrapper integrated into the Android APK, app-owned central `VpnService`, authenticated Binder/ParcelFileDescriptor handoff, or real `VpnService.protect()` callback wiring exists yet. The app still depends on the AAR's `GoBackend.VpnService`.
- Do not claim FD ownership is fully solved: the patch addresses audited UAPI listener partial-failure paths and prior Android entry-point lifecycle issues, but Java/Binder/Go ownership transfer and process-death cleanup remain unimplemented.
- PR #4 remains Draft/open/unmerged. Stable Production remains BLOCKED.



## Latest verified CI and debug-artifact audit — 2026-11-22 (head c39df6a)

- [PR run 45041123188](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/45041123188) and [push run 45041118237](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/45041118237): **all required jobs passed** on commit `c39df6a601d1fe2be4953cd9eb425c786a206981`.
- Passed: Android source validation, unit tests/lint/build checks, exactly-one-VPN-service manifest guard, physical-device debug-test APK assembly and signature verification, 24 Android Keystore instrumentation tests on CI emulator, pinned WireGuard Android entry-point and UAPI cleanup transformer tests plus Go-only native source build, Xray ARM64 AAR build validation, and self-hosted control-plane tests.
- Keystore emulator log: **24 tests started, 24 finished, BUILD SUCCESSFUL**.
- The manifest guard confirms the only VPN service remains upstream `com.wireguard.android.backend.GoBackend$VpnService`; this is expected at this migration stage and is not proof of Molido-owned TUN.
- The uploaded artifact `molido-vpn-android-device-test-debug` (artifact ID `14760859634`) is a **debug-only test APK**, not a release. APK signature verification passed with v2; its signer is the standard **Android Debug** certificate, SHA-256 `39c97819cb3274996fc4c80943a92f9603b45fe6c3f27984f674a2be83a9bdba`. It must not be represented as the permanent owner production signing identity.
- The Xray build-validation artifact is `xray-android-arm64-build-9f17f25e0f48fbfa43b2df7fd5d6c7dae47228de` (artifact ID `14761113734`). A successful AAR build does not clear the GPL/LGPL/MPL distribution obligations, integrate an Xray runtime adapter, or prove traffic.
- CI log warnings include the emulator console startup warning (the instrumentation task nevertheless completed successfully), native-library strip warnings for the currently packaged WireGuard AAR libraries, and GitHub Actions Node.js 20 deprecation notices. These are recorded as warnings, not failed checks; native-library packaging still needs source/AAR inventory review.
- Signed-release jobs were **skipped by design** for the PR event. No production-signed APK, physical-device VPN test, runtime FD-leak test, DNS/IP leak test, or network-handover test was produced by these runs.
- PR #4 remains Draft/open/unmerged. Stable Production remains **BLOCKED**.


## Latest source-integration audit — 2026-11-22

- Current PR head at this documentation update: d687c81d70846d4aa5359700f01c7f3856d212dd. PR #4 remains Draft/open/unmerged.
- Re-inspected pinned WireGuard Android tag 1.0.20260102 Java backend, JNI C exports and Android Go entry point. Exact source blob IDs and findings are recorded in SINGLE_TUN_OWNER_MIGRATION.md section 11.
- Confirmed a further security-ordering defect: upstream Java calls native wgTurnOn, which runs device.Up(), before it calls VpnService.protect() on IPv4/IPv6 sockets. It also ignores protect() boolean results and does not reject negative socket FDs. A post-start check cannot prove protection-before-traffic. Replacement must protect sockets during bind/creation before packet transmission is possible and fail closed on rejection.
- Confirmed JNI exports are named specifically for GoBackend. A new Kotlin wrapper class alone will not resolve these symbols; explicit JNI registration/export changes and proof of the intended .so inside the APK are required.
- The current CI APK still packages the AAR-provided libwg-go.so, libwg-quick.so and libwg.so. The separate Go-only source build has not been integrated into the APK. This remains a hard blocker for claiming the patched source is running in the app.
- No production code was changed in this audit because a superficial wrapper would not close the protection-ordering or JNI-binding gaps. The current upstream VPN service and AAR are retained; no second VpnService, merge, release or production deployment was performed.
- Next implementation gate: design and test the native socket-bind protection callback plus JNI binding and APK native-library substitution as one coherent WireGuard backend change; only then proceed to central VpnService and authenticated Binder/PFD transfer.
