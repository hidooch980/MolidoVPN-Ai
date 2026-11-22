import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("patch_xray_wrapper.py")
SPEC = importlib.util.spec_from_file_location("patch_xray_wrapper", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(MODULE)


class PatchXrayWrapperTests(unittest.TestCase):
    def test_patch_requires_exact_pinned_source_shape(self):
        with self.assertRaisesRegex(RuntimeError, "expected exactly one"):
            MODULE.patch_main_source("package libv2ray\n")

    def test_patch_refuses_double_application(self):
        with self.assertRaisesRegex(RuntimeError, "already applied"):
            MODULE.patch_main_source("// MOLIDO_PATCH: fail-closed lifecycle v1")

    def test_patch_adds_fail_closed_callback_checks_and_cleanup(self):
        source = Path(__file__).parents[2] / "tests" / "fixtures" / "androidlibxray-main-sample.go"
        if not source.exists():
            self.skipTest("Pinned upstream source fixture is not checked into this repository")
        patched = MODULE.patch_main_source(source.read_text(encoding="utf-8"))
        self.assertIn("prepareVPNService(v.SupportSet)", patched)
        self.assertIn("errors.Join(coreErr, serviceErr)", patched)
        self.assertIn("v.SupportSet.Prepare()", patched)
        self.assertIn("v.SupportSet.Setup(\"\")", patched)
        self.assertIn("shutdownVPNService(s)", patched)


if __name__ == "__main__":
    unittest.main()
