# Optional accountless WARP identity for WireGuard

Status: implementation planned; not enabled and not production-ready.

## User requirement

The user does not have a personal WireGuard server, WireGuard account, or Cloudflare account. The app must not require the user to create or paste an account credential merely to try the optional WARP-backed WireGuard route.

## Architecture decision

- Keep the existing standard WireGuard profile-import path unchanged.
- Add WARP-backed WireGuard as an explicitly optional external-provider mode.
- Do not make Cloudflare a critical dependency of MolidoVPN. The self-hosted / user-supplied WireGuard path remains available and independent.
- Device enrollment is performed only after an in-app, explicit consent action. Explain that a device identity and public key are registered with Cloudflare; never imply that this is self-hosted or anonymous.
- Generate the WireGuard private key locally on the Android device. Never send the private key to the provider, GitHub, logs, analytics, or diagnostics.
- Treat registration as a per-device identity, not a personal user account. The provider may refuse, rate-limit, or change this registration flow; fail closed and retain no partial identity.
- Store the resulting profile/identity only with explicit user consent using Android Keystore-backed encryption. Revoke must delete the local encrypted identity and key material.
- Do not auto-connect after enrollment unless the user separately initiates Connect and grants Android VPN permission.
- Do not import or copy MSN-GUARD source code. Its repository is AGPL-3.0; use an independently implemented integration and review provider terms and third-party protocol constraints.

## Required implementation stages

1. Add a provider-neutral identity model and an accountless WARP enrollment client, isolated behind an optional provider interface.
2. Validate HTTPS, response status, bounded response size, JSON schema, assigned addresses, peer key, and endpoint before constructing a WireGuard profile.
3. Build a standard WireGuard configuration in memory; pass it to the existing WireGuard adapter without introducing a second VPN service or TUN owner.
4. Add explicit consent UI describing external registration and the data involved.
5. Persist identity only through the existing AndroidSecureProfileStore (or a dedicated encrypted store with equivalent guarantees); clear transient key and response buffers where possible.
6. Add deterministic unit tests for malformed/partial responses, refusal, rate limiting, timeout, endpoint validation, profile generation, consent gating, persistence/revocation, and secret redaction.
7. Add instrumentation tests for Android Keystore persistence and a device-level manual test plan. CI success alone must not be reported as a real VPN connection.
8. Keep WARP provider availability separate from WireGuard engine implementation status. No route is marked connected until handshake and data-plane traffic are independently verified.

## Acceptance criteria

- No personal Cloudflare login or user-supplied account token is requested for the optional consumer WARP enrollment path.
- The user sees and accepts an explicit external-provider disclosure before enrollment.
- No private key is transmitted or logged.
- No success state is shown on registration alone; WireGuard handshake and data-plane verification are distinct states.
- Standard user-supplied WireGuard profiles continue to work without contacting Cloudflare.
- Failure to reach or enroll with the provider does not prevent use of self-hosted/user-supplied WireGuard.
- No claim of live connectivity is made until verified on a physical Android device and a working network.

## Current limitations

This document records the intended integration only. No enrollment client, WARP credentials, server, or live tunnel has been provisioned by this change.
