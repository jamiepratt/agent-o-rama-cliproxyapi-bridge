"""Run with root/CAP_NET_ADMIN on Linux; checks never apply firewall rules."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(shutil.which('nft') and os.geteuid() == 0,
                     'requires nft and root/CAP_NET_ADMIN on Linux')
class FirewallParserTests(unittest.TestCase):
    def test_ingress_policy_passes_real_kernel_validation_without_changes(self):
        before = subprocess.run(['nft', '-j', 'list', 'ruleset'],
                                capture_output=True, text=True, check=True).stdout
        result = subprocess.run(['nft', '--check', '-f',
                                 str(ROOT / 'config' / 'firewall.nft')],
                                capture_output=True, text=True)
        after = subprocess.run(['nft', '-j', 'list', 'ruleset'],
                               capture_output=True, text=True, check=True).stdout
        self.assertEqual(before, after, 'check-only validation changed rules')
        self.assertEqual(0, result.returncode, result.stderr)

    @unittest.skipIf(Path('/opt/bridge').exists() or Path('/etc/bridge').exists(),
                     'installer preflight requires an uninstalled host')
    def test_installer_refuses_existing_table_and_failed_inspection(self):
        # Only external preflight commands are replaced. Invalid artifact paths
        # ensure a guard regression cannot reach account creation or installation.
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            for name, body in {
                'tailscale': 'echo \'{"BackendState":"Running"}\'\n',
                'getent': 'exit 2\n',
            }.items():
                command = folder / name
                command.write_text('#!/bin/sh\n' + body)
                command.chmod(0o755)
            cases = [
                ('echo \'{"nftables":[{"table":{"family":"inet","name":"bridge_private"}}]}\'\n',
                 'Existing bridge firewall'),
                ('echo inspection-denied >&2\nexit 1\n', 'inspection-denied'),
                ('echo \'{"nftables":[]}\'\necho partial-inventory >&2\nexit 1\n',
                 'partial-inventory'),
                ('echo malformed-json\n', 'JSONDecodeError'),
            ]
            for body, message in cases:
                with self.subTest(message=message):
                    command = folder / 'nft'
                    command.write_text('#!/bin/sh\n' + body)
                    command.chmod(0o755)
                    result = subprocess.run(
                        ['bash', str(ROOT / 'install.sh'), '--approved-install',
                         tmp, tmp, '0' * 64], capture_output=True, text=True,
                        env={**os.environ, 'PATH': tmp + ':' + os.environ['PATH']})
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn(message, result.stderr)
                    self.assertNotIn('FileNotFoundError', result.stderr,
                                     'installer passed the firewall safety gate')


if __name__ == '__main__':
    unittest.main()
