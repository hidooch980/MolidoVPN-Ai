# MolidoVPN AI — Five Workstream Acceptance Plan

Status: acceptance criteria only. This document does not assert that any blocked work, external enrollment, real-network test, physical-device test, or signed release has been completed.

Applies to PR #4 and the six-engine architecture. Keep the PR Draft and Stable release blocked until the applicable evidence below is attached to an exact commit.

## 1. Psiphon — legal decision before implementation
- [ ] Review the exact upstream Android library/core versions, SPDX identifiers, notices, source-offer obligations, redistribution terms, and all transitive dependency licenses.
- [ ] Obtain a written legal/compliance decision: (a) GPL-3.0-compliant distribution and corresponding-source process, or (b) a valid written commercial permission/exception covering the intended embedding and distribution.
- [ ] Separately review Psiphon service Terms of Service and obtain written authorization for any intended third-party client access to the Psiphon network; a code license alone does not grant service access.
- [ ] Record reviewer, date, document references, permitted use, obligations, and approval scope in the repository's license decision record.
- [ ] Until all required approvals exist, keep the Psiphon engine BLOCKED and unavailable in runtime selection; no network connection attempts to the Psiphon service.
- [ ] After approval only: implement adapter under the single TUN owner, then run lifecycle, DNS, bidirectional traffic, cleanup, and failure tests.

Exit evidence: approved legal decision, dependency/license inventory, source/notice compliance artifacts where applicable, adapter commit, and passing test evidence. If approval is denied or unavailable, retain BLOCKED with reason.

## 2. MASQUE — supported integration-path decision
- [ ] Identify an official, public Android SDK/API or documented integration contract that explicitly permits third-party app embedding and production distribution.
- [ ] Verify publisher, supported Android ABIs/versions, authentication/enrollment model, API stability, terms, license, security model, and maintenance/support status from primary documentation.
- [ ] Confirm the integration does not require private endpoints, undocumented APIs, protocol impersonation, or reverse engineering.
- [ ] Record a reproducible proof-of-concept and vendor-supported support path before implementation is approved.
- [ ] If no qualifying supported path is verified, retain MASQUE/WARP-compatible as BLOCKED; do not expose a selectable or connected state.

Exit evidence: official documentation and applicable terms, approved architecture decision, buildable proof-of-concept, and test plan. Absence of evidence means BLOCKED, not presumed support.

## 3. WARP — official enrollment and credential lifecycle
- [ ] Distinguish consumer WARP from Cloudflare One / Zero Trust enrollment; document the exact product and supported enrollment flow being implemented.
- [ ] Use only an official, documented, permitted registration/enrollment and credential-provisioning path. Do not reverse-engineer private endpoints or automate unsupported flows.
- [ ] Generate the device WireGuard private key locally on-device; keep it within app-controlled secure storage backed by Android Keystore where feasible.
- [ ] Never transmit, log, export, back up, or include the device private key in diagnostics, analytics, crash reports, CI artifacts, or Git. Only the public key and the minimum provider-required metadata may leave the device, with user consent.
- [ ] Validate provider responses strictly; encrypt persisted credentials; implement revoke, reset, timeout, rate-limit, retry/backoff, and enrollment cancellation.
- [ ] A successful registration response alone must not mark the tunnel connected. Require actual tunnel lifecycle and independent data-plane checks.
- [ ] Test enrollment refusal, malformed response, timeout, duplicate registration, credential rotation/revocation, app restart, and secure deletion behavior.

Exit evidence: official flow documentation, privacy/data-flow review, tests proving private-key non-disclosure, successful authorized enrollment, and real tunnel/data-plane acceptance. If no supported official flow exists, keep WARP BLOCKED.

## 4. WireGuard — real authorized endpoint acceptance
- [ ] Use a server and profile explicitly authorized for this test; record endpoint identity, server software/version, profile provenance, test window, and Android build without publishing private keys or secrets.
- [ ] Verify the app's single-TUN-owner lifecycle, deterministic stop, descriptor/resource release, and no competing VpnService.
- [ ] Verify bidirectional traffic with independent client/server observations; handshake or byte counters alone are insufficient.
- [ ] Verify IPv4 routing and public egress; verify IPv6 routing and public egress where the server/network supports IPv6. If IPv6 is unsupported, prove the configured fail-closed/no-leak behavior and record the limitation.
- [ ] Verify DNS resolution, selected resolver path, tunnel routing, and absence of unintended physical-interface DNS leakage.
- [ ] Verify route and leak behavior during connect, disconnect, handshake loss, endpoint loss, network handover, and recovery.
- [ ] Verify repeated connect/disconnect, cancellation, rapid retry, process death/restart, and cleanup; no orphaned TUN, stale owner lease, or false connected state.
- [ ] Record packet/route/DNS observations and redacted logs. Never attach private keys, full credentials, or sensitive profile contents.

Exit evidence: signed-off test report with exact app commit/build, device model/Android version, authorized server version, timestamps, pass/fail per case, sanitized logs, and independent data-plane observations.

## 5. Physical Android device and Stable release
- [ ] Install the candidate build on a physical Android device; record manufacturer/model, Android version/API level, build fingerprint, app versionCode/versionName, and install source.
- [ ] Execute the device matrix: permission grant/deny/revoke, connect/disconnect, real authorized traffic, DNS, IPv4/IPv6, Wi-Fi/mobile transitions, network loss/return, process death, reboot, upgrade, rollback, and uninstall.
- [ ] Re-run relevant unit, integration, instrumentation, security, dependency/license, and regression suites after every fix. Skipped tests are not passes.
- [ ] Resolve all Critical/High defects and confirm no known key disclosure, DNS/IP leak, TUN ownership race, orphaned service, or false-positive connected state.
- [ ] Build the release from the exact reviewed commit using controlled CI inputs and a permanent owner-controlled signing identity.
- [ ] Store signing secrets only in protected GitHub Actions secrets or an equivalent secure signing environment; never commit keystore files, passwords, or private signing material.
- [ ] Verify APK signature with independent tooling; record signer certificate SHA-256 fingerprint, APK SHA-256, package ID, version, build commit, and build workflow run.
- [ ] Install the exact signed APK on the physical device and verify signature/package/version after installation.
- [ ] Publish release notes, permissions, privacy disclosures, supported engines, known limitations, and archived acceptance evidence tied to the APK hash.

Exit evidence: physical-device test report, all required CI jobs green, signed APK artifact, verified certificate fingerprint and APK hash, installation evidence, and release notes. Emulator tests and CI assembly are not physical-device acceptance or proof of a signed distributable release.

## Release decision
- [ ] Psiphon: approved or explicitly excluded/blocked with reason.
- [ ] MASQUE: supported path proven or explicitly blocked.
- [ ] WARP: official enrollment proven and private-key non-disclosure tested, or explicitly blocked.
- [ ] WireGuard: real authorized endpoint and data-plane acceptance passed.
- [ ] Physical device: required test matrix passed and evidence archived.
- [ ] Signed Stable: exact APK signature, certificate fingerprint, APK hash, commit, and installation verified.

Do not mark Stable, merge the delivery PR, or claim production readiness until every required release gate is evidenced. A blocked optional engine may remain excluded only if product scope and release notes clearly disclose that exclusion.