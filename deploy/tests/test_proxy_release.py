import hashlib
import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('proxy_release', Path(__file__).parents[1] / 'proxy_release.py')
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def digest(data):
    return hashlib.sha256(data).hexdigest()


class ProxyReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.base = self.root / 'opt/bridge'
        self.old = self.base / 'releases/old'
        self.old.mkdir(parents=True)
        for name in ('rama', 'zk'):
            (self.old / name).mkdir()
            (self.old / name / 'runtime').write_bytes(b'unchanged runtime')
        (self.old / 'cli-proxy-api').write_bytes(b'old proxy')
        (self.old / 'cli-proxy-api').chmod(0o755)
        (self.base / 'current').symlink_to(self.old)
        self.archive = self.root / 'proxy.tar.gz'
        with tarfile.open(self.archive, 'w:gz') as archive:
            item = tarfile.TarInfo('cli-proxy-api')
            item.size = len(b'new proxy')
            archive.addfile(item, io.BytesIO(b'new proxy'))
        self.archive_hash = digest(self.archive.read_bytes())
        self.new = self.base / 'releases/new'

    def stage(self, archive_hash=None):
        return module.stage(self.root, 'old', 'new', self.archive,
                            archive_hash or self.archive_hash,
                            digest(b'old proxy'), digest(b'new proxy'))

    def test_bad_archive_checksum_does_not_create_release_or_change_current(self):
        with self.assertRaisesRegex(ValueError, 'checksum'):
            self.stage('0' * 64)
        self.assertFalse(self.new.exists())
        self.assertEqual((self.base / 'current').resolve(), self.old)

    def test_stage_preserves_old_binary_runtime_and_state_without_switch(self):
        state = self.root / 'var/lib/bridge-proxy/auth'
        state.mkdir(parents=True)
        (state / 'oauth').write_bytes(b'private unchanged')
        self.stage()
        self.assertEqual((self.new / 'cli-proxy-api').read_bytes(), b'new proxy')
        self.assertEqual((self.old / 'cli-proxy-api').read_bytes(), b'old proxy')
        self.assertEqual((self.new / 'rama').resolve(), self.old / 'rama')
        self.assertEqual((self.new / 'zk').resolve(), self.old / 'zk')
        self.assertEqual((state / 'oauth').read_bytes(), b'private unchanged')
        self.assertEqual((self.base / 'current').resolve(), self.old)
        self.assertTrue((self.new / 'proxy-release.json').is_file())

    def test_failed_start_restores_original_release_and_restarts_it(self):
        self.stage()
        events = []
        def service(action):
            events.append((action, (self.base / 'current').resolve().name))
            if action == 'start' and (self.base / 'current').resolve() == self.new:
                raise RuntimeError('candidate unhealthy')
        with self.assertRaisesRegex(RuntimeError, 'candidate unhealthy'):
            module.switch(self.root, 'old', 'new', digest(b'old proxy'), digest(b'new proxy'), service)
        self.assertEqual((self.base / 'current').resolve(), self.old)
        self.assertEqual(events, [('stop', 'old'), ('stopped', 'old'), ('start', 'new'),
                                 ('stop', 'new'), ('stopped', 'new'), ('start', 'old')])
        self.assertEqual((self.new / 'cli-proxy-api').read_bytes(), b'new proxy')

    def test_upgrade_and_rollback_keep_runtime_and_history_identity(self):
        self.stage()
        history = self.root / 'history'
        history.write_bytes(b'saved')
        inode = history.stat().st_ino
        module.switch(self.root, 'old', 'new', digest(b'old proxy'), digest(b'new proxy'), lambda action: None)
        self.assertEqual((self.base / 'current').resolve(), self.new)
        module.switch(self.root, 'new', 'old', digest(b'new proxy'), digest(b'old proxy'), lambda action: None)
        self.assertEqual((self.base / 'current').resolve(), self.old)
        self.assertEqual(history.stat().st_ino, inode)
        self.assertEqual(history.read_bytes(), b'saved')

    def test_switch_refuses_stale_target_changed_binary_or_runtime_before_stop(self):
        self.stage()
        def unexpected(action):
            self.fail('Service must not be touched on failed preflight')
        with self.assertRaisesRegex(ValueError, 'Current release changed'):
            module.switch(self.root, 'new', 'old', digest(b'new proxy'), digest(b'old proxy'), unexpected)
        with self.assertRaisesRegex(ValueError, 'checksum'):
            module.switch(self.root, 'old', 'new', digest(b'old proxy'), '0' * 64, unexpected)
        (self.new / 'rama').unlink()
        (self.new / 'rama').mkdir()
        with self.assertRaisesRegex(ValueError, 'Only proxy'):
            module.switch(self.root, 'old', 'new', digest(b'old proxy'), digest(b'new proxy'), unexpected)

    def test_proxy_processes_remaining_prevent_switch(self):
        self.stage()
        def service(action):
            if action == 'stopped':
                raise RuntimeError('processes remain')
        with self.assertRaisesRegex(RuntimeError, 'processes remain'):
            module.switch(self.root, 'old', 'new', digest(b'old proxy'), digest(b'new proxy'), service)
        self.assertEqual((self.base / 'current').resolve(), self.old)

    def test_stage_refuses_executable_checksum_and_writable_or_traversal_paths(self):
        with self.assertRaisesRegex(ValueError, 'checksum'):
            module.stage(self.root, 'old', 'new', self.archive, self.archive_hash,
                         digest(b'old proxy'), '0' * 64)
        self.assertFalse(self.new.exists())
        self.old.chmod(0o777)
        with self.assertRaisesRegex(ValueError, 'writable'):
            self.stage()
        self.old.chmod(0o755)
        with self.assertRaisesRegex(ValueError, 'Invalid release'):
            module.stage(self.root, 'old', '../escape', self.archive, self.archive_hash,
                         digest(b'old proxy'), digest(b'new proxy'))
        self.assertFalse(self.new.exists())
