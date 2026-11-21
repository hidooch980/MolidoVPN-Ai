# Optional VPN Provider Integrations

Status: provider integration plan only. No provider is enabled, enrolled, authenticated, or connected by this document.

## Non-negotiable architecture

- Keep the six approved VPN engines only: WireGuard, AmneziaWG, V2Ray/Xray, Psiphon, Tor, and MASQUE/WARP-compatible.
- Providers are profiles/backends for those engines, not additional VPN engines.
- Preserve the self-hosted-first design. No provider is a critical dependency for the app.
- A provider may be selected only when its adapter is implemented, its configuration is valid, and its transport has passed real verification.
- Never scrape private APIs, bypass provider access controls, hardcode shared credentials, or ship provider secrets in the APK.
- Keep one Android VPNService/TUN owner. Provider adapters must use the existing session supervisor.
- Never label enrollment, server-list retrieval, or handshake alone as full internet connectivity. Verify handshake, data-plane traffic, DNS behavior, and public egress separately.
- External provider registration and data handling require clear user disclosure and explicit consent.

## Provider matrix

| Provider / source | Engine | Account requirement | Planned integration | Current state |
|---|---|---|---|---|
| Cloudflare WARP consumer | MASQUE/WARP-compatible and/or WireGuard-compatible where officially supported | Consumer device enrollment may not require a user-created account; provider can change or reject registration | Optional device enrollment; local key generation; provider-issued configuration; strict response validation | Planned; no enrollment client |
| Cloudflare Zero Trust / Teams | MASQUE/WARP-compatible | Organization enrollment, administrator policy and device authorization | Separate enterprise profile path; never pretend it is accountless | Not configured |
| Proton VPN | WireGuard | Proton account and generated WireGuard configuration; free/paid plan limits apply | Import generated configuration first; later provider-specific flow only if officially supported | Not configured |
| Psiphon | Psiphon engine | Usually no personal VPN server/account for consumer app use; service availability varies | Investigate supported SDK/integration terms; isolated adapter; no private API imitation | Not implemented |
| Tor Project / Orbot | Tor | No personal VPN account; bridges may be needed on restricted networks | Tor-native transport integration or supported local proxy bridge; preserve single TUN ownership | Not implemented |
| User-owned WireGuard | WireGuard | User/server operator supplies a valid peer profile | Existing standard profile import | Source implemented; device test pending |
| User-owned AmneziaWG | AmneziaWG | User/server operator supplies compatible server configuration | Add only after engine implementation and tests | Not implemented |
| Xray/V2Ray node providers | V2Ray/Xray | Usually a subscription or node credentials | Import user-authorized subscription/config; parse safely and redact secrets | Not implemented |
| Other commercial WireGuard providers (e.g. Mullvad, IVPN, Surfshark) | WireGuard | Provider-specific account/subscription and generated credentials/config | Provider-neutral manual configuration import; no account automation without documented supported API | Not configured |

## Implementation order

1. Provider-neutral catalog and capability/status model. A provider appears as unavailable until its adapter and verification are complete.
2. Cloudflare consumer WARP optional enrollment, only after confirming the current supported registration flow and terms. Generate private keys locally; never transmit them or log them.
3. Generic WireGuard configuration import and validation, enabling providers that issue standard WireGuard profiles without custom APIs.
4. Tor integration research and prototype using supported Tor/Orbot interfaces; do not create a second Android VPNService.
5. Psiphon integration research; proceed only with a documented supported integration route and compatible license.
6. Xray/V2Ray subscription import with strict bounds, scheme allowlist, parser tests, and secret redaction.
7. AmneziaWG engine implementation and server-side compatibility tests.
8. Add optional enterprise Cloudflare Zero Trust only when the user supplies organization authorization.

## UI behavior

- Show provider name, engine, account requirement, external data disclosure, configuration source, and implementation status.
- Distinguish: Not configured, Needs account, Needs profile, Enrollment pending, Ready to test, Handshake verified, Data-plane verified, Device tested, Provider unavailable.
- Do not display a Connect action for Not Implemented or Not Configured providers.
- Do not auto-register, subscribe, purchase, accept terms, or transmit device identifiers without explicit user action.
- Keep user-provided profile secrets encrypted at rest and redact them from diagnostics.
- Provide a clear disconnect/revoke action and remove provider-specific local identity material when revoked.

## Acceptance criteria

- The app works with no third-party provider account by retaining the user-owned/self-hosted WireGuard profile path.
- Accountless options are clearly distinguished from account-required services.
- No subscription or paid service is purchased automatically.
- Provider failure never disables unrelated engines or self-hosted configurations.
- CI success is not presented as proof of a live provider connection.
- Each provider must pass unit, parser/security, Android instrumentation, and physical-device network tests before it is marked ready.
- Terms, supported APIs, and current enrollment policies must be reviewed immediately before implementation; this document is not evidence of provider authorization.

## Verified external requirements (research snapshot)

- Proton's official support flow requires signing in to generate/download a WireGuard configuration. Free plan configuration options are more limited than paid plans.
- Tor's Android VPN mode is a Tor routing mode, not a WireGuard server. Tor-powered apps may be excluded from routing through Tor VPN to avoid loops.
- Psiphon provides its own Android VPNService-based app; integration into this app requires a separately verified supported integration approach.

## Research-only candidate: MasterDnsVPN (DNS tunnel)

Decision: **Approved for source/security evaluation only; runtime integration and connection remain DISABLED.**

Why it is the strongest candidate among GooseRelayVPN, Skirk, and MasterDnsVPN:
- The upstream core is Go-based and MIT-licensed, making source-level review and a controlled build technically feasible.
- A separate community Android client exists, but it is not the upstream project's official Android application.
- The transport carries TCP through DNS queries and responses; it is an experimental fallback transport, not a free public VPN or an exit service.

Hard blockers:
- It requires an operator-controlled reachable server, a delegated tunnel subdomain (A + NS records), a matching encryption key, and working DNS resolvers. The Android community client README explicitly requires an operational MasterDnsVPN server before connecting.
- It therefore cannot be made operational under the current GitHub-only/no-server/no-domain constraint. No server, domain, resolver, public IP, or connection status may be fabricated.
- DNS tunneling can have high latency, limited throughput, resolver-dependent reliability, and detectable unusual DNS patterns. It must not be marketed as equivalent to WireGuard or a guaranteed censorship bypass.
- The upstream core and community Android wrapper require separate source, dependency, license, signing, privacy, and supply-chain audits before any code reuse.

Architecture decision:
- Do NOT add a seventh engine slot.
- Do NOT mislabel MasterDnsVPN as WireGuard, Xray, Tor, or MASQUE/WARP; it is a distinct DNS-tunnel protocol and cannot honestly be represented as one of those engines.
- Do NOT register an EngineAdapter, expose a Connect action, add a selectable profile, or start a second VPNService/TUN owner.
- Keep this entry as an active research item only. Reconsider implementation only after an explicit architecture decision resolves the six-engine constraint and a lawful, user-authorized server/domain endpoint is available. Any future prototype must use the existing single-TUN supervisor and pass unit, security, Android instrumentation, and physical-device tests before it can be enabled.

Current status: **Preliminary source review complete; critical crypto and secret-storage findings open; complete audit pending; runtime disabled; no connection tested; not production-ready.**

Review report: [docs/research/MASTERDNSVPN_SECURITY_REVIEW.md](../research/MASTERDNSVPN_SECURITY_REVIEW.md). No Molido runtime code or engine catalog was changed.
