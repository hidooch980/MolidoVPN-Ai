# Master Prompt Compliance Audit

Repository: hidooch980/MolidoVPN-Ai
Branch: development/molido-vpn-ai-from-scratch

## Immutable architecture

- Exactly six approved engine slots and eight complementary cores.
- One TUN owner at a time; WireGuard upstream GoBackend owns the Android VPN service.
- WireGuard is the first engine. Five other engine slots remain inventory-only.
- Self-hosted architecture; GitHub Actions is CI/CD, not VPN infrastructure.
- No fabricated endpoint, network measurement, server status, handshake, traffic, or test result.
- No infrastructure or permanent signing identity is provisioned without owner approval.
- Stable Production only; no experimental APK distribution.
- **No end-user accounts:** no registration/login/password flows. Device identity and operator authentication are separate machine/server concerns.

## Core status

| Core | Status | Evidence / boundary |
|---|---|---|
| Session & TUN Supervisor | Source Implemented | Exclusive controller and cleanup retry tests |
| Network Intelligence | Source Implemented | Passive default-network observation, timestamped assessment, stale/lost-network rejection; physical Android callback behavior pending |
| Adaptive Path & Policy | Partially Source Implemented | Fresh Android-observed connectivity gates start; selectable network policies |
| Security & Privacy | Partially Source Implemented | Redaction, HTTPS-only bounded client, Android Keystore encrypted trust material; full security validation pending |
| Resilience & Recovery | Partially Source Implemented | Bounded backoff and user-triggered cleanup preparation; no automatic reconnect or process-death recovery |
| Diagnostics & Observability | Partially Source Implemented | Disabled-by-default bounded local ring buffer |
| Control Plane & Configuration | Partially Source Implemented | Self-hosted FastAPI source, ECDSA config signatures, Android HTTPS client; server not deployed |
| Update & Integrity | Partially Source Implemented | Signed manifest/digest verification and PackageInstaller staging; download transport and device-level rollback validation pending |

## Engine status

| Slot | Status |
|---|---|
| WireGuard | Source Implemented; device/server validation pending |
| AmneziaWG | Not Implemented |
| V2Ray/Xray | Not Implemented |
| Psiphon | Not Implemented |
| Tor | Not Implemented |
| MASQUE/WARP-compatible | Not Implemented |

Do not add a seventh engine. Each remaining engine needs license, dependency, security and one-TUN compatibility review.

## Dashboard

- Native Android dashboard uses dark layered/glass cards and raised teal controls.
- Displays live session lifecycle and Android default-network assessment.
- Profile import is local and memory-only.
- Network policy options: ANY_VALIDATED, UNMETERED_ONLY, WIFI_ONLY.
- Only WireGuard can be selected; the other five slots remain visibly unavailable.
- No fabricated public IP, country, ping, throughput, handshake, or server status.
- Dashboard visual/device interaction validation remains pending.

## CI evidence

[Android CI #122](https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/44576617166), commit `86bc0c62d655fab4a3d34a1cad62ba7ddd37755e`: backend tests, Android unit tests, Android lint, and release-variant assembly all completed successfully. This is CI Tested only, not Android Device Tested, Server Deployment Verified, or Production Verified.

## Remaining release blockers

- Physical Android tests and authorized WireGuard endpoint.
- DNS/IPv4/IPv6/leak tests and real network transition/recovery.
- Production backend deployment only if explicitly approved.
- Owner-approved permanent signing identity.
- Production update download transport and physical PackageInstaller tests.
- Five unimplemented engines.
- Automatic retry scheduling/process-death recovery remain unimplemented.
- Full threat-model validation, dependency scanning and penetration testing.
- Stable Production release gates remain blocked.
