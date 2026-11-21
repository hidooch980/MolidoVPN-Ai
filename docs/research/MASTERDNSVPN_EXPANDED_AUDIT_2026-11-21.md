# MasterDnsVPN — Expanded Static Security & Integration Audit

Audit date: 2026-11-21  
Scope: expanded static source inspection of the upstream Go project and the community Android client.  
Decision: **NO-GO for prototype, runtime integration, or activation.**  
Permitted work: documentation and isolated source research only.

## 1. Exact source revisions inspected

- Upstream Go core: [masterking32/MasterDnsVPN](https://github.com/masterking32/MasterDnsVPN), branch `main`, commit/tree snapshot `7198b45b86c7def2e226e41bcca3eca8ca2ea502`. Repository metadata reports MIT.
- Community Android client: [Hidden-Node/MasterDnsVPN-AndroidClient](https://github.com/Hidden-Node/MasterDnsVPN-AndroidClient), branch `main`, commit `167312c99253f2d593b2444372eed75c6215c1e4`, tree snapshot `167312c99253f2d593b2444372eed75c6215c1e4`. Repository metadata reports MIT and describes itself as a community client.
- The Android client is a separate repository, not the upstream core repository's official Android application. Its Go module is based on the same module name but carries additional/different dependencies (including gVisor) and different pinned versions. The precise source delta from upstream was not exhaustively diffed; do not assume byte-for-byte upstream equivalence.

## 2. Executive decision

**NO-GO. Do not create the requested prototype yet.** The audit did not pass its security gate.

Reasons:
1. Default encryption is XOR, and other selectable modes include unauthenticated ChaCha20 and AES modes with inadequate key derivation.
2. The Android client stores profile key material in ordinary Room fields and writes the secret into a plaintext TOML config file.
3. The community Android app owns its own `VpnService`, calls `Builder.establish()`, and starts a separate gVisor TUN bridge.
4. The gVisor bridge's fd-duplication helper falls back to the original descriptor if `dup` fails, explicitly accepting a double-close risk.
5. The transport is DNS-over-UDP and requires a user-controlled server, delegated tunnel domain, matching key, and reachable resolver infrastructure. It is not a free public VPN exit.
6. The Android app permits cleartext traffic at the application network-security base configuration.
7. The Android mobile wrapper and its build provenance/dependency relationship to the upstream core require a separate reproducibility and supply-chain review.

Because these are unresolved security and architecture blockers, **no prototype design or code has been added**. This follows the user's condition that a prototype may be designed only after a successful audit.

## 3. License and dependency review

### License observations
- Upstream core repository metadata and its `LICENSE` identify MIT.
- Community Android client repository metadata and its `LICENSE` identify MIT.
- MIT permits reuse subject to retaining copyright/license notices and the standard warranty disclaimer.
- This is not a complete transitive-license audit. All Go, gomobile, gVisor, Android Gradle, Kotlin, and bundled AAR dependencies still need a machine-generated SBOM and license scan pinned to exact artifacts before reuse.

### Dependency and build observations
- Upstream core `go.mod` declares Go 1.25.0 and direct dependencies including BurntSushi/toml, klauspost/compress, pierrec/lz4, golang.org/x/crypto, and golang.org/x/sys.
- The community Android client's `go.mod` uses different pinned versions and adds gVisor plus indirect btree/time dependencies.
- Android builds a gomobile AAR from the community repository's own source tree using a pinned gomobile version. The AAR is described as a gitignored build artifact, so an APK alone does not prove which Go source was embedded.
- The Android build workflow builds the AAR, builds a debug APK, runs JVM tests, and uploads artifacts. Workflow definitions were inspected; no external workflow was executed as part of this audit, and no CI pass is claimed.

Required gate:
- Pin the exact upstream commit and document every community-client patch as a reviewed patch set.
- Produce an SBOM and license inventory for all transitive Go/Android/native dependencies.
- Rebuild the AAR and APK from a clean, pinned source tree; record Go, gomobile, Android SDK/NDK, Gradle, dependency lock, and artifact hashes.
- Verify APK/AAR signatures and embedded native/library contents independently.

## 4. Cryptography and key management

### F-CRYPTO-01 — CRITICAL: insecure encryption is the default
- Upstream server defaults set `DataEncryptionMethod: 1`; the client and Android profile defaults also select method 1.
- Method 1 is repeating-key XOR, not cryptographic encryption.
- Method 0 is no encryption.
- Method 2 is raw ChaCha20 via `chacha20.NewUnauthenticatedCipher`, with no authentication tag. It is malleable and must not be used as an authenticated secure channel.
- The client configuration normalizer falls back to method 1 for out-of-range values.

Gate: reject methods 0, 1, and 2 for any Molido-controlled configuration. Do not silently rewrite imported profiles; fail closed and explain incompatibility.

### F-CRYPTO-02 — HIGH: AES key derivation is not suitable for password-based security
- Method 3 derives the AES-128 key with MD5(rawKey).
- Method 4 copies raw key bytes into a 24-byte buffer; this is not a password KDF.
- Method 5 derives a 32-byte value with a single SHA-256(rawKey), without a salt or work factor.
- AES-GCM itself provides authenticated encryption, but a sound AEAD primitive does not repair weak or low-entropy key provisioning.
- Server key generation currently generates a 32-character hexadecimal string for the default key-length case (16 random bytes of source entropy), then method 5 hashes the ASCII representation. This is not 256 bits of input entropy, despite producing a 256-bit AES key.

Gate:
- For any future isolated evaluation, require a randomly generated high-entropy secret with explicit entropy/encoding semantics and a reviewed KDF or direct random 256-bit key provisioning.
- Prefer a modern AEAD-only policy; method 5 is only a candidate pending interoperability, nonce, key-provisioning, and protocol review.
- Add tamper/replay/nonce/negative tests and cross-version client/server vectors before considering use.
- Do not change upstream defaults or protocol behavior in-place without compatibility tests.

### F-KEY-01 — HIGH: secrets are persisted in plaintext application data
- Android `ProfileEntity` contains ordinary `encryptionKey` and `advancedJson` fields.
- `AppDatabase` uses standard Room `databaseBuilder`; no database encryption layer is configured in the inspected path.
- `ConfigGenerator` serializes `ENCRYPTION_KEY` into a generated TOML file.
- `MasterDnsVpnService.prepareConfigFiles` writes that TOML file under app-private `filesDir/config/client_config.toml`.
- The manifest sets `allowBackup=false`, which is a useful backup control but does not encrypt the Room database or the generated file at rest.

Gate:
- Do not import this persistence model.
- Use Android Keystore-backed envelope encryption for profile secrets, keep plaintext lifetime minimal, use restrictive file permissions, clear generated secret-bearing config on stop/failure, redact logs, and define revoke/delete semantics.
- Test migrations, exports, backups, crash recovery, and secret cleanup.

## 5. DNS transport and network behavior

### Observed design
- The core sends its tunnel transport as DNS queries/responses over UDP to configured resolvers.
- Server defaults listen on UDP port 53 and include public resolver addresses such as 1.1.1.1:53 and 1.0.0.1:53 for upstream resolution.
- The DNS tunnel server requires an operator-controlled authoritative domain/delegation and reachable server-side DNS service. The Android client profile must supply the corresponding domains, encryption key, and resolvers.
- DNS tunneling does not eliminate the need for an egress server. It transports traffic to the operator's endpoint; it does not create a free public exit.
- DNS names, resolver choice, timing, packet sizes, and traffic patterns remain visible to network observers even where payload encryption is enabled. DNS tunneling can be throttled, filtered, logged, or blocked by resolver/network policy.
- The gVisor bridge source comments describe TCP flow forwarding through the local SOCKS5 proxy, in-process UDP DNS handling, and refusal of other UDP destinations. It also documents DNS-over-TCP and DoT limitations. Therefore arbitrary UDP/QUIC traffic cannot be assumed to work like a full general-purpose IP VPN.

### DNS and privacy gates
- Review domain-label encoding/length limits, DNS parser bounds, transaction-ID matching, response truncation/fragment assembly, cache poisoning resistance, resolver failover, timeouts, and malformed-packet handling.
- Require strict resolver allowlisting/validation and explicit handling of DNS rebinding, split-horizon responses, and network changes.
- Never claim DNS leak protection from the presence of a local DNS listener alone. Test IPv4, IPv6, UDP DNS, TCP DNS, DoT/DoH interactions, captive portals, and network handover on actual Android devices.
- No real resolver/server traffic was generated during this audit.

## 6. Android client and TUN ownership

### F-TUN-01 — CRITICAL: the community Android app owns a separate VPNService
Evidence in the inspected Android source:
- Manifest registers `MasterDnsVpnService` with `android.permission.BIND_VPN_SERVICE`.
- `MasterDnsVpnService` extends Android `VpnService`.
- It creates a `VpnService.Builder`, configures routes and DNS, and calls `Builder.establish()`.
- It passes that descriptor to `mobile.Mobile.startTunGvisor(...)`.
- The Go mobile package runs a client singleton and manages a gVisor bridge lifecycle.

This service must not be copied, registered, or run inside Molido. Doing so would create a second VPNService/TUN owner and violate the single-owner architecture.

### F-TUN-02 — HIGH: descriptor duplication has a fail-open fallback
- `mobile/tun/gvisor_api.go` calls `syscall.Dup(fd)` so Android and gVisor can own separate descriptors.
- If `Dup` fails, the implementation returns the original fd and its own comment acknowledges a double-close risk.
- For Molido, that fallback is unacceptable. Descriptor duplication failure must fail closed, and descriptor ownership/release must be represented in the central supervisor's lease/lifecycle state.

### Possible architecture boundary — NOT YET APPROVED
A future design could theoretically retain the Molido Supervisor as the only creator/owner of the Android TUN descriptor, then pass a duplicated descriptor to a transport adapter. The adapter would expose only start/stop/status/local-SOCKS capabilities and must never call `VpnService.Builder.establish()`, register a VPNService, or independently claim the TUN lease.

However, this is only a theoretical boundary. It is not proof of compatibility. The current community bridge has global singleton client/TUN state, its own lifecycle assumptions, and a local SOCKS-to-gVisor packet path. It needs refactoring and concurrency/ownership tests before it could be considered. No such adapter exists in Molido today.

## 7. Android application security observations

- `network_security_config.xml` sets `cleartextTrafficPermitted="true"` on the base configuration. This is broader than a loopback-only exception and permits cleartext HTTP at the app network-policy layer. Subscription endpoints or other remote HTTP traffic could therefore lack transport confidentiality/integrity.
- The Android manifest declares camera, internet, network-state, foreground-service, notification, and wake-lock permissions; camera is used for QR scanning. Each permission and exported component should be justified in a Molido threat model before reuse.
- The client importer, profile sharing, subscription fetching, logs, and generated configuration paths require dedicated adversarial review; this audit did not exhaustively inspect every UI/data path.

Gate:
- Default to HTTPS-only remote endpoints; allow cleartext only for narrowly scoped loopback use if technically required.
- Validate imported profile URLs, TOML, domains, resolver endpoints, and subscription responses with strict bounds.
- Redact keys, credentials, domains if sensitive, and user traffic metadata from logs.
- Review all exported components, intent filters, file providers, QR import, and sharing endpoints.

## 8. Integration decision against Molido constraints

| Requirement | Result | Decision |
|---|---|---|
| Preserve exactly six engines and eight cores | MasterDnsVPN is a distinct DNS tunnel, not one of the six approved engines | Do not add a seventh engine or relabel it |
| One central Android VPNService/TUN owner | Community client registers its own service | Reject direct reuse; only a future adapter behind Supervisor could be studied |
| No VPS/server/domain/PC dependency | MasterDnsVPN requires a reachable server and delegated tunnel domain | Operational use blocked |
| No fabricated server/IP/country/ping data | No endpoint was supplied or contacted | No connection claims or fake status |
| Security suitable for production | Weak defaults, unauthenticated mode, inadequate KDF, plaintext secret storage | No-go |
| Supply-chain confidence | Community fork and AAR provenance not yet fully established | No-go pending pinned reproducible build |
| Prototype gate | Audit has critical/high findings | **Not passed; do not design or code a prototype** |

## 9. Required remediation sequence before reconsideration

1. Obtain a reviewed upstream/community source diff and pin exact source commits.
2. Resolve crypto policy and key provisioning, with compatibility vectors and tamper tests.
3. Design and test Keystore-backed secret storage and secure config/log cleanup.
4. Remove application-wide cleartext permission or scope any exception narrowly.
5. Refactor the gVisor bridge to accept a Supervisor-owned duplicated descriptor only; remove the unsafe dup fallback; add descriptor-leak, close-order, failure, cancellation, reconnect, and concurrent-engine tests.
6. Prove that the adapter can start/stop without creating a second VPNService or TUN and without conflicting with other Go engine runtimes.
7. Complete DNS/parser/session/SOCKS5 threat review and run static analysis, dependency audit, fuzzing, and unit/integration tests.
8. Build reproducibly from pinned sources, generate SBOM, verify signatures, then perform isolated Android instrumentation.
9. Only after all gates pass, request explicit approval for a non-connectable, disabled prototype. Real network validation would still require a lawful, user-controlled server/domain and a physical Android device.

## 10. Validation boundary and final status

- Expanded static source inspection: **completed for the files and paths listed above; not a line-by-line audit of every file**.
- Cryptography/key-management review: **critical/high blockers identified**.
- DNS transport review: **design and selected source paths inspected; full protocol fuzzing not performed**.
- Android client/TUN review: **separate VPNService and descriptor lifecycle identified**.
- License review: **top-level MIT licenses checked; transitive license audit pending**.
- Build/CI: **not run**.
- APK install/signature verification: **not performed**.
- Penetration test/fuzzing: **not performed**.
- Molido runtime source changes: **none**.
- Engine catalog / six-engine count: **unchanged**.
- Eight-core architecture: **unchanged**.
- Second VPNService/TUN owner: **not added**.
- Prototype: **not designed or implemented because the audit failed its gate**.
- Runtime activation / connection: **DISABLED**.
- Stable / production status: **NOT APPROVED**.

This report records static-source observations only. It is not a penetration-test certificate, a proof of secure operation, or a claim of live connectivity.


## 10. Additional DNS-client and transport findings (second-pass review)

The following findings were added after inspecting the upstream Go DNS listener, client dispatch path, DNS response reassembly, server DNS resolver path, SOCKS5 upstream, and packet parser. This remains static source review; no fuzzing, race detector, build, or live network test was run.

### F-DNS-03 — CRITICAL: DNS cache misses have no visible response path to the original local UDP requester
- `DNSListener.handleQuery` invokes `Client.ProcessDNSQuery` with a callback that writes to the local UDP socket.
- `ProcessDNSQuery` invokes that callback only for a ready local-cache hit. On a cache miss it dispatches the query into the tunnel and returns without retaining the original peer address or response callback.
- `HandleDNSQueryRes` reassembles the tunneled response and stores it in the local cache, but the inspected implementation does not correlate that response to the original UDP requester or invoke its callback.
- Consequence: a first-time DNS lookup appears not to receive its answer; a later retry may hit the cache. This is a protocol-correctness blocker unless another separately verified response path exists outside the inspected call chain.

Gate: add request-ID/transaction correlation with bounded pending entries, deadlines, cancellation, and exact peer/callback delivery. Tests must prove first-query success, concurrent identical queries, transaction-ID preservation, timeout, duplicate response, late response, and cache-hit behavior.

### F-DNS-04 — HIGH: unbounded goroutine creation on local DNS ingress and stop/write race
- `DNSListener.Start` starts one goroutine per received UDP datagram with no visible semaphore, bounded worker queue, or per-peer admission limit.
- `DNSListener.Stop` closes the socket and sets `l.conn = nil`; the read loop and asynchronous response callbacks access `l.conn`. The inspected code has no synchronization around that pointer, creating a potential data race / nil dereference during stop or in-flight responses.
- The handler receives a context parameter but the inspected `ProcessDNSQuery` dispatch path does not use it to cancel a pending request.

Gate: use a bounded worker/queue or explicit concurrency limiter; synchronize listener state; capture a stable socket handle for callbacks; wait for in-flight handlers on shutdown; add race-detector tests for stop-under-load, socket-close, callback-after-stop, overload, and cancellation.

### F-DNS-05 — HIGH: local DNS request failure semantics are ambiguous
- On malformed DNS input or an unready session, the current path returns without a DNS error response. On cache misses, it also returns immediately while tunnel dispatch is asynchronous.
- This can cause resolver timeouts and retries rather than a deterministic SERVFAIL/REFUSED response, and increases load during outages.

Gate: define explicit response semantics for malformed input, no session, queue saturation, timeout, and upstream failure; test DNS transaction IDs and response flags for each case.

### F-PROTO-01 — HIGH: packet header check is not cryptographic integrity
- The VPN packet parser validates a short custom header-check byte derived from header bytes. This is useful for accidental corruption detection only; it is not a MAC and cannot authenticate a packet.
- Payload AEAD, where enabled, must be mandatory and must bind the complete packet header/metadata as associated data. The inspected AES-GCM calls use a nil associated-data argument, so header/session/stream metadata is not cryptographically bound by this codec layer.

Gate: version the protocol and authenticate header plus payload together; add tamper tests for session ID, packet type, stream ID, sequence, fragment metadata, compression metadata, and ciphertext. Reject legacy unauthenticated modes.

### F-SOCKS-01 — HIGH: target validation does not visibly protect against DNS rebinding/private-address resolution
- `validateSOCKSTargetHost` rejects literal localhost/private/link-local/reserved IP addresses, but returns success for ordinary hostnames without resolving and validating all resulting A/AAAA addresses.
- A hostname resolving to loopback, private, link-local, or metadata-service addresses may therefore bypass the literal-IP checks unless another later layer enforces an equivalent policy.

Gate: resolve through a controlled resolver, validate every returned A/AAAA address against an explicit egress policy immediately before dialing, pin the validated result to the connection attempt, and test rebinding, mixed public/private answers, IPv4-mapped IPv6, IPv6 ULA/link-local, and metadata ranges.

### F-RESOURCE-01 — HIGH: DNS hedging can fan out upstream work
- When multiple upstream resolvers are configured, the DNS resolver launches concurrent goroutines for the primary and then all remaining resolvers after the hedge delay or early failure. The reviewed function has a timeout for the caller but does not visibly cancel already launched resolver operations when one response wins.
- This may leave bounded-by-resolver-count background work running until per-query timeouts. Under high request volume, resource use can multiply.

Gate: pass a cancellable context through every upstream query, cancel losing attempts immediately, cap resolver count and concurrent DNS lookups, and test cancellation/resource bounds under load.

These findings strengthen the existing **NO-GO** decision. They are not evidence of a confirmed exploit or runtime failure; they are source-level correctness/security risks requiring targeted tests and remediation.
