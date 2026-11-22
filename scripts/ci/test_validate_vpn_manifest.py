import tempfile
import unittest
from pathlib import Path

from scripts.ci.validate_vpn_manifest import validate


class VpnManifestValidationTests(unittest.TestCase):
    def _manifest(self, services: str) -> Path:
        temp = tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False)
        self.addCleanup(Path(temp.name).unlink, missing_ok=True)
        temp.write(
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
            f'<application>{services}</application></manifest>'
        )
        temp.close()
        return Path(temp.name)

    def test_accepts_exactly_one_vpn_service(self):
        path = self._manifest(
            '<service android:name="example.Vpn"><intent-filter>'
            '<action android:name="android.net.VpnService"/>'
            '</intent-filter></service>'
        )
        self.assertEqual(["example.Vpn"], validate(path))

    def test_rejects_no_vpn_service(self):
        path = self._manifest('<service android:name="example.Other"/>')
        with self.assertRaisesRegex(ValueError, "found 0"):
            validate(path)

    def test_rejects_multiple_vpn_services(self):
        path = self._manifest(
            '<service android:name="example.One"><intent-filter>'
            '<action android:name="android.net.VpnService"/>'
            '</intent-filter></service>'
            '<service android:name="example.Two"><intent-filter>'
            '<action android:name="android.net.VpnService"/>'
            '</intent-filter></service>'
        )
        with self.assertRaisesRegex(ValueError, "found 2"):
            validate(path)


if __name__ == "__main__":
    unittest.main()
