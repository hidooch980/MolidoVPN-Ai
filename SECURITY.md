# Security Policy

## Reporting
Do not publish exploitable vulnerabilities, credentials, private keys, or personal data in public issues. Use GitHub's private vulnerability reporting feature when enabled for this repository, or contact the repository owner through a private channel.

## Secret handling
- Never commit signing keystores, passwords, access tokens, VPN private keys, production configs, or customer data.
- CI secrets must be narrowly scoped and never printed in logs.
- Release signing identity must be stable, owner-controlled, and backed up securely.
- Do not rotate or replace an established signing identity without a documented migration and owner authorization.

## Security principles
Least privilege, fail-closed tunnel policy, authenticated configuration, signed updates, dependency review, minimal telemetry, and redacted diagnostics are mandatory.
