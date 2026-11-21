"""Fail-closed guardrails for future autonomous patch proposals.

This module validates metadata and unified-diff structure only. It does not
apply patches, write files, run commands, or open pull requests.
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


def _valid_path(path: Any) -> tuple[bool, str]:
    if not isinstance(path, str) or not path or "\\" in path:
        return False, "invalid_path"
    parsed = PurePosixPath(path)
    if parsed.is_absolute() or any(part in ("", ".", "..") for part in path.split("/")):
        return False, "path_traversal"
    normalized = parsed.as_posix()
    if not normalized.startswith(ALLOWED_PREFIXES):
        return False, "path_not_allowlisted"
    if any(part.lower() in FORBIDDEN_PARTS for part in parsed.parts):
        return False, "sensitive_path"
    return True, normalized


def validate_patch_plan(plan: Any) -> tuple[bool, str]:
    """Accept only small, non-sensitive proposals whose diff matches metadata."""
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

    declared: dict[str, tuple[int, int]] = {}
    for item in files:
        if not isinstance(item, dict) or set(item) != {"path", "additions", "deletions"}:
            return False, "invalid_file_entry"
        valid, result = _valid_path(item["path"])
        if not valid:
            return False, result
        path = result
        if path in declared:
            return False, "duplicate_path"
        additions, deletions = item["additions"], item["deletions"]
        if not isinstance(additions, int) or isinstance(additions, bool) or additions < 0:
            return False, "invalid_additions"
        if not isinstance(deletions, int) or isinstance(deletions, bool) or deletions < 0:
            return False, "invalid_deletions"
        declared[path] = (additions, deletions)

    changed = sum(a + d for a, d in declared.values())
    if changed > MAX_CHANGED_LINES:
        return False, "change_limit_exceeded"

    # Parse file sections and count actual +/- lines, excluding file headers.
    actual: dict[str, list[int]] = {}
    current: str | None = None
    saw_old_header = False
    saw_new_header = False
    for line in diff.splitlines():
        if line.startswith("diff --git "):
            if current is not None and not (saw_old_header and saw_new_header):
                return False, "malformed_diff_headers"
            current = None
            saw_old_header = saw_new_header = False
            continue
        if line.startswith("--- "):
            if current is not None or saw_old_header:
                return False, "malformed_diff_headers"
            old_path = line[4:]
            if not old_path.startswith("a/"):
                return False, "malformed_diff_headers"
            valid, result = _valid_path(old_path[2:])
            if not valid:
                return False, result
            current = result
            if current not in declared or current in actual:
                return False, "diff_paths_mismatch"
            actual[current] = [0, 0]
            saw_old_header = True
            continue
        if line.startswith("+++ "):
            if current is None or saw_new_header or line[4:] != "b/" + current:
                return False, "malformed_diff_headers"
            saw_new_header = True
            continue
        if current is not None and saw_old_header and saw_new_header:
            if line.startswith("+") and not line.startswith("+++"):
                actual[current][0] += 1
            elif line.startswith("-") and not line.startswith("---"):
                actual[current][1] += 1

    if current is not None and not (saw_old_header and saw_new_header):
        return False, "malformed_diff_headers"
    if set(actual) != set(declared):
        return False, "diff_paths_mismatch"
    for path, counts in declared.items():
        if tuple(actual[path]) != counts:
            return False, "diff_line_count_mismatch"
    return True, "metadata_policy_passed_draft_only"
