import unittest

from scripts.ci.patch_wireguard_android import REPLACEMENTS, transform


class WireGuardPatchTests(unittest.TestCase):
    def source_fixture(self):
        return "\n".join(old for old, _new, _label in REPLACEMENTS)

    def test_all_exact_anchors_are_transformed(self):
        patched = transform(self.source_fixture())
        for _old, new, _label in REPLACEMENTS:
            self.assertIn(new, patched)

    def test_patch_is_idempotent(self):
        patched = transform(self.source_fixture())
        self.assertEqual(transform(patched), patched)

    def test_unexpected_upstream_shape_fails_closed(self):
        source = self.source_fixture().replace('IpcSet: %v', 'different upstream shape')
        with self.assertRaises(ValueError):
            transform(source)

    def test_partial_patch_fails_closed(self):
        source = self.source_fixture().replace(
            'var tunnelHandles map[int32]TunnelHandle',
            'var tunnelHandles map[int32]TunnelHandle\nvar tunnelHandlesMu sync.RWMutex',
        )
        with self.assertRaises(ValueError):
            transform(source)


if __name__ == "__main__":
    unittest.main()
