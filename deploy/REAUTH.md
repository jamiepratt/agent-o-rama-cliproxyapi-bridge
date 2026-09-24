# Human OAuth reauthentication checkpoint

Issue [#5](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/5)
remains open until the real account owner completes reauthentication and the
operator verifies the resulting credential with a fresh provider stream and
saved replay. Existing working authentication does not satisfy this checkpoint.

Do this only when the owner is ready, in their private, unrecorded terminal.
Do not run the login command through an agent tool, tee, shell tracing, a job
logger, or screen recording. Never paste device codes, tokens, account details,
auth JSON, or login output into chat or GitHub. Do not delete or revoke the
working login to manufacture a failure. This is an authorized maintenance
checkpoint, not a request for another deployment approval.

## Private terminal command

Connect with `ssh -t bridge-vps`, then enter the block below. It takes the same
maintenance lock as `admin.sh`, stops only the proxy, preserves its complete
state in a new root-only backup, and authenticates into a separate empty auth
directory. It restarts the existing proxy on shell exit. The new credential is
**not promoted automatically**. Allow up to 15 minutes for the official device
flow. An interrupted or failed login leaves production credentials in place.

```bash
sudo bash <<'BRIDGE_REAUTH'
set -euo pipefail
umask 077
exec 9>/run/lock/bridge-maintenance.lock
flock -n 9 || { echo 'Another maintenance operation is active.' >&2; exit 1; }
systemctl is-active --quiet bridge-proxy
trap 'systemctl start bridge-proxy' EXIT
systemctl stop bridge-proxy
if pgrep -u bridge-proxy >/dev/null; then
  echo 'Proxy processes remain; stop and ask the operator.' >&2
  exit 1
fi
backup=$(mktemp -d /var/backups/bridge-reauth-XXXXXXXX)
chmod 0700 "$backup"
cp -a /var/lib/bridge-proxy "$backup/proxy-state"
stage=$(mktemp -d /var/lib/bridge-proxy/reauth-XXXXXXXX)
install -d -m 0700 -o bridge-proxy -g bridge-proxy "$stage/auth"
python3 - "$stage" <<'PY'
import pathlib, re, sys
stage = pathlib.Path(sys.argv[1])
source = pathlib.Path('/var/lib/bridge-proxy/config.yaml').read_text()
pattern = r'(?m)^auth-dir:[^\n]*$'
assert len(re.findall(pattern, source)) == 1, 'Expected one top-level auth-dir'
text = re.sub(pattern, 'auth-dir: "' + str(stage / 'auth') + '"', source)
(stage / 'config.yaml').write_text(text)
PY
chown bridge-proxy:bridge-proxy "$stage" "$stage/config.yaml"
chmod 0700 "$stage"
chmod 0600 "$stage/config.yaml"
printf 'Preserved backup: %s\nStaged login: %s\n' "$backup" "$stage"
runuser -u bridge-proxy -- /opt/bridge/current/cli-proxy-api \
  -config "$stage/config.yaml" -codex-device-login -no-browser
python3 - "$stage/auth" <<'PY'
import os, pathlib, pwd, stat, sys
root = pathlib.Path(sys.argv[1])
owner = pwd.getpwnam('bridge-proxy')
files = list(root.iterdir())
assert len(files) == 1, 'Expected exactly one staged credential; operator review required'
entry = files[0]
info = entry.lstat()
assert stat.S_ISREG(info.st_mode), 'Credential must be a regular file'
assert info.st_uid == owner.pw_uid and info.st_gid == owner.pw_gid
assert stat.S_IMODE(info.st_mode) == 0o600, 'Credential mode must be 0600'
print('One staged credential; ownership and mode verified. Await operator validation.')
PY
BRIDGE_REAUTH
```

The command receives no interactive terminal input: device approval happens in
the owner's browser at the official URL printed by the binary. The owner must
use the intended account. Report only success/failure and the staged directory
path. A nonzero exit is not success even if the browser appeared to complete.
The operator must confirm the original proxy returned healthy after any exit.

## Operator continuation

Keep every backup and the original auth directory. Acquire the maintenance lock
again before credential promotion. Check there is exactly one regular staged
JSON credential, owned by `bridge-proxy:bridge-proxy`, mode 0600, under a 0700
directory. Compare account identity privately against the existing intended
account without printing either identity or credential contents. Count and
ownership alone do not establish account identity or token validity.

Validate the staged credential using an isolated proxy instance and private
config, with only the intended account loaded. Stop that instance before
promotion; avoid concurrent refresh of the same credential. Preserve the live
auth directory in a new root-only backup, stop the production proxy under the
lock, and promote only the verified credential. Retain displaced files in the
backup; never delete them. Restore the preserved credential if verification
fails, while retaining the unsuccessful staged material privately for diagnosis.

Restart the proxy and verify readiness, private listeners, credential ownership
and permissions. Confirm one fresh real provider stream and exact saved replay
with zero additional provider dispatch. Check refresh/reload behavior through
sanitized status evidence; do not claim a refresh occurred without observing it.
Do not deliberately expire or revoke a working token. Record UTC completion,
binary version/hash, success counts and replay evidence in #5, without secrets.
Only then mark reauthentication passed.

## Source and compatibility scope

The official [Codex guide](https://help.router-for.me/configuration/provider/codex)
documents browser OAuth. The pinned [command-line source](https://github.com/router-for-me/CLIProxyAPI/blob/v7.3.16/cmd/server/main.go)
also exposes `-codex-device-login` and `-no-browser`. The pinned
[device-flow implementation](https://github.com/router-for-me/CLIProxyAPI/blob/v7.3.16/sdk/auth/codex_device.go)
uses `https://auth.openai.com/codex/device`, a 15-minute timeout, and saves an
auth record after successful token exchange. It prints a sensitive device code,
which is why the flow belongs in the owner's private terminal.

[7.3.16](https://github.com/router-for-me/CLIProxyAPI/releases/tag/v7.3.16)
is a distinct patch release after the deployed
[7.3.15](https://github.com/router-for-me/CLIProxyAPI/releases/tag/v7.3.15).
The [complete source comparison](https://github.com/router-for-me/CLIProxyAPI/compare/v7.3.15...v7.3.16)
does not change Codex login, token storage, executor, configuration schema, or
Codex translators. It changes shared quota handling, usage/log identifiers and
file-priority handling, among other provider/plugin behavior. Source inspection
finds no Codex credential migration; this is compatibility evidence, not a
publisher rollback guarantee. An upgrade/rollback drill proves only that
component transition. It does not prove Rama/Agent-o-rama history migration or
human reauthentication.
