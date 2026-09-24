"""Run with root/CAP_NET_ADMIN on Linux; checks never apply firewall rules."""
import os
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


def ruleset_structure(raw):
    """Ignore only packet/byte totals in nft counter statements and objects."""
    def normalize(value, counter=False):
        if isinstance(value, list):
            return [normalize(item) for item in value]
        if isinstance(value, dict):
            return {key: normalize(item, counter=(key == 'counter'))
                    for key, item in value.items()
                    if not (counter and key in ('packets', 'bytes'))}
        return value
    return normalize(json.loads(raw))


class FirewallComparisonTests(unittest.TestCase):
    def test_live_counter_values_do_not_change_ruleset_structure(self):
        before = {"nftables": [{"rule": {"family": "ip", "table": "filter",
                  "expr": [{"counter": {"packets": 87978, "bytes": 100000}},
                           {"accept": None}]}},
                  {"counter": {"family": "inet", "table": "filter", "name": "traffic",
                               "packets": 12, "bytes": 100}}]}
        after = json.loads(json.dumps(before))
        after['nftables'][0]['rule']['expr'][0]['counter'].update(packets=87979, bytes=100060)
        after['nftables'][1]['counter'].update(packets=13, bytes=160)
        self.assertEqual(ruleset_structure(json.dumps(before)),
                         ruleset_structure(json.dumps(after)))


    def test_policy_changes_remain_visible(self):
        before = {"nftables": [{"table": {"family": "inet", "name": "bridge_private"}},
                  {"rule": {"table": "bridge_private", "expr": [
                      {"counter": {"packets": 1, "bytes": 60}}, {"accept": None}]}},
                  {"counter": {"name": "traffic", "packets": 1, "bytes": 60}},
                  {"rule": {"expr": [{"quota": {"bytes": 1000}}]}}]}
        for change in ('table', 'verdict', 'counter-name', 'quota', 'counter-removal', 'order'):
            with self.subTest(change=change):
                after = json.loads(json.dumps(before))
                objects = after['nftables']
                if change == 'table':
                    objects[0]['table']['name'] = 'other'
                elif change == 'verdict':
                    objects[1]['rule']['expr'][1] = {'drop': None}
                elif change == 'counter-name':
                    objects[2]['counter']['name'] = 'other'
                elif change == 'quota':
                    objects[3]['rule']['expr'][0]['quota']['bytes'] = 2000
                elif change == 'counter-removal':
                    objects[1]['rule']['expr'].pop(0)
                else:
                    objects[1]['rule']['expr'].reverse()
                self.assertNotEqual(ruleset_structure(json.dumps(before)),
                                    ruleset_structure(json.dumps(after)))


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
        self.assertEqual(ruleset_structure(before), ruleset_structure(after),
                         'check-only validation changed rules')
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
