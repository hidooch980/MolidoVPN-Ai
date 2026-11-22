# Control-plane request validation

The Android control-plane client validates request parameters before constructing HTTP headers or opening a connection.

## Token rules

- A device token must be non-empty and no longer than 512 characters.
- Whitespace and ISO control characters are rejected to prevent malformed or injected HTTP header values.
- The token is sent only as a Bearer authorization value over HTTPS.

## Timeout rules

- Connect timeout: 1–60,000 ms.
- Read timeout: 1–120,000 ms.
- Zero, negative, and unbounded timeout values are rejected.

## Test coverage

Unit tests cover normal values, empty and oversized tokens, ASCII header control characters, and timeout lower/upper bounds.

These checks validate local request construction only. They do not prove the control-plane endpoint is deployed, reachable, or correctly configured in production.
