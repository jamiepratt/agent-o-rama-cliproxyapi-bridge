#!/usr/bin/env python3
"""Preserve a stopped installed bridge and initialize a separate state domain.

The public CLI always operates on /. The root argument on the library function
exists for filesystem integration tests; it is deliberately not a CLI option.
"""
import argparse
import hashlib
import os
from pathlib import Path
import pwd
import shutil
import stat
import subprocess
import tarfile

UNITS = ('bridge-ui', 'bridge-supervisor', 'bridge-conductor', 'bridge-zk', 'bridge-proxy')
ACCOUNTS = ('bridge-rama', 'bridge-zk', 'bridge-proxy')


MAINTENANCE_ROOT = Path('/')
INHIBITOR = '[Unit]\nConditionPathExists=!/etc/bridge/fresh-state-maintenance\n'


def stopped_boundary():
    marker = MAINTENANCE_ROOT / 'etc/bridge/fresh-state-maintenance'
    real_path(marker)
    if not marker.is_file() or marker.stat().st_uid != os.geteuid() or marker.stat().st_mode & 0o077:
        raise ValueError('Private maintenance marker required')
    for unit in UNITS:
        relative = f'etc/systemd/system/{unit}.service.d/90-fresh-state.conf'
        inhibitor = MAINTENANCE_ROOT / relative
        real_path(inhibitor)
        if (not inhibitor.is_file() or inhibitor.stat().st_uid != os.geteuid() or
                inhibitor.stat().st_mode & 0o022 or inhibitor.read_text() != INHIBITOR):
            raise ValueError(f'{unit}: exact root-owned maintenance inhibitor required')
        result = subprocess.run(['systemctl', 'show', unit + '.service',
                                 '--property=ActiveState,DropInPaths,NeedDaemonReload'],
                                check=True, capture_output=True, text=True)
        values = dict(line.split('=', 1) for line in result.stdout.splitlines())
        if (values.get('ActiveState') not in ('inactive', 'failed') or
                values.get('NeedDaemonReload') != 'no' or
                values.get('DropInPaths', '').split() != ['/' + relative]):
            raise RuntimeError(f'{unit} must be stopped with loaded maintenance inhibitor')
    for account in ACCOUNTS:
        uid = pwd.getpwnam(account).pw_uid
        for flag in ('-u', '-U'):
            result = subprocess.run(['pgrep', flag, str(uid)], capture_output=True)
            if result.returncode != 1:
                raise RuntimeError(f'{account}: processes remain or process inspection failed')


def digest(stream):
    value = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b''):
        value.update(block)
    return value.hexdigest()


def verify_archive(archive, root):
    with tarfile.open(archive, 'r:') as saved:
        for member in saved:
            path = root / member.name
            if member.isfile():
                with saved.extractfile(member) as source, path.open('rb') as live:
                    if digest(source) != digest(live):
                        raise RuntimeError('Archive content verification failed')
            elif member.issym():
                if os.readlink(path) != member.linkname:
                    raise RuntimeError('Archive link verification failed')
            elif not member.isdir():
                raise RuntimeError('Unsupported archive member')


def real_path(path):
    for ancestor in (path, *path.parents):
        if ancestor.is_symlink():
            raise ValueError(f'Symlink path refused: {ancestor}')


def preserve_and_initialize(root, destination, check_stopped=stopped_boundary):
    root, destination = Path(root), Path(destination)
    real_path(root)
    real_path(destination)
    if '..' in destination.parts:
        raise ValueError('Parent traversal refused')
    if not destination.is_absolute() or destination.exists() or not destination.parent.is_dir():
        raise ValueError('Preservation path must be new, absolute, with existing parent')
    parent = destination.parent.stat()
    if parent.st_uid != os.geteuid() or parent.st_mode & 0o022:
        raise ValueError('Preservation parent must be operator-owned and not group/world writable')
    sources = [root / p for p in ('var/lib/bridge-rama', 'var/lib/bridge-zk',
                                 'var/lib/bridge-proxy', 'etc/bridge', 'opt/bridge')]
    sources += [root / f'etc/systemd/system/{unit}.service' for unit in UNITS + ('bridge-firewall',)]
    sources += [path for unit in UNITS + ('bridge-firewall',)
                if (path := root / f'etc/systemd/system/{unit}.service.d').exists() or path.is_symlink()]
    for source in sources:
        real_path(source)
        if not source.exists():
            raise ValueError(f'Missing required source: {source}')
        if destination == source or source in destination.parents or destination in source.parents:
            raise ValueError('Preservation path overlaps source')
    rama, zk = sources[:2]
    for source in (rama, zk, rama / 'licenses', rama / 'bearer'):
        real_path(source)
    if not (rama / 'licenses').is_dir() or not (rama / 'bearer').is_file():
        raise ValueError('Required licenses directory or bearer missing')
    if any(p.stat().st_dev != destination.parent.stat().st_dev for p in (rama, zk)):
        raise ValueError('Preservation must share the state filesystem')
    entries = []
    for source in sources:
        entries.append(source)
        if source.is_dir():
            entries.extend(source.rglob('*'))
    for path in entries:
        mode = path.lstat().st_mode
        if not (stat.S_ISREG(mode) or stat.S_ISDIR(mode) or stat.S_ISLNK(mode)):
            raise ValueError(f'Unsupported filesystem entry: {path}')
    # Symlinks inside runtime/config are archived without following them. Never
    # allow credentials copied to the fresh root to reference legacy state.
    if any(p.is_symlink() for p in (rama / 'licenses').rglob('*')):
        raise ValueError('Symlink licenses refused')
    needed = sum(p.lstat().st_size + 2048 for p in entries) + 64 * 1024 * 1024
    if shutil.disk_usage(destination.parent).free < needed:
        raise ValueError('Insufficient free space for verified preservation archive')
    check_stopped()
    destination.mkdir(mode=0o700)
    archive = destination / 'state.tar'
    # PAX avoids truncation; dereference duplicates hardlinked file bytes so
    # verification checks each file and never follows runtime symlinks.
    with archive.open('xb') as output:
        os.chmod(archive, 0o600)
        with tarfile.open(fileobj=output, mode='w:', dereference=False) as saved:
            for path in entries:
                info = saved.gettarinfo(str(path), str(path.relative_to(root)))
                if info.islnk():
                    info.type = tarfile.REGTYPE
                    info.linkname = ''
                    info.size = path.stat().st_size
                if info.isfile():
                    with path.open('rb') as source:
                        saved.addfile(info, source)
                else:
                    saved.addfile(info)
        output.flush()
        os.fsync(output.fileno())
    verify_archive(archive, root)
    with archive.open('rb') as stream:
        checksum = digest(stream)
    (destination / 'state.tar.sha256').write_text(checksum + '  state.tar\n')
    (destination / 'state.tar.sha256').chmod(0o600)
    # Recheck after potentially lengthy archival, before moving either domain.
    check_stopped()
    for source in (rama, zk):
        source.rename(destination / source.name)
    # If any operation fails, leave all services inhibited and all preserved roots
    # intact. Never roll back automatically across a partially initialized domain.
    for source, children in ((rama, ('data', 'locks')), (zk, ('data', 'txn'))):
        previous = destination / source.name
        identity = previous.stat()
        source.mkdir(mode=0o700)
        os.chown(source, identity.st_uid, identity.st_gid)
        for child in children:
            target = source / child
            target.mkdir(mode=0o700)
            os.chown(target, identity.st_uid, identity.st_gid)
    shutil.copytree(destination / 'bridge-rama/licenses', rama / 'licenses')
    shutil.copy2(destination / 'bridge-rama/bearer', rama / 'bearer')
    identity = (destination / 'bridge-rama').stat()
    for path in [rama / 'licenses', *(rama / 'licenses').rglob('*'), rama / 'bearer']:
        os.chown(path, identity.st_uid, identity.st_gid)
        path.chmod(0o700 if path.is_dir() else 0o600)
    (destination / 'INITIALIZED').write_text('Fresh Rama and ZooKeeper roots initialized; services remain inhibited.\n')
    (destination / 'INITIALIZED').chmod(0o600)
    return checksum


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--approved-fresh-state', required=True, metavar='PRESERVATION_ROOT')
    args = parser.parse_args()
    if os.geteuid() != 0 or os.uname().sysname != 'Linux':
        parser.error('Requires root on the installed Linux host')
    os.umask(0o077)
    checksum = preserve_and_initialize(Path('/'), Path(args.approved_fresh_state))
    print('Preserved state; fresh roots initialized; services remain inhibited. Archive SHA256: ' + checksum)


if __name__ == '__main__':
    main()
