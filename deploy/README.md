# Private single-host deployment preparation

[Issue #5 remains open](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/5).
These are reviewable installation artifacts, not an accepted production deployment.
Local preparation and validation ran on macOS. No production login or target
Linux service/firewall validation was performed during local validation. Production approval, device login,
network isolation, memory/update, reboot and recovery acceptance remain in #5;
[the complete acceptance story is #8](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/8).

## Payload and trust

`prepare.py CACHE` downloads only public release artifacts and verifies their
pinned digests in `artifacts.json`. It does not unpack or run anything. Rama
1.9.0's SHA-256 was measured from the official ZIP on 2026-09-23; this is a local
integrity pin, not an independently signed publisher checksum. CLIProxyAPI
7.3.15 Linux amd64 and ZooKeeper 3.9.6 match publisher checksum files.
ZooKeeper 3.9.6 fixes security issues affecting earlier 3.9.x releases; this
changes the server only, not Rama's embedded client libraries. Actual
server/client integration remains a Linux acceptance gate.

`mvn -f deploy/pom.xml package` builds the non-AOT Clojure module uberjar at
`deploy/target/bridge-jar-with-dependencies.jar`. Rama is `provided`, never bundled.
AOR 0.10.0, Clojure 1.12.4 and the official LC4j OpenAI provider
1.18.1-beta28 match the tested IPC dependencies. Build on a trusted machine;
record the source commit, JAR SHA-256, Maven dependency tree and target OS package
versions with private operator evidence. Dependencies are version-pinned; Maven
transitive artifacts are not claimed to have an independent signed lockfile.

The actual Rama distribution puts `jackson-datatype-jdk8` 2.10.2 ahead of module
jars (its Jackson core/databind are 2.18.2). An
unmodified bundle failed a real fake-provider invocation because OpenAI SDK
4.41.0 requires Jackson 2.13.4 or newer. `openai-isolated/pom.xml` packages the
SDK and its Jackson 2.18.2 dependencies with a private Jackson namespace. The
main module excludes the original SDK dependency. AOR/Clojure sources remain
unrelocated, and Rama's shipped libraries are untouched. The isolated SDK merges
service-provider descriptors. This additional packaging is checked using the
actual Rama distribution classpath, not just the Clojure dependency classpath.

The root-owned runtime payload is in `/opt/bridge/releases/`, with `current`
pointing at the pinned three-runtime release. Content-addressed submitted jars
live in `/opt/bridge/modules/`; `module.jar` points to the last successful deploy.
Installer refuses an existing `/opt/bridge`, `/etc/bridge`, service account or
`inet bridge` firewall table. It never imports a local OAuth credential.

## Host boundary

Use a dedicated x86_64 Linux system with systemd, cgroup process tracking,
Python 3, supported Java 21, `nft`, `restic`, `tailscale`, `unzip`, `tar`, `flock`,
`openssl` and ordinary account utilities already installed. Ubuntu 24.04 supplies
Java 21; Debian 12 needs a separately approved Java 21 source. Patch OS tools from
supported repositories and record exact installed versions. These host package
versions have not been tested or frozen here. No script installs OS packages. On the approved Ubuntu 24.04 host, the OS
prerequisites can be installed with `sudo apt-get update` followed by
`sudo apt-get install openjdk-21-jre-headless python3 nftables restic unzip
openssl ca-certificates procps util-linux`. Install Tailscale separately using
its [official Linux instructions](https://tailscale.com/kb/1031/install-linux)
and complete the human device login before running the host installer.

Before installation, approve cost/region, arrange provider rescue-console access,
complete Tailscale device login, and verify a second SSH session over Tailscale.
Review `config/firewall.nft` together with the host's existing nftables rules.
It adds its own table without flushing existing policy. Its drop policy applies
to IPv4 and IPv6; it permits established traffic, loopback, ICMP, DHCP and
Tailscale UDP 41641. Only TCP 22/1974/8888 is admitted on `tailscale0`. Tailscale's
own access policy must restrict the approved administrator identities/devices;
this repository does not modify that policy. Existing public SSH sessions can
survive via established traffic, but new public SSH connections are denied.
Do not install this policy on a shared host.

Rama's `conductor.host` and `supervisor.host` advertise addresses; they do not
constrain socket bindings. AOR0.10.0's UI also ignores its documented `:host`
option. Therefore **the firewall is mandatory**, and every application unit
requires it before start. AOR UI 1974 and Rama UI 8888 may listen on wildcard
addresses behind that boundary. ZooKeeper 2181, CLIProxyAPI 18317 and observer 18318
bind numeric loopback. Foreign clients run on the VPS, reached via Tailscale SSH.
Metrics can be read through an SSH local port forward; they are not tailnet-wide.
Verify `nft list ruleset`, `ss -lntup`, public IPv4/IPv6 scans and tailnet scans
before using credentials. Do not remove firewall protection while services run.

Dedicated users: `bridge-rama` owns cluster state, UI/observer and module workers;
`bridge-zk` owns ZooKeeper; `bridge-proxy` owns the provider OAuth state.
Directories with state/credentials use 0700, bearer/config files 0600, and service
umask 0077. Root-owned runtime code is readable but never writable by these users.
Rama's writable logs are symlinked to `/var/log/bridge-rama`. Raw application
logging is suppressed because provider exceptions can contain bodies; systemd
retains unit lifecycle status but service stdout/stderr is discarded. Do not
turn raw logs on without reviewing their contents and retention.

The permanent `/var/lib/bridge-rama/locks` is shared by all workers and module
versions. Never recreate, rotate, clean or relocate it while any worker can run.
`PrivateTmp` does not affect this directory. The persisted lock domain, PStates,
depots and traces must remain consistent across updates and restores. One-hour
replay expiry does not erase durable depot, trace or backup history.

## Approved installation and launch

These commands mutate the approved VPS only. The explicit flags record an
operator checkpoint; they are not evidence that user approval was obtained.
No CI or push hook runs them.

```sh
# Trusted build machine, no production mutation:
python3 deploy/prepare.py /absolute/artifact-cache
mvn -f deploy/pom.xml package
shasum -a 256 deploy/target/bridge-jar-with-dependencies.jar

# Approved dedicated host, after verified upload of repository/artifacts/JAR:
sudo bash deploy/install.sh --approved-install /absolute/artifact-cache /absolute/bridge.jar JAR_SHA256
```

Installation starts only the firewall. Before starting the proxy, a human runs
the pinned provider device flow in an interactive private terminal:

```sh
sudo -u bridge-proxy sh -c 'umask 077; exec /opt/bridge/current/cli-proxy-api -config /var/lib/bridge-proxy/config.yaml -codex-device-login'
```

Keep the device code, auth output and JSON files out of tickets, recordings and
logs. Do not copy existing workstation credentials. Confirm auth files are 0600,
owned by `bridge-proxy`, and exactly the intended Codex account is configured.
Inspect the installed binary help before login; the flag matches the pinned
source used by the successful local spike. Reauthentication uses the same
interactive command and auth directory during an approved maintenance window.

```sh
sudo /opt/bridge/admin.sh --approved-maintenance start
sudo /opt/bridge/admin.sh --approved-maintenance deploy launch /absolute/bridge.jar JAR_SHA256
```

Wait for healthy ZooKeeper/Conductor/Supervisor before deploy. The CLI submits
`bridge.deployed/ProxyModule`, whose actual Rama module name is
`bridge.module/ProxyModule`. Initial parallelism is 4 tasks, 2 threads, 1 worker and
replication factor1. The UI/observer starts after the first successful deploy and
is enabled for reboot. Services have five starts per five minutes with ten-second
restart delay; a prolonged startup failure requires operator inspection and
`systemctl reset-failed`. Boot ordering is not an application readiness test.

The observer reads durable admission state with a bounded asynchronous selector.
`GET /ready` and `GET /metrics` never create provider traffic. Explicit
`POST http://127.0.0.1:18318/refresh` consumes quota and tests model visibility plus
one small completion. Run the existing live acceptance harness against the target
through an approved invocation client before declaring streaming/reboot healthy;
the local IPC harness alone cannot establish production acceptance.

## Memory and service failure

Configured maximum Java heaps: Conductor 512MiB, Supervisor 256MiB, worker 2048MiB,
UI/observer 768MiB, ZooKeeper 256MiB. Heap sum is 3.75GiB normally, 5.75GiB while two
worker versions overlap. Add native/direct memory, thread stacks, mmap/page cache,
proxy, OS and build/deploy client overhead. These are limits/estimates, not RSS
measurements or a sizing guarantee. No host-wide MemoryMax is fabricated here.
Measure full cgroup and host peaks under 10 active/50 queued calls and a module
update, including failure and restart, before choosing the smallest safe plan.
A swap policy is an explicit host decision; swap is not proof of safe latency.

Supervisor `KillMode=process` preserves running workers if its main process dies,
matching Rama's recommendation. Normal unit stop alone therefore is insufficient
for backups. The backup path explicitly checks for surviving service-user
processes and aborts. A failed shutdown or surviving worker requires diagnosis;
never snapshot active state just to get past the gate.

## Encrypted backup and isolated restore

Choose an off-host S3 or SFTP repository, initialize it with `restic init`, and
create `/etc/bridge/backup.env` root-owned 0600 with shell assignments such as
`RESTIC_REPOSITORY`, `RESTIC_PASSWORD_FILE`, and the required backend credentials.
The password file must also be root-owned 0600. Keep a separate offline recovery
copy of the password and repository credentials. Never pass resolved secrets as
arguments. Backup credentials must permit only the intended repository. No target
or credentials are filled in automatically and no destructive retention policy
is installed.

Free Rama requires an application-consistent maintenance window:

```sh
sudo /opt/bridge/admin.sh --approved-maintenance shutdown
# Human verifies Rama Conductor UI state exactly [:cluster-shutdown-complete].
sudo /opt/bridge/admin.sh --approved-maintenance backup --confirmed-cluster-shutdown
```

Stop new clients before shutdown. The confirmation is a required human observation,
not a machine-verified state in this script. Backup then stops UI/proxy/all Rama
services and ZooKeeper, verifies no process remains for any service user, and
backs up ZooKeeper snapshots **and transaction logs**, complete Rama state,
permanent lock directory, OAuth state, configuration, systemd units and pinned runtime/jars using
restic encryption. A stopped ZooKeeper's on-disk snapshot+logs form the recoverable
state; this does not call Rama's paid online backup feature. `restic check` follows.
Services remain stopped on success or failure. After review, start core services
with `admin.sh ... start`, wait for cluster recovery, then `systemctl start bridge-ui`.

Use a concrete snapshot ID returned by `restic snapshots`:

```sh
sudo /opt/bridge/admin.sh --approved-maintenance restore-drill SNAPSHOT_ID /var/tmp/bridge-restore-UNIQUE
```

This restores and verifies into a new 0700 directory and starts nothing. It is only
an extraction check. Full recovery must be tested on an isolated host with no
route to the live cluster and no provider access until explicitly approved:
install the same runtime/OS prerequisites; create the three accounts with the
original numeric UID/GID recorded in `/etc/bridge/accounts.json`; keep all bridge units stopped; restore exact
`/var/lib/bridge-*`, `/etc/bridge` and `/opt/bridge` paths from that verified
snapshot; restore root-owned service unit files from the backup; verify numeric ownership and 0700/0600 permissions; inspect symlink targets
and lock path; apply the reviewed firewall before starting services. Do not
combine ZooKeeper data from one snapshot with Rama data from another. Never start
a restored second cluster against production ZooKeeper or the original account.

Resume only on the same Rama version as the backup. Confirm UI state, durable
replay/metrics and a streamed invocation before directing clients to recovered
service. Recovery rewinds state: requests completed after the snapshot may run
again and bill again. Restored OAuth may require human reauthentication. Local
extraction, remote upload, target restart and account recovery are separate tests.

## Pinned updates and rollback

For a code-only update on unchanged Rama/AOR/proxy pins, build a new JAR, preserve
its hash and the previous hash, take the verified maintenance backup, then resume
the cluster and submit:

```sh
sudo /opt/bridge/admin.sh --approved-maintenance deploy update /absolute/new-bridge.jar NEW_SHA256
```

The command always reapplies `worker.yaml` and never changes the lock path. It
switches the UI JAR only after Rama accepts the deployment. Wait for the module
transition to finish and validate memory/stream/replay before considering the
update successful. A command return alone is not this acceptance gate.
For a code rollback, use the same update command with the preserved prior JAR and
hash **only if its state schema is backward-compatible**. Otherwise restore the
whole consistent pre-update snapshot in an approved outage. Do not destroy and
relaunch the module to roll back.

Runtime upgrades require new reviewed pins/checksums and matching clients, modules
and release directories. This installer refuses to overwrite a host; it is not a
runtime upgrade engine. Follow Rama's documented atomic-version procedure and
same-version restore rule, preserve the previous whole release and full snapshot,
and do not point `current` at a new major/minor runtime while old workers run.
That production upgrade/rollback drill remains unperformed under #5.

## Sources and local verification

Official sources checked 2026-09-23:
[Rama operation and Clojure CLI](https://redplanetlabs.com/docs/~/operating-rama.html),
[Rama free-license backup](https://redplanetlabs.com/docs/~/backups.html),
[Rama downloads](https://redplanetlabs.com/download),
[ZooKeeper releases](https://zookeeper.apache.org/releases/),
[ZooKeeper security](https://zookeeper.apache.org/security/),
[CLIProxyAPI7.3.15](https://github.com/router-for-me/CLIProxyAPI/releases/tag/v7.3.15),
[AOR0.10.0 source](https://github.com/redplanetlabs/agent-o-rama/tree/2d94b569333abc081c9bf8c7aa835dd0c4f38182).

Local checks: approval refusal before host commands; corrupt checksum rejection;
strict private runtime configuration; shell syntax; Maven packaging; module
construction using actual Rama 1.9.0 distribution plus packaged JAR. Target nft
syntax/kernel rules, systemd sandbox/process behavior, Linux amd64 binary startup,
real ZooKeeper/Rama cluster interoperability, remote encrypted backup/restore,
reboot, OAuth and normal/update memory remain unperformed. macOS has no running
Linux VM or systemd tooling; no VM was installed to imply these passed.

Reproduce local checks from the repository root:

```sh
python3 -m unittest discover -s deploy/tests -v
python3 -m unittest discover -s ipc -p 'test_*.py' -v
python3 -m unittest discover -s spike -v
bash -n deploy/install.sh deploy/admin.sh
clj-paren-repair ipc/src/bridge/runtime.clj ipc/src/bridge/deployed.clj ipc/test/bridge/runtime_test.clj ipc/test/bridge/ipc_test.clj
clj-kondo --lint ipc/src/bridge/runtime.clj ipc/src/bridge/deployed.clj ipc/test/bridge/runtime_test.clj ipc/test/bridge/ipc_test.clj
clojure -M:test
mvn -q -f deploy/pom.xml package
# After checksum verification, unpack Rama into a private temporary directory.
# RAMA_DIST below denotes that directory; this starts only loopback fake/IPC tests.
java -Xss6m -Xmx4g -Djdk.attach.allowAttachSelf \
  -cp "$RAMA_DIST/rama.jar:$RAMA_DIST/lib/*:deploy/target/bridge-jar-with-dependencies.jar:ipc/test" \
  clojure.main -m bridge.ipc-test
```

Run JVM suites serially. The runtime tests temporarily bind loopback port 18318; ensure it is unused
before running. UI startup failure is injected at the external library boundary
to avoid starting its wildcard listener on the developer machine. They generate no provider traffic.
The existing conflict regression has a one-second result deadline. It failed
in both an overlapping source-suite run and a serial packaged-suite run, while
an isolated rerun passed. Concurrency alone has not been established as the cause.
Keep those failures in the validation record; a passing narrow rerun is not a
passing full suite.

Validation record, 2026-09-23:

- Final serial source suite: **39 tests, 548 assertions, zero failures/errors**.
- New runtime namespace: **3 tests, 12 assertions**, including real IPC invocation
  and HTTP metrics plus cleanup after an injected external UI startup failure.
- Actual-distribution packaged invocation/observer check before the cleanup slice:
  **2 tests, 10 assertions**, zero failures/errors.
- Actual-distribution packaged full suite: **39 tests, 548 assertions, one failure**
  in the existing one-second in-flight conflict result assertion, zero errors.
  All other assertions passed. An earlier overlapping source full run had the same
  failure; its isolated unchanged rerun passed all three assertions. The final
  serial source full run passed. Cause remains unresolved and is tracked in
  [issue #14](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/14); the assertion is still
  one second and now reports the caught exception class for future diagnosis.
  This record does **not** claim a passing full packaged suite.
- Seven Python tests passed (two deployment guards/checksums, two IPC evidence
  schema checks, three direct-spike regressions); shell syntax, Clojure repair,
  lint and `git diff --check` passed.
- All three downloaded public release archives matched their pinned checksums.
  Maven built the module JAR; content checks confirmed isolated SDK Jackson and
  exclusion of the provided Rama implementation.
- No target Linux, production account, remote backup or VPS acceptance test was
  performed by this local validation run. No real provider request was made.
