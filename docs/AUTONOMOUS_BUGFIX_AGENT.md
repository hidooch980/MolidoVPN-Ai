# Autonomous Bug-Fix Agent

## Current verified capabilities
- Classifies CI failures, diagnoses known categories, redacts common secrets, retries only first-attempt failures positively classified as entirely transient (at most once), and creates or reuses evidence-backed GitHub issues for non-transient, mixed, or unknown failures.
- GitHub API response reads and HTTP error bodies are bounded; HTTP error summaries and unexpected exception details are redacted and truncated.
- Log ZIP extraction and log-download response reads are bounded by archive size, member count, per-member and total expanded sizes, and compression ratio. Encrypted and symlink log members are rejected.
- Signed AWS, Google Cloud, and Azure URL query credentials, environment-style and quoted JSON secret assignments, Android signing values, AWS temporary credentials, private-key PEM blocks, and common GitHub tokens are redacted.
- Redirects remove GitHub Authorization on cross-host, cross-port, or HTTPS-downgrade redirects.
- Patch-policy validation is proposal-only: it validates a small allowlisted unified diff and metadata; it does not apply patches, write files, execute commands, or open pull requests.

## Latest verified CI evidence
- Workflow run #304 on branch `feature/wireguard-runtime-diagnostics`, head commit `fb7bec7effffc5544e19cbb5e08f9dafac969f86`, completed with overall conclusion `success`.
- The bug-fix and patch-policy suite ran **44 tests: all passed**.
- Backend suite ran **13 tests: all passed** (one upstream deprecation warning).
- Android Keystore instrumentation ran **16 tests on the CI Android emulator** and Gradle reported `BUILD SUCCESSFUL`.
- Android source validation passed unit tests, lint, and release-variant assembly (CI validation only; no distribution).
- Pinned WireGuard Go-only source build passed. This validates the pinned Go source build only; it does not prove Android TUN ownership, descriptor transfer, JNI integration, tunnel traffic, or a live VPN connection.
- The signed-release job and autonomous bug-fix agent job were skipped on this successful pull-request run. The agent's own failure-triage/retry/issue-creation path has not been exercised end-to-end by this green run.
- CI emulator instrumentation is not a physical Android-device acceptance test. No real endpoint handshake, bidirectional internet routing, DNS/IPv6 leak, network-switch, or long-duration stability result is claimed.

## Patch-policy guardrail (proposal validation only)
- `scripts/automation/patch_policy.py` validates patch-plan metadata and unified-diff headers/paths/actual added-deleted line counts; it does not edit files, execute commands, or create pull requests.
- Default allowlist is limited to `scripts/automation/` and `docs/automation/`; Android application, VPN/TUN, native, cryptography, networking, signing, keystore, Gradle, and security paths are excluded.
- Limits: at most 2 files, 200 changed lines, and 32 KB diff. Traversal, binary diffs, duplicate paths, mismatched diff targets, and unknown schema fields fail closed.
- A policy pass is not authorization to apply a patch. Any future executor must revalidate actual diff contents in an isolated worktree, run mandatory regression tests, and produce draft-PR-only output.

## Safety boundaries
- No automatic merge, release, APK publication, signing-key access, secret rotation, paid model/API, external server, or permanent runtime is used.
- The agent does not claim a bug is fixed because a retry passes. CI evidence is kept visible.
- Unknown, mixed, compilation, assertion, instrumentation, and security failures are not automatically retried as transient infrastructure.
- The current agent is a CI triage/recovery assistant, not an autonomous application-source repair agent. It does not yet author or apply source-code fixes.

## Verification
- Run the suite with: `python -m unittest scripts/automation/test_bugfix_agent.py -v`
- Latest verified result: 44 tests passed in CI run #304.
- Backend suite: 13 passed in CI run #304.
- Android Keystore emulator instrumentation: 16 passed in CI run #304.
- Workflow: `.github/workflows/android-ci.yml`
- CI run: https://github.com/hidooch980/MolidoVPN-Ai/actions/runs/44985204571
