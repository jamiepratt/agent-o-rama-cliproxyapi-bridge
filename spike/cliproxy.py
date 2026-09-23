#!/usr/bin/env python3
"""Secret-safe, macOS arm64 CLIProxyAPI compatibility spike (stdlib only)."""
import argparse
import hashlib
import http.client
import json
import os
import platform
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tarfile
import time
import urllib.request

VERSION = '7.3.15'
ASSET = f'CLIProxyAPI_{VERSION}_darwin_aarch64.tar.gz'
SHA256 = 'c1e49c148a94c476dc43a6a0eed28bca34239d5153ebb7792048d8c18f3b92f0'
BINARY_SHA256 = '7212d39890dac46fac10d75f8029d8c377fdcc75798d5dcecefffc90b987d0a9'
STATE = Path.home() / '.local/share/cliproxyapi-spike'
PORT = 18317
os.umask(0o077)


def setup():
    assert (platform.system(), platform.machine()) == ('Darwin', 'arm64'), 'This pin is macOS arm64 only'
    STATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    STATE.chmod(0o700)
    for name in ('auth', 'downloads'):
        (STATE / name).mkdir(exist_ok=True, mode=0o700)
        (STATE / name).chmod(0o700)
    archive = STATE / 'downloads' / ASSET
    if not archive.exists():
        urllib.request.urlretrieve(f'https://github.com/router-for-me/CLIProxyAPI/releases/download/v{VERSION}/{ASSET}', archive)
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == SHA256, 'Release checksum mismatch'
    with tarfile.open(archive) as tar:
        data = tar.extractfile('cli-proxy-api').read()
    (STATE / 'cli-proxy-api').write_bytes(data)
    (STATE / 'cli-proxy-api').chmod(0o700)
    if not (STATE / 'bearer').exists():
        (STATE / 'bearer').write_text(secrets.token_urlsafe(32))
    key = (STATE / 'bearer').read_text().strip()
    (STATE / 'bearer').chmod(0o600)
    config = f'''host: "127.0.0.1"
port: {PORT}
auth-dir: {json.dumps(str(STATE / 'auth'))}
api-keys: [{json.dumps(key)}]
remote-management:
  allow-remote: false
  secret-key: ""
  disable-control-panel: true
  disable-auto-update-panel: true
debug: false
logging-to-file: false
request-log: false
commercial-mode: true
usage-statistics-enabled: false
request-retry: 0
max-retry-credentials: 1
max-retry-interval: 0
pprof:
  enable: false
discovery:
  enabled: false
plugins:
  enabled: false
'''
    (STATE / 'config.yaml').write_text(config)
    (STATE / 'config.yaml').chmod(0o600)
    print('Pinned release verified; private configuration prepared.')


def command():
    assert hashlib.sha256((STATE / 'cli-proxy-api').read_bytes()).hexdigest() == BINARY_SHA256, 'Binary checksum mismatch'
    return [str(STATE / 'cli-proxy-api'), '-config', str(STATE / 'config.yaml')]


def auth_check():
    files = list((STATE / 'auth').glob('*.json'))
    assert len(files) == 1, 'Exactly one OAuth account is required'
    for p in files:
        assert p.stat().st_mode & 0o077 == 0, 'Auth permissions must be private'
        assert json.loads(p.read_text()).get('type') == 'codex', 'Expected Codex account'
    return len(files)


def request(path, payload=None, authenticated=True, timeout=90):
    conn = http.client.HTTPConnection('127.0.0.1', PORT, timeout=timeout)
    headers = {'Content-Type': 'application/json'}
    if authenticated:
        headers['Authorization'] = 'Bearer ' + (STATE / 'bearer').read_text().strip()
    try:
        conn.request('POST' if payload is not None else 'GET', path,
                     json.dumps(payload) if payload is not None else None, headers)
        return conn, conn.getresponse()
    except BaseException:
        conn.close()
        raise


def json_request(path, payload=None, authenticated=True):
    conn, response = request(path, payload, authenticated)
    try:
        return response.status, json.loads(response.read())
    finally:
        conn.close()


def stream(payload, cancel=False):
    start = time.monotonic()
    conn, response = request('/v1/chat/completions', payload)
    result = {'status': response.status, 'sse': response.getheader('Content-Type', '').startswith('text/event-stream'),
              'chunks': 0, 'content_chars': 0, 'done': False, 'usage': {}, 'tool_valid': False}
    calls = {}
    try:
        if response.status != 200:
            response.read()
            return result
        while True:
            line = response.readline()
            if not line:
                break
            if not line.startswith(b'data:'):
                continue
            raw = line[5:].strip()
            if raw == b'[DONE]':
                result['done'] = True
                break
            chunk = json.loads(raw)
            result['chunks'] += 1
            usage = chunk.get('usage') or {}
            for key in ('prompt_tokens', 'completion_tokens', 'total_tokens'):
                if type(usage.get(key)) is int:
                    result['usage'][key] = usage[key]
            for choice in chunk.get('choices', []):
                delta = choice.get('delta') or {}
                result['content_chars'] += len(delta.get('content') or '')
                for tool in delta.get('tool_calls', []):
                    entry = calls.setdefault(tool['index'], {'name': '', 'arguments': ''})
                    for field in entry:
                        entry[field] += tool.get('function', {}).get(field) or ''
            if cancel and result['content_chars']:
                result['client_closed_before_done'] = True
                break
        for call in calls.values():
            if call['name'] == 'spike_echo':
                result['tool_valid'] = json.loads(call['arguments']) == {'value': 'ping'}
    finally:
        response.close()
        conn.close()
    result['elapsed_seconds'] = round(time.monotonic() - start, 3)
    return result


def run(model):
    auth_check()
    evidence = {'release': VERSION, 'archive_sha256': SHA256, 'model': model,
                'recorded_utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()), 'runs': []}
    for iteration in range(2):
        # Refuse to attach to an unrelated daemon.
        with socket.socket() as guard:
            guard.bind(('127.0.0.1', PORT))
        process = subprocess.Popen(command(), cwd=STATE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            for attempt in range(100):
                assert process.poll() is None, 'Daemon exited'
                try:
                    status, _ = json_request('/v1/models', authenticated=False)
                    break
                except (OSError, http.client.HTTPException):
                    time.sleep(.1)
            else:
                raise RuntimeError('Daemon startup timed out')
            assert status == 401, 'Unauthenticated request must fail'
            listeners = subprocess.check_output(['lsof', '-nP', '-a', '-p', str(process.pid), '-iTCP', '-sTCP:LISTEN'], text=True)
            addresses = [line.split()[-2] for line in listeners.splitlines()[1:]]
            assert addresses == [f'127.0.0.1:{PORT}'], 'Unexpected listener'
            status, models = json_request('/v1/models')
            available = [x['id'] for x in models.get('data', [])]
            assert status == 200 and model in available, 'Selected model unavailable'
            base = {'model': model, 'stream': True, 'stream_options': {'include_usage': True},
                    'messages': [{'role': 'user', 'content': 'Reply with exactly OK.'}]}
            normal = stream(base)
            tool = stream({**base, 'messages': [{'role': 'user', 'content': 'Call spike_echo with value ping.'}],
                           'tools': [{'type': 'function', 'function': {'name': 'spike_echo', 'parameters': {
                               'type': 'object', 'properties': {'value': {'type': 'string'}},
                               'required': ['value'], 'additionalProperties': False}}}],
                           'tool_choice': {'type': 'function', 'function': {'name': 'spike_echo'}}})
            cancelled = stream({**base, 'messages': [{'role': 'user', 'content': 'List integers from 1 through 10000, one per line.'}]}, cancel=True)
            deadline = {'observed': False}
            conn = None
            try:
                conn, resp = request('/v1/chat/completions', base, timeout=.001)
                resp.read()
            except (TimeoutError, socket.timeout):
                deadline['observed'] = True
            finally:
                if conn:
                    conn.close()
            err_status, err = json_request('/v1/chat/completions', {**base, 'model': 'spike-nonexistent-model', 'stream': False})
            # Save only shape, never upstream error text or request/response content.
            error = {'status': err_status, 'has_error_object': isinstance(err.get('error'), dict), 'origin': 'proxy routing'}
            bad_status, bad = json_request('/v1/chat/completions', {
                **base, 'stream': False,
                'tools': [{'type': 'function', 'function': {'name': 'spike_invalid',
                           'parameters': {'type': 'not-a-json-schema-type'}}}]})
            upstream_error = {'status': bad_status, 'has_error_object': isinstance(bad.get('error'), dict),
                              'trigger': 'invalid function JSON schema',
                              'invalid_schema_message': 'Invalid schema for function' in str(bad.get('error', {}).get('message', '')),
                              'origin': 'Codex upstream (inferred from pinned executor forwarding and schema-error response)'}
            recovery = stream(base)
            record = {'iteration': iteration + 1, 'unauthenticated_status': 401, 'listeners': addresses,
                      'model_count': len(available), 'auth_files': auth_check(), 'stream': normal,
                      'tool': tool, 'cancellation': cancelled, 'client_timeout': deadline,
                      'invalid_model': error, 'invalid_tool_schema': upstream_error, 'recovery': recovery}
            evidence['runs'].append(record)
            print(json.dumps(record, indent=2), flush=True)
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['setup', 'login', 'run'])
    parser.add_argument('--model', default='gpt-6-luna')
    parser.add_argument('--evidence', type=Path)
    args = parser.parse_args()
    if args.action == 'setup':
        setup()
    elif args.action == 'login':
        assert sys.stdout.isatty(), 'Login must run in a human terminal without output capture'
        subprocess.run(command() + ['-codex-device-login'], cwd=STATE, check=True)
        auth_check()
    else:
        evidence = run(args.model)
        if args.evidence:
            args.evidence.write_text(json.dumps(evidence, indent=2) + '\n')
        passed = all(r['stream']['sse'] and r['stream']['done'] and r['stream']['content_chars'] > 0 and r['stream']['usage']
                     and r['tool']['sse'] and r['tool']['done'] and r['tool']['tool_valid'] and r['client_timeout']['observed']
                     and r['cancellation'].get('client_closed_before_done') and r['recovery']['done']
                     and r['invalid_tool_schema']['status'] >= 400
                     and r['invalid_tool_schema']['invalid_schema_message']
                     for r in evidence['runs'])
        if not passed:
            sys.exit('Compatibility checks failed; see redacted evidence.')


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        # Exception messages can contain paths, account IDs or server bodies.
        sys.exit('Spike failed (' + type(exc).__name__ + '); raw details suppressed.')
