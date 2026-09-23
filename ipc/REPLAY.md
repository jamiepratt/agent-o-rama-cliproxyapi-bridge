# Completed model-call replay

Implemented for [issue #6](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/6), on the pinned runtime in [README.md](README.md).

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

## Persistence, expiry and sensitive data

Fingerprint binding is an atomic Rama PState transformation before dispatch.
A pending binding contains a digest, generation and one-hour expiry; it binds the
identity even if the provider subsequently errors. Same-request retries remain
allowed. Successful completion conditionally records the first response for that
generation, starting a fresh one-hour replay period. A stale completion cannot
overwrite a newer generation after expiry and reuse. Reading a replay never
extends its expiry.

Completed state contains the digest, generation, expiry, ordered text chunks,
AI message and concrete response metadata, including tool calls and usage.
It contains no request body or credential. Responses and tool arguments may
contain sensitive prompt-derived information. Treat this state as sensitive.
Hashes are not encryption and can reveal guessed low-entropy prompts.

Lookups reject expired records. A Rama tick visits every task partition and
removes expired entries even when idle, normally within the configured
`:sweep-ms` interval (60 seconds by default). Pending bindings are removed too.
This is active-state removal and one-hour replay eligibility, not a guarantee
of physical deletion from every copy. Rama depot history, replication and
backups have their own retention. Agent-o-rama already retains invocation
inputs and model traces; its store tracing also records returned state values.
Those independent retention policies require operational configuration.
Runtime logging remains disabled because raw SDK exceptions can contain data.

## Limits and preserved behavior

Completed retries and later duplicates make no new CLIProxyAPI request.
Same-fingerprint requests that overlap before completion can still dispatch
more than once. In-flight coalescing and admission belong to
[issue #3](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/3).
The first committed completion remains the replay result. An overlapping caller
may already have streamed its own response. A crash after provider execution but
before durable completion can also cause another request. This adapter does not
claim universal exactly-once provider execution or billing.

Fresh responses stream immediately. Replay emits the original text chunks
through the same Agent-o-rama instrumentation; model traces and returned usage
remain available. Replayed usage describes the original response, not another
charge. Summing logical model traces does not measure actual provider cost.
[Issue #4](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/4)
tracks provider observability. Errors are not cached as successful results.
Closing a subscription stops callbacks but does not terminate upstream generation
or prove billing cessation. The tool agent executes again during a replayed tool exchange; the fixture is
pure echo. Completed model replay does not deduplicate external tool side effects.
Existing timeout and provider-error behavior remains unchanged.

## Validation

Run `clojure -M:test`, the Python tests listed in README, then
`python3 ipc/run_live.py`. The live probe retains the seven compatibility
scenarios and now requires one CLIProxyAPI request across forced Rama retry.
The fake counts HTTP requests into CLIProxyAPI's boundary, not provider attempts.
IPC module update uses [Rama's public worker replacement path](https://redplanetlabs.com/docs/~/operating-rama.html#_updating_modules) with retained
PStates; it does not simulate a machine reboot or disaster recovery.

Observed validation, 2026-09-23: **16 Clojure tests, 63 assertions**, passing on
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
