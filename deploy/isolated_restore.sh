#!/usr/bin/env bash
set -euo pipefail
if [[ ${1:-} != --approved-isolated-restore ]]; then
  echo 'Explicit approval required: isolated_restore.sh --approved-isolated-restore [--plan] TARGET -- COMMAND [ARGS]' >&2
  exit 64
fi
shift
plan=false
if [[ ${1:-} == --plan ]]; then plan=true; shift; fi
if [[ $# -lt 3 || ! $1 =~ ^/var/tmp/bridge-restore-[A-Za-z0-9._-]+$ || $2 != -- ]]; then
  echo 'Require a dedicated /var/tmp/bridge-restore-NAME target and -- COMMAND.' >&2
  exit 64
fi
target=$1; shift 2
roots=(/var/lib/bridge-rama /var/lib/bridge-zk /var/lib/bridge-proxy /etc/bridge /opt/bridge)
if $plan; then
  printf '%s\n' '900 second deadline: unshare --mount --net --pid --fork --kill-child --mount-proc --propagation private'
  printf 'Bind restored %s\n' "${roots[@]}"
  printf '%s\n' 'Private /run, /tmp, /var/tmp and fresh logs; network loopback only.' 'Start restored ZooKeeper, conductor, supervisor; run command as bridge-rama.' 'Terminate all namespace children and wait; retain restored state and logs.'
  exit 0
fi
[[ $EUID == 0 && $(uname -s) == Linux ]] || { echo 'Linux root required.' >&2; exit 1; }
umask 077
exec 9>/run/lock/bridge-maintenance.lock
flock -n 9 || { echo 'Another bridge operation holds the maintenance lock.' >&2; exit 1; }
# Preflight does not print restored config, credentials or filenames inside auth state.
python3 - "$target" <<'PY'
import json, os, pathlib, posixpath, pwd, stat, sys
base = pathlib.Path(sys.argv[1])
roots = ['/var/lib/bridge-rama', '/var/lib/bridge-zk', '/var/lib/bridge-proxy', '/etc/bridge', '/opt/bridge']
allowed = roots + ['/var/log/bridge-rama', '/var/log/bridge-zk']
def require(ok, message):
    if not ok:
        raise SystemExit(message)
require(str(base.resolve()) == str(base), 'Restore target must have no symlink ancestors.')
st = base.stat()
require(st.st_uid == 0 and stat.S_IMODE(st.st_mode) == 0o700, 'Restore target must be root-owned 0700.')
for root in roots:
    p = base / root[1:]
    require(p.is_dir() and not p.is_symlink() and p.resolve() == p, 'Restored roots must be real directories.')
    require(pathlib.Path(root).is_dir() and not pathlib.Path(root).is_symlink(), 'Live mountpoint must be a real directory.')
    for current, dirs, files in os.walk(p, followlinks=False):
        for name in dirs + files:
            entry = pathlib.Path(current) / name
            st = entry.lstat()
            if stat.S_ISLNK(st.st_mode):
                link = os.readlink(entry)
                original = '/' + str(entry.relative_to(base))
                dest = posixpath.normpath(link if link.startswith('/') else posixpath.join(posixpath.dirname(original), link))
                require(any(dest == r or dest.startswith(r + '/') for r in allowed), 'Restored symlink escapes mounted roots.')
            else:
                require(stat.S_ISDIR(st.st_mode) or stat.S_ISREG(st.st_mode), 'Special files forbidden in restored roots.')
                if root in ['/etc/bridge', '/opt/bridge']:
                    require(st.st_uid == 0 and not st.st_mode & 0o022, 'Restored config/runtime must be root-owned and not writable by others.')
accounts = json.loads((base / 'etc/bridge/accounts.json').read_text())
for user in ['bridge-rama', 'bridge-zk', 'bridge-proxy']:
    account = pwd.getpwnam(user)
    require(accounts[user] == {'uid': account.pw_uid, 'gid': account.pw_gid}, 'Restored numeric service identities differ.')
for path in ['/var/log/bridge-rama', '/var/log/bridge-zk']:
    p = pathlib.Path(path)
    require(p.is_dir() and p.resolve() == p, 'Log mountpoints must be real directories.')
print('Restore path, ownership, symlink and account preflight passed.')
PY
for tool in unshare mount ip timeout runuser python3; do command -v "$tool" >/dev/null; done
# Each invocation retains its separate logs. Restored state is deliberately writable.
work=$(mktemp -d "$target/drill-XXXXXXXX")
for user in bridge-rama bridge-zk; do install -d -m 0700 -o "$user" -g "$user" "$work/$user"; done
printf 'Private drill logs: %s\n' "$work"
# A fresh PID namespace ensures killing its PID 1 also reaps every descendant.
# FD 9 remains open in the outer process for the complete drill.
timeout --signal=TERM --kill-after=20s 900s \
  unshare --mount --net --pid --fork --kill-child=KILL --mount-proc --propagation private \
  bash -s -- "$target" "$work" "$@" <<'INNER'
set -euo pipefail
[[ $$ == 1 ]] || { echo 'Refusing to run outside a fresh PID namespace.' >&2; exit 1; }
target=$1; work=$2; shift 2
mount --make-rprivate /
for root in /var/lib/bridge-rama /var/lib/bridge-zk /var/lib/bridge-proxy /etc/bridge /opt/bridge; do
  mount --bind "$target$root" "$root"
done
for user in bridge-rama bridge-zk; do
  mount --bind "$work/$user" "/var/log/$user"
done
# Runtime/config cannot be modified by the drill. State/lock domains remain paired.
mount -o remount,bind,ro /opt/bridge
mount -o remount,bind,ro /etc/bridge
for path in /run /tmp /var/tmp; do mount -t tmpfs -o mode=1777,nosuid,nodev tmpfs "$path"; done
ip link set lo up
[[ $(ip -o link show | wc -l) == 1 && -z $(ip route show) && -z $(ip -6 route show default) ]]
cleanup() {
  trap - EXIT TERM INT
  kill -TERM -1 2>/dev/null || true
  sleep 3
  kill -KILL -1 2>/dev/null || true
  wait 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 143' TERM INT
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS CLASSPATH BASH_ENV ENV
ulimit -n 65536
runuser -u bridge-zk -- env -i PATH="$PATH" HOME=/var/lib/bridge-zk ZOO_LOG_DIR=/var/log/bridge-zk ZK_SERVER_HEAP=256 \
  /opt/bridge/current/zk/bin/zkServer.sh start-foreground /etc/bridge/zoo.cfg > /var/log/bridge-zk/restore.log 2>&1 &
wait_port() {
  python3 - "$1" <<'PY'
import socket, sys, time
end = time.monotonic() + 120
while time.monotonic() < end:
    try:
        with socket.create_connection(('127.0.0.1', int(sys.argv[1])), timeout=1):
            sys.exit(0)
    except OSError:
        time.sleep(1)
raise SystemExit('Restored service did not become reachable within 120 seconds.')
PY
}
wait_port 2181
cd /opt/bridge/current/rama
runuser -u bridge-rama -- env -i PATH="$PATH" HOME=/var/lib/bridge-rama ./rama conductor > /var/log/bridge-rama/conductor.log 2>&1 &
wait_port 1973
runuser -u bridge-rama -- env -i PATH="$PATH" HOME=/var/lib/bridge-rama ./rama supervisor > /var/log/bridge-rama/supervisor.log 2>&1 &
# UI/observer uses restored configuration and has no route to a real provider.
(
  for attempt in {1..12}; do
    runuser -u bridge-rama -- env -i PATH="$PATH" HOME=/var/lib/bridge-rama JAVA_TOOL_OPTIONS='-Xmx768m -Xss6m' \
      ./rama runClj bridge.runtime /opt/bridge/module.jar && break
    sleep 5
  done
) > /var/log/bridge-rama/observer.log 2>&1 &
python3 - <<'PYREADY'
import time, urllib.request
end = time.monotonic() + 180
while time.monotonic() < end:
    try:
        with urllib.request.urlopen('http://127.0.0.1:18318/metrics', timeout=15) as response:
            if response.status == 200 and b'bridge_upstream_dispatches_total' in response.read():
                break
    except OSError:
        pass
    time.sleep(2)
else:
    raise SystemExit('Restored module metrics did not become readable within 180 seconds.')
PYREADY
# Command owns module-readiness checks, replay assertions and any loopback fixture.
# It inherits no cloud/provider credentials. All output is private evidence.
runuser -u bridge-rama -- env -i PATH="$PATH" HOME=/var/lib/bridge-rama "$@" > /var/log/bridge-rama/probe.log 2>&1
printf '%s\n' 'Isolated restore command passed; terminating restored processes.'
INNER
