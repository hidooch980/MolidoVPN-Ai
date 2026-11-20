# WireGuard physical Android device acceptance test

This is a manual acceptance procedure, not evidence that a device test has already passed.

## Prerequisites

- Install the artifact named `molido-vpn-android-device-test-debug` from the matching GitHub Actions run. It is a debug-signed test build, not a stable/release APK.
- Obtain a valid WireGuard client profile from a server/provider you are authorized to use. Do not send the private key or full profile to chat or commit it to GitHub.
- Test once on Wi-Fi and, if available, once on mobile data.

## Test sequence

1. Install the APK and launch MolidoVPN AI.
2. Import the authorized WireGuard profile locally. Confirm the app does not expose its private key in UI diagnostics or logs.
3. Tap Connect and approve Android's VPN permission dialog.
4. Confirm the session state becomes RUNNING. This is only a local backend state, not proof of connectivity.
5. Refresh WireGuard diagnostics. Record whether a fresh peer handshake timestamp appears and whether RX and TX counters increase during traffic.
6. With the tunnel active, open a trusted HTTPS site and compare the public egress IP against the expected server egress. Do not treat a successful page load alone as sufficient.
7. Check DNS behavior using a reputable DNS leak test. Confirm resolvers are expected and no unintended direct DNS path is exposed.
8. Generate traffic in both directions and confirm RX and TX counters both advance.
9. Disconnect. Confirm Android's VPN key/icon clears and the UI reports IDLE.
10. Reconnect, then disconnect again. Repeat at least three times.
11. Switch Wi-Fi/mobile network while connected. Confirm the app never claims verified connectivity solely from the backend UP state; record whether handshake/data-plane recover or whether a clean reconnect is required.
12. Force-stop and relaunch the app. Confirm no stale active-session claim remains and that a new connection requires explicit user action.

## Pass criteria

- Fresh handshake from the expected peer.
- Bidirectional RX/TX increments under generated traffic.
- Public egress matches the authorized server route.
- DNS leak test shows no unexpected resolver or direct DNS leakage.
- Disconnect releases the Android VPN session; repeated connect/disconnect cycles do not leave a stale VPN icon or block the next start.
- Failure/timeout does not release session ownership until cleanup succeeds.

## Evidence to report

Record device model, Android version, network type, app commit SHA, handshake age, RX/TX before and after traffic, public egress result (IP may be redacted), DNS test outcome, and disconnect/reconnect outcome. Never include private keys, profile text, account tokens, or unredacted server credentials.

A test cannot pass without a valid authorized peer/server. CI and emulator success do not replace this physical-device procedure.
