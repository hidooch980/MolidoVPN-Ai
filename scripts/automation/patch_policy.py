"""Fail-closed guardrails for future patch proposals.

Validates a narrow unified-diff subset only. It never applies patches, writes
files, executes commands, or opens pull requests.
"""
from __future__ import annotations

import re
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
HUNK_HEADER = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(?: .*)?$")


def _valid_path(path: Any) -> tuple[bool, str]:
    if not isinstance(path, str) or not path or "\\" in path:
        return False, "invalid_path"
    # A deliberately conservative path alphabet avoids quoting/escaping
    # ambiguities in the diff header and shell/tooling interpretation.
    if not re.fullmatch(r"[A-Za-z0-9._/-]+", path):
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


def _finish_hunk(old_left: int, new_left: int) -> bool:
    return old_left == 0 and new_left == 0


def validate_patch_plan(plan: Any) -> tuple[bool, str]:
    """Accept only small edits to existing allowlisted files with valid hunks."""
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

    if sum(a + d for a, d in declared.values()) > MAX_CHANGED_LINES:
        return False, "change_limit_exceeded"

    actual: dict[str, list[int]] = {}
    current: str | None = None
    old_header = new_header = False
    saw_hunk = False
    old_left = new_left = 0

    def finish_section() -> bool:
        return (
            current is not None
            and old_header
            and new_header
            and saw_hunk
            and _finish_hunk(old_left, new_left)
        )

    for line in diff.splitlines():
        if line.startswith("diff --git "):
            if current is not None and not finish_section():
                return False, "malformed_diff_hunk"
            header = line[len("diff --git "):]
            matched = None
            for path in declared:
                if header == f"a/{path} b/{path}":
                    matched = path
                    break
            if matched is None or matched in actual:
                return False, "diff_paths_mismatch"
            current = matched
            actual[current] = [0, 0]
            old_header = new_header = saw_hunk = False
            old_left = new_left = 0
            continue

        if current is None:
            return False, "malformed_diff_headers"

        if line.startswith("--- "):
            if old_header or new_header or saw_hunk or line != f"--- a/{current}":
                return False, "malformed_diff_headers"
            old_header = True
            continue
        if line.startswith("+++ "):
            if not old_header or new_header or saw_hunk or line != f"+++ b/{current}":
                return False, "malformed_diff_headers"
            new_header = True
            continue

        hunk = HUNK_HEADER.match(line)
        if hunk:
            if not (old_header and new_header):
                return False, "malformed_diff_headers"
            if saw_hunk and not _finish_hunk(old_left, new_left):
                return False, "malformed_diff_hunk"
            old_left = int(hunk.group(2) or "1")
            new_left = int(hunk.group(4) or "1")
            saw_hunk = True
            continue

        if line.startswith(("new file mode ", "deleted file mode ", "old mode ",
                            "new mode ", "rename from ", "rename to ",
                            "copy from ", "copy to ")):
            return False, "unsupported_file_operation"

        if not saw_hunk:
            # Only the optional git blob index line is accepted between file
            # headers and the first hunk. Reject mode changes and metadata.
            if line.startswith("index "):
                continue
            return False, "malformed_diff_hunk"

        if _finish_hunk(old_left, new_left):
            if line.startswith("\\ No newline at end of file"):
                continue
            return False, "malformed_diff_hunk"

        if line.startswith(" "):
            old_left -= 1
            new_left -= 1
        elif line.startswith("+") and not line.startswith("+++"):
            new_left -= 1
            actual[current][0] += 1
        elif line.startswith("-") and not line.startswith("---"):
            old_left -= 1
            actual[current][1] += 1
        elif line.startswith("\\ No newline at end of file"):
            continue
        else:
            return False, "malformed_diff_hunk"
        if old_left < 0 or new_left < 0:
            return False, "malformed_diff_hunk"

    if current is None or not finish_section():
        return False, "malformed_diff_hunk"
    if set(actual) != set(declared):
        return False, "diff_paths_mismatch"
    for path, counts in declared.items():
        if tuple(actual[path]) != counts:
            return False, "diff_line_count_mismatch"
    return True, "metadata_policy_passed_draft_only"
