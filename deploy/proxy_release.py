#!/usr/bin/env python3
"""Stage and switch proxy-only releases; never modify credentials or durable state."""
import hashlib
import json
import os
import re
import tarfile
from pathlib import Path


def checksum(path, expected):
    with Path(path).open('rb') as source:
        actual = hashlib.file_digest(source, 'sha256').hexdigest()
    if actual != expected:
        raise ValueError('Artifact checksum mismatch')
    return actual


def secure(path, root):
    path, root = Path(path), Path(root)
    if not path.is_relative_to(root) or '..' in path.parts:
        raise ValueError('Path outside installation')
    for item in (path, *path.parents):
        if not item.is_relative_to(root):
            break
        if item.is_symlink():
            raise ValueError('Unexpected symlink')
        info = item.stat()
        if info.st_uid != os.geteuid() or info.st_mode & 0o022:
            raise ValueError('Path must be operator-owned and not group/world writable')


def release(root, name):
    if not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9._-]*', name):
        raise ValueError('Invalid release name')
    return Path(root) / 'opt/bridge/releases' / name


def current(root, expected):
    old = release(root, expected)
    secure(old, root)
    link = Path(root) / 'opt/bridge/current'
    if not link.is_symlink() or link.resolve(strict=True) != old:
        raise ValueError('Current release changed; refusing stale operation')
    return old


def runtime(root, directory, name):
    path = (directory / name).resolve(strict=True)
    if not path.is_relative_to(Path(root) / 'opt/bridge/releases') or not path.is_dir():
        raise ValueError('Runtime must remain inside preserved releases')
    secure(path, root)
    return path


def stage(root, expected, target, archive, archive_sha256, old_sha256, new_sha256):
    checksum(archive, archive_sha256)
    old = current(root, expected)
    destination = release(root, target)
    if destination.exists() or destination.is_symlink():
        raise ValueError('Release destination must be new')
    secure(old / 'cli-proxy-api', root)
    checksum(old / 'cli-proxy-api', old_sha256)
    if old_sha256 == new_sha256:
        raise ValueError('Distinct proxy artifact required')
    paths = {name: runtime(root, old, name) for name in ('rama', 'zk')}
    with tarfile.open(archive, 'r:gz') as saved:
        members = [m for m in saved if m.name == 'cli-proxy-api']
        if len(members) != 1 or not members[0].isfile() or members[0].size > 200 * 1024 * 1024:
            raise ValueError('Archive must contain one regular proxy executable')
        data = saved.extractfile(members[0]).read()
    if hashlib.sha256(data).hexdigest() != new_sha256:
        raise ValueError('Executable checksum mismatch')
    destination.mkdir(mode=0o755)
    binary = destination / 'cli-proxy-api'
    binary.write_bytes(data)
    binary.chmod(0o755)
    for name, path in paths.items():
        (destination / name).symlink_to(path)
    (destination / 'proxy-release.json').write_text(json.dumps({
        'previous': expected, 'archive_sha256': archive_sha256,
        'previous_binary_sha256': old_sha256, 'binary_sha256': new_sha256,
        'runtime_paths': {k: str(v) for k, v in paths.items()},
    }, indent=2) + '\n')
    return destination


def replace_current(root, expected, target):
    current(root, expected)
    link = Path(root) / 'opt/bridge/current'
    temporary = link.with_name('current.proxy-switch-' + str(os.getpid()))
    temporary.symlink_to(release(root, target))
    try:
        os.replace(temporary, link)
    finally:
        temporary.unlink(missing_ok=True)


def switch(root, expected, target, old_sha256, new_sha256, service):
    old = current(root, expected)
    new = release(root, target)
    secure(new, root)
    for directory, digest in ((old, old_sha256), (new, new_sha256)):
        secure(directory / 'cli-proxy-api', root)
        checksum(directory / 'cli-proxy-api', digest)
    if old_sha256 == new_sha256:
        raise ValueError('Distinct proxy artifact required')
    for name in ('rama', 'zk'):
        if runtime(root, old, name) != runtime(root, new, name):
            raise ValueError('Only proxy may change')
    service('stop')
    service('stopped')
    replace_current(root, expected, target)
    try:
        service('start')
    except BaseException:
        # Never switch beneath a still-running candidate, even during recovery.
        service('stop')
        service('stopped')
        replace_current(root, target, expected)
        service('start')
        raise


def system_service(action):
    import pwd
    import socket
    import subprocess
    import time
    unit = 'bridge-proxy.service'
    if action == 'stopped':
        state = subprocess.run(['systemctl', 'show', unit, '--property=ActiveState', '--value'],
                               check=True, capture_output=True, text=True).stdout.strip()
        if state not in ('inactive', 'failed'):
            raise RuntimeError('Proxy must be stopped')
        uid = str(pwd.getpwnam('bridge-proxy').pw_uid)
        for flag in ('-u', '-U'):
            if subprocess.run(['pgrep', flag, uid], capture_output=True).returncode != 1:
                raise RuntimeError('Proxy processes remain or inspection failed')
        return
    subprocess.run(['systemctl', action, unit], check=True, capture_output=True)
    if action == 'start':
        # Listener checks do not replace authenticated provider/replay probes.
        for _ in range(5):
            time.sleep(1)
            subprocess.run(['systemctl', 'is-active', '--quiet', unit], check=True,
                           capture_output=True)
        with socket.create_connection(('127.0.0.1', 18317), timeout=5):
            pass


def main():
    import argparse
    import fcntl
    import signal
    import sys
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('stage', 'switch'))
    parser.add_argument('--approved-maintenance', action='store_true', required=True)
    parser.add_argument('--clients-quiesced', action='store_true')
    parser.add_argument('--expected-current', required=True, help='Release directory basename')
    parser.add_argument('--target', required=True, help='Release directory basename')
    parser.add_argument('--current-sha256', required=True)
    parser.add_argument('--target-sha256', required=True)
    parser.add_argument('--archive', type=Path)
    parser.add_argument('--archive-sha256')
    args = parser.parse_args()
    if sys.platform != 'linux' or os.geteuid() != 0:
        parser.error('Linux root required')
    if args.operation == 'switch' and not args.clients_quiesced:
        parser.error('Stop client submissions and drain active/queued work; --clients-quiesced required')
    if args.operation == 'stage' and (not args.archive or not args.archive_sha256):
        parser.error('Stage requires --archive and --archive-sha256')
    def interrupted(signum, frame):
        raise KeyboardInterrupt('Maintenance interrupted')
    signal.signal(signal.SIGTERM, interrupted)
    lock_path = Path('/run/lock/bridge-maintenance.lock')
    fd = os.open(lock_path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'r+') as lock:
        info = os.fstat(lock.fileno())
        if info.st_uid != 0 or info.st_mode & 0o022:
            raise ValueError('Unsafe maintenance lock')
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if args.operation == 'stage':
            result = stage(Path('/'), args.expected_current, args.target, args.archive,
                           args.archive_sha256, args.current_sha256, args.target_sha256)
            print('Staged:', result)
        else:
            switch(Path('/'), args.expected_current, args.target, args.current_sha256,
                   args.target_sha256, system_service)
            print('Proxy switched. Run authenticated readiness, stream and saved replay probes.')


if __name__ == '__main__':
    main()
