#!/usr/bin/env python3
"""Offline 7.3.15 -> 7.3.16 -> 7.3.15 fixture drill. Run in a fresh network namespace.

Example (root): unshare --net --pid --fork --mount-proc sh -c
  'ip link set lo up; exec python3 proxy_compatibility.py --baseline ...
   --candidate ... --target /var/tmp/bridge-proxy-drill-UNIQUE'
The caller must verify the namespace differs from the host before invoking this.
This proves the compatible-provider streaming path, not OAuth or Rama schemas.
"""
import argparse
import hashlib
import http.server
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.request


def require_isolation():
    message = 'requires an isolated loopback-only Linux network namespace with no routes'
    if sys.platform != 'linux' or os.geteuid() != 0:
        raise RuntimeError(message)
    if {name for _, name in socket.if_nameindex()} != {'lo'}:
        raise RuntimeError(message)
    for family in ('-4', '-6'):
        result = subprocess.run(['ip', '-j', family, 'route', 'show', 'table', 'all'],
                                check=True, text=True, capture_output=True)
        # Kernel local loopback routes are required for the fixture server.
        if any(route.get('dev') != 'lo' or route.get('type') not in ('local', 'broadcast')
               for route in json.loads(result.stdout)):
            raise RuntimeError(message)


class FixtureHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', '0'))))
        if (self.path != '/v1/chat/completions' or body.get('model') != 'fixture-model'
                or body.get('stream') is not True
                or self.headers.get('Authorization') != 'Bearer fixture-key'):
            self.send_error(400)
            return
        self.server.dispatches += 1
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.end_headers()
        for text in ('compatibility ', 'ok'):
            self.wfile.write(('data: ' + json.dumps({'id': 'fixture', 'object': 'chat.completion.chunk',
                'created': 1, 'model': 'fixture-model',
                'choices': [{'index': 0, 'delta': {'content': text}, 'finish_reason': None}]}) + '\n\n').encode())
            self.wfile.flush()
        event = {'id': 'fixture', 'object': 'chat.completion.chunk', 'created': 1,
                 'model': 'fixture-model', 'choices': [{'index': 0, 'delta': {}, 'finish_reason': 'stop'}],
                 'usage': {'prompt_tokens': 7, 'completion_tokens': 2, 'total_tokens': 9}}
        self.wfile.write(('data: ' + json.dumps(event) + '\n\ndata: [DONE]\n\n').encode())
        self.wfile.flush()


def fixture_server():
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), FixtureHandler)
    server.dispatches = 0
    return server


def read_stream(response):
    pieces, usage, done = [], None, False
    for line in response:
        if not line.startswith(b'data: '):
            continue
        payload = line[6:].strip()
        if payload == b'[DONE]':
            done = True
            break
        event = json.loads(payload)
        if event.get('usage'):
            usage = event['usage']
        for choice in event.get('choices', []):
            text = choice.get('delta', {}).get('content')
            if text:
                pieces.append(text)
    if not done:
        raise RuntimeError('stream missing DONE')
    return {'text': ''.join(pieces), 'content_chunks': len(pieces), 'usage': usage}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', required=True, type=Path)
    parser.add_argument('--candidate', required=True, type=Path)
    parser.add_argument('--target', required=True, type=Path)
    args = parser.parse_args()
    require_isolation()
    os.umask(0o077)
    target = args.target.absolute()
    if target.parent != Path('/var/tmp') or not target.name.startswith('bridge-proxy-drill-'):
        raise RuntimeError('target must be new /var/tmp/bridge-proxy-drill-* directory')
    binaries = [args.baseline.resolve(strict=True), args.candidate.resolve(strict=True)]
    digests = [hashlib.sha256(path.read_bytes()).hexdigest() for path in binaries]
    if digests[0] == digests[1]:
        raise RuntimeError('candidate must be a distinct artifact')
    target.mkdir(mode=0o700)  # Refuse any existing directory or symlink.
    (target / 'auth').mkdir(mode=0o700)
    server = fixture_server()
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    with socket.socket() as port_socket:
        port_socket.bind(('127.0.0.1', 0))
        proxy_port = port_socket.getsockname()[1]
    config = target / 'config.yaml'
    config.write_text(json.dumps({
        'host': '127.0.0.1', 'port': proxy_port, 'auth-dir': str(target / 'auth'),
        'api-keys': ['drill-key'], 'logging-to-file': False, 'request-log': False,
        'remote-management': {'disable-control-panel': True},
        'openai-compatibility': [{'name': 'drill',
            'base-url': f'http://127.0.0.1:{server.server_port}/v1',
            'api-key-entries': [{'api-key': 'fixture-key'}],
            'models': [{'name': 'fixture-model', 'alias': 'drill-model'}]}]}))
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def request(path, body=None):
        return opener.open(urllib.request.Request(
            f'http://127.0.0.1:{proxy_port}/v1/{path}',
            None if body is None else json.dumps(body).encode(),
            {'Authorization': 'Bearer drill-key', 'Content-Type': 'application/json'}), timeout=10)
    records = []
    try:
        for stage, index, version in [('baseline', 0, '7.3.15'), ('upgrade', 1, '7.3.16'),
                                      ('rollback', 0, '7.3.15')]:
            for restart in range(2):
                log_path = target / f'{stage}-{restart}.log'
                with log_path.open('wb') as log:
                    process = subprocess.Popen([str(binaries[index]), '-config', str(config)],
                        cwd=target, stdout=log, stderr=subprocess.STDOUT, start_new_session=True,
                        env={'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'HOME': str(target)})
                    try:
                        deadline = time.monotonic() + 30
                        while True:
                            if process.poll() is not None:
                                raise RuntimeError(f'{stage} exited before readiness; inspect private log')
                            try:
                                with request('models') as response:
                                    models = sorted(item['id'] for item in json.load(response)['data'])
                                break
                            except (OSError, ValueError):
                                if time.monotonic() >= deadline:
                                    raise RuntimeError(f'{stage} readiness timeout')
                                time.sleep(0.2)
                        if 'drill-model' not in models:
                            raise RuntimeError(f'{stage} missing fixture model')
                        before = server.dispatches
                        with request('chat/completions', {'model': 'drill-model', 'stream': True,
                            'stream_options': {'include_usage': True},
                            'messages': [{'role': 'user', 'content': 'fixture'}]}) as response:
                            result = read_stream(response)
                        expected = {'text': 'compatibility ok', 'content_chunks': 2,
                            'usage': {'prompt_tokens': 7, 'completion_tokens': 2, 'total_tokens': 9}}
                        if result != expected or server.dispatches - before != 1:
                            raise RuntimeError(f'{stage} streaming or dispatch mismatch')
                        if f'Version: {version}' not in log_path.read_text(errors='replace'):
                            raise RuntimeError(f'{stage} unexpected binary version')
                        records.append({'stage': stage, 'restart': restart, 'version': version,
                            'sha256': digests[index], 'models': models, 'dispatches': 1,
                            'content_chunks': result['content_chunks'], 'usage': result['usage']})
                    finally:
                        if process.poll() is None:
                            os.killpg(process.pid, signal.SIGTERM)
                            try:
                                process.wait(timeout=10)
                            except subprocess.TimeoutExpired:
                                os.killpg(process.pid, signal.SIGKILL)
                                process.wait(timeout=5)
        evidence = {'status': 'passed', 'network': 'loopback-only', 'dispatches': server.dispatches,
                    'auth_files': len(list((target / 'auth').rglob('*'))), 'runs': records,
                    'scope': 'fixture provider streaming only; no OAuth or Rama state validation'}
        (target / 'evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
        print(json.dumps(evidence))
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=5)

if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print(f'proxy compatibility drill failed: {exc}', file=sys.stderr)
        sys.exit(1)
