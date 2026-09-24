#!/usr/bin/env python3
"""Compare copied OAuth bootstrap across old/new/old in a loopback-only netns."""
import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request


def require_isolation(interfaces=None, platform=None, routes=None):
    if (sys.platform if platform is None else platform) != 'linux':
        raise ValueError('Requires Linux network isolation')
    names = {name for _, name in (socket.if_nameindex() if interfaces is None else interfaces)}
    if names != {'lo'}:
        raise ValueError('Requires an isolated loopback-only network namespace')
    if routes is None:
        routes = []
        for family in ['-4', '-6']:
            routes.extend(json.loads(subprocess.check_output(
                ['ip', '-j', family, 'route', 'show', 'table', 'all'],
                stderr=subprocess.DEVNULL, timeout=5)))
    for route in routes:
        if route.get('type') in {'unreachable', 'prohibit', 'blackhole', 'throw'}:
            continue
        destination = route.get('dst', 'default')
        try:
            network = ipaddress.ip_network(destination, strict=False)
        except ValueError:
            raise ValueError('Non-loopback route present') from None
        loopback = ipaddress.ip_network('127.0.0.0/8' if network.version == 4 else '::1/128')
        if route.get('dev') != 'lo' or 'gateway' in route or not network.subnet_of(loopback):
            raise ValueError('Non-loopback route present')


def manifest(root):
    result = {}
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            raise ValueError('Refusing symlink in copied credential state')
        if path.is_file():
            result[str(path.relative_to(root))] = hashlib.sha256(path.read_bytes()).hexdigest()
        elif not path.is_dir():
            raise ValueError('Refusing special file in copied state')
    return result


def isolated_config(text, auth_dir, port):
    allowed = {'host', 'port', 'auth-dir', 'api-keys', 'remote-management', 'debug',
               'logging-to-file', 'request-log', 'commercial-mode', 'usage-statistics-enabled',
               'request-retry', 'max-retry-credentials', 'max-retry-interval', 'pprof',
               'discovery', 'plugins'}
    nested = {'allow-remote: false', 'secret-key: ""', 'disable-control-panel: true',
              'disable-auto-update-panel: true', 'enable: false', 'enabled: false'}
    seen = set()
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        if line[0].isspace():
            if line.strip() not in nested:
                raise ValueError('Unsupported nested configuration')
            continue
        key = line.split(':', 1)[0]
        if key not in allowed or key in seen:
            raise ValueError('Unsupported or duplicate configuration field')
        seen.add(key)
    def replace(name, value):
        nonlocal text
        text, count = re.subn(r'(?m)^' + re.escape(name) + r':[^\n]*$', name + ': ' + value, text)
        if count != 1:
            raise ValueError('Required configuration field missing')
    match = re.search(r'(?m)^api-keys: (\[[^\n]*\])$', text)
    keys = json.loads(match.group(1)) if match else []
    if len(keys) != 1 or not isinstance(keys[0], str) or not keys[0]:
        raise ValueError('Expected one private API key')
    replace('auth-dir', json.dumps(str(auth_dir)))
    replace('host', '"127.0.0.1"')
    replace('port', str(port))
    replace('logging-to-file', 'false')
    replace('request-log', 'false')
    return text, keys[0]


class EmptyInventory(ValueError):
    """A valid response before the asynchronous credential registry is ready."""


def inventory(response):
    if not isinstance(response, dict) or not isinstance(response.get('data'), list):
        raise ValueError('Invalid model inventory')
    if not response['data']:
        raise EmptyInventory('Model registry not ready')
    if any(not isinstance(item, dict) or not isinstance(item.get('id'), str)
           or not item['id'] for item in response['data']):
        raise ValueError('Invalid model inventory')
    names = sorted(item['id'] for item in response['data'])
    return len(names), hashlib.sha256(json.dumps(names, separators=(',', ':')).encode()).hexdigest()


def summarize(results):
    if len(results) != 3 or any(result != results[0] for result in results):
        raise ValueError('Model inventories differ across old/new/old')
    return {'model_count': results[0][0], 'model_ids_sha256': results[0][1], 'equal': True}


def boot(binary, config, key, cwd, port):
    process = subprocess.Popen([str(binary), '-config', str(config)], cwd=cwd,
                               stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL, start_new_session=True,
                               env={'PATH': '/usr/bin:/bin', 'HOME': str(cwd)})
    try:
        deadline = time.monotonic() + 30
        request = urllib.request.Request(f'http://127.0.0.1:{port}/v1/models',
                                         headers={'Authorization': 'Bearer ' + key})
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise ValueError('Proxy exited before inventory became available')
            try:
                with opener.open(request, timeout=2) as response:
                    return inventory(json.load(response))
            except (urllib.error.URLError, TimeoutError, EmptyInventory):
                time.sleep(0.1)
        raise ValueError('Proxy bootstrap timed out')
    finally:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--copy-root', type=Path, required=True)
    parser.add_argument('--old-binary', type=Path, required=True)
    parser.add_argument('--new-binary', type=Path, required=True)
    args = parser.parse_args()
    os.umask(0o077)
    require_isolation()
    source = args.copy_root.resolve(strict=True)
    evidence = Path('/var/tmp/bridge-b04-evidence').resolve(strict=True)
    if not source.is_relative_to(evidence) or source == evidence:
        raise ValueError('Copy must be inside the private b04 evidence directory')
    if evidence.stat().st_uid != os.geteuid() or evidence.stat().st_mode & 0o077:
        raise ValueError('Evidence directory must be owner-only')
    before = manifest(source)
    binaries = [args.old_binary.resolve(strict=True), args.new_binary.resolve(strict=True)]
    if binaries[0].read_bytes() == binaries[1].read_bytes():
        raise ValueError('Distinct binary versions are required')
    scratch = Path(tempfile.mkdtemp(prefix='auth-bootstrap-', dir=evidence))
    copied = scratch / 'proxy'
    shutil.copytree(source, copied)
    auth_before = manifest(copied / 'auth')
    if not auth_before:
        raise ValueError('Copied credentials are empty')
    config, key = isolated_config((copied / 'config.yaml').read_text(), copied / 'auth', 18318)
    (copied / 'config.yaml').write_text(config)
    results = []
    for binary in [binaries[0], binaries[1], binaries[0]]:
        results.append(boot(binary, copied / 'config.yaml', key, copied, 18318))
        if manifest(copied / 'auth') != auth_before:
            raise ValueError('Copied auth manifest changed during bootstrap')
    if manifest(source) != before:
        raise ValueError('Source copy changed during bootstrap')
    result = summarize(results)
    result.update({'source_manifest_unchanged': True, 'auth_manifest_unchanged': True})
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    try:
        main()
    except Exception:
        # Never format upstream errors or private paths/content in public output.
        print('{"ok": false, "error": "isolated auth bootstrap failed"}')
        raise SystemExit(1)
