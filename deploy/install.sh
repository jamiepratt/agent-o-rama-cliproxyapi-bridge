#!/usr/bin/env bash
# Dedicated Debian 12/13 or Ubuntu 24.04+ x86_64 host only. Never called by CI.
set -euo pipefail
if [[ ${1:-} != --approved-install || $# != 4 ]]; then
  echo 'Explicit host-install approval required: install.sh --approved-install CACHE MODULE_JAR SHA256' >&2
  exit 64
fi
[[ $EUID == 0 && $(uname -s) == Linux && $(uname -m) == x86_64 ]]
source_dir=$(cd -- "$(dirname -- "$0")" && pwd)
cache=$(realpath -- "$2")
module_jar=$(realpath -- "$3")
module_hash=$4
[[ $module_hash =~ ^[a-f0-9]{64}$ ]]
for command in java python3 unzip tar nft tailscale systemctl restic runuser openssl flock; do command -v "$command" >/dev/null; done
java -version 2>&1 | head -1 | grep -E '"21[.]'
[[ ! -e /opt/bridge && ! -L /opt/bridge && ! -e /etc/bridge && ! -L /etc/bridge ]]
for account in bridge-rama bridge-proxy bridge-zk; do
  [[ ! -e /var/lib/$account && ! -L /var/lib/$account && ! -e /var/log/$account && ! -L /var/log/$account ]]
  if getent passwd "$account" >/dev/null; then echo 'Dedicated service account already exists; refusing install.' >&2; exit 1; fi
done
for unit in "$source_dir/systemd/"*.service; do
  [[ ! -e /etc/systemd/system/$(basename "$unit") && ! -L /etc/systemd/system/$(basename "$unit") ]]
done
# Operator must verify console rescue and Tailscale SSH access before this gate.
tailscale status --json | python3 -c 'import json,sys; assert json.load(sys.stdin)["BackendState"] == "Running"'
# Listing a named table conflates absence with inspection/parser failures.
# Require a successful complete inventory before deciding installation is safe.
nft -j list tables | python3 -c '
import json,sys
tables=json.load(sys.stdin)["nftables"]
if any(entry.get("table", {}).get("family") == "inet" and
       entry["table"].get("name") == "bridge_private" for entry in tables):
    sys.exit("Existing bridge firewall; refusing overwrite.")
'
# Verify cached artifacts only. The root installer never downloads code.
python3 - "$source_dir" "$cache" "$module_jar" "$module_hash" <<'PY'
import json,sys
from pathlib import Path
sys.path.insert(0,sys.argv[1])
from prepare import verify
for pin in json.loads((Path(sys.argv[1])/'artifacts.json').read_text()):
    verify(Path(sys.argv[2])/pin['file'],pin['algorithm'],pin['digest'])
verify(Path(sys.argv[3]),'sha256',sys.argv[4])
PY
# Check nft syntax before writing any host files; never flush existing tables.
nft --check -f "$source_dir/config/firewall.nft"
umask 077
for account in bridge-rama bridge-proxy bridge-zk; do
  useradd --system --user-group --home-dir "/var/lib/$account" --create-home --shell /usr/sbin/nologin "$account"
  chmod 0700 "/var/lib/$account"
done
install -d -m 0755 /opt/bridge /opt/bridge/modules /opt/bridge/releases /etc/bridge
release=/opt/bridge/releases/rama-1.9.0-zk-3.9.6-proxy-7.3.15
install -d -m 0755 "$release/rama" "$release/zk"
unzip -q "$cache/rama-1.9.0.zip" -d "$release/rama"
tar -xzf "$cache/apache-zookeeper-3.9.6-bin.tar.gz" --strip-components=1 -C "$release/zk"
tar -xzf "$cache/CLIProxyAPI_7.3.15_linux_amd64.tar.gz" -C "$release" cli-proxy-api
chmod -R a+rX "$release"
chmod 0755 "$release/cli-proxy-api" "$release/rama/rama"
install -m 0644 "$module_jar" "/opt/bridge/modules/$module_hash.jar"
ln -s "modules/$module_hash.jar" /opt/bridge/module.jar
ln -s "$release" /opt/bridge/current
for account in bridge-rama bridge-zk; do install -d -m 0700 -o "$account" -g "$account" "/var/log/$account"; done
for child in data licenses locks; do install -d -m 0700 -o bridge-rama -g bridge-rama "/var/lib/bridge-rama/$child"; done
for child in data txn; do install -d -m 0700 -o bridge-zk -g bridge-zk "/var/lib/bridge-zk/$child"; done
install -d -m 0700 -o bridge-proxy -g bridge-proxy /var/lib/bridge-proxy/auth
# Pinned ZIP has an empty logs directory. Refuse unexpected contents.
rmdir "$release/rama/logs"
ln -s /var/log/bridge-rama "$release/rama/logs"
install -m 0644 "$source_dir/config/"* /etc/bridge/
ln -sf /etc/bridge/rama.yaml "$release/rama/rama.yaml"
ln -sf /etc/bridge/log4j2.properties "$release/rama/log4j2.properties"
install -m 0755 "$source_dir/admin.sh" /opt/bridge/admin.sh
install -m 0644 "$source_dir/artifacts.json" /opt/bridge/artifacts.json
# Two private bearer copies avoid group-readable credentials. Never print it.
python3 - <<'PY'
import json,os,pwd,secrets
from pathlib import Path
accounts={u: {'uid':pwd.getpwnam(u).pw_uid,'gid':pwd.getpwnam(u).pw_gid} for u in ['bridge-rama','bridge-proxy','bridge-zk']}
Path('/etc/bridge/accounts.json').write_text(json.dumps(accounts))
key=secrets.token_urlsafe(32)
base=Path('/var/lib/bridge-proxy')
config='''host: "127.0.0.1"
port: 18317
auth-dir: "/var/lib/bridge-proxy/auth"
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
'''+ 'api-keys: ['+json.dumps(key)+']\n'
for path,data,user in [(base/'config.yaml',config,'bridge-proxy'),(Path('/var/lib/bridge-rama/bearer'),key,'bridge-rama')]:
    path.write_text(data); path.chmod(0o600)
    account=pwd.getpwnam(user); os.chown(path,account.pw_uid,account.pw_gid)
PY
install -m 0644 "$source_dir/systemd/"*.service /etc/systemd/system/
systemctl daemon-reload
# Enable the firewall first. Failure prevents every application service start.
systemctl enable --now bridge-firewall.service
# Installation deliberately does not start account login or any provider traffic.
echo 'Installed. Human device login, backup configuration, and explicit start/deploy remain required.'
