#!/usr/bin/env bash
set -euo pipefail
if [[ ${1:-} != --approved-maintenance || $# -lt 2 ]]; then
  echo 'Explicit maintenance approval required: admin.sh --approved-maintenance COMMAND [ARGS]' >&2
  exit 64
fi
[[ $EUID == 0 && $(uname -s) == Linux ]]
shift
umask 077
exec 9>/run/lock/bridge-maintenance.lock
flock -n 9 || { echo 'Another bridge operation holds the maintenance lock.' >&2; exit 1; }
cmd=$1; shift
rama() { (cd /opt/bridge/current/rama && runuser -u bridge-rama -- ./rama "$@"); }
services=(bridge-ui bridge-proxy bridge-supervisor bridge-conductor bridge-zk)
stop_all() {
  systemctl stop "${services[@]}"
  # KillMode=process deliberately preserves workers on supervisor crashes.
  # After the verified graceful shutdown, explicitly reap any remaining children.
  systemctl kill --kill-whom=all --signal=SIGKILL bridge-supervisor.service 2>/dev/null || true
  for user in bridge-rama bridge-proxy bridge-zk; do
    if pgrep -u "$user" >/dev/null; then echo 'Service processes remain; refusing snapshot or restore.' >&2; exit 1; fi
  done
}
backup_env() {
  [[ $(stat -c %u /etc/bridge/backup.env) == 0 && $(stat -c %a /etc/bridge/backup.env) == 600 ]]
  set -a
  # Root-managed shell assignments, no command-line or logged resolved secrets.
  source /etc/bridge/backup.env
  set +a
  : "${RESTIC_REPOSITORY:?}" "${RESTIC_PASSWORD_FILE:?}"
  [[ $(stat -c %u "$RESTIC_PASSWORD_FILE") == 0 && $(stat -c %a "$RESTIC_PASSWORD_FILE") == 600 ]]
  case "$RESTIC_REPOSITORY" in s3:*|sftp:*) ;; *) echo 'Off-host S3/SFTP repository required.' >&2; exit 1;; esac
}
case "$cmd" in
  start)
    [[ $# == 0 ]]
    systemctl reset-failed "${services[@]}"
    systemctl enable --now bridge-zk bridge-conductor bridge-supervisor bridge-proxy
    # Start UI only after a module has been launched (deploy does this).
    ;;
  deploy)
    [[ $# == 3 && ( $1 == launch || $1 == update ) && $3 =~ ^[a-f0-9]{64}$ ]]
    action=$1; jar=$(realpath -- "$2"); digest=$3
    [[ $(sha256sum "$jar" | cut -d ' ' -f1) == "$digest" ]]
    install -m 0644 "$jar" "/opt/bridge/modules/$digest.jar"
    args=(--action "$action" --jar "/opt/bridge/modules/$digest.jar" --module bridge.deployed/ProxyModule --configOverrides /etc/bridge/worker.yaml)
    if [[ $action == launch ]]; then args+=(--tasks 4 --threads 2 --workers 1 --replicationFactor 1); fi
    rama deploy "${args[@]}"
    ln -s "modules/$digest.jar" /opt/bridge/module.jar.next
    mv -Tf /opt/bridge/module.jar.next /opt/bridge/module.jar
    systemctl reset-failed bridge-ui
    systemctl enable bridge-ui
    systemctl restart bridge-ui
    ;;
  shutdown)
    [[ $# == 0 ]]
    systemctl stop bridge-ui
    rama shutdownCluster
    echo 'Wait for Conductor UI state [:cluster-shutdown-complete], then run backup with confirmation.'
    ;;
  backup)
    [[ $# == 1 && $1 == --confirmed-cluster-shutdown ]]
    backup_env
    stop_all
    restic backup --quiet --tag bridge /var/lib/bridge-rama /var/lib/bridge-zk /var/lib/bridge-proxy /etc/bridge /opt/bridge /etc/systemd/system/bridge-*.service
    restic check --quiet
    echo 'Encrypted backup complete. Services remain stopped. Explicit start after review.'
    ;;
  restore-drill)
    [[ $# == 2 && $1 =~ ^[a-f0-9]{8,64}$ && $2 =~ ^/var/tmp/bridge-restore-[A-Za-z0-9._-]+$ && ! -e $2 && ! -L $2 ]]
    snapshot=$1; target=$2
    backup_env
    mkdir -m 0700 "$target"
    restic restore "$snapshot" --target "$target" --verify --quiet
    echo 'Restored into isolated directory. No service started; do not run duplicate workers against production.'
    ;;
  *) echo 'Commands: start, deploy, shutdown, backup, restore-drill' >&2; exit 64;;
esac
