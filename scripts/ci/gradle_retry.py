#!/usr/bin/env python3
"""Retry Gradle only when the final failure is a recognized repository/network error."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
from collections import deque
from typing import Sequence

_TRANSIENT_FAILURES = (
    re.compile(r"Could not (?:GET|HEAD|find) https?://", re.IGNORECASE),
    re.compile(r"Could not resolve .* because of .*?(?:unknown host|temporary failure|connection)", re.IGNORECASE),
    re.compile(r"Temporary failure in name resolution|UnknownHostException", re.IGNORECASE),
    re.compile(r"Connection reset|Connection refused|Connect timed out|Read timed out", re.IGNORECASE),
    re.compile(r"(?:Received )?status code (?:429|500|502|503|504)\b", re.IGNORECASE),
    re.compile(r"Remote host (?:closed|terminated) the connection|unexpected end of stream", re.IGNORECASE),
)

# A deterministic build/test failure must never be retried just because an earlier
# line happened to mention a transient repository warning.
_DETERMINISTIC_FAILURES = (
    re.compile(r"AssertionError|There were failing tests|FAILED\s+in\s+\d", re.IGNORECASE),
    re.compile(r"Unresolved reference|Compilation error|e: file://", re.IGNORECASE),
    re.compile(r"Could not compile .*?\.(?:kt|java)", re.IGNORECASE),
)


def classify_failure(output_tail: str) -> str:
    """Return TRANSIENT_NETWORK or DETERMINISTIC/UNKNOWN from the final log tail."""
    lines = output_tail.splitlines()[-100:]
    excerpt = "\n".join(lines)
    if any(pattern.search(excerpt) for pattern in _DETERMINISTIC_FAILURES):
        return "DETERMINISTIC"
    if any(pattern.search(excerpt) for pattern in _TRANSIENT_FAILURES):
        return "TRANSIENT_NETWORK"
    return "DETERMINISTIC_OR_UNKNOWN"


def run(command: Sequence[str], attempts: int = 3, delay_seconds: int = 15) -> int:
    if not command:
        print("No command supplied after --", file=sys.stderr)
        return 2
    if attempts < 1 or delay_seconds < 0:
        print("Invalid retry configuration", file=sys.stderr)
        return 2

    for attempt in range(1, attempts + 1):
        tail: deque[str] = deque(maxlen=500)
        print(f"[gradle-retry] attempt {attempt}/{attempts}: {' '.join(command)}", flush=True)
        process = subprocess.Popen(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            errors="replace",
            bufsize=1,
        )
        assert process.stdout is not None
        for line in process.stdout:
            sys.stdout.write(line)
            sys.stdout.flush()
            tail.append(line)
        result = process.wait()
        if result == 0:
            return 0

        classification = classify_failure("".join(tail))
        if classification != "TRANSIENT_NETWORK" or attempt == attempts:
            print(
                f"[gradle-retry] stopping after exit={result}; classification={classification}. "
                "No retry will be attempted for test, compilation, lint, or unknown failures.",
                file=sys.stderr,
                flush=True,
            )
            return result if result > 0 else 1

        delay = delay_seconds * (2 ** (attempt - 1))
        print(
            f"[gradle-retry] recognized transient repository/network failure; "
            f"retrying in {delay}s.",
            flush=True,
        )
        time.sleep(delay)
    return 1


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--attempts", type=int, default=3)
    parser.add_argument("--delay-seconds", type=int, default=15)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    command = list(args.command)
    if command and command[0] == "--":
        command = command[1:]
    return run(command, args.attempts, args.delay_seconds)


if __name__ == "__main__":
    raise SystemExit(main())
