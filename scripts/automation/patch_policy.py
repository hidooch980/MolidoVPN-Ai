"""Fail-closed guardrails for future autonomous patch proposals.

This module validates metadata only. It does not apply patches, write files,
run commands, or open pull requests. A later patch executor must independently
revalidate the diff in an isolated worktree and publish draft PRs only.
"""
from __future__ import annotations

from pathlib import PurePosixPath
from typing import Any

MAX_FILES = 2
MAX_CHANGED_LINES = 200
MAX_DIFF_BYTES = 32_000
ALLOWED_PREFIXES = ("scripts/automation/", "docs/automation/")
FORBIDDEN_PARTS = {
    ".git", ".github", "android", "app", "vpn", "wireguard", "crypto",
    "security", "signing", "keystore", "native", "jni", "network",
    "gradle", "build",
}


def validate_patch_plan(plan: Any) -> tuple[bool, str]:
    """Accept only small, non-sensitive automation/docs proposals; fail closed."""
    if not isinstance(plan, dict):
        return False, "plan_must_be_object"
    if set(plan) != {"files", "diff"}:
        return False, "unexpected_or_missing_fields"
    files, diff = plan["files"], plan["diff"]
    if not isinstance(files, list) or not files or len(files) > MAX_FILES:
        return False, "invalid_file_count"
    if not isinstance(diff, str) or not diff or len(diff.encode("utf-8")) > MAX_DIFF_BYTES:
        return False, "invalid_diff_size"
    if "\x00" in diff or "GIT binary patch" in diff:
        return False, "binary_or_nul_diff"
    seen: set[str] = set()
    for item in files:
        if not isinstance(item, dict) or set(item) != {"path", "additions", "deletions"}:
            return False, "invalid_file_entry"
        path, additions, deletions = item["path"], item["additions"], item["deletions"]
        if not isinstance(path, str) or not path or "\\" in path:
            return False, "invalid_path"
        parsed = PurePosixPath(path)
        if parsed.is_absolute() or any(part in ("", ".", "..") for part in path.split("/")):
            return False, "path_traversal"
        normalized = parsed.as_posix()
        if normalized in seen:
            return False, "duplicate_path"
        seen.add(normalized)
        if not normalized.startswith(ALLOWED_PREFIXES):
            return False, "path_not_allowlisted"
        if any(part.lower() in FORBIDDEN_PARTS for part in parsed.parts):
            return False, "sensitive_path"
        if not isinstance(additions, int) or isinstance(additions, bool) or additions < 0:
            return False, "invalid_additions"
        if not isinstance(deletions, int) or isinstance(deletions, bool) or deletions < 0:
            return False, "invalid_deletions"
    changed = sum(x["additions"] + x["deletions"] for x in files)
    if changed > MAX_CHANGED_LINES:
        return False, "change_limit_exceeded"
    # Every declared path must appear as a unified-diff target; do not trust
    # caller-supplied metadata that does not match the proposed patch.
    targets = set()
    for line in diff.splitlines():
        if line.startswith("+++ b/"):
            targets.add(line[6:])
    if targets != seen:
        return False, "diff_paths_mismatch"
    return True, "metadata_policy_passed_draft_only"
