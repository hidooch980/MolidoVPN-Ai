#!/usr/bin/env python3
"""Apply Molido's reviewed fail-closed patch to a pinned AndroidLibXrayLite checkout."""
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
    return replace_once(source, old_pointloop, new_pointloop, "core and VPN startup")


def patch_support_test_source(source: str) -> str:
    return replace_once(
        source,
        r'fmt.Fprintf(conn, fmt.Sprintf("GET / HTTP/1.1\r\nHost: %s\r\n\r\n", _host))',
        r'fmt.Fprintf(conn, "GET / HTTP/1.1\r\nHost: %s\r\n\r\n", _host)',
        "non-constant fmt.Fprintf test call",
    )


LIFECYCLE_TEST = r'''package libv2ray

import (
    "errors"
    "strings"
    "testing"
)

type molidoTestVPNService struct {
    prepareCode int
    setupCode int
    shutdownCode int
    prepareCalls int
    setupCalls int
    shutdownCalls int
    statuses int
}

func (s *molidoTestVPNService) Prepare() int { s.prepareCalls++; return s.prepareCode }
func (s *molidoTestVPNService) Setup(string) int { s.setupCalls++; return s.setupCode }
func (s *molidoTestVPNService) Shutdown() int { s.shutdownCalls++; return s.shutdownCode }
func (s *molidoTestVPNService) Protect(int) bool { return true }
func (s *molidoTestVPNService) OnEmitStatus(int, string) int { s.statuses++; return 0 }

func TestMolidoPrepareVPNServiceSuccess(t *testing.T) {
    s := &molidoTestVPNService{}
    if err := prepareVPNService(s); err != nil { t.Fatal(err) }
    if s.prepareCalls != 1 || s.setupCalls != 1 || s.shutdownCalls != 0 {
        t.Fatalf("unexpected callback counts: %+v", s)
    }
}

func TestMolidoPrepareFailureTriggersShutdown(t *testing.T) {
    s := &molidoTestVPNService{prepareCode: 17}
    err := prepareVPNService(s)
    if err == nil || !strings.Contains(err.Error(), "prepare failed") { t.Fatalf("unexpected error: %v", err) }
    if s.prepareCalls != 1 || s.setupCalls != 0 || s.shutdownCalls != 1 {
        t.Fatalf("partial Prepare was not rolled back: %+v", s)
    }
}

func TestMolidoSetupFailureTriggersShutdown(t *testing.T) {
    s := &molidoTestVPNService{setupCode: 23}
    err := prepareVPNService(s)
    if err == nil || !strings.Contains(err.Error(), "setup failed") { t.Fatalf("unexpected error: %v", err) }
    if s.prepareCalls != 1 || s.setupCalls != 1 || s.shutdownCalls != 1 {
        t.Fatalf("partial Setup was not rolled back: %+v", s)
    }
}

func TestMolidoNilServiceFailsClosed(t *testing.T) {
    if err := prepareVPNService(nil); err == nil { t.Fatal("nil service unexpectedly accepted") }
    if err := shutdownVPNService(nil); err != nil { t.Fatal(err) }
}

func TestMolidoShutdownErrorIsReturned(t *testing.T) {
    s := &molidoTestVPNService{shutdownCode: 9}
    err := shutdownVPNService(s)
    if err == nil || !strings.Contains(err.Error(), "shutdown failed") { t.Fatalf("unexpected error: %v", err) }
    _ = errors.Is(err, nil)
}
'''


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-dir", type=Path, required=True)
    args = parser.parse_args()
    main_path = args.source_dir / "libv2ray_main.go"
    support_test_path = args.source_dir / "libv2ray_support_test.go"
    lifecycle_test_path = args.source_dir / "molido_lifecycle_test.go"
    if lifecycle_test_path.exists():
        raise RuntimeError("Molido lifecycle tests already exist")
    patched_main = patch_main_source(main_path.read_text(encoding="utf-8"))
    patched_support_test = patch_support_test_source(support_test_path.read_text(encoding="utf-8"))
    main_path.write_text(patched_main, encoding="utf-8")
    support_test_path.write_text(patched_support_test, encoding="utf-8")
    lifecycle_test_path.write_text(LIFECYCLE_TEST, encoding="utf-8")
    print("Applied Molido lifecycle hardening and deterministic Go tests")


if __name__ == "__main__":
    main()
