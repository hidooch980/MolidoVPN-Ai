# Autonomous Bug-Fix Agent

## Current verified capabilities
- Classifies CI failures, diagnoses known categories, redacts common secrets, retries only first-attempt all-transient failures once, and creates/reuses evidence-backed GitHub issues.
- The current CI run has passed the 20 existing bug-fix-agent unit tests, Android Keystore emulator instrumentation, Android unit tests/lint/assemble, and pinned WireGuard Go-only source build.

## Patch-policy guardrail (proposal validation only)
- `scripts/automation/patch_policy.py` validates patch-plan metadata only; it does not edit files, execute commands, or create pull requests.
- Default allowlist is limited to `scripts/automation/` and `docs/automation/`; Android application, VPN/TUN, native, cryptography, networking, signing, keystore, Gradle, and security paths are excluded.
- Limits: at most 2 files, 200 changed lines, and 32 KB diff. Traversal, binary diffs, duplicate paths, mismatched diff targets, and unknown schema fields fail closed.
- A policy pass is not authorization to apply a patch. Any future executor must revalidate actual diff contents in an isolated worktree, run mandatory regression tests, and produce draft-PR-only output.

## Safety boundaries
- No automatic merge, release, APK publication, signing-key access, secret rotation, paid model/API, external server, or permanent runtime is used.
- The agent does not claim a bug is fixed because a retry passes. CI evidence is kept visible.
- Unknown, mixed, compilation, assertion, instrumentation, and security failures are not automatically retried as transient infrastructure.

## Verification
- Bug-fix agent tests: `python -m unittest scripts/automation/test_bugfix_agent.py -v`
- Patch policy tests are included in the same test module.
- Workflow: `.github/workflows/android-ci.yml`
