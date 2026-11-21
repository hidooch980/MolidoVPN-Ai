# MasterDnsVPN — Preliminary Source & Security Review

Review date: 2026-11-21  
Review type: static source inspection of public upstream and community Android repositories.  
Execution status: no code was built or run; no APK was installed; no server or DNS endpoint was contacted. Findings below are source observations, not a penetration test.

## Repositories and reviewed revisions

- Upstream core: https://github.com/masterking32/MasterDnsVPN
  - inspected main tree at `7198b45b86c7def2e226e41bcca3eca8ca2ea502`
  - license: MIT (`LICENSE`).
  - Go module declares Go 1.25.0.
- Community Android wrapper: https://github.com/Hidden-Node/MasterDnsVPN-AndroidClient
  - inspected main tree at `167312c99253f2d593b2444372eed75c6215c1e4`
  - repository metadata identifies it as a community client, not the upstream official Android app.
  - Android release page currently lists v1.2.4; this review did not verify or install its APK.

## Executive decision

**NO-GO for runtime integration or activation at this time.**  
**GO for continued isolated source review only.**

MasterDnsVPN is a DNS-carried TCP transport with a client/server design. It is not a free public exit service. It needs an operator-controlled server, a delegated tunnel domain, a matching encryption key, and reachable DNS resolvers. It cannot provide a working connection under MolidoVPN AI's current no-server/no-domain constraint.

The community Android app is not suitable for direct transplantation: it declares its own Android `VpnService`, establishes a TUN interface, and starts a Go gVisor TUN bridge. Copying that application/service into MolidoVPN would create a second TUN owner and violate Molido's single-owner architecture.

## Findings

### F-01 — CRITICAL: weak cryptographic modes are defaults

Evidence:
- Upstream `internal/config/client.go`: default `DataEncryptionMethod: 1`.
- Upstream `internal/config/server.go`: default `DataEncryptionMethod: 1`.
- Community Android `ProfileEntity.kt`: default `encryptionMethod: Int = 1`.
- Upstream `internal/security/codec.go` maps method 0 to no encryption, method 1 to repeating-key XOR, and method 2 to unauthenticated ChaCha20. Methods 3–5 use AES-GCM; method 3 derives through MD5.

Impact:
- Default profile creation does not select authenticated encryption.
- Method 0, XOR, and unauthenticated ChaCha20 must not be accepted for a security-sensitive Molido integration.
- AES-GCM methods need compatibility and key-derivation review; method 3's MD5 derivation is not acceptable for a new secure profile.

Required gate:
- Reject methods 0–4 in any Molido-controlled profile path; allow only method 5 (AES-256-GCM) after confirming both client and server interoperate.
- Require high-entropy generated key material, not a human password. Review the upstream key-length/generation behavior because current server key generation produces 32 hexadecimal characters for the default non-AES-128/192 cases; do not describe the resulting SHA-256 output as 256 bits of input entropy.
- Add negative tests proving weak methods fail closed. Do not silently upgrade a profile because that can break interoperability.

### F-02 — HIGH: Android profile secrets are stored in an ordinary Room database

Evidence:
- Community Android `ProfileEntity.kt` stores `encryptionKey` and `advancedJson` as ordinary entity fields.
- `AppDatabase.kt` uses the default `Room.databaseBuilder(..., "masterdns_vpn.db")`; no database encryption builder/key is visible in the inspected file.
- `MasterDnsVpnService.kt` writes a generated client TOML containing `ENCRYPTION_KEY` into app-private `filesDir/config/client_config.toml`.
- Manifest sets `allowBackup=false`, which is useful but does not encrypt local database/config files.

Impact:
- Profile keys and advanced credentials are not shown as encrypted at rest by the inspected persistence path.

Required gate:
- Do not import this persistence design into Molido. Store secrets using an Android Keystore-backed encryption envelope, minimize plaintext lifetime, redact logs, and define deletion/revocation behavior.
- Verify the full write/read/migration/export path before any production use.

### F-03 — CRITICAL ARCHITECTURE: community Android app owns a separate VPNService/TUN

Evidence:
- Manifest declares `MasterDnsVpnService` with `android.permission.BIND_VPN_SERVICE`.
- `MasterDnsVpnService.kt` extends `VpnService`, calls `Builder.establish()`, then passes its TUN fd to `mobile.Mobile.startTunGvisor(...)`.
- The service also manages Go core start/stop and its own TUN lifecycle.

Impact:
- Embedding this Android service into Molido would introduce another VPNService/TUN owner. That is prohibited.

Required gate:
- Never copy or register the community Android service in Molido.
- If future work is approved, isolate only the upstream Go DNS transport/client and expose its local SOCKS5 endpoint behind a Molido-owned adapter. The existing Molido supervisor must remain the sole TUN owner. This requires a separate design proving how packets from the one TUN are safely bridged to the local SOCKS5 endpoint; no such integration is claimed here.

### F-04 — HIGH: imported configuration and parser require a dedicated security review

Evidence:
- Community `ProfileTomlImporter.kt` is a hand-written line parser: it strips text after `#`, handles a limited flat key/value shape, and joins a simple `DOMAINS` array.
- `ConfigGenerator.kt` writes profile key material and many advanced values into a generated TOML file.

Impact:
- This is not a general TOML parser and must not be treated as one. Quoted values containing comment characters, escapes, arrays, duplicate keys, and malformed input need adversarial coverage.

Required gate:
- Use a maintained strict TOML parser or a narrowly defined schema parser; reject unknown/duplicate/oversized values; validate domains, resolver addresses, ports, and enum ranges; never log secret-bearing config.

### F-05 — MEDIUM: upstream security-response policy appears to be a template

Evidence:
- Upstream `SECURITY.md` contains generic version rows (5.1.x / 5.0.x / 4.0.x) and generic vulnerability-reporting instructions.

Impact:
- The published security support matrix and disclosure process are not sufficiently project-specific to rely on for incident response.

Required gate:
- Confirm supported release branches and a real private vulnerability-reporting channel with maintainers before depending on upstream security response.

### F-06 — MEDIUM: build provenance and Android binary reproducibility need verification

Evidence:
- Community `android/BUILDING.md` says the Go mobile AAR is a gitignored build artifact and must be rebuilt with the provided gomobile script.
- Android Gradle build runs `verifyMobileAar` and checks expected Go mobile symbols.
- Android build targets SDK 36 and Go core declares Go 1.25.0.

Impact:
- A release APK alone is not enough to prove the embedded Go core matches reviewed upstream source.

Required gate:
- Rebuild the AAR from a pinned upstream commit in a clean, reproducible CI job; record Go, gomobile, NDK, Gradle, dependency, and source hashes; compare the produced APK/AAR contents with reviewed source. Verify release signing independently.

## Integration compatibility assessment

| Area | Preliminary result | Reason |
|---|---|---|
| Upstream Go core reuse | Potentially feasible, not approved | MIT license; still needs crypto hardening and reproducible build |
| Community Android UI/service reuse | Rejected as-is | Owns a separate Android VPNService/TUN |
| Existing Molido six-engine slots | Not compatible as a new engine | MasterDnsVPN is a distinct DNS-tunnel protocol; do not mislabel it as WireGuard/Xray/Tor/MASQUE |
| Existing single TUN owner | Must remain unchanged | Any future adapter must feed the existing supervisor, never establish another TUN |
| Current no-server/no-domain constraint | Blocked | No valid DNS tunnel endpoint or exit exists |
| Production activation | Blocked | Crypto, secret storage, architecture, server, and device acceptance gates remain open |

## Required next steps

1. Obtain a source-level review of the complete packet/session lifecycle, DNS parser, server ingress, resolver behavior, SOCKS5 boundary, and resource limits.
2. Confirm encryption method 5 interoperability and define a secure key provisioning format. Do not change upstream protocol or defaults without compatibility tests.
3. Review Android secret persistence, generated config cleanup, logs, exported components, imported profiles, subscriptions, and sharing features.
4. Produce a pinned-source Go mobile build and software bill of materials; verify all transitive dependencies and licenses.
5. Write an architecture proposal for a SOCKS5 transport adapter behind the existing single-TUN supervisor. This proposal must not add a seventh engine slot or register a connectable adapter.
6. Only after the above passes, request explicit approval for a prototype. Real connectivity tests still require a lawful user-controlled server/domain and Android device validation.

## Current status

- Source inspection: **Preliminary complete**
- Critical security findings: **Open**
- Complete security audit / penetration test: **Not performed**
- Build / CI test of external projects: **Not performed**
- Android APK install/device test: **Not performed**
- Molido runtime code changes: **None**
- New engine slot / second VPNService / TUN: **None**
- Runtime activation: **DISABLED**
- Stable/production claim: **NOT APPROVED**
