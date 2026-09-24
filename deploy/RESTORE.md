# Same-host isolated restore drill

`isolated_restore.sh` boots an extracted, same-version snapshot in Linux mount,
network and PID namespaces. It never starts systemd services or CLIProxyAPI.
All networking is loopback within the new namespace. The restored ZooKeeper,
Rama state and permanent locks retain their original absolute paths together.
This tests the copied state, never legacy state mixed into the live cluster.

First use `admin.sh ... restore-drill SNAPSHOT_ID TARGET` to extract and verify an
encrypted off-host snapshot into a new root-owned 0700
`/var/tmp/bridge-restore-NAME` directory. Use a complete stopped-state snapshot
with the exact pinned runtime and module JAR; verify their hashes separately.
Do not use this harness with an untrusted backup. Config and binaries execute
with the restored service identities, so the snapshot remains trusted code.
No arbitrary archive can be made safe merely by validating its paths.

Before running, review available memory. The restored ZooKeeper, conductor,
supervisor, worker and observer can approach another 4 GiB of Java heap plus
native memory. The operator may stop live services in an approved maintenance
window to avoid overlapping memory demand. This script neither stops nor
restarts live services and holds the normal maintenance lock throughout.

Copy a reviewed probe wrapper, `probe.clj` and its private warm record into the
extracted `var/lib/bridge-rama/drill-input`, owned by `bridge-rama`, directory
0700 and record 0600. Probe inputs must be inside a mounted restored root:
`/tmp`, `/var/tmp` and `/run` are hidden by private tmpfs mounts. For example:

```sh
sudo bash deploy/isolated_restore.sh --approved-isolated-restore --plan \
  /var/tmp/bridge-restore-NAME -- \
  /bin/bash /var/lib/bridge-rama/drill-input/run-probe.sh
sudo bash deploy/isolated_restore.sh --approved-isolated-restore \
  /var/tmp/bridge-restore-NAME -- \
  /bin/bash /var/lib/bridge-rama/drill-input/run-probe.sh
```

The wrapper runs as `bridge-rama` with a clean environment and a working
directory of `/opt/bridge/current/rama`. ZooKeeper, conductor, supervisor and
UI/observer are already starting; the harness waits for readable module metrics.
The wrapper must assert restored replay using `probe.clj replay`, the unchanged
warm call ID and a record younger than the one-hour replay TTL. This verifies
output/chunks/usage equality and zero new upstream dispatches. For a new stream,
the wrapper must first start a bounded loopback OpenAI-compatible fixture on
18317 inside the namespace, then run `probe.clj cold`. No real provider is
reachable, and OAuth login is deliberately outside this drill.

Success means the supplied command exited zero, not an automatic assertion that
every recovery acceptance criterion passed. Review the wrapper and evidence.
The harness has a 900-second deadline, terminates all namespace descendants and
waits on exit. Kernel teardown also kills descendants if namespace PID 1 exits.
Private logs remain under `TARGET/drill-XXXXXXXX/bridge-rama` and `bridge-zk`;
probe output is `bridge-rama/probe.log`. Raw logs may contain private data.
The restored copy changes during the drill and remains preserved afterward.
Use a new extraction for another pristine restore test. Nothing is deleted or
pruned. The live application roots, credentials and backups are untouched.

Portable tests cover approval refusal, invalid target/command refusal and the
non-executing plan. They do not validate Linux namespace behavior. A real target
driver must record snapshot ID, JAR digest, replay/new-stream assertions,
namespace cleanup and absence of changes to live roots before claiming recovery.
Acceptance evidence and remaining operational gates belong in
[issue #5](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/5).
