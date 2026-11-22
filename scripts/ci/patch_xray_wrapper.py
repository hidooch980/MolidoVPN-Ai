#!/usr/bin/env python3
"""Apply the reviewed Molido fail-closed lifecycle patch to the pinned AndroidLibXrayLite source."""
from __future__ import annotations

import argparse
from pathlib import Path


def replace_once(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one source match, found {count}")
    return source.replace(old, new, 1)


def patch_main_source(source: str) -> str:
    marker = "// MOLIDO_PATCH: fail-closed lifecycle v1"
    if marker in source:
        raise RuntimeError("Molido lifecycle patch was already applied")
    source = replace_once(
        source,
        """\t\t\t\t\tv.StopLoop()
\t\t\t\t\tv.SupportSet.Shutdown()""",
        """\t\t\t\t\tif stopErr := v.StopLoop(); stopErr != nil {
\t\t\t\t\t\tlog.Printf("VPN shutdown after DNS failure: %v", stopErr)
\t\t\t\t\t}""",
        "DNS failure shutdown",
    )
    source = replace_once(
        source,
        """\t\terr = v.pointloop()
\t}
\treturn""",
        """\t\terr = v.pointloop()
\t\tif err != nil && v.closeChan != nil {
\t\t\tclose(v.closeChan)
\t\t\tv.closeChan = nil
\t\t}
\t}
\treturn""",
        "RunLoop failed-start cancellation",
    )
    old_stop = """func (v *V2RayPoint) StopLoop() (err error) {
\tv.v2rayOP.Lock()
\tdefer v.v2rayOP.Unlock()
\tif v.IsRunning {
\t\tclose(v.closeChan)
\t\tv.shutdownInit()
\t\tv.SupportSet.OnEmitStatus(0, "Closed")
\t}
\treturn
}"""
    new_stop = """func (v *V2RayPoint) StopLoop() error {
\tv.v2rayOP.Lock()
\tdefer v.v2rayOP.Unlock()
\tif !v.IsRunning {
\t\treturn nil
\t}
\tif v.closeChan != nil {
\t\tclose(v.closeChan)
\t\tv.closeChan = nil
\t}
\tcoreErr := v.shutdownInit()
\tserviceErr := shutdownVPNService(v.SupportSet)
\tif v.SupportSet != nil {
\t\tv.SupportSet.OnEmitStatus(0, "Closed")
\t}
\treturn errors.Join(coreErr, serviceErr)
}"""
    source = replace_once(source, old_stop, new_stop, "StopLoop")
    source = replace_once(
        source,
        """func (v *V2RayPoint) shutdownInit() {
\tv.IsRunning = false
\tv.Vpoint.Close()
\tv.Vpoint = nil
\tv.statsManager = nil
}""",
        """func (v *V2RayPoint) shutdownInit() error {
\tv.IsRunning = false
\tvar closeErr error
\tif v.Vpoint != nil {
\t\tcloseErr = v.Vpoint.Close()
\t}
\tv.Vpoint = nil
\tv.statsManager = nil
\treturn closeErr
}""",
        "nil-safe core cleanup",
    )
    old_pointloop = """\tlog.Println("start core")
\tv.IsRunning = true
\tif err := v.Vpoint.Start(); err != nil {
\t\tv.IsRunning = false
\t\tlog.Println(err)
\t\treturn err
\t}

\tv.SupportSet.Prepare()
\tv.SupportSet.Setup("")
\tv.SupportSet.OnEmitStatus(0, "Running")
\treturn nil
}"""
    new_pointloop = """\tlog.Println("start core")
\tv.IsRunning = true
\tif err := v.Vpoint.Start(); err != nil {
\t\tlog.Println(err)
\t\treturn errors.Join(err, v.shutdownInit())
\t}

\tif err := prepareVPNService(v.SupportSet); err != nil {
\t\treturn errors.Join(err, v.shutdownInit())
\t}
\tv.SupportSet.OnEmitStatus(0, "Running")
\treturn nil
}

// MOLIDO_PATCH: fail-closed lifecycle v1
func prepareVPNService(s V2RayVPNServiceSupportsSet) error {
\tif s == nil {
\t\treturn errors.New("VPN service callbacks are unavailable")
\t}
\tif code := s.Prepare(); code != 0 {
\t\treturn errors.Join(fmt.Errorf("VPN service prepare failed: code %d", code), shutdownVPNService(s))
\t}
\tif code := s.Setup(""); code != 0 {
\t\treturn errors.Join(fmt.Errorf("VPN service setup failed: code %d", code), shutdownVPNService(s))
\t}
\treturn nil
}

func shutdownVPNService(s V2RayVPNServiceSupportsSet) error {
\tif s == nil {
\t\treturn nil
\t}
\tif code := s.Shutdown(); code != 0 {
\t\treturn fmt.Errorf("VPN service shutdown failed: code %d", code)
\t}
\treturn nil
}"""
    source = replace_once(source, old_pointloop, new_pointloop, "core and VPN startup")
    return source


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-dir", type=Path, required=True)
    args = parser.parse_args()
    path = args.source_dir / "libv2ray_main.go"
    original = path.read_text(encoding="utf-8")
    patched = patch_main_source(original)
    path.write_text(patched, encoding="utf-8")
    print(f"Applied fail-closed lifecycle patch: {path}")


if __name__ == "__main__":
    main()
