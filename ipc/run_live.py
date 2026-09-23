#!/usr/bin/env python3
"""Ephemeral counting forwarder for the IPC experiment, never an adapter service."""
import http.client
import http.server
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'spike'))
import cliproxy


class Forwarder(http.server.ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self):
        super().__init__(('127.0.0.1', 0), Handler)
        self.calls = 0
        self.lock = threading.Lock()


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        if self.path == '/v1/models':
            self.forward('GET')
            return
        if self.path != '/count':
            self.send_error(404)
            return
        with self.server.lock:
            data = json.dumps({'requests': self.server.calls}).encode()
        self.send_response(200)
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        if self.path != '/v1/chat/completions':
            self.send_error(404)
            return
        self.forward('POST')

    def forward(self, method):
        payload = self.rfile.read(int(self.headers['Content-Length'])) if method == 'POST' else None
        if method == 'POST':
            with self.server.lock:
                self.server.calls += 1
        conn = http.client.HTTPConnection('127.0.0.1', cliproxy.PORT, timeout=120)
        try:
            conn.request(method, self.path, payload, {
                'Content-Type': 'application/json',
                'Authorization': 'Bearer ' + (cliproxy.STATE / 'bearer').read_text().strip()})
            response = conn.getresponse()
            self.send_response(response.status)
            self.send_header('Content-Type', response.getheader('Content-Type', 'application/json'))
            self.end_headers()
            while line := response.readline():
                self.wfile.write(line)
                self.wfile.flush()
        except (OSError, http.client.HTTPException):
            # Deliberate client timeout/disconnect; never log body or headers.
            pass
        finally:
            conn.close()



def checked_evidence(value):
    """Reject unexpected fields/types before public evidence is persisted."""
    versions = {'agent_o_rama': '0.10.0', 'rama': '1.9.0', 'langchain4j': '1.18.1-beta28'}
    stream = {'complete': bool, 'chunks': int, 'content_chars': int, 'usage': dict,
              'trace_model_calls': int, 'trace_usage_matches': bool, 'stream_resets': int,
              'tool_requests': int, 'proxy_requests': int}
    failure = {'error_observed': bool, 'trace_failure': bool, 'expected_failure_type': bool,
               'proxy_requests': int}
    cancellation = {'subscription_closed_before_completion': bool, 'agent_completed': bool,
                    'callbacks_after_close': int, 'usage': dict, 'upstream_termination_proven': bool,
                    'billing_cessation_proven': bool, 'proxy_requests': int}
    observability = {'available': bool, 'configured': bool, 'verified': bool, 'timestamped': bool,
                     'scrape_success': bool, 'requests': int, 'dispatches': int, 'replays': int,
                     'retries': int, 'upstream_errors': int, 'probe_requests': int}
    schemas = {'observability': observability, 'stream': stream, 'tool': stream, 'retry': stream, 'error': failure,
               'timeout': failure, 'cancellation': cancellation, 'recovery': stream}
    if type(value) is not dict or set(value) != set(versions) | set(schemas):
        raise ValueError('Unexpected evidence fields')
    for name, expected in versions.items():
        if value[name] != expected:
            raise ValueError('Unexpected dependency pin')
    for name, schema in schemas.items():
        item = value[name]
        if type(item) is not dict or set(item) != set(schema):
            raise ValueError('Unexpected scenario fields')
        for key, kind in schema.items():
            if type(item[key]) is not kind:
                raise ValueError('Unexpected evidence type')
            if kind is int and item[key] < 0:
                raise ValueError('Negative evidence count')
        if 'usage' in item:
            usage = item['usage']
            if set(usage) != {'input', 'output', 'total'} or any(type(n) is not int or n < 0 for n in usage.values()):
                raise ValueError('Unexpected usage fields')
    return value

def main():
    cliproxy.auth_check()
    for name in ('bearer', 'config.yaml'):
        assert (cliproxy.STATE / name).stat().st_mode & 0o077 == 0
    assert cliproxy.STATE.stat().st_mode & 0o077 == 0
    with socket.socket() as guard:
        guard.bind(('127.0.0.1', cliproxy.PORT))
    process = subprocess.Popen(cliproxy.command(), cwd=cliproxy.STATE,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    forwarder = None
    try:
        for _ in range(100):
            assert process.poll() is None, 'Daemon exited'
            try:
                status, _ = cliproxy.json_request('/v1/models', authenticated=False)
                break
            except (OSError, http.client.HTTPException):
                time.sleep(.1)
        else:
            raise RuntimeError('Daemon startup timed out')
        assert status == 401
        listeners = subprocess.check_output(['lsof', '-nP', '-a', '-p', str(process.pid),
                                              '-iTCP', '-sTCP:LISTEN'], text=True)
        assert [line.split()[-2] for line in listeners.splitlines()[1:]] == ['127.0.0.1:18317']
        forwarder = Forwarder()
        worker = threading.Thread(target=forwarder.serve_forever, daemon=True)
        worker.start()
        env = {**os.environ, 'IPC_BASE_URL': f'http://127.0.0.1:{forwarder.server_port}',
               'IPC_MODEL': 'gpt-6-luna'}
        child = subprocess.run(['clojure', '-M:live'], cwd=ROOT, env=env,
                               stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, timeout=360)
        if child.returncode:
            # Clojure emits a fixed sanitized error record, never exception text.
            failure = json.loads(child.stdout) if child.stdout.strip().startswith('{') else {}
            stage = failure.get('failed_stage')
            allowed = {'startup', 'stream', 'tool', 'retry', 'error', 'timeout', 'cancellation', 'recovery',
                       'tool-result', 'tool-arguments', 'callbacks-after-close', 'nested-chunks', 'nested-order', 'numeric-usage', 'model-trace', 'trace-usage',
                       'agent-error', 'error-trace', 'failure-type', 'cancellation-first-chunk',
                       'subscription-closed-before-completion', 'completion-after-unsubscribe',
                       'observability', 'readiness-verified', 'metrics-observed', 'tool-roundtrip-count', 'tool-request-count', 'rama-retry-count', 'rama-stream-reset'}
            print(json.dumps({'failed_stage': stage if stage in allowed else 'unclassified'}))
            raise SystemExit(1)
        evidence = checked_evidence(json.loads(child.stdout))
        evidence.update({'recorded_utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                         'cliproxy_version': cliproxy.VERSION,
                         'model': 'gpt-6-luna', 'counter_boundary': 'Chat completion HTTP requests forwarded to CLIProxyAPI'})
        (ROOT / 'ipc/adapter-evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
        print(json.dumps(evidence, indent=2))
    finally:
        if forwarder:
            forwarder.shutdown()
            forwarder.server_close()
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


if __name__ == '__main__':
    main()
