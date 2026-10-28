import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location('apk_delta', Path(__file__).with_name('apk-runtime-delta.py'))
delta = importlib.util.module_from_spec(spec)
spec.loader.exec_module(delta)

class ApkRuntimeDeltaTest(unittest.TestCase):
    def test_exact_restore_and_wrong_base_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            base, target, patch, result = [folder / name for name in ('base.apk', 'old.apk', 'patch.zip', 'restored.apk')]
            def apk(path, runtime, content):
                with zipfile.ZipFile(path, 'w') as output:
                    output.writestr('foo', content)
                    output.writestr(delta.ENTRY, runtime, compress_type=zipfile.ZIP_STORED)
                    output.writestr('bar', content[::-1])
            apk(base, b'large runtime bytes' * 1000, b'new version')
            apk(target, b'large runtime bytes' * 1000, b'old version')
            delta.create(base, target, patch)
            delta.restore(base, patch, result)
            self.assertEqual(target.read_bytes(), result.read_bytes())
            self.assertTrue(target.exists())
            with self.assertRaises(FileExistsError):
                delta.restore(base, patch, result)
            wrong = folder / 'wrong.apk'
            apk(wrong, b'different runtime', b'new version')
            with self.assertRaises(ValueError):
                delta.restore(wrong, patch, folder / 'wrong-output.apk')
            with self.assertRaises(ValueError):
                delta.create(wrong, target, folder / 'wrong-delta.zip')
            corrupt = folder / 'corrupt.zip'
            with zipfile.ZipFile(patch) as original, zipfile.ZipFile(corrupt, 'w') as changed:
                for name in original.namelist():
                    content = original.read(name)
                    if name == 'prefix':
                        content = bytes([content[0] ^ 1]) + content[1:]
                    changed.writestr(name, content)
            broken_output = folder / 'broken.apk'
            with self.assertRaises(ValueError):
                delta.restore(base, corrupt, broken_output)
            self.assertFalse(broken_output.exists())

if __name__ == '__main__':
    unittest.main()
