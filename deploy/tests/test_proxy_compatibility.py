"""Public safety and fixture protocol checks for the isolated proxy drill."""
import importlib.util
import json
import pathlib
import subprocess
import sys
import threading
import unittest
import urllib.request

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / 'proxy_compatibility.py'

class CompatibilityTest(unittest.TestCase):
    def test_refuses_host_before_creating_target(self):
        result = subprocess.run([sys.executable, str(SCRIPT), '--baseline', '/missing',
                                 '--candidate', '/missing', '--target', '/var/tmp/bridge-proxy-test'],
                                text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('isolated loopback', result.stderr)

    def test_fixture_stream_reports_ordered_text_usage_and_dispatch(self):
        spec = importlib.util.spec_from_file_location('proxy_drill', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        server = module.fixture_server()
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            request = urllib.request.Request(
                f'http://127.0.0.1:{server.server_port}/v1/chat/completions',
                json.dumps({'model': 'fixture-model', 'stream': True, 'messages': []}).encode(),
                {'Content-Type': 'application/json', 'Authorization': 'Bearer fixture-key'})
            with urllib.request.urlopen(request) as response:
                result = module.read_stream(response)
            self.assertEqual(result, {'text': 'compatibility ok', 'content_chunks': 2,
                                    'usage': {'prompt_tokens': 7, 'completion_tokens': 2, 'total_tokens': 9}})
            self.assertEqual(server.dispatches, 1)
        finally:
            server.shutdown()
            server.server_close()
            worker.join()

if __name__ == '__main__':
    unittest.main()
