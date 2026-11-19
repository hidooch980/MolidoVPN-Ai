# MolidoVPN AI — Master Engineering Specification

## 1. Product mandate
Build MolidoVPN AI from a clean baseline. This specification and the owner's master prompt are the only product requirements authority. Do not import code, architecture, release assumptions, credentials, or decisions from legacy repositories.

The product is intended to provide resilient, privacy-preserving connectivity across difficult network conditions, including restrictive and unstable networks. It must not promise connectivity during a complete loss of all usable routes to the public internet. In that condition it may preserve local/offline functionality and recover when a route becomes available.

## 2. Immutable constraints
1. Exactly six VPN engines.
2. Exactly eight complementary platform cores.
3. A single Android TUN owner at any instant.
4. A central session supervisor arbitrates engine lifecycle, routing, handover, and recovery.
5. WireGuard is the first engine to implement and validate end-to-end.
6. No other engine is marked ready merely because an adapter, dependency, UI item, or configuration exists.
7. No Debug/Beta/Alpha/Preview/Test APK is distributed to the owner. Engineering CI may run tests, but owner-facing releases are Stable Production only.
8. No fake server inventory, countries, IPs, endpoint health, traffic counters, latency, throughput, or security-test results.
9. Self-hosted operation is the target. External SaaS/API services must not be critical dependencies.
10. Telemetry is off by default or strictly minimized, anonymous, documented, and privacy-preserving.
11. No infrastructure spending, paid API, domain, VPS, or hosting purchase without explicit approval.
12. Preserve repository history and release identity. Never expose signing keys or secrets in source control.
13. A successful CI build does not prove real-device connectivity or production readiness.
14. All capabilities carry an explicit implementation/verification status.

## 3. Fixed engine inventory (six)
1. WireGuard — first implementation and first real data-plane validation.
2. AmneziaWG.
3. V2Ray/Xray family.
4. Psiphon.
5. Tor transport integration.
6. MASQUE/WARP-compatible transport integration.

This is a bounded inventory, not a claim that any engine is currently implemented, independently deployable, or suitable for every network. Before integration, each engine requires a license review, threat model, upstream maintenance review, protocol compatibility review, and a real Android data-plane plan. Do not add Hysteria2, NaiveProxy, Shadowsocks, Snowflake, or any other protocol as a seventh engine. A compatible protocol may only be considered as an implementation detail within an existing approved slot after architecture review.

## 4. Eight complementary platform cores
1. **Session & TUN Supervisor** — sole authority for VPN session lifecycle and TUN ownership.
2. **Network Intelligence** — collects permitted local network signals and estimates current network conditions without fabricating observations.
3. **Adaptive Path & Policy Core** — selects among validated paths using explainable, measured signals and user policy.
4. **Security & Privacy Core** — key handling, secure storage, least privilege, threat controls, privacy boundaries.
5. **Resilience & Recovery Core** — reconnect, backoff, handover, captive portal handling, offline state, crash recovery.
6. **Diagnostics & Observability Core** — local diagnostics, redacted logs, health state, opt-in anonymous telemetry.
7. **Control Plane & Configuration Core** — authenticated configuration, policy, device enrollment, and future self-hosted administration.
8. **Update & Integrity Core** — signed update metadata, artifact verification, rollback protection, safe recovery.

These cores complement the six engines; they are not additional VPN engines.

## 5. Android client
- Native Android client with a clear connection state machine.
- Use Android `VpnService`; only the supervisor may establish/tear down the TUN.
- Engine adapters must not independently create or own a TUN.
- Explicit states: idle, preparing, connecting, connected, reconnecting, disconnecting, blocked/error, and offline/unavailable.
- Fail closed when policy requires a tunnel and the tunnel is not established.
- DNS, IPv4, IPv6, route, kill-switch, and recovery behavior must be tested on a physical Android device before production claims.
- Store credentials and private keys using Android platform-backed secure storage where supported.
- Permission, foreground-service, battery, and Android-version behavior must be handled transparently.
- No hardcoded production endpoint or embedded private key.

## 6. Backend and server source
Prepare a self-hosted backend and server deployment source with authenticated device enrollment/revocation, signed configuration distribution, actual server inventory/health, key lifecycle/rotation, access control/audit trail, health/readiness endpoints, backup/restore, upgrade/rollback, and reproducible deployment documentation.

Until an operator provisions infrastructure and deploys it, backend/server capabilities must be labelled **Server Deployment Required**. GitHub Actions must never be represented as the VPN server, permanent API backend, relay, or traffic proxy.

## 7. Adaptive intelligence
- Begin with deterministic, explainable rules based on measured signals.
- Do not claim autonomous learning, AI optimization, or prediction without implemented models, datasets, evaluation, and reproducible evidence.
- No external AI API is required for core operation.
- Any future model must be bounded by security policy, resource limits, privacy requirements, and a safe deterministic fallback.
- Never select an engine solely from marketing claims or unverified network assumptions.

## 8. Privacy and security
- Minimize collection; do not collect browsing content or destination history for analytics.
- Redact secrets, tokens, private keys, and sensitive endpoint data from logs.
- Use modern cryptography and maintained upstream implementations; do not invent cryptographic primitives.
- Pin and review dependencies; track licenses and known vulnerabilities.
- Protect update integrity and prevent downgrade/replay.
- Provide a documented threat model, security contact, and incident response procedure.
- Never commit secrets, signing keystores, passwords, or private keys.

## 9. Build, release, and delivery
- GitHub Actions is used for reproducible CI, security checks, and release automation only.
- The owner-facing artifact channel is Stable Production only.
- No release is published until permanent signing identity, reproducible release build, dependency/security checks, release notes, and required test evidence are in place.
- Preserve the signing identity once established; back it up securely under owner control.
- Do not generate a replacement signing identity without explicit authorization.
- Production verification requires physical-device testing against a real, authorized server deployment.

## 10. Required status vocabulary
Every feature and engine must use one or more of:
- Not Implemented
- Source Implemented
- CI Built
- CI Tested
- Android Device Tested
- Server Deployment Required
- Production Verified
- Blocked

Statuses must be evidence-based and scoped to a specific commit/build/device/server where relevant.

## 11. Definition of done
MolidoVPN AI is not production complete until all six engine slots and eight cores have documented implementation states; WireGuard first-run and recovery are validated on a real Android device; DNS/IPv4/IPv6 leak and routing tests pass; a self-hosted server deployment is verified; security and privacy controls are reviewed; stable signing and update integrity are proven; and a Stable Production release is published with traceable evidence.
