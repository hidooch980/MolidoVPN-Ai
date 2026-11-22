#!/usr/bin/env python3
"""Apply the reviewed Molido hardening patch to the pinned WireGuard Android Go entry point.

This is a deliberately strict source transformer: it fails closed if upstream source shape
changes. It does not patch the published AAR or alter the Android runtime dependency.
"""
from __future__ import annotations

import argparse
from pathlib import Path

IMPORT_OLD = '\t"runtime"\n\t"runtime/debug"'
IMPORT_NEW = '\t"runtime"\n\t"runtime/debug"\n\t"sync"'

MAP_OLD = """var tunnelHandles map[int32]TunnelHandle

func init() {"""
MAP_NEW = """var tunnelHandles map[int32]TunnelHandle
var tunnelHandlesMu sync.RWMutex

func init() {"""

IPC_OLD = """\terr = device.IpcSet(settings)
\tif err != nil {
\t\tunix.Close(int(tunFd))
\t\tlogger.Errorf("IpcSet: %v", err)
\t\treturn -1
\t}"""
IPC_NEW = """\terr = device.IpcSet(settings)
\tif err != nil {
\t\t// NewDevice owns the TUN device and its descriptor after construction.
\t\t// Close the device so its workers and TUN are released exactly once.
\t\tdevice.Close()
\t\tlogger.Errorf("IpcSet: %v", err)
\t\treturn -1
\t}"""

UAPI_OLD = """\tuapiFile, err := ipc.UAPIOpen(name)
\tif err != nil {
\t\tlogger.Errorf("UAPIOpen: %v", err)
\t} else {
\t\tuapi, err = ipc.UAPIListen(name, uapiFile)
\t\tif err != nil {
\t\t\tuapiFile.Close()
\t\t\tlogger.Errorf("UAPIListen: %v", err)
\t\t} else {
\t\t\tgo func() {
\t\t\t\tfor {
\t\t\t\t\tconn, err := uapi.Accept()
\t\t\t\t\tif err != nil {
\t\t\t\t\t\treturn
\t\t\t\t\t}
\t\t\t\t\tgo device.IpcHandle(conn)
\t\t\t\t}
\t\t\t}()
\t\t}
\t}"""
UAPI_NEW = """\tuapiFile, err := ipc.UAPIOpen(name)
\tif err != nil {
\t\tlogger.Errorf("UAPIOpen: %v", err)
\t} else {
\t\tuapi, err = ipc.UAPIListen(name, uapiFile)
\t\tif err != nil {
\t\t\t_ = uapiFile.Close()
\t\t\tlogger.Errorf("UAPIListen: %v", err)
\t\t} else {
\t\t\t// net.FileListener duplicates the descriptor. Close the original
\t\t\t// *os.File now; the returned listener owns its independent copy.
\t\t\tif closeErr := uapiFile.Close(); closeErr != nil {
\t\t\t\t_ = uapi.Close()
\t\t\t\tdevice.Close()
\t\t\t\tlogger.Errorf("UAPI file close: %v", closeErr)
\t\t\t\treturn -1
\t\t\t}
\t\t\tuapiFile = nil
\t\t\tgo func() {
\t\t\t\tfor {
\t\t\t\t\tconn, err := uapi.Accept()
\t\t\t\t\tif err != nil {
\t\t\t\t\t\treturn
\t\t\t\t\t}
\t\t\t\t\tgo device.IpcHandle(conn)
\t\t\t\t}
\t\t\t}()
\t\t}
\t}"""

UP_OLD = """\terr = device.Up()
\tif err != nil {
\t\tlogger.Errorf("Unable to bring up device: %v", err)
\t\tuapiFile.Close()
\t\tdevice.Close()
\t\treturn -1
\t}"""
UP_NEW = """\terr = device.Up()
\tif err != nil {
\t\tlogger.Errorf("Unable to bring up device: %v", err)
\t\tif uapi != nil {
\t\t\t_ = uapi.Close()
\t\t}
\t\tif uapiFile != nil {
\t\t\t_ = uapiFile.Close()
\t\t}
\t\tdevice.Close()
\t\treturn -1
\t}"""

HANDLE_OLD = """\tvar i int32
\tfor i = 0; i < math.MaxInt32; i++ {
\t\tif _, exists := tunnelHandles[i]; !exists {
\t\t\tbreak
\t\t}
\t}
\tif i == math.MaxInt32 {
\t\tlogger.Errorf("Unable to find empty handle")
\t\tuapiFile.Close()
\t\tdevice.Close()
\t\treturn -1
\t}
\ttunnelHandles[i] = TunnelHandle{device: device, uapi: uapi}
\treturn i"""
HANDLE_NEW = """\ttunnelHandlesMu.Lock()
\tvar i int32
\tfor i = 0; i < math.MaxInt32; i++ {
\t\tif _, exists := tunnelHandles[i]; !exists {
\t\t\tbreak
\t\t}
\t}
\tif i == math.MaxInt32 {
\t\ttunnelHandlesMu.Unlock()
\t\tlogger.Errorf("Unable to find empty handle")
\t\tif uapi != nil {
\t\t\t_ = uapi.Close()
\t\t}
\t\tif uapiFile != nil {
\t\t\t_ = uapiFile.Close()
\t\t}
\t\tdevice.Close()
\t\treturn -1
\t}
\ttunnelHandles[i] = TunnelHandle{device: device, uapi: uapi}
\ttunnelHandlesMu.Unlock()
\treturn i"""

OFF_OLD = """func wgTurnOff(tunnelHandle int32) {
\thandle, ok := tunnelHandles[tunnelHandle]
\tif !ok {
\t\treturn
\t}
\tdelete(tunnelHandles, tunnelHandle)
\tif handle.uapi != nil {"""
OFF_NEW = """func wgTurnOff(tunnelHandle int32) {
\ttunnelHandlesMu.Lock()
\thandle, ok := tunnelHandles[tunnelHandle]
\tif ok {
\t\tdelete(tunnelHandles, tunnelHandle)
\t}
\ttunnelHandlesMu.Unlock()
\tif !ok {
\t\treturn
\t}
\tif handle.uapi != nil {"""

SOCKET4_OLD = """func wgGetSocketV4(tunnelHandle int32) int32 {
\thandle, ok := tunnelHandles[tunnelHandle]"""
SOCKET4_NEW = """func wgGetSocketV4(tunnelHandle int32) int32 {
\ttunnelHandlesMu.RLock()
\tdefer tunnelHandlesMu.RUnlock()
\thandle, ok := tunnelHandles[tunnelHandle]"""

SOCKET6_OLD = """func wgGetSocketV6(tunnelHandle int32) int32 {
\thandle, ok := tunnelHandles[tunnelHandle]"""
SOCKET6_NEW = """func wgGetSocketV6(tunnelHandle int32) int32 {
\ttunnelHandlesMu.RLock()
\tdefer tunnelHandlesMu.RUnlock()
\thandle, ok := tunnelHandles[tunnelHandle]"""

CONFIG_OLD = """func wgGetConfig(tunnelHandle int32) *C.char {
\thandle, ok := tunnelHandles[tunnelHandle]"""
CONFIG_NEW = """func wgGetConfig(tunnelHandle int32) *C.char {
\ttunnelHandlesMu.RLock()
\tdefer tunnelHandlesMu.RUnlock()
\thandle, ok := tunnelHandles[tunnelHandle]"""

REPLACEMENTS = (
    (IMPORT_OLD, IMPORT_NEW, "sync import"),
    (MAP_OLD, MAP_NEW, "handle registry mutex"),
    (IPC_OLD, IPC_NEW, "IpcSet cleanup"),
    (UAPI_OLD, UAPI_NEW, "UAPI descriptor ownership"),
    (UP_OLD, UP_NEW, "device.Up cleanup"),
    (HANDLE_OLD, HANDLE_NEW, "serialized handle allocation"),
    (OFF_OLD, OFF_NEW, "serialized handle removal"),
    (SOCKET4_OLD, SOCKET4_NEW, "IPv4 socket lookup lock"),
    (SOCKET6_OLD, SOCKET6_NEW, "IPv6 socket lookup lock"),
    (CONFIG_OLD, CONFIG_NEW, "config lookup lock"),
)

def transform(source: str) -> str:
    if "var tunnelHandlesMu sync.RWMutex" in source:
        if all(new in source for _, new, _ in REPLACEMENTS):
            return source
        raise ValueError("partially patched WireGuard source; refusing to guess")
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
    print("PASS: pinned WireGuard Android Go entry point hardened for descriptor cleanup and handle synchronization")

if __name__ == "__main__":
    main()
