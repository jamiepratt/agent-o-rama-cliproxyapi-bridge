# Final acceptance evidence, 2026-09-24

Tracking: [acceptance #8](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/8)
and [parent #1](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/1).
All acceptance gates passed, including the final owner decisions and post-plan
private-access checks below. Contract limits remain explicit.

## Evidence boundaries

The [composed fixture acceptance](../ipc/PROXY-ACCEPTANCE.md) places the real
pinned CLIProxyAPI between the packaged adapter and a counting fake provider.
Its counts are HTTP requests received by that fake external provider. The
[authenticated live evidence](../ipc/adapter-evidence.json) instead counts HTTP
requests entering CLIProxyAPI. Production metrics count adapter dispatch
reservations. None measures hidden real-provider executions or billed attempts.

The [replay contract](../ipc/REPLAY.md) retains completed responses for one hour.
The tested forced retry occurs after durable completion. Crashes before durable
completion, lost worker completion writes and expired replay can cause another
request. Tool effects are not deduplicated. Subscription closure stops callbacks;
explicit logical cancellation finishes waiters but retains active capacity until
transport termination. Neither promises upstream termination or billing cessation.
These limits were already recorded in #2, #3 and #6, and the #1/#8 comments.

The [observability contract](../ipc/OBSERVABILITY.md) maps every required metric
to real IPC scrape tests, including success, replay, conflict, coalescing,
overload, errors, timeout and FIFO gauges. Readiness tests cover failed visibility,
authentication, malformed responses, deadlines and expiry. Labels are bounded.
Readiness probes consume separate provider traffic and do not increment adapter
dispatch counters. Prior complete packaged validation was 46 tests / 628
assertions on the unchanged artifact; it is historical evidence, not a new run.
Fresh composed validation passed **14 tests / 374 assertions**, zero failures or
errors, using that exact packaged artifact. The runner adds final provider-count
assertions after each case, including completed duplicates. The five IPC/spike
Python regressions passed; deployment regressions passed 31 tests with three
expected host-only skips. The new Clojure harness passed repair then lint before
its targeted run. No product source or production artifact was rebuilt.

## Fresh production and security checks

The deployed JAR still hashes to
`f3145e3596733590b3487dcd4348f94311f821743b2734fb5801ce2dda10289c`.
All six bridge units were active. A fresh `probe.clj warm` invocation through
the deployed public Agent-o-rama API returned one ordered chunk, usage
307/5/312, one new adapter dispatch and zero replay dispatches. Replay matched
text, chunks and usage exactly. Explicit readiness refresh reported
available/configured/verified true, reason `ok`. Active and queued gauges drained
to zero. A later read, more than five minutes after refresh, correctly reported
`verified: false`, reason `expired`, while the six units remained active and
active/queued gauges stayed zero. Cached verification expiry is the documented
TTL behavior, not a newly observed provider failure. The private probe record
is retained on the host; no response content
is included here.

Fresh external TCP connection probes tested all observed listener ports plus
the existing boundary ports: 22, 53, 1973, 1974, 2181, 5432, 8785, 8888,
18317, 18318, 20000, 20001, 20002, 20241, 21000, 37117, 40012 and 56746.
All 18 were unreachable over both public IPv4 and IPv6. This is a measured
point-in-time connection test, not a claim about every possible port or protocol.
The mandatory firewall remained active. The separate tailscaled listener is
not an application endpoint.

Credential-pattern scanning of all 162 reachable Git history blobs found no
private-key, OpenAI project-key, GitHub-token or JWT pattern matches. A private
comparison of current credential/token values against today's bridge service
journal found zero matches across 21,915 bytes; two known probe prompt strings
also had zero matches. Only counts were returned. The one live OAuth file and
Rama bearer remained mode 0600. Proxy commercial mode is enabled, request/file
logging disabled, stdout/stderr discarded, and its state root contained no
`.log` files. These bounded checks do not prove absence of every unknown secret
or arbitrary historical payload. Rama traces, depot history and encrypted
backups intentionally retain sensitive application state under the documented
private retention contract.

## Operational acceptance reused without disruptive repetition

[Deployment evidence](DEPLOYMENT-2026-09-24.md) records the actual reboot,
same-artifact module replacement, 10-active/50-queued fixture load and measured
memory, forced proxy recovery, encrypted backup and isolated working restore.
It also records CLIProxyAPI 7.3.15 -> 7.3.16 -> 7.3.15 and owner-confirmed fresh
OAuth login, with real stream/replay verification at each version. Production
remains on 7.3.15. No application artifact, service configuration, credential,
state root, lock domain or backup was changed for this acceptance batch.
New calls add normal private application history. All five encrypted snapshots
and both preserved legacy archives remain retained. This does not establish
arbitrary rebuilt-JAR, Rama/AOR upgrade, crash or legacy recovery compatibility.

## Recorded cost and human decisions

The actual VPS checkout was **USD 10/month including displayed taxes**, monthly
renewal without annual commitment or paid extras: 8 GB RAM, 4 vCPU, 75 GB NVMe,
Warsaw, Ubuntu 24.04. The owner replied `paid` and payment was verified in
task `01a0ce32-23e8-7f00-995f-6ea89fff2105`. This is the approved order amount,
not a current generic list-price estimate. No separate IPv4 charge was verified.

The owner already selected usage-billed R2 storage in
[#5's recorded decision](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/5#issuecomment-5807867389).
On September 24, read-only `restic stats --mode raw-data --json --no-lock`
reported five snapshots referencing 606,961,833 compressed bytes and 9,078
blobs. Referenced restic bytes are not total bucket storage or invoice usage.
[R2 Standard pricing](https://developers.cloudflare.com/r2/pricing/) checked
that day is $0.015/GB-month, $4.50/million Class A operations and $0.36/million
Class B operations, with free monthly allowances of 10 GB-month, one million
Class A and ten million Class B operations, and free egress. Billing rounds up
units. A subsequent read-only check of the existing authenticated Cloudflare
dashboard showed **$0.00 for September 1-24**, 233 Class A operations, 1.36k
Class B operations and 3.37 GB account-wide storage. The backup bucket displayed
578.03 MB. These are dashboard observations, subject to usage reporting delay,
not a promise of zero future charges or a finalized month-end invoice.

The initial Tailscale review found a free trial without a continuing plan.
The owner subsequently confirmed non-commercial use and selected Personal in
coordinator task `01a0d299-6d3b-7720-9441-62a53cb894b3`. The coordinator completed
the approved switch. Independent inspection of the authenticated billing page
then showed **Personal plan, Free**, seats 1/6 and tagged resources 0/50,
without a trial. The deployment uses Rama's free-license operating procedure.
The accepted ongoing infrastructure cost is therefore **$10/month VPS plus
usage-billed R2 and free Personal Tailscale**. R2 was $0 month-to-date at the
observation above; its future bill remains usage-dependent.

The owner confirmed the selected account permits CLIProxyAPI use, recorded in
[#7](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/7#issuecomment-5791042105).
The approved parent scope explicitly selects one node and excludes high
availability. During the final continuation, the owner explicitly accepted
host-loss/restore outages, maintenance interruptions, and provider quota,
account-access and service-change risks in the same coordinator task. This
records owner acceptance, not a publisher guarantee of account continuity.

## Post-plan private-access verification

After the Personal switch, the refreshed saved Tailscale policy retained one
grant from the approved administrator device to the VPS, limited to TCP 22,
1974 and 8888. Its stored accept/deny tests remained present; no wildcard grant
or policy edit was introduced. The local host firewall retained its default
drop policy and matching Tailscale-only port allowlist.

Fresh connections from the approved administrator reached SSH and both UIs;
HTTP 1974 and 8888 each returned 200. TCP 1973, 2181, 18317, 18318, 20000 and
20002 remained unreachable through the tailnet. SSH command execution worked.
All six actual units (`bridge-zk`, `bridge-conductor`, `bridge-supervisor`,
`bridge-ui`, `bridge-proxy`, `bridge-firewall`) were active. The deployed JAR
digest remained the exact value recorded above. No runtime, credential, state,
backup or application configuration changed in this final continuation.

The final change is documentation and issue bookkeeping. Existing composed
14-test/374-assertion evidence and deployment drills remain applicable; no
costly suite, provider login or disruptive maintenance was repeated.
