# Private VPS deployment evidence, 2026-09-24

The validated module runs on the existing private VPS with fresh application
state. Complete legacy state and existing backups remain preserved. Streaming,
replay, reboot, bounded fixture load and an isolated working restore passed.
The later proxy upgrade/rollback and owner-confirmed reauthentication below
complete [deployment issue #5](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/5);
[end-to-end acceptance #8](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/8) remains separate.
This validates a distinct proxy patch transition, not a rebuilt application JAR
or Rama/Agent-o-rama upgrade. Legacy recovery is not claimed.

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


## Distinct pinned proxy upgrade and rollback

The later same-day drill upgraded the real CLIProxyAPI binary from 7.3.15 to
7.3.16, then rolled back to 7.3.15. Production remains on 7.3.15 after the
successful rollback. Both releases remain under `/opt/bridge/releases/`.
Rama 1.9.0, AOR 0.10.0, ZooKeeper 3.9.6 and the exact module JAR above never
changed. The new release links the original Rama/ZooKeeper directories.

Official [release 7.3.16](https://github.com/router-for-me/CLIProxyAPI/releases/tag/v7.3.16)
and the [complete source comparison](https://github.com/router-for-me/CLIProxyAPI/compare/v7.3.15...v7.3.16)
were inspected before mutation. Codex login/token storage, executor, translators
and configuration schema are unchanged; shared quota, priority and logging
behavior does change. No credential migration was found in that comparison.
This source evidence supported the bounded drill; it is not a publisher guarantee
of arbitrary backward compatibility.

| Pin | 7.3.15 baseline/rollback | 7.3.16 candidate |
| --- | --- | --- |
| Source commit | `673131f57484517c3a1eae7e36c4cfa7b9bb4efc` | `c404af96ebacedf8168b3c2bdbf4449a21cd1c1e` |
| Official Linux amd64 archive SHA-256 | `801c3a23061d57a830e67fcd033fda26e96c2bfe93e1b2e34e4428ed7defc7e5` | `64f84d7a08570f8e5310707857bc9edfb032292a9753b5f840da6c8caa325a72` |
| Executable SHA-256 | `370d68b028b2906493eee0cc37562f295c74cc188e8114373776ee52d31f459b` | `3e5ef2dd28c008ca3d6dc2e10bbeb6d880e83d0deea59e07d4e9050c8fe22abb` |

Both downloaded archives matched publisher checksums and GitHub asset digests.
The actual running executable, through `/proc/PID/exe`, matched the candidate
hash after upgrade and the baseline hash after rollback.

Before switching, normal graceful shutdown reached `cluster-shutdown-complete`
on Conductor port 8888. A new application-consistent encrypted snapshot,
`c4cc8739cd90d6b6750ca4ae8e9ec10c43666bc578bbc0a7091eeb50b0da7787`,
was created at 2026-09-24T16:15:10Z; `restic check` passed. The stack restarted
on its retained state. Initial UI startup retried once while the cluster opened;
module metrics and both UIs subsequently passed before the live drill.

Isolated component checks preceded live switching:

- The real old/new/old binaries each started twice in network namespace
  `4026532292`, distinct from host `4026531840`, with only loopback and no
  external routes. Six fixture streams passed, each with two ordered content
  chunks, usage 7/2/9 and exactly one fixture dispatch. No real account was used
  in this fixture configuration.
- A private copy of OAuth/configuration from the verified prior restored snapshot
  booted on old/new/old. All three exposed the same 13-model inventory; copied
  credential bytes and source manifests remained unchanged. No provider request
  or login was possible in this namespace. An initial empty-registry startup
  race exposed a harness readiness defect; the corrected bounded wait passed.
- These are proxy component checks. The earlier whole-cluster same-version
  restore above is separate evidence; no different Rama/JAR restore is claimed.

The [guarded release helper](PROXY-UPDATE.md) staged the candidate, checked exact
hashes and unchanged runtime paths, and switched `current` atomically after
stopping the proxy and checking that no proxy-account process remained. Client
submissions were paused and active/queued gauges were zero before each switch.
The successful live sequence was:

| Stage | Saved replay | Fresh stream | Readiness refresh |
| --- | --- | --- | --- |
| Baseline 7.3.15 after owner login | Exact match, zero extra dispatches | One dispatch | available/configured/verified true |
| Upgrade 7.3.16 | Exact baseline text/chunks/usage, zero dispatches | One dispatch | available/configured/verified true |
| Rollback 7.3.15 | Exact baseline text/chunks/usage, zero dispatches | One dispatch | available/configured/verified true |

Every fresh stream reported usage 307/5/312; saved replay retained that exact
usage. Probes used `deploy/probe.clj` and private record
`/var/lib/bridge-rama/probe-b04/record.edn` within its one-hour TTL.
Readiness's separate small provider completion is not an adapter dispatch.

Across both switches, non-proxy service PIDs remained unchanged, the live and
preserved lock inodes stayed 2098247 and 526255, and the original runtime,
configuration and live OAuth byte manifests were unchanged. Durable application
history was retained, with saved replay checked above. All six units are active.
The original module digest is still
`f3145e3596733590b3487dcd4348f94311f821743b2734fb5801ce2dda10289c`.

Final public IPv4/IPv6 probes could not reach any tested current listener or
boundary port: 22, 53, 1973, 1974, 2181, 5432, 8785, 8888, 18317, 18318,
20000, 20001, 20002, 20241, 21000, 35515, 37117, 56746 and 40012.
Bridge access over Tailscale remained limited to SSH and the two UIs. Tailscale's
own daemon listener 56746 was also tailnet-reachable; it is not a bridge service.
Firewall structure, ignoring only traffic counters, was unchanged.

All five encrypted snapshots remain, including the legacy and fresh snapshots
listed above. Both preserved archives remain mode 0600 with their recorded byte
sizes. No state, old release, credential, archive or backup was deleted.
Private drill artifacts remain in `/var/tmp/bridge-b04-evidence` and
`/var/tmp/bridge-proxy-drill-b04`; all isolated test processes exited.

## Owner-confirmed OAuth reauthentication

The account owner explicitly confirmed completing a fresh Codex device login on
the VPS during this task. The agent did not observe or capture the browser flow,
device code, tokens or auth JSON. No additional login flow was launched.
The current credential differs from the prior restored snapshot, matches the
same intended account on two privately compared identity fields, and remains
one service-owned 0600 file in the private auth directory.

The baseline live stream/replay and explicit readiness refresh above validate the
resulting login, followed by successful upgrade and rollback provider checks.
Owner attestation plus these results complete the reauthentication checkpoint;
a merely healthy pre-existing credential alone would not do so. The
[private reauthentication runbook](REAUTH.md) remains available for future use.
No deliberate expiration, revocation or forced refresh failure was performed.

## Batch validation and acceptance scope

The portable deployment suite ran 34 tests: 31 passed and three expected host skips;
the five existing IPC/spike Python tests and shell syntax checks passed.
On the target, four checks passed with one uninstalled-host-only skip, including
real nft parser validation, unchanged rules and both UIs/durable module metrics.
The unchanged application JAR did not require another expensive packaged suite.

TDD evidence covers rejected archive checksums without mutation, staged releases
preserving runtime/state, failed-start recovery restoring the prior release,
process/stale-target guards, isolated fixture protocol and copied OAuth bootstrap.
The empty-model startup regression first reproduced the actual failure, then
passed with a bounded retry. Failed-candidate service recovery is unit-tested;
the live rollback was an intentional successful-version rollback.

Together with the earlier host, reboot, memory/load and working-restore evidence,
this satisfies #5's original deployment criteria. The distinct pinned transition
is specifically CLIProxyAPI 7.3.15 -> 7.3.16 -> 7.3.15. It does not establish
rebuilt application bytecode, Rama/AOR upgrades, schema migrations, arbitrary
crash recovery, or legacy-history recovery. #8's full end-to-end acceptance and
#1's parent acceptance remain separate.
