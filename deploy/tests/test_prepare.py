import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('prepare', ROOT / 'prepare.py')
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)

class PreparationTests(unittest.TestCase):
    def test_corrupt_artifact_is_rejected_before_extract(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'artifact'
            path.write_bytes(b'corrupt')
            with self.assertRaisesRegex(ValueError, 'checksum'):
                prepare.verify(path, 'sha256', '0' * 64)

class ApprovalGuards(unittest.TestCase):
    def test_host_mutators_require_explicit_approval_before_any_command(self):
        import subprocess
        for script in ['install.sh', 'admin.sh']:
            result = subprocess.run(['bash', str(ROOT / script)], capture_output=True, text=True)
            self.assertEqual(64, result.returncode, script)
            self.assertIn('approval', result.stderr, script)

if __name__ == '__main__':
    unittest.main()
