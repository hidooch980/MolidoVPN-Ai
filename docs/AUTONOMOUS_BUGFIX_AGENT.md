# Autonomous Bug-Fix Agent

## Purpose

The agent is wired into the Android CI pipeline. It inspects failed jobs, downloads their logs, classifies the failure, and takes only a bounded action supported by the evidence.

## Current automatic actions

1. Read the completed run and all failed job logs.
2. Classify each failed job as transient infrastructure, code/test failure, or unknown.\n3. Match known Android, Gradle, Emulator, WireGuard native-build, and signing failures to a conservative diagnosis, exact log evidence, and a bounded remediation recommendation. Unknown errors remain explicitly unclassified.
4. Retry failed jobs exactly once only when every failed job is positively classified as transient infrastructure and the run is on its first attempt.
5. For code/test, unknown, mixed, or repeat failures, create or reuse one GitHub issue per run. The issue includes the commit, workflow/run links, job links, classification, and bounded log excerpts.
5. Run twelve unit tests for classification, secret redaction, and root-cause diagnosis on every CI execution.

## Safety boundaries

- No speculative application source-code patch is generated or applied by this version. Root-cause guidance is diagnostic only; source-level auto-repair remains gated until a safe, testable patch policy exists.
- No automatic merge, release, APK publication, signing-key access, secret rotation, paid API/model, external server, or runtime service is used.
- No automatic retry is made for compilation, assertion, instrumentation, or security failures.
- An unknown or mixed failure is treated as not safe to retry.\n- Log retrieval strips the GitHub authorization header on cross-host redirects; issue excerpts redact private-key blocks, token patterns, and common password/secret assignments.
- The agent does not claim a bug is fixed merely because a retry passes; the original failure and recovery are both visible in Actions.
- The issue creation uses the repository's GitHub Actions token and requires the `actions: write` and `issues: write` job permissions.

## Why source auto-patching is not enabled yet

A general-purpose code-repair model needs a model/runtime, credentials or a local inference host, and a trusted patch-validation sandbox. This repository's current operating constraints do not authorize a paid external model or production runtime, and GitHub-hosted Actions is CI rather than a permanent execution service. Applying speculative source patches from log text would be unsafe, especially in VPN ownership, cryptography, networking, signing, and security code.

The safe first increment therefore automates diagnosis, a single bounded retry for known infrastructure failures, and evidence-backed issue creation. Source-level autonomous repair can be added later only with a pinned local model/runtime or an explicitly authorized provider, isolated worktree, mandatory regression tests, security-sensitive path denylist, patch-size limits, and draft-PR-only output. It must never merge or publish.

## Verification

- Unit tests: `python -m unittest scripts/automation/test_bugfix_agent.py -v` (12 cases)
- Workflow: `.github/workflows/android-ci.yml`
- Implementation: `scripts/automation/bugfix_agent.py`
- Regression tests: `scripts/automation/test_bugfix_agent.py`
