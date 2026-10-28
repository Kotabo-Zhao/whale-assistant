"""Small synthetic fixtures exercise the development APK refresh and verifier."""
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent


class RefreshTests(unittest.TestCase):
    def test_voice_and_other_replacements_are_verified(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            base = root / 'base.apk'
            with zipfile.ZipFile(base, 'w') as archive:
                for name, value in {
                    'classes.dex': b'old dex', 'assets/support/plugin-manager.cjs': b'old plugin',
                    'assets/support/harness-voice-read.js': b'old voice', 'assets/public/index.html': b'old html',
                    'assets/public/assets/old.js': b'old frontend', 'assets/runtime.bundle': b'untouched runtime',
                }.items(): archive.writestr(name, value)
            build = root / 'build'
            dex = build / 'intermediates/dex/debug/mergeProjectDexDebug/0/classes.dex'
            dex.parent.mkdir(parents=True)
            dex.write_bytes(b'new dex')
            mapping = root / 'mapping.json'
            mapping.write_text(json.dumps({'base': '5c22000', 'dex': {'classes.dex': dex.relative_to(build).as_posix()}}))
            dist = root / 'dist'
            (dist / 'assets').mkdir(parents=True)
            (dist / 'index.html').write_text('new html')
            (dist / 'assets/new.js').write_text('new frontend')
            plugin, voice, apk = root / 'plugin', root / 'voice', root / 'out.apk'
            plugin.write_bytes(b'new plugin')
            voice.write_bytes(b'new voice')
            # The production script has a pinned base digest. Substitute the digest only
            # in this isolated test copy so the fixture exercises the real implementation.
            script = root / 'refresh.py'
            script.write_text((SCRIPTS / 'refresh-debug-apk.py').read_text().replace(
                '3ae33e6c63efe7da890d3ac52bd8e356e8b30528298f24be72f6e02190c08c01',
                hashlib.sha256(base.read_bytes()).hexdigest()))
            command = [sys.executable, str(script), str(base), str(mapping), str(build), str(dist), str(plugin), str(voice), str(apk)]
            subprocess.run(command, check=True, capture_output=True)
            verify = [sys.executable, str(SCRIPTS / 'verify-refreshed-debug-apk.py'), str(base), str(mapping), str(build), str(plugin), str(voice), str(apk)]
            subprocess.run(verify, check=True, capture_output=True)
            with zipfile.ZipFile(apk) as actual:
                self.assertEqual(actual.read('assets/support/harness-voice-read.js'), b'new voice')
                self.assertEqual(actual.read('assets/runtime.bundle'), b'untouched runtime')
                self.assertEqual(actual.read('assets/public/assets/new.js'), b'new frontend')
                self.assertNotIn('assets/public/assets/old.js', actual.namelist())
            voice.write_bytes(b'changed after packaging')
            self.assertNotEqual(subprocess.run(verify, capture_output=True).returncode, 0)
            self.assertNotEqual(subprocess.run(command, capture_output=True).returncode, 0)  # never overwrite


if __name__ == '__main__':
    unittest.main()
