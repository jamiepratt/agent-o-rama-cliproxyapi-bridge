"""Opt-in deployed-cluster checks. GETs never invoke the provider."""
import json
import os
import unittest
from urllib.request import urlopen


@unittest.skipUnless(os.environ.get('BRIDGE_CHECK_RUNNING') == '1',
                     'requires a deployed stack; set BRIDGE_CHECK_RUNNING=1')
class RunningStackTests(unittest.TestCase):
    def test_module_is_readable_and_both_uis_serve(self):
        for port in (8888, 1974):
            with urlopen(f'http://127.0.0.1:{port}/', timeout=20) as response:
                self.assertEqual(200, response.status)
        with urlopen('http://127.0.0.1:18318/ready', timeout=20) as response:
            readiness = json.load(response)
        self.assertIn('verified', readiness)
        # Reading admission metrics requires the real deployed module to be alive.
        with urlopen('http://127.0.0.1:18318/metrics', timeout=20) as response:
            metrics = response.read().decode()
        self.assertIn('bridge_active_calls ', metrics)
        self.assertIn('bridge_queue_depth ', metrics)


if __name__ == '__main__':
    unittest.main()
