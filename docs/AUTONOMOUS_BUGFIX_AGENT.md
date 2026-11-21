# Autonomous Bug-Fix Agent

## Current verified capabilities
- Classifies CI failures, diagnoses known categories, redacts common secrets, retries only first-attempt all-transient failures once, and creates/reuses evidence-backed GitHub issues. GitHub API response reads and HTTP error bodies are bounded; HTTP error summaries are redacted. Log ZIP extraction and log-download response reads are bounded by archive size, member count, per-member and total expanded sizes, and compression ratio. Signed AWS, Google Cloud, and Azure URL query credentials are redacted from issue evidence. Environment-style and quoted JSON secret assignments, including Android signing and AWS temporary credentials, are redacted. Redirects drop GitHub authorization on cross-host/port changes and HTTPS downgrades.
- Latest completed evidence before the newest API-response hardening: CI run #291 on commit `dc0ea967` passed 40 bug-fix/patch-policy tests, 13 backend tests, 16 Android Keystore emulator instrumentation tests, Android unit tests/lint/release assembly, and the pinned WireGuard Go-only source build. Signed-release and the autonomous bug-fix job were skipped for this pull-request run. The new API-response regression tests require a fresh CI run.

## Patch-policy guardrail (proposal validation only)
- `scripts/automation/patch_policy.py` validates patch-plan metadata and unified-diff headers/paths/actual added-deleted line counts; it does not edit files, execute commands, or create pull requests.
- Default allowlist is limited to `scripts/automation/` and `docs/automation/`; Android application, VPN/TUN, native, cryptography, networking, signing, keystore, Gradle, and security paths are excluded.
- Limits: at most 2 files, 200 changed lines, and 32 KB diff. Traversal, binary diffs, duplicate paths, mismatched diff targets, and unknown schema fields fail closed.
- A policy pass is not authorization to apply a patch. Any future executor must revalidate actual diff contents in an isolated worktree, run mandatory regression tests, and produce draft-PR-only output.

## Safety boundaries
- No automatic merge, release, APK publication, signing-key access, secret rotation, paid model/API, external server, or permanent runtime is used.
- The agent does not claim a bug is fixed because a retry passes. CI evidence is kept visible.
- Unknown, mixed, compilation, assertion, instrumentation, and security failures are not automatically retried as transient infrastructure.

## Verification
- Bug-fix agent and patch-policy tests: `python -m unittest scripts/automation/test_bugfix_agent.py -v` (44 tests are now defined; the newest regression tests cover pre-extraction network read limits, signed cloud-URL secret redaction, bounded GitHub API reads, redacted HTTP error summaries, safe redirect handling for API calls, and generic exception redaction. The newest four tests require a fresh CI run).
- Patch policy tests are included in the same test module.
- Workflow: `.github/workflows/android-ci.yml`


### Additional redirect hardening (latest source change)

- GitHub API calls now use the same redirect policy as log downloads: Authorization is removed on cross-host, cross-port, or HTTPS-downgrade redirects.
- Unexpected top-level exception details are redacted and truncated before they are written to CI logs.
- Two new regression tests cover the API opener policy and generic error-detail redaction. The expanded suite now defines 44 tests; the latest changes require a fresh CI run.
