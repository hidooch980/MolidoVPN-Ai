#!/usr/bin/env python3
"""Validate the merged Android manifest has exactly the expected VPN provider."""
from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
VPN_ACTION = "android.net.VpnService"
BIND_VPN_PERMISSION = "android.permission.BIND_VPN_SERVICE"
# Temporary invariant while the upstream AAR is still the TUN provider.
# Change this only in the same reviewed migration that installs MolidoVpnService.
EXPECTED_VPN_SERVICE = "com.wireguard.android.backend.GoBackend$VpnService"


def vpn_services(manifest_path: Path) -> list[tuple[str, str | None]]:
    root = ET.parse(manifest_path).getroot()
    found: list[tuple[str, str | None]] = []
    for service in root.findall("./application/service"):
        name = service.get(f"{ANDROID_NS}name", "<unnamed>")
        actions = {
            action.get(f"{ANDROID_NS}name")
            for action in service.findall("./intent-filter/action")
        }
        if VPN_ACTION in actions:
            found.append((name, service.get(f"{ANDROID_NS}permission")))
    return found


def validate(manifest_path: Path) -> list[str]:
    services = vpn_services(manifest_path)
    if len(services) != 1:
        raise ValueError(
            f"Expected exactly one merged VPN service, found {len(services)}: "
            f"{[name for name, _ in services]}"
        )
    name, permission = services[0]
    if name != EXPECTED_VPN_SERVICE:
        raise ValueError(
            f"Expected VPN provider {EXPECTED_VPN_SERVICE!r}, found {name!r}"
        )
    if permission != BIND_VPN_PERMISSION:
        raise ValueError(
            f"VPN provider {name!r} must declare permission "
            f"{BIND_VPN_PERMISSION!r}, found {permission!r}"
        )
    return [name]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args()
    try:
        services = validate(args.manifest)
    except (OSError, ET.ParseError, ValueError) as error:
        print(f"VPN manifest validation failed: {error}", file=sys.stderr)
        return 1
    print(f"PASS: exactly one expected VPN service with required permission: {services[0]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
