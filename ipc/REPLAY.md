# Model-call replay and admission

Implemented for [issue #6](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/6) and [issue #3](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/3), on the pinned runtime in [README.md](README.md).

`bridge.module/proxy-module` now wraps the official streaming model with
`bridge.replay/replay-model`, inside Agent-o-rama's model instrumentation.
The live suite writes `adapter-evidence.json`; `evidence.json` preserves the
original compatibility spike, where a forced Rama retry made two requests.

## Contract

Invoke the `chat` agent with a string `:prompt` and optionally an opaque, nonempty
string `:call-id`. Repeating that ID with the same request replays the completed
response, including ordered text chunks, tool calls and usage. Without a caller
ID, an upstream `identify` node generates a UUID and durably emits it to the
model node. Rama retries of the model node retain that ID. Individual model
rounds use distinct ordinal suffixes, so tool exchanges retain separate results.
A new independent invocation without a supplied ID is a new call.

The module also accepts `:tool?`, `:timeout?`, `:model-name`, and the test-only
`:force-retry?` control. Unknown fields, invalid IDs and invalid field types
return `{:error {:type :bridge.replay/invalid-request}}` before model dispatch.
Reusing an active identity with another fingerprint returns
`{:error {:type :bridge.replay/identity-conflict}}`, through the public Rama
client, without dispatching that conflicting request.

The SHA-256 fingerprint covers the actual merged LangChain4j `ChatRequest`,
including messages, content, model parameters, tool definitions and response
schemas, plus endpoint/default model/deadline configuration. Object keys are
sorted recursively; list order is preserved. Concrete LangChain4j type tags
preserve semantic distinctions such as string versus boolean JSON schemas.
Fingerprint serialization and response serialization are version-pinned to the
listed dependencies. Revalidate and migrate or namespace the store when upgrading
those dependencies. The wrapper delegates model capabilities, provider and
listeners; the outer default chat method invokes listeners once.

IDs are scoped to this Rama module and its configured private provider route.
Use a new module namespace or explicit caller ID namespace when changing the
account/route's meaning. Credential rotation does not change a fingerprint;
credentials and credential paths are not included in it.

## Concurrent calls and FIFO admission

An identity and fingerprint share one in-flight model call across all workers of
this module. Late joiners receive the text chunks already produced, then the
remaining chunks and the same final response, tool calls and usage. Each caller
retains its own Agent-o-rama trace. Identical callers still share a live call if
the replay binding expires; the completion depot records the shared result for
the current matching binding before releasing admission. Completed replay bypasses admission.

Configuration defaults are `:active-limit 10` and `:queue-limit 50`. These count
distinct model rounds, across both model objects and every worker, not subscribers
or JVM-local pools. A duplicate joins even when the queue is full. FIFO order is
the order of atomic admission in Rama, not client wall-clock submission order.
The fifty-first queued distinct call returns, without waiting for capacity:

```clojure
{:error {:type :bridge.admission/overloaded}}
```

Positive integer active limits and nonnegative integer queue limits are supported.
Change limits or the lock directory only after pending generations have drained;
inconsistent worker configuration fails closed. No provider routing, retry,
OAuth, cooldown or wire-protocol implementation was added.

## Cancellation and errors

To cancel a logical invocation, supply `:call-id` when invoking `chat`, then call
the module's `cancel` agent with exactly `{:call-id "that-id"}`. It returns
`{:cancelled true}` idempotently. Every joined waiter returns:

```clojure
{:error {:type :bridge.admission/cancelled}}
```

Cancellation covers all model rounds for that ID. Queued work releases capacity
immediately. Active cancellation completes logical waiters immediately, discards
subsequent output, and **retains the slot and process fence until the SDK reports
transport completion or failure**. It does not promise immediate HTTP abortion or
provider billing cessation. A stalled transport therefore still occupies capacity
until its configured SDK timeout/terminal callback. Closing an `agent-stream`
subscription only stops that subscription's callbacks; it does not call `cancel`.

A cancelled ID stays cancelled for one hour, extended from the eventual terminal
callback of an active cancelled transport. Use a new ID for independent work.
Cancellation is checked before replay, each model round, and before starting the
tool agent. Already-started tool effects are not undone. Completed model replay
still does not deduplicate tool side effects.

SDK timeout and upstream failure complete all waiters and release the slot at the
terminal callback. Errors are not successful replay entries. Coalesced failures
carry a sanitized cause class; the dispatching caller retains its original SDK
exception. Existing Rama retry behavior remains in force, so a later retry may
make another upstream request. A lost or expired admission generation fails
explicitly instead of leaving a waiter polling forever.

## Single-host ownership and worker replacement

`$$admission` and `*admission-changes` provide one atomic, durable controller
partition for the entire module. It holds active generations, the FIFO, identity
bindings, ordered text chunks and waiter registrations. The dedicated depot lets
a terminal callback free its slot after the cancelled agent invocation has
already returned; agent-scoped store writes cannot safely do that.

Rama stream events can be redelivered. Each admission command has a stable UUID
and a one-hour write deadline. The controller mutation and UUID receipt commit
in the same synchronous event, across separate native PStates. Redelivery is a
no-op; expired commands are rejected even after the idle tick removes receipts.
An operation delayed beyond that deadline fails explicitly instead of reviving
retired work. This follows [Rama's event atomicity and at-least-once stream semantics](https://redplanetlabs.com/docs/~/stream.html).

Each owning generation also holds a Java/POSIX file lock. Configure `:lock-dir`
to the **same private, persistent local directory on the same host** for every
worker. Its harness default is `~/.local/share/agent-o-rama-bridge/locks` on the
machine constructing the module. Deployment must set an explicit host path.
The directory must be mode `0700`; marker and lock files use `0600`. The marker
and canonical path identify the lock domain. Replacing/removing the directory or
changing its domain while generations are pending fails closed. Do not delete
live lock files, use network filesystems, or place workers on different hosts.

A worker's model `close` stops its pollers. Async reads check this stop signal
every 100 milliseconds, so an outstanding old-client read cannot strand an
undispatched owner's fence. Surviving asynchronous transports
keep their fences. Recovery never steals a slot because a heartbeat or wall-clock
lease expired. It can retire an orphan only after acquiring that generation's
OS lock. Process death releases that lock. Queued work and promoted but
undispatched reservations can transfer their fence
to a retried owner or an already-joined waiter while retaining FIFO position.
Dispatch is marked durably under the fence immediately before entering the SDK.
Caller leases last 180 seconds and renew every 15 seconds while waiting. This
exceeds the tested 90-second update allowance. Only an unfenced, undispatched
reservation with no unexpired caller lease may be retired as abandoned. Caller
expiry never releases capacity held by a live transport. A local channel registry only
prevents POSIX descriptor-close races; admission and cross-process exclusion do
not depend on a JVM singleton.

Terminal entries normally disappear when their final waiter leaves. An idle tick
removes cancelled-ID tombstones and terminal history left by crashed waiters
after one hour. Pending generations are never expired merely to free a live slot.
Retired lock files are removed only after confirmed durable retirement; uncertain
writes and queued transfers retain the inode. A crash before initial registration
can leave an empty UUID lock file; remove such remnants only during quiescent
maintenance, never while workers are running.

Real IPC module replacement is tested with ten gated transports and two queued
calls: replacement returns while the original transports remain held, capacity
stays at ten, queued dispatch order survives, and every invocation completes after
release. A retired worker's depot client can close before its SDK callbacks
commit remaining chunks or completion. That attempt is then recovered as worker
loss after its transport terminates; Rama may make another upstream request.
The fixture observed Rama's depot `assert-open` failure in this window. Already
committed completion still replays without another request. The public update
can take more than 30 seconds; the test allows 90 seconds while transport gates and SDK deadlines remain at 120 seconds. A separate
OS subprocess test proves exclusion across processes and lock release after a
hard process kill. IPC has no public persistent-cluster reopen/worker-kill API;
this is not evidence of whole-machine reboot or disaster recovery. Deployment
validation remains tracked in [issue #5](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/5).

## Persistence, expiry and sensitive data

Fingerprint binding is an atomic Rama PState transformation before dispatch.
A pending binding contains a digest, generation and one-hour expiry; it binds the
identity even if the provider subsequently errors. Same-request retries remain
allowed. Successful completion uses an independent depot, so persistence does
not depend on the original agent invocation remaining active. Before admission
releases its identity, the completion command records the first result for the current matching
fingerprint, including a newer matching binding. If the binding expired and was
removed, it creates a fresh generation. A different fingerprint or any existing
result is never overwritten. Commands expire at completion time plus one hour;
redelivery cannot renew that deadline or resurrect an expired response. Reading
a replay never extends its expiry.

Completed state contains the digest, generation, expiry, ordered text chunks,
AI message and concrete response metadata, including tool calls and usage.
Admission state additionally retains in-flight chunks, identity digests,
cancellation metadata and sanitized error classes. Its depot history carries the
corresponding mutations and response data. Command receipts contain UUIDs and
deadlines and expire after one hour. None contains credentials. Responses and tool arguments may
contain sensitive prompt-derived information. Treat this state as sensitive.
Hashes are not encryption and can reveal guessed low-entropy prompts.

Lookups reject expired records. A Rama tick visits every task partition and
removes expired entries even when idle, normally within the configured
`:sweep-ms` interval (60 seconds by default). Pending bindings are removed too.
Completion may restore a removed binding while its identical call is still live.
This is active-state removal and one-hour replay eligibility, not a guarantee
of physical deletion from every copy. Rama depot history, replication and
backups have their own retention. Agent-o-rama already retains invocation
inputs and model traces; its store tracing also records returned state values.
Those independent retention policies require operational configuration.
Runtime logging remains disabled because raw SDK exceptions can contain data.

## Limits and preserved behavior

Completed retries and identical in-flight duplicates make no new
CLIProxyAPI request. A crash after provider execution but before durable
completion can still cause another request. The first committed completion
remains the replay result; stale generations cannot overwrite or replay a newer
identity's response. This adapter does not claim universal exactly-once provider
execution or billing.

Fresh responses stream immediately. Replay emits the original text chunks
through the same Agent-o-rama instrumentation; model traces and returned usage
remain available. Replayed usage describes the original response, not another
charge. Summing logical model traces does not measure actual provider cost.
[Issue #4](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/4)
tracks provider observability. Errors are not cached as successful results.
Closing a subscription stops callbacks but does not terminate upstream generation
or prove billing cessation. The tool agent executes again during a replayed tool exchange; the fixture is
pure echo. Completed model replay does not deduplicate external tool side effects.
Existing SDK timeout and provider-error behavior remains; admission now releases capacity and fans failures out to coalesced waiters.

## Validation

Run `clojure -M:test`, the Python tests listed in README, then
`python3 ipc/run_live.py`. The live probe retains the seven compatibility
scenarios and now requires one CLIProxyAPI request across forced Rama retry.
The fake counts HTTP requests into CLIProxyAPI's boundary, not provider attempts.
IPC module update uses [Rama's public worker replacement path](https://redplanetlabs.com/docs/~/operating-rama.html#_updating_modules) with retained
PStates; it does not simulate a machine reboot or disaster recovery.

Issue #3 validation, 2026-09-23: **29 Clojure tests, 477 assertions**, passing.
Regression coverage includes public gated IPC calls on two tasks, two threads
and two workers: global 10/50 capacity and every FIFO promotion,
configured limits, late stream join, shared failures/timeouts, logical
cancellation, expired bindings and cancellation tombstones, actual depot command
redelivery, live worker replacement, and cross-process OS fences.

| Slice | Observed RED | GREEN behavior |
| --- | --- | --- |
| In-flight sharing | Two HTTP requests for identical callers | One request; late subscriber catches up before release |
| Global admission | 59 transports started while ten were gated | Ten active; fifty FIFO queued; typed overflow before release |
| Cancellation | Missing cancel agent; agent-scoped callback writes rejected after cancellation | All waiters finish; dedicated depot releases capacity only on transport terminal |
| Worker replacement | Serial release dispatched queued request 11 before 10 and retried request 0 | Distinguish reservations from dispatched transports; adopt pending ownership and persist completion through an independent depot |
| Command redelivery | Duplicate chunks and resurrected retired generation | Atomic command receipts suppress duplicates and reject expired events |
| Binding expiry | Identical late caller started a second request | Live call shared across binding expiry; subsequent completed replay remains one request |
| Cancellation expiry | Retried caller failed; terminal did not renew cancellation | Pending cancelled transport retains cancellation and terminal extends expiry |

Historical issue #6 validation, 2026-09-23: **16 Clojure tests, 63 assertions**, passing on
Rama IPC with two tasks, two threads and two workers. Coverage includes completed
retry/replay counts, conflicting in-flight identities, invalid inputs, canonical
schema types and request parameters, tool-response serialization, expiry without
traffic, unchanged expiry on replay, stale-generation completion and worker
replacement. The expiry test shortens completion retention to five seconds;
production retention remains 3,600,000 ms. Worker replacement rebuilds the retry
latch: replay still produces one stream reset and two logical model traces while
the upstream count stays one.

TDD progression through public agent/client behavior:

| Slice | Observed RED | GREEN |
| --- | --- | --- |
| Completed retry | Two fake upstream requests instead of one | One request across forced Rama retry |
| Idle expiry | Expired completion remained in PState | Tick removes it without another request |
| Typed conflict/input | Untyped failure and silently accepted invalid fields | Serialized typed results; no conflicting dispatch |
| Canonical schema types | String and boolean schemas had identical hashes | Recursive concrete type tags distinguish them |
| In-flight identity binding | Two differing requests dispatched | Atomic binding permits one and returns typed conflict for the other |

All changed Clojure files passed `clj-paren-repair` then `clj-kondo`; no running
nREPL was available. The two IPC evidence-boundary Python tests and three direct
spike regressions passed. The [redacted live evidence](adapter-evidence.json)
records all seven compatibility scenarios passing: forced retry uses one proxy
request, two logical model traces and one stream reset; tools use two requests;
provider error and timeout each use three Rama attempts; normal and recovery
use one request each. Subscription closure produces no further callbacks while
the agent still completes. These are proxy-boundary counts, not independently
measured provider executions. The daemon was stopped after validation.
