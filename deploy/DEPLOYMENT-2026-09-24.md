# Private VPS deployment evidence, 2026-09-24

The validated module runs on the existing private VPS with fresh application
state. Complete legacy state and existing backups remain preserved. Streaming,
replay, reboot, bounded fixture load and an isolated working restore passed.
This does not close [deployment issue #5](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/5)
or [end-to-end acceptance #8](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/8).
Cross-artifact/runtime upgrade and rollback and actual human OAuth
reauthentication were not performed. Legacy recovery is not claimed.

## Artifact and host

- Module source: `2435f4181f5a45b63fcc9037d97ba6719fd4f784`; prior validation
  evidence on main: `af5178396f6cd5f6317d313ea639801d7ff8b00a`.
- Exact deployed JAR SHA-256:
  `f3145e3596733590b3487dcd4348f94311f821743b2734fb5801ce2dda10289c`.
- Ubuntu 24.04, Java 21.0.12.1, Rama 1.9.0, Agent-o-rama 0.10.0,
  ZooKeeper 3.9.6, CLIProxyAPI 7.3.15.
- Existing 8 GiB VPS, 7751 MiB usable memory. No new infrastructure was created.

## Preserved old state and fresh boundary

`/var/lib/bridge-preserved-20260924-b03` contains complete old Rama and ZooKeeper
roots, including data, locks, diagnostics and ZooKeeper transaction history.
Its verified `state.tar` is 3,681,105,920 bytes, SHA-256
`7a6e5a5ac05bcd41be02be8ee4ad039c27f3a12db92dac906fedc49158786d93`.
Old lock inode `526255` is retained there; fresh locks use inode `2098247`.
Only bearer/licenses were copied into fresh Rama state. Old and new Rama/ZooKeeper
state domains were not mixed. The existing proxy OAuth root stayed in place.

The prior `/var/tmp/bridge-b02-stopped-20260923/state.tar` remains preserved,
SHA-256 `4573ce1e568555f35dc1f2f0d5236c2dd945faf356cc610d3e9a70e5597894da`.
The latest encrypted legacy-preservation snapshot is
`86136b0ff2f5cbeab10ac2678658df1bfa859edadde35df209bdb5c49ca56c0c`.
Neither archives nor old snapshots were pruned. Private archives contain
credentials and must remain access-restricted.

## Invocation, reboot and service behavior

The initial real-provider warm stream dispatched once. Replay dispatched zero
times, with exact text, chunks and usage equality: input/output/total tokens
`307/5/312`. After an actual reboot, the boot ID changed and all five application
services started automatically. UI needed one startup retry; module metrics
became ready about four minutes later. Cold replay matched the saved stream
without dispatch, and a new stream dispatched once.

A forced proxy failure recovered in 10.22 seconds. These results establish
same-artifact orderly operation and reboot recovery on this host. They do not
establish cross-version persistence compatibility or arbitrary crash recovery.

Public IPv4 and IPv6 probes could not reach ports `22`, `1973`, `1974`, `2181`,
`8888`, `18317`, `18318`, `20000`, `20001`, `21000` or observed ZooKeeper dynamic
port `36761`. Tailnet reachability was limited to `22`, `1974` and `8888`.
A final scan after the update also covered every observed listener: `1973`,
`1974`, `2181`, `8888`, `18317`, `18318`, `20000`, `20002` and `35515`, plus
`22`, `20001` and `21000`. All were blocked over public IPv4 and IPv6; tailnet
access remained limited to `22`, `1974` and `8888`. These are measured probe
results at the time of the drills.

## Measured memory and bounded load

Initial deployment plus warm probes recorded minimum host available memory
3,059,179,520 bytes and maximum summed bridge service cgroups 3,864,662,016 bytes.
A bounded loopback fixture load submitted 60 calls, reached 10 active and 50
queued, completed all 60 successfully and drained both counts to zero. Across
19 samples, minimum available memory was 2,582,388,736 bytes and maximum summed
bridge cgroups 4,118,585,344 bytes. This was not a 60-call real-provider load.
Measurements describe this host and workload; sampled peaks are not a universal
minimum-memory guarantee.


## Same-artifact module replacement

The exact deployed JAR above was resubmitted through `admin.sh ... deploy update`.
The module transitioned from instance `3a5c09e2-425d-ddf8-704f-5280cfe2460d` to
`bea722c2-d859-9f19-5cd9-e84b8e87c146` in 314 seconds. Across 313 memory samples,
minimum host available memory was 1,947,910,144 bytes (1.81 GiB), and maximum
summed bridge cgroups were 5,579,550,720 bytes (5.20 GiB). At most two workers
ran simultaneously; the old worker retired. The final module state was running
and the metrics endpoint returned HTTP 200. Live lock inode `2098247` and
preserved old lock inode `526255` were unchanged.


The post-update cold probe passed: saved text, chunks and usage `307/5/312`
matched exactly, replay dispatched zero times, and a new real-provider stream
dispatched once with usage `307/5/312`. An explicit readiness refresh afterward
reported available/configured/verified all true, reason `ok`. Both UIs and the
metrics target check passed; all six bridge units, including the firewall, were
active. The exact JAR hash was unchanged. An earlier refresh immediately after
proxy failure had reported the model not visible before registry loading;
readiness was verified again after stable startup.

The existing 8 GiB host passed these bounded normal-load and update memory
checks. These sampled measurements do not establish safety for arbitrary
workloads or a smaller host. Replacing an instance with the identical artifact
does not test cross-build/runtime upgrade or rollback compatibility.

## Encrypted backup and working isolated restore

After confirmed Conductor shutdown and absence of service-account processes,
fresh state was encrypted off-host in snapshot
`3921536c71f1b3b48e3bb4a353e98546be26f32cf8531afe3c4094f3d5692727`.
`restic check` passed. `restore --verify` restored 7,138 files/directories,
3.057 GiB, into `/var/tmp/bridge-restore-b03-20260924`.

The [isolated restore harness](RESTORE.md) booted the restored cluster with
network namespace `4026532281`, distinct from host `4026531840`, loopback only
and an empty route table. Live Rama root inode was `2097822`; restored root inode
was `2140519`, also observed through the restored process's `/proc/.../root`.
This checked the running process's actual restored filesystem boundary.

Cold restored replay matched the saved stream and usage with zero dispatches.
A fresh stream against the namespace-local fixture dispatched once, reporting
usage `1/1/2`. It did not access the real provider. All restored processes exited;
SHA-256 manifests of all live files across state, configuration and runtime were
unchanged by the drill. Private target logs remain under
`/var/tmp/bridge-restore-b03-20260924/drill-eUzQhGiw`.
This is a working same-version restore, beyond extraction verification. It does
not test provider account reauthentication or a different artifact/runtime.
