import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("patch_xray_wrapper.py")
SPEC = importlib.util.spec_from_file_location("patch_xray_wrapper", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(MODULE)

UPSTREAM_SHAPE = """package libv2ray
func resolverFailure(v *V2RayPoint) {
\t\t\t\t\tv.StopLoop()
\t\t\t\t\tv.SupportSet.Shutdown()
}
func run(v *V2RayPoint) {
\t\terr = v.pointloop()
\t}
\treturn
}
func (v *V2RayPoint) StopLoop() (err error) {
\tv.v2rayOP.Lock()
\tdefer v.v2rayOP.Unlock()
\tif v.IsRunning {
\t\tclose(v.closeChan)
\t\tv.shutdownInit()
\t\tv.SupportSet.OnEmitStatus(0, "Closed")
\t}
\treturn
}
func (v *V2RayPoint) shutdownInit() {
\tv.IsRunning = false
\tv.Vpoint.Close()
\tv.Vpoint = nil
\tv.statsManager = nil
}
func (v *V2RayPoint) pointloop() error {
\tlog.Println("start core")
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
}
"""


class PatchXrayWrapperTests(unittest.TestCase):
    def test_patch_requires_exact_pinned_source_shape(self):
        with self.assertRaisesRegex(RuntimeError, "expected exactly one"):
            MODULE.patch_main_source("package libv2ray\n")

    def test_patch_refuses_double_application(self):
        with self.assertRaisesRegex(RuntimeError, "already applied"):
            MODULE.patch_main_source("// MOLIDO_PATCH: fail-closed lifecycle v1")

    def test_upstream_test_vet_issue_is_fixed_without_changing_wire_text(self):
        sample = r'fmt.Fprintf(conn, fmt.Sprintf("GET / HTTP/1.1\r\nHost: %s\r\n\r\n", _host))'
        patched = MODULE.patch_support_test_source(sample)
        self.assertIn(r'fmt.Fprintf(conn, "GET / HTTP/1.1\r\nHost: %s\r\n\r\n", _host)', patched)
        self.assertNotIn("fmt.Sprintf", patched)

    def test_lifecycle_tests_cover_callback_failures(self):
        self.assertIn("TestMolidoPrepareFailureTriggersShutdown", MODULE.LIFECYCLE_TEST)
        self.assertIn("TestMolidoSetupFailureTriggersShutdown", MODULE.LIFECYCLE_TEST)
        self.assertIn("TestMolidoNilServiceFailsClosed", MODULE.LIFECYCLE_TEST)

    def test_patch_adds_fail_closed_callback_checks_and_cleanup(self):
        patched = MODULE.patch_main_source(UPSTREAM_SHAPE)
        self.assertIn("prepareVPNService(v.SupportSet)", patched)
        self.assertIn("errors.Join(coreErr, serviceErr)", patched)
        self.assertIn("code := s.Prepare()", patched)
        self.assertIn('code := s.Setup("")', patched)
        self.assertIn("shutdownVPNService(s)", patched)
        self.assertIn("v.Vpoint != nil", patched)
        self.assertIn("v.closeChan = nil", patched)


if __name__ == "__main__":
    unittest.main()
