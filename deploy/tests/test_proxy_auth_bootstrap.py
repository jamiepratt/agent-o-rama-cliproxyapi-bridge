import importlib.util
import json
from pathlib import Path
import socket
import sys
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('auth_bootstrap', Path(__file__).parents[1] / 'proxy_auth_bootstrap.py')
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class AuthBootstrapTests(unittest.TestCase):
    def test_requires_loopback_only_namespace(self):
        with self.assertRaisesRegex(ValueError, 'loopback'):
            module.require_isolation([(1, 'lo'), (2, 'eth0')], 'linux', [])
        module.require_isolation([(1, 'lo')], 'linux', [])
        with self.assertRaisesRegex(ValueError, 'Linux'):
            module.require_isolation([(1, 'lo')], 'darwin', [])
        with self.assertRaisesRegex(ValueError, 'route'):
            module.require_isolation([(1, 'lo')], 'linux', [{'dev': 'lo', 'dst': 'default'}])
        module.require_isolation([(1, 'lo')], 'linux', [
            {'dev': 'lo', 'dst': '127.0.0.0/8', 'type': 'local'},
            {'dev': 'lo', 'dst': '::1', 'type': 'local'}])

    def test_config_relocates_auth_and_retains_private_key_without_logging(self):
        text = 'host: "127.0.0.1"\nport: 18317\nauth-dir: "/var/lib/bridge-proxy/auth"\napi-keys: ["private-test-key"]\nlogging-to-file: false\nrequest-log: false\n'
        config, key = module.isolated_config(text, Path('/private-copy/auth'), 18318)
        self.assertEqual(key, 'private-test-key')
        self.assertIn('auth-dir: "/private-copy/auth"', config)
        self.assertNotIn('/var/lib/bridge-proxy', config)
        self.assertIn('port: 18318', config)
        with self.assertRaisesRegex(ValueError, 'Unsupported'):
            module.isolated_config(text + 'token-store: /live\n', Path('/copy'), 1)

    def test_manifest_rejects_symlinks_and_detects_changed_credential(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            token = root / 'credential.json'
            token.write_text('{"fixture": true}')
            before = module.manifest(root)
            token.write_text('{"fixture": false}')
            self.assertNotEqual(before, module.manifest(root))
            (root / 'link').symlink_to(token)
            with self.assertRaisesRegex(ValueError, 'symlink'):
                module.manifest(root)

    def test_inventory_ignores_order_but_rejects_changed_models(self):
        first = module.inventory({'data': [{'id': 'b'}, {'id': 'a'}]})
        same = module.inventory({'data': [{'id': 'a'}, {'id': 'b'}]})
        self.assertEqual(first, same)
        with self.assertRaisesRegex(ValueError, 'inventories'):
            module.summarize([first, same, module.inventory({'data': [{'id': 'a'}]})])
        result = module.summarize([first, same, first])
        self.assertEqual(result['model_count'], 2)
        self.assertTrue(result['equal'])
        self.assertNotIn('private-test-key', json.dumps(result))

    def test_malformed_inventory_is_not_retryable(self):
        for response in [{}, {'data': None}, {'data': {}}, {'data': [1]},
                         {'data': [{'id': 1}]}, {'data': [{'id': ''}]}]:
            with self.subTest(response=response):
                with self.assertRaises(ValueError) as error:
                    module.inventory(response)
                self.assertNotIsInstance(error.exception, module.EmptyInventory)

    def test_boot_uses_bearer_and_reaps_process_after_inventory(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with socket.socket() as listener:
                listener.bind(('127.0.0.1', 0))
                port = listener.getsockname()[1]
            binary = root / 'fixture-proxy'
            binary.write_text('#!' + sys.executable + '\n' + f'''
from http.server import BaseHTTPRequestHandler, HTTPServer
class Handler(BaseHTTPRequestHandler):
    calls = 0
    def log_message(self, *args): pass
    def do_GET(self):
        if self.headers.get('Authorization') != 'Bearer fixture-key':
            self.send_response(403)
            self.end_headers()
            return
        self.send_response(200)
        self.end_headers()
        Handler.calls += 1
        if Handler.calls == 1:
            self.wfile.write(b'{{"data":[]}}')
        else:
            self.wfile.write(b'{{"data":[{{"id":"fixture-model"}}]}}')
HTTPServer(('127.0.0.1', {port}), Handler).serve_forever()
''')
            binary.chmod(0o700)
            config = root / 'config.yaml'
            config.write_text('fixture')
            result = module.boot(binary, config, 'fixture-key', root, port)
            self.assertEqual(result[0], 1)
            with socket.socket() as listener:
                self.assertNotEqual(listener.connect_ex(('127.0.0.1', port)), 0)


if __name__ == '__main__':
    unittest.main()
