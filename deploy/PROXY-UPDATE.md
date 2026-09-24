# Proxy-only release maintenance

`proxy_release.py` stages a checksum-pinned CLIProxyAPI binary and switches only
`bridge-proxy.service`. Rama, ZooKeeper, the module JAR, state, credentials,
firewall and backups are unchanged. The replacement release links the exact
preserved Rama/ZooKeeper directories. Keep both release directories permanently
while either is referenced. This workflow does not establish compatibility of
Rama, Agent-o-rama or module upgrades.

Run as Linux root on the existing host. Both operations require the explicit
maintenance flag and acquire `/run/lock/bridge-maintenance.lock`, shared with
backup/restore operations. There is deliberately no alternate-root CLI option.
Use reviewed release basenames and independently verified SHA-256 values below;
never infer a version from an arbitrary binary name. The archive must contain one
regular `cli-proxy-api` member. Other members are ignored, never extracted.

1. Check official release notes/source for config, OAuth and request compatibility.
   Download the official archive, verify its published archive checksum, and
   independently record its executable checksum. Record the installed executable
   checksum and exact current symlink target. Preserve the working release and
   take a verified private backup using the normal maintenance path.
2. Stage a new release, without stopping any service or switching current:

   ```sh
   python3 deploy/proxy_release.py stage --approved-maintenance \
     --expected-current "$old_release" --target "$new_release" \
     --current-sha256 "$old_binary_sha256" --target-sha256 "$new_binary_sha256" \
     --archive "$official_archive" --archive-sha256 "$published_archive_sha256"
   ```

   This writes `proxy-release.json` containing hashes and retained runtime paths.
   It refuses an existing destination and identical executable hashes. An
   interrupted stage may leave an incomplete destination; it never changes
   current. Inspect it and use a fresh release basename for a retry.
3. Complete the isolated restored-state compatibility drill with a fake upstream
   before live switching. Verify streaming, saved replay, readiness, state and
   lock identity. Keep external routes disabled in that drill.
4. Pause every client that can submit work. Verify active and queued calls have
   drained to zero using the authenticated adapter metrics. The helper cannot
   establish submission quiescence itself. `--clients-quiesced` records the
   operator's assertion of this checkpoint, not an automatic metrics check.
5. Switch, leaving Rama/ZooKeeper and their workers running:

   ```sh
   python3 deploy/proxy_release.py switch --approved-maintenance --clients-quiesced \
     --expected-current "$old_release" --target "$new_release" \
     --current-sha256 "$old_binary_sha256" --target-sha256 "$new_binary_sha256"
   ```

   Preflight checks both binary hashes, directory ownership/permissions, current
   target, and identical resolved runtime directories. The helper stops the
   proxy, checks its systemd state and absence of real/effective proxy-UID
   processes, then atomically replaces the symlink. It checks service activity
   over five seconds and its loopback listener. These checks are not proof of
   provider authentication: run the authenticated readiness, real stream and
   saved replay probes before resuming clients.
6. For the rollback drill, keep clients paused and reverse the exact release and
   binary hash arguments:

   ```sh
   python3 deploy/proxy_release.py switch --approved-maintenance --clients-quiesced \
     --expected-current "$new_release" --target "$old_release" \
     --current-sha256 "$new_binary_sha256" --target-sha256 "$old_binary_sha256"
   ```

   Repeat the provider/readiness/replay probes and compare state, history, lock
   identity, firewall rules and backup checks. Same-binary switching is refused.

A failed candidate start triggers stop/process verification, atomic restoration
of the original release, and restart of the original proxy. If stop or recovery
fails, the helper raises an error; inspect service state and the current symlink
before any retry. It never switches under detected live proxy processes. A host
crash or SIGKILL can interrupt any maintenance; the symlink remains either old or
new, and the normal private operational recovery checkpoint still applies.

OAuth reauthentication is a separate account-owner checkpoint. This helper
never starts a login, deletes credentials, or claims that a healthy existing
login proves reauthentication.
