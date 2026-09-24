"""Exercise fixture over real loopback HTTP with a private temporary directory."""
import http.client
import pathlib
import tempfile
import threading
import unittest
from load_fixture import make_server, private_root


class FixtureTest(unittest.TestCase):
    def test_private_root_and_authenticated_stream(self):
        with tempfile.TemporaryDirectory() as directory:
            root = private_root(directory)
            (root / 'release').touch()
            with make_server(root, 'fixture-test', 0) as server:
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                try:
                    client = http.client.HTTPConnection(*server.server_address, timeout=3)
                    client.request('POST', '/v1/chat/completions', '{}')
                    self.assertEqual(403, client.getresponse().status)
                    client.close()
                    client = http.client.HTTPConnection(*server.server_address, timeout=3)
                    client.request('POST', '/v1/chat/completions', '{}', {'Authorization': 'Bearer fixture-test'})
                    response = client.getresponse()
                    self.assertEqual(200, response.status)
                    body = response.read()
                    self.assertIn(b'"content": "OK"', body)
                    self.assertIn(b'"total_tokens": 2', body)
                    self.assertTrue(body.endswith(b'data: [DONE]\n\n'))
                    client.close()
                finally:
                    server.shutdown()
                    thread.join()
            pathlib.Path(directory).chmod(0o755)
            with self.assertRaises(ValueError):
                private_root(directory)


if __name__ == '__main__':
    unittest.main()
