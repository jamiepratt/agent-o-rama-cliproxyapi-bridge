import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import tarfile

SPEC = importlib.util.spec_from_file_location('fresh_state', Path(__file__).parents[1] / 'fresh_state.py')
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class FreshStateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        for name in ['var/lib/bridge-rama/data', 'var/lib/bridge-rama/locks',
                     'var/lib/bridge-rama/licenses', 'var/lib/bridge-zk/data',
                     'var/lib/bridge-zk/txn', 'var/lib/bridge-proxy/auth',
                     'etc/bridge', 'opt/bridge', 'etc/systemd/system', 'var/lib/preserved']:
            (self.root / name).mkdir(parents=True, exist_ok=True)
        for name in ['var/lib/bridge-rama/data/history', 'var/lib/bridge-rama/locks/permanent',
                     'var/lib/bridge-rama/licenses/license', 'var/lib/bridge-rama/bearer',
                     'var/lib/bridge-zk/txn/history', 'var/lib/bridge-proxy/auth/oauth',
                     'etc/bridge/rama.yaml', 'opt/bridge/runtime']:
            (self.root / name).write_text(name)
        for unit in module.UNITS + ('bridge-firewall',):
            (self.root / f'etc/systemd/system/{unit}.service').write_text(unit)
        self.destination = self.root / 'var/lib/preserved/run-1'

    def run_fresh(self, check=lambda: None):
        return module.preserve_and_initialize(self.root, self.destination, check)

    def test_preserves_history_and_credentials_but_initializes_independent_state(self):
        old_lock = (self.root / 'var/lib/bridge-rama/locks/permanent').stat().st_ino
        self.run_fresh()
        self.assertEqual(old_lock, (self.destination / 'bridge-rama/locks/permanent').stat().st_ino)
        self.assertFalse((self.root / 'var/lib/bridge-rama/data/history').exists())
        self.assertFalse((self.root / 'var/lib/bridge-rama/locks/permanent').exists())
        self.assertFalse((self.root / 'var/lib/bridge-zk/txn/history').exists())
        self.assertEqual((self.root / 'var/lib/bridge-rama/bearer').read_text(), 'var/lib/bridge-rama/bearer')
        self.assertTrue((self.root / 'var/lib/bridge-rama/licenses/license').exists())
        self.assertTrue((self.root / 'var/lib/bridge-proxy/auth/oauth').exists())
        self.assertTrue((self.destination / 'state.tar.sha256').is_file())
        self.assertEqual(self.destination.stat().st_mode & 0o777, 0o700)
        self.assertEqual((self.destination / 'state.tar').stat().st_mode & 0o777, 0o600)


    def test_refuses_writable_preservation_parent_before_archive(self):
        self.destination.parent.chmod(0o777)
        with self.assertRaises(ValueError):
            self.run_fresh()
        self.assertFalse(self.destination.exists())

    def test_active_workers_leave_live_roots_untouched(self):
        def active():
            raise RuntimeError('workers remain')
        with self.assertRaises(RuntimeError):
            self.run_fresh(active)
        self.assertFalse(self.destination.exists())
        self.assertTrue((self.root / 'var/lib/bridge-rama/data/history').exists())

    def test_late_worker_detection_preserves_archive_without_moving_state(self):
        with patch.object(module, 'stopped_boundary') as check:
            check.side_effect = [None, RuntimeError('worker appeared')]
            with self.assertRaises(RuntimeError):
                self.run_fresh(check)
        self.assertTrue((self.destination / 'state.tar').exists())
        self.assertTrue((self.root / 'var/lib/bridge-rama/data/history').exists())

    def test_refuses_existing_destination_missing_source_and_symlink_root(self):
        self.destination.mkdir()
        with self.assertRaises(ValueError):
            self.run_fresh()
        self.destination.rmdir()
        bearer = self.root / 'var/lib/bridge-rama/bearer'
        bearer.unlink()
        with self.assertRaises(ValueError):
            self.run_fresh()
        bearer.symlink_to(self.root / 'etc/bridge/rama.yaml')
        with self.assertRaises(ValueError):
            self.run_fresh()
        self.assertFalse(self.destination.exists())

    def test_low_disk_space_refuses_before_mutation(self):
        with patch.object(module.shutil, 'disk_usage', return_value=type('Usage', (), {'free': 0})()):
            with self.assertRaises(ValueError):
                self.run_fresh()
        self.assertFalse(self.destination.exists())

    def test_archive_verification_failure_never_moves_live_state(self):
        with patch.object(module, 'verify_archive', side_effect=RuntimeError('corrupt archive')):
            with self.assertRaises(RuntimeError):
                self.run_fresh()
        self.assertTrue((self.root / 'var/lib/bridge-rama/data/history').exists())
        self.assertFalse((self.destination / 'bridge-rama').exists())

    def test_archive_contains_complete_sources_and_runtime_symlinks(self):
        (self.root / 'opt/bridge/current').symlink_to('runtime')
        (self.root / 'var/lib/bridge-rama/licenses/license').unlink()
        checksum = self.run_fresh()
        with (self.destination / 'state.tar').open('rb') as stream:
            self.assertEqual(checksum, module.digest(stream))
        with tarfile.open(self.destination / 'state.tar') as saved:
            for name in ['var/lib/bridge-rama/locks/permanent', 'var/lib/bridge-zk/txn/history',
                         'var/lib/bridge-proxy/auth/oauth', 'etc/bridge/rama.yaml',
                         'etc/systemd/system/bridge-supervisor.service', 'opt/bridge/runtime']:
                self.assertTrue(saved.getmember(name).isfile())
            self.assertEqual(saved.getmember('opt/bridge/current').linkname, 'runtime')
        self.assertEqual(list((self.root / 'var/lib/bridge-rama/licenses').iterdir()), [])


    def test_unit_overrides_are_archived(self):
        override = self.root / 'etc/systemd/system/bridge-supervisor.service.d'
        override.mkdir()
        (override / 'limits.conf').write_text('[Service]\nMemoryMax=2G\n')
        self.run_fresh()
        with tarfile.open(self.destination / 'state.tar') as saved:
            self.assertTrue(saved.getmember('etc/systemd/system/bridge-supervisor.service.d/limits.conf').isfile())


    def test_interrupted_second_rename_keeps_both_histories_and_does_not_initialize(self):
        original = Path.rename
        def rename(path, target):
            if path.name == 'bridge-zk':
                raise OSError('simulated interrupted rename')
            return original(path, target)
        with patch.object(Path, 'rename', rename):
            with self.assertRaises(OSError):
                self.run_fresh()
        self.assertTrue((self.destination / 'bridge-rama/data/history').exists())
        self.assertTrue((self.root / 'var/lib/bridge-zk/txn/history').exists())
        self.assertFalse((self.root / 'var/lib/bridge-rama').exists())
        self.assertFalse((self.destination / 'INITIALIZED').exists())

    def test_boundary_requires_installed_maintenance_inhibitors(self):
        with patch.object(module, 'MAINTENANCE_ROOT', self.root):
            with self.assertRaises(ValueError):
                module.stopped_boundary()


    def test_boundary_accepts_loaded_inhibitors_and_refuses_worker_or_stale_reload(self):
        from subprocess import CompletedProcess
        marker = self.root / 'etc/bridge/fresh-state-maintenance'
        marker.touch(mode=0o600)
        outputs = []
        for unit in module.UNITS:
            relative = f'etc/systemd/system/{unit}.service.d/90-fresh-state.conf'
            inhibitor = self.root / relative
            inhibitor.parent.mkdir()
            inhibitor.write_text(module.INHIBITOR)
            outputs.append(CompletedProcess([], 0, f'ActiveState=inactive\nNeedDaemonReload=no\nDropInPaths=/{relative}'))
        user = type('User', (), {'pw_uid': 123})()
        with patch.object(module, 'MAINTENANCE_ROOT', self.root), patch.object(module.pwd, 'getpwnam', return_value=user):
            with patch.object(module.subprocess, 'run', side_effect=outputs + [CompletedProcess([], 1)] * 6):
                module.stopped_boundary()
            with patch.object(module.subprocess, 'run', side_effect=outputs + [CompletedProcess([], 0)]):
                with self.assertRaises(RuntimeError):
                    module.stopped_boundary()
            stale = CompletedProcess([], 0, outputs[0].stdout.replace('NeedDaemonReload=no', 'NeedDaemonReload=yes'))
            with patch.object(module.subprocess, 'run', return_value=stale):
                with self.assertRaises(RuntimeError):
                    module.stopped_boundary()


if __name__ == '__main__':
    unittest.main()
