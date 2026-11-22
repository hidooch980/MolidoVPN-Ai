#!/usr/bin/env python3
"""Strict cleanup patch for the exact pinned WireGuard Go UAPI listener source."""
from __future__ import annotations

import argparse
from pathlib import Path

INIT_OLD = """\tuapi.inotifyFd, err = unix.InotifyInit()
\tif err != nil {
\t\treturn nil, err
\t}"""
INIT_NEW = """\tuapi.inotifyFd, err = unix.InotifyInit()
\tif err != nil {
\t\t_ = listener.Close()
\t\treturn nil, err
\t}"""

WATCH_OLD = """\tif err != nil {
\t\treturn nil, err
\t}

\tuapi.inotifyRWCancel, err = rwcancel.NewRWCancel(uapi.inotifyFd)"""
WATCH_NEW = """\tif err != nil {
\t\t_ = unix.Close(uapi.inotifyFd)
\t\t_ = listener.Close()
\t\treturn nil, err
\t}

\tuapi.inotifyRWCancel, err = rwcancel.NewRWCancel(uapi.inotifyFd)"""

RWCANCEL_OLD = """\tuapi.inotifyRWCancel, err = rwcancel.NewRWCancel(uapi.inotifyFd)
\tif err != nil {
\t\tunix.Close(uapi.inotifyFd)
\t\treturn nil, err
\t}"""
RWCANCEL_NEW = """\tuapi.inotifyRWCancel, err = rwcancel.NewRWCancel(uapi.inotifyFd)
\tif err != nil {
\t\t_ = unix.Close(uapi.inotifyFd)
\t\t_ = listener.Close()
\t\treturn nil, err
\t}"""

REPLACEMENTS = (
    (INIT_OLD, INIT_NEW, "inotify init cleanup"),
    (WATCH_OLD, WATCH_NEW, "inotify watch cleanup"),
    (RWCANCEL_OLD, RWCANCEL_NEW, "rwcancel initialization cleanup"),
)


def transform(source: str) -> str:
    if all(new in source for _old, new, _label in REPLACEMENTS):
        return source
    if any(new in source for _old, new, _label in REPLACEMENTS):
        raise ValueError("partially patched pinned UAPI source; refusing to guess")
    for old, new, label in REPLACEMENTS:
        count = source.count(old)
        if count != 1:
            raise ValueError(f"expected exactly one {label} anchor, found {count}")
        source = source.replace(old, new, 1)
    return source


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path)
    args = parser.parse_args()
    original = args.source.read_text(encoding="utf-8")
    patched = transform(original)
    if patched != original:
        args.source.write_text(patched, encoding="utf-8")
    print("PASS: pinned WireGuard UAPI listener partial-failure cleanup patched")


if __name__ == "__main__":
    main()
