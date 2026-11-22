import unittest

from scripts.ci.patch_wireguard_uapi import REPLACEMENTS, transform


class WireGuardUapiPatchTests(unittest.TestCase):
    def source_fixture(self):
        return "\n".join(old for old, _new, _label in REPLACEMENTS)

    def test_all_cleanup_paths_are_patched(self):
        patched = transform(self.source_fixture())
        for _old, new, _label in REPLACEMENTS:
            self.assertIn(new, patched)

    def test_patch_is_idempotent(self):
        patched = transform(self.source_fixture())
        self.assertEqual(transform(patched), patched)

    def test_unexpected_upstream_shape_fails_closed(self):
        source = self.source_fixture().replace("unix.InotifyInit()", "unexpected.Init()")
        with self.assertRaises(ValueError):
            transform(source)

    def test_partial_patch_fails_closed(self):
        source = self.source_fixture().replace(
            "if err != nil {\n\t\treturn nil, err\n\t}",
            "if err != nil {\n\t\t_ = listener.Close()\n\t\treturn nil, err\n\t}",
            1,
        )
        with self.assertRaises(ValueError):
            transform(source)


if __name__ == "__main__":
    unittest.main()
