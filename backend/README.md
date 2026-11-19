# MolidoVPN Self-hosted Control Plane

This is operator-deployed control-plane source. It is not hosted by GitHub Actions and is not a VPN relay, tunnel endpoint, or packet data plane.

## Current scope

- Admin-token protected device enrollment and revocation.
- Random per-device bearer token returned once; only SHA-256 hash is stored.
- SQLite persistence with foreign keys and WAL.
- Per-device configuration revisioning, expiry, and ECDSA P-256 signatures.
- Device-authenticated configuration retrieval and minimal audit events.
- Liveness and readiness endpoints.

## Local development

Python 3.11+ is recommended. Install \`backend/requirements.txt\`, then set \`MOLIDO_ADMIN_TOKEN\`, \`MOLIDO_CONFIG_SIGNING_KEY_FILE\`, and \`MOLIDO_DB_PATH\`. Run:

\`\`\`sh
uvicorn backend.app.main:app --host 127.0.0.1 --port 8000
\`\`\`

The signing key must be provisioned and backed up by the operator outside the repository. The service intentionally does not generate or persist signing keys.

## Production requirements

Deploy only on approved self-hosted infrastructure. Put the API behind a TLS 1.2+ reverse proxy; restrict access; protect admin token and private signing key; use encrypted backups; test restore, key rotation, revocation, monitoring, upgrade and rollback. Run as a non-root service account. No production deployment or security certification is claimed.

## Signature format

The ECDSA signature covers UTF-8 bytes: \`revision + LF + expires_at_epoch_seconds + LF + canonical_json_payload\`. Android verifies the same canonical bytes using an owner-pinned public key and advances its encrypted local revision only after successful verification.
