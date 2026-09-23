import http.server
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

import cliproxy


class ProbeTests(unittest.TestCase):
    def test_stream_reassembly_and_evidence_excludes_content(self):
        secret = 'SENTINEL_DO_NOT_RECORD'
        chunks = [
            {'choices': [{'delta': {'content': secret}}], 'id': secret},
            {'choices': [{'delta': {'tool_calls': [{'index': 0, 'id': secret,
             'function': {'name': 'spike_', 'arguments': '{"value":'}}]}}]},
            {'choices': [{'delta': {'tool_calls': [{'index': 0,
             'function': {'name': 'echo', 'arguments': '"ping"}'}}]}}]},
            {'choices': [], 'usage': {'prompt_tokens': 5, 'completion_tokens': 2,
                                     'total_tokens': 7, 'untrusted': secret}},
        ]
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                self.rfile.read(int(self.headers['Content-Length']))
                self.send_response(200)
                self.send_header('Content-Type', 'text/event-stream')
                self.end_headers()
                for item in chunks:
                    self.wfile.write(('data: ' + json.dumps(item) + '\n\n').encode())
                self.wfile.write(b'data: [DONE]\n\n')
            def log_message(self, *args):
                pass
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'bearer').write_text(secret)
            server = http.server.HTTPServer(('127.0.0.1', 0), Handler)
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            try:
                with patch.object(cliproxy, 'PORT', server.server_port), patch.object(cliproxy, 'STATE', root):
                    result = cliproxy.stream({})
                self.assertTrue(result['done'])
                self.assertTrue(result['tool_valid'])
                self.assertEqual(result['usage']['total_tokens'], 7)
                self.assertNotIn(secret, json.dumps(result))
            finally:
                server.shutdown()
                server.server_close()
                worker.join()

    def test_timeout_closes_connection_before_propagating(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'bearer').write_text('test')
            with patch.object(cliproxy, 'STATE', root), patch('cliproxy.http.client.HTTPConnection') as factory:
                factory.return_value.getresponse.side_effect = TimeoutError
                with self.assertRaises(TimeoutError):
                    cliproxy.request('/v1/models')
                factory.return_value.close.assert_called_once()

    def test_auth_rejects_multiple_accounts_and_public_permissions(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'auth').mkdir()
            auth = root / 'auth' / 'account.json'
            auth.write_text('{"type":"codex"}')
            auth.chmod(0o644)
            with patch.object(cliproxy, 'STATE', root):
                with self.assertRaises(AssertionError):
                    cliproxy.auth_check()
                auth.chmod(0o600)
                self.assertEqual(cliproxy.auth_check(), 1)
                (root / 'auth' / 'second.json').write_text('{}')
                with self.assertRaises(AssertionError):
                    cliproxy.auth_check()


if __name__ == '__main__':
    unittest.main()
