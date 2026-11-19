# Architecture Decision: No End-User Accounts

Status: Accepted
Date: 2026-11-19
Project: MolidoVPN AI

## Decision

MolidoVPN AI does not require human end-user accounts. The Android application must not provide user registration, sign-in, password reset, or account-profile flows. Users open the installed application and configure/connect locally.

## Security boundaries retained

- Removing end-user accounts does not remove Android VPN permission consent.
- Device-to-control-plane authentication remains a separate machine identity concern. If a control plane is used, device credentials are provisioned and revocable; they are not human usernames/passwords.
- Operator/admin credentials and signing private keys remain server-side and must never be embedded in the APK.
- Local VPN profiles remain memory-only until a separate, explicit secure-storage decision is approved.
- No production server, account service, or external SaaS dependency is implied by this decision.

## UX consequences

- Launch directly into the dashboard; no login gate.
- Provide local profile import, engine status, connection controls, network policy, diagnostics and recovery.
- Never display fabricated IP, country, latency, speed, handshake, or server availability.
- Unsupported engines remain visible as unavailable inventory, not selectable working services.

## Scope

This decision removes only end-user account functionality. It does not authorize removing device security, Android VPN consent, operator authentication, signed configuration verification, or update integrity controls.
