# MasterDnsVPN remediation and integration gates

Date: 2026-11-21
Status: BLOCKED — no runtime integration, no prototype, no merge authorization for this candidate.

This plan translates the expanded static audit into implementation and verification work. It is intentionally documentation-only until every security gate is satisfied. The current community Android client must not be copied into Molido as-is.

## Non-negotiable Molido constraints

- Preserve exactly six existing engine slots and eight platform cores.
- MasterDnsVPN is not a seventh engine. It may only be reconsidered as a transport implementation behind an already approved slot if a documented product/architecture decision proves that classification valid. Do not silently relabel it.
- The existing central Supervisor and single VPNService/TUN provider remain the only owners.
- No adapter may create a VPNService, call `VpnService.Builder.establish()`, acquire a second lease, or bypass the supervisor.
- No server, resolver, country, IP, ping, latency, or connected status may be fabricated.
- No real connection can be verified without an authorized server, delegated DNS domain, resolver endpoint, and physical Android test device.
- No secrets may be added to repository files, CI logs, test fixtures, or plaintext app storage.

## Work packages and acceptance criteria

### A. Source provenance and licenses
- Pin immutable upstream core and Android-client commits.
- Produce a complete source diff between upstream and the community mobile wrapper; classify every changed, added, and removed file.
- Generate SBOMs for Go, gomobile, gVisor, Gradle, Kotlin, AndroidX, and native/AAR artifacts.
- Scan and archive all transitive license texts and notices.
- Rebuild the mobile AAR from a clean pinned checkout and record toolchain versions and SHA-256 hashes.
- Acceptance: reproducible build procedure, complete provenance, no unresolved license incompatibility.

### B. Cryptographic protocol
- Define a versioned protocol suite that rejects no-encryption, XOR, and unauthenticated ChaCha20 modes.
- Do not rely on MD5, zero-padding, or unsalted single-pass hashes for password/key derivation.
- Define key entropy, encoding, provisioning, rotation, revocation, nonce uniqueness, and compatibility behavior.
- Use a reviewed AEAD construction with authenticated metadata and replay/tamper protection.
- Add cross-language client/server test vectors, wrong-key tests, modified-ciphertext tests, nonce-reuse tests, replay tests, truncation tests, and downgrade tests.
- Acceptance: independent crypto review and all protocol tests pass. No fallback to insecure legacy mode.

### C. Android secret lifecycle
- Replace ordinary Room plaintext key fields with Android Keystore-backed envelope encryption.
- Keep plaintext key material in memory only for the shortest possible duration.
- Avoid writing secrets to generated TOML; if unavoidable, use tightly scoped private files, atomic writes, restrictive permissions, cleanup on all exits, and no backups.
- Redact secrets from logs, crash reports, analytics, intents, exports, and diagnostics.
- Define migration for existing profiles and fail closed when migration cannot securely protect a secret.
- Acceptance: instrumentation tests for create/read/update/delete, migration, process death, crash cleanup, backup/export, and wrong/unavailable Keystore key.

### D. Android network security and input validation
- Remove app-wide cleartext HTTP permission. If local loopback cleartext is indispensable, scope it narrowly to loopback only.
- Validate subscription URLs, redirects, DNS names, resolver addresses, TOML, profile imports, QR payloads, lengths, and parser bounds.
- Add strict timeout, retry, cancellation, resolver allowlist, and network-change handling.
- Acceptance: tests reject malformed/oversized input, unsafe URLs, redirects to disallowed targets, and cleartext remote endpoints.

### E. TUN and gVisor ownership
- Refactor the bridge to accept only a descriptor supplied by Molido's central service under the exact active lease token.
- The bridge must never instantiate or register a VPNService and must never call Builder.establish().
- Remove the unsafe dup-failure fallback. Descriptor duplication failure must fail closed and preserve ownership until cleanup is confirmed.
- Define explicit ownership of original FD and duplicated FD; each must close exactly once.
- Remove or constrain global singleton state so start/stop/cancel/reconnect and concurrent requests are deterministic.
- Do not start another Go runtime in an existing engine process; preserve process isolation.
- Acceptance: unit/instrumentation tests for dup failure, FD leak, double close, stale lease, wrong PID, process death, cancellation, timeout, partial start, stop idempotency, reconnect, concurrent starts, and cleanup acknowledgement.

### F. DNS/SOCKS transport correctness
- Review DNS wire format, label encoding, size limits, response matching, fragmentation/reassembly, compression, malformed packets, cache behavior, and transaction handling.
- Review DNS rebinding, poisoning, resolver failover, split-horizon behavior, captive portals, IPv4/IPv6, UDP/TCP DNS, DoT/DoH interaction, and network handover.
- Establish an explicit support matrix for TCP, DNS, UDP, QUIC, and unsupported traffic. Do not call it a full IP VPN unless all required traffic classes are actually supported.
- Fuzz parsers and state machines; add packet loss, reordering, duplication, truncation, timeout, and congestion tests.
- Acceptance: protocol fuzzing has no unresolved crash, panic, memory-safety, or state-corruption issue; documented network behavior matches observed tests.

### G. Molido adapter boundary
- Only after A–F pass, implement a disabled-by-default adapter in a dedicated isolated process.
- Adapter receives a Supervisor-issued lease and descriptor; it exposes start, stop, health, and cleanup acknowledgement only.
- It cannot claim its own lease, create a TUN, persist profiles/secrets, or publish provider inventory.
- Catalog readiness must remain BLOCKED/NOT_IMPLEMENTED until tests and required external prerequisites pass.
- Acceptance: architecture tests prove exactly six engine slots, eight cores, one TUN owner, no extra VPNService declaration, no fake countries/status, and no unapproved network start.

### H. Test and release sequence
1. Static analysis, formatting, dependency/license/SBOM checks.
2. Go unit tests, race tests, fuzz smoke tests, Kotlin/JVM unit tests.
3. Android lint, debug/release assemble, instrumentation tests on emulator.
4. Failure-path tests for lease ownership and cleanup.
5. Only after a lawful test server/domain exists: end-to-end DNS transport tests on physical Android, including DNS leak, IPv4/IPv6, resolver failover, network switching, and packet loss.
6. Rebuild signed artifact from the same tested commit; verify signer certificate fingerprint and artifact hash.
7. Independent review and explicit human approval before changing Draft status, merging, or publishing.

## Current execution record

- Expanded static source audit: completed for selected source paths; not a full line-by-line audit.
- Top-level MIT license metadata: checked; transitive license audit pending.
- Crypto/key weaknesses: identified; not fixed in upstream or Molido runtime.
- Android plaintext secret persistence: identified; not fixed.
- App-wide cleartext policy: identified; not fixed.
- Separate VPNService/TUN ownership: identified; not refactored.
- gVisor descriptor fallback: identified; not fixed.
- DNS protocol fuzzing and end-to-end validation: not run.
- SBOM and reproducible AAR build: not produced.
- Molido runtime adapter: not implemented.
- New engine slot / new core / second VPNService: none.
- CI, emulator, APK build, device test: must be checked from Actions for the commit that contains this plan; no test pass is asserted by this document.
- Production or stable release: not approved.

## Stop conditions

Any unresolved critical/high security finding, license issue, inability to reproduce the AAR, TUN ownership ambiguity, descriptor cleanup uncertainty, unsupported traffic class misrepresented as supported, or missing external test prerequisites keeps this candidate blocked. Do not merge merely to make the status green.


### F2. DNS request/response correlation and bounded lifecycle (added second-pass gate)
- Repair the first-query response path: preserve a bounded pending-request record containing transaction identity, original local UDP peer, response callback/socket generation, deadline, and cancellation handle.
- Match reassembled tunnel responses to exactly one pending requester; preserve/patch the DNS transaction ID safely and prevent late/duplicate responses from reaching a reused request slot.
- Define deterministic SERVFAIL/REFUSED behavior for malformed queries, unavailable session, queue saturation, timeout, and upstream failure.
- Replace one-goroutine-per-datagram ingress with bounded concurrency and backpressure; synchronize socket lifecycle, wait for in-flight handlers, and prevent callbacks after listener shutdown.
- Acceptance: first uncached lookup succeeds on the first attempt; concurrent same-name queries each receive the correct transaction ID; stop-under-load and callback-after-stop pass Go race tests; overload and cancellation remain bounded.

### F3. Authenticated packet metadata and SOCKS destination policy (added second-pass gate)
- Do not treat the custom header check byte as packet authentication. Authenticate the complete packet header and payload together using a versioned AEAD scheme and associated data; reject legacy modes without downgrade.
- Resolve SOCKS hostnames under a controlled policy and validate every A/AAAA result immediately before dialing; block loopback, private, link-local, ULA, multicast, unspecified, carrier-grade NAT, reserved, and metadata-service ranges as applicable.
- Pin the validated resolution to the actual dial attempt to prevent DNS rebinding between validation and connection.
- Cancel losing DNS hedge requests after a valid response, propagate context cancellation through resolver operations, and cap concurrent DNS work.
- Acceptance: tamper tests for every header field, replay/downgrade tests, DNS-rebinding and mixed-answer tests, and load tests demonstrating bounded goroutines/sockets/memory.
