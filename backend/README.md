# MolidoVPN Self-hosted Control Plane

This is operator-deployed control-plane source. It is not hosted by GitHub Actions and is not a VPN relay, tunnel endpoint, or packet data plane.

## Current scope

- Admin-token protected device enrollment and revocation.
- Random per-device bearer token returned once; only SHA-256 hash is stored.
- SQLite persistence with foreign keys and WAL.
- POSIX SQLite database file is created/restricted to owner read/write permissions (0600); its parent data directory must be owned by the service account and mode 0700 or readiness fails closed. SQLite WAL/SHM sidecars are protected by the directory boundary.
- Per-device configuration revisioning, expiry, and ECDSA P-256 signatures.
- Device-authenticated configuration retrieval and minimal audit events.
- Liveness and readiness endpoints. Readiness fails closed unless the admin token is at least 32 characters and the P-256 signing key loads successfully.

## Local development

Python 3.11+ is recommended. Install backend/requirements.txt, then set MOLIDO_ADMIN_TOKEN, MOLIDO_CONFIG_SIGNING_KEY_FILE, and MOLIDO_DB_PATH. Run:

    uvicorn backend.app.main:app --host 127.0.0.1 --port 8000

The signing key must be provisioned and backed up by the operator outside the repository. The service intentionally does not generate or persist signing keys.

## Production requirements

Deploy only on approved self-hosted infrastructure. Put the API behind a TLS 1.2+ reverse proxy; restrict access; protect admin token and private signing key; use encrypted backups; test restore, key rotation, revocation, monitoring, upgrade and rollback. Run as a non-root service account. Keep the database parent directory owned by the service account with mode 0700; the application verifies this on every database connection and fails readiness closed if the directory is group/world accessible or owned by another user. Keep backups equally restricted. No production deployment or security certification is claimed.

## Signature format

The ECDSA signature covers UTF-8 bytes: revision + LF + expires_at_epoch_seconds + LF + canonical_json_payload. Android verifies the same canonical bytes using an owner-pinned public key and advances its encrypted local revision only after successful verification.

## Database filesystem boundary

On POSIX systems, the service creates missing database directories with mode 0700 and rejects an existing directory unless it is owned by the service account and has no group/other permissions. The database file is opened without following a final-component symlink where the platform supports `O_NOFOLLOW`, verified as a regular file owned by the service account, and forced to mode 0600. This protects SQLite WAL/SHM files through the private parent directory. Operators must provision the service data directory under the service account; do not point the database at shared directories such as a world-readable temporary directory. These checks are defense-in-depth, not protection against a privileged/root attacker.
