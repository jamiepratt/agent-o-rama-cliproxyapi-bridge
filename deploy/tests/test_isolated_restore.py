"""Portable checks of the restore drill's public refusal and command-plan interface."""
import pathlib
import subprocess
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / 'isolated_restore.sh'


class IsolatedRestoreTest(unittest.TestCase):
    def run_script(self, *args):
        return subprocess.run(['bash', str(SCRIPT), *args], text=True, capture_output=True)

    def test_refuses_without_explicit_approval_before_host_commands(self):
        result = self.run_script('/var/tmp/bridge-restore-test', '--', 'true')
        self.assertEqual(result.returncode, 64)
        self.assertIn('approval', result.stderr)

    def test_plan_requires_a_dedicated_restore_target_and_command(self):
        for target in ['/var/lib/bridge-rama', '/var/tmp/bridge-restore-x/../live', '/var/tmp/bridge-restore-']:
            with self.subTest(target=target):
                result = self.run_script('--approved-isolated-restore', '--plan', target, '--', 'true')
                self.assertEqual(result.returncode, 64)
        result = self.run_script('--approved-isolated-restore', '--plan', '/var/tmp/bridge-restore-test', '--')
        self.assertEqual(result.returncode, 64)

    def test_plan_describes_isolation_without_running_supplied_command(self):
        result = self.run_script('--approved-isolated-restore', '--plan', '/var/tmp/bridge-restore-test', '--', '/definitely/not/a/command')
        self.assertEqual(result.returncode, 0, result.stderr)
        for boundary in ['--net', '--mount', '--pid', '--kill-child', 'loopback only', 'bridge-rama', '900']:
            self.assertIn(boundary, result.stdout)
        self.assertIn('/var/lib/bridge-zk', result.stdout)
        self.assertIn('/var/lib/bridge-rama', result.stdout)


if __name__ == '__main__':
    unittest.main()
