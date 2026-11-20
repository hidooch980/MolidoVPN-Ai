# MolidoVPN AI

**A self-hosted, privacy-first VPN platform with an Android client and an adaptive multi-engine architecture.**

## Current engine delivery status

| Engine / subsystem | Current verified state |
|---|---|
| WireGuard | Source adapter and CI validation; real Android device/server acceptance pending |
| TUN ownership / native IPC | Process-isolation scaffold only; unified TUN service and descriptor handoff not implemented |
| Tor | Official Tor Android and MIT tun2socks candidates reviewed; no integrated Tor VPN adapter |
| Xray / libXray | Official API and Go-runtime isolation requirements reviewed; no runtime adapter |
| AmneziaWG | Official source build path reviewed; source build not integrated |
| Psiphon | GPL-3.0 confirmed; distribution/license approval required before bundling |
| MASQUE / WARP | No supported public consumer embedding SDK/API established; integration blocked |

Engines without a complete registered adapter remain unavailable in the app. CI build success is not device connectivity evidence. Debug/Test APKs are not published as owner deliverables; only verified Stable Production artifacts may be delivered.

**A self-hosted, privacy-first VPN platform with an Android client and an adaptive multi-engine architecture.**

This repository is the clean, authoritative home of MolidoVPN AI. Product development takes place on `development/molido-vpn-ai-from-scratch`. No legacy repository or previous implementation is treated as a product source of truth.

## Non-negotiable architecture
- Exactly **6 VPN engines** and **8 complementary platform cores**. No seventh engine and no ninth core.
- One Android TUN owner at a time, controlled by a single session supervisor.
- WireGuard is the first implementation and validation milestone.
- Local-first intelligence; open-source components; no critical dependency on external SaaS or proprietary APIs.
- Self-hosted backend and server source are prepared for future deployment. GitHub Actions is CI/CD, not a permanent VPN or backend runtime.
- No fabricated endpoints, server locations, IP addresses, network measurements, availability, or test results.
- Only Stable Production artifacts may be delivered to the owner. No Debug, Alpha, Beta, Preview, or Test APK distribution.
- CI build success is not proof of real VPN connectivity. Device tests and server deployment are reported separately.
- No paid infrastructure or service is provisioned without explicit owner approval.

## Current delivery status
The repository and isolated development branch have been initialized. This is an engineering baseline, not a production release. Android device validation, real server deployment, permanent release signing, and production verification remain separate gates.

See [Master specification](docs/MASTER_SPEC.md), [Architecture](docs/ARCHITECTURE.md), [Delivery status](docs/DELIVERY_STATUS.md), and [Security policy](SECURITY.md).
