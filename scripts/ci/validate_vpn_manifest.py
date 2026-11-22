#!/usr/bin/env python3
"""Validate the merged Android manifest has exactly one VPN service."""
from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
VPN_ACTION = "android.net.VpnService"


def vpn_services(manifest_path: Path) -> list[str]:
    root = ET.parse(manifest_path).getroot()
    found: list[str] = []
    for service in root.findall("./application/service"):
        name = service.get(f"{ANDROID_NS}name", "<unnamed>")
        actions = {
            action.get(f"{ANDROID_NS}name")
            for action in service.findall("./intent-filter/action")
        }
        if VPN_ACTION in actions:
            found.append(name)
    return found


def validate(manifest_path: Path) -> list[str]:
    services = vpn_services(manifest_path)
    if len(services) != 1:
        raise ValueError(
            f"Expected exactly one merged VPN service, found {len(services)}: {services}"
        )
    return services


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args()
    try:
        services = validate(args.manifest)
    except (OSError, ET.ParseError, ValueError) as error:
        print(f"VPN manifest validation failed: {error}", file=sys.stderr)
        return 1
    print(f"PASS: exactly one VPN service in merged manifest: {services[0]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
