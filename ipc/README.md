# Agent-o-rama IPC compatibility spike

Tracking: [issue #2](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/2).
This document records the original experiment. The module now includes
[durable completed replay](REPLAY.md); this remains an IPC harness, not a deployment.

## Pinned runtime

- Agent-o-rama 0.10.0, source [2d94b569](https://github.com/redplanetlabs/agent-o-rama/tree/2d94b569333abc081c9bf8c7aa835dd0c4f38182).
- Rama 1.9.0 and Clojure 1.12.4, matching that release's `project.clj`.
- LangChain4j core 1.18.1 and `langchain4j-open-ai-official` 1.18.1-beta28.
  The official provider artifact has a beta suffix; version 1.18.1 without it
  does not exist. No transport fallback is used.
- OpenJDK 21.0.7, Clojure CLI, Python 3, and the existing
  [CLIProxyAPI 7.3.15 pin](../spike/README.md).

Dependencies come from Maven Central, Clojars, and Red Planet Labs' public Maven
repository. No Rama installation or external cluster is needed for IPC.

## Run

From the repository root:

```sh
clojure -M:test
python3 -m unittest discover -s ipc -p 'test_*.py' -v
python3 -m unittest discover -s spike -v
python3 ipc/run_live.py
```

Run the controllable fake first. The live command reuses the dedicated account
login and private state documented in the direct spike. It writes only validated,
redacted adapter measurements to `ipc/adapter-evidence.json` and always terminates its daemon.
It refuses an occupied daemon port and validates binary checksum, private file
permissions, bearer protection, and the loopback listener before invoking Clojure.
No UI or production service is started.

`bridge.module/proxy-module` returns a first-class Clojure `aor/agentmodule`.
It registers an `OpenAiOfficialStreamingChatModel` agent object and uses
`lc4j/chat` from an Agent-o-rama node. Agent-o-rama wraps that streaming object,
records its model trace, and forwards nested chunks to `agent-stream`.
A real `tools/new-tools-agent` executes the pure `spike_echo` fixture; its result
is sent back to the model in a second request. The node returns text and usage.

Configuration takes `:base-url`, `:model`, `:timeout-ms`, and optional
`:bearer-file`. For direct daemon access use `http://127.0.0.1:18317/v1` and the
absolute path to the dedicated bearer file. Its contents are read only inside
an agent-object builder after rejecting group/other permissions; resolved bearer
values are never captured in the serialized module definition. Without a bearer
file the builder uses the non-secret fixture key, appropriate for the fake and
measurement harness only.

The live measurement harness starts an ephemeral loopback Python forwarder.
It counts requests and injects the real bearer in memory, forwarding SSE lines
immediately. It retains no bodies or headers. This is an observation boundary,
not part of the proposed adapter architecture. Counts are HTTP requests into
CLIProxyAPI, not independently measured provider executions or billed calls.
Both the SDK and CLIProxyAPI disable their own request retries during the probe.

The `:force-retry?` input deliberately throws after the first completed model
response, once per launched module. A task-local latch permits the subsequent
Rama retry. This is fault injection, not durable replay logic. The `:timeout?`
input selects a separate short-deadline model object (1 ms live, 100 ms against
the delayed fake). Per-request `:model-name` permits the unknown-model error probe.
These controls are for this sequential experiment only.

## Behavioral checks

The fake is an external HTTP/SSE boundary. The tests run real Rama IPC,
Agent-o-rama nodes, model instrumentation, and tool agents; they do not mock the
Agent-o-rama transport or simulate Rama retries in a client loop.

- Final text equals the concatenated nested chunks in order.
- Streamed usage is returned and saved in the model trace with first-token time.
- Tool request, execution, matching call ID, returned `ping` content and second
  model response complete end to end.
- A post-model node failure produces two upstream fake requests via Rama retry.
- HTTP 400 is observed as the SDK's `BadRequestException` in the saved trace.
- The delayed fake receives the request and the deadline produces a timeout
  exception in the saved trace. An unrelated construction error cannot pass.
- The fake pauses after the first chunk. Closing `agent-stream` stops callbacks;
  after releasing the fake, the agent still returns the complete response.

Agent-o-rama 0.10.0 exposes no public trace reader. The experiment isolates a
version-pinned inspection helper in `ipc/test/bridge/inspect.clj`, following the
upstream release's `tracing_test.clj`: it reads the root invoke and tracing query.
Application code uses public APIs only. No internal execution hooks force retry.

`log4j2.xml` disables raw runtime logging because SDK exceptions may include
response bodies. Traces are inspected in memory inside IPC. Public evidence is
restricted to fixed dependency strings, booleans, numeric counts, and numeric
usage. Python rejects unknown fields, textual payloads, and wrong numeric types
before saving. Tests inject a secret sentinel to verify this boundary.

## TDD record

| Slice | Expected RED | GREEN |
| --- | --- | --- |
| Nested streaming | Module absent | Real IPC returns `one two` with ordered chunks |
| Usage | Text result lacks text/usage fields, 2 failures | Structured result matches streamed counts |
| Rama retry | 1 upstream request instead of 2 | Post-model fault triggers 2 requests |
| Tool exchange | Ordinary response and 1 request, 2 failures | Tool result returned in second request |
| Timeout | Delayed request returns instead of throwing | Deadline failure recorded in trace |
| Provider error | Model override ignored, request succeeds | HTTP 400 failure recorded in trace |

Every Clojure edit was gated by `clj-paren-repair`, `clj-kondo`, then the IPC
namespace tests or live namespace probe. No nREPL was running.

## Observed live result, 2026-09-23

All seven experiments passed against `gpt-6-luna`; see the
[redacted evidence](evidence.json).

| Experiment | Observed result |
| --- | --- |
| Normal stream | 1 proxy request, complete ordered nested stream, saved model trace |
| Usage | 307 input / 5 output / 312 total, identical in result and trace |
| Tool roundtrip | 2 proxy requests, 1 `spike_echo` request with expected arguments, executed result `ping`, 2 model traces |
| Forced Rama retry | 2 proxy requests, 2 model traces, 1 stream reset |
| Unknown model | HTTP 400 SDK exception saved in trace; 3 proxy requests due to Rama retries |
| Model deadline | Timeout exception saved in trace; 3 proxy requests despite SDK retries being disabled |
| Subscription cancellation | Closed before agent completion; zero further callbacks; agent still completed with 315 input / 503 output / 818 total tokens |
| Recovery | 1 successful normal request after error, timeout and cancellation |

Usage in the returned result and this evidence is for the final model response,
not the aggregate cost of tool calls or retried attempts. The separate saved
model traces retain each call's usage. Request counts do not prove provider-side
attempt counts, deduplication, or billing.

Decision: **GO for the thin reliability adapter preserving these observed
semantics**, including subscription cancellation. Completed replay is justified
by the measured second call after Rama retry; that behavior is now implemented in [the replay adapter](REPLAY.md), tracked by
[issue #6](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/6).

**NO-GO for upstream generation cancellation or billing-cessation guarantees.**
Closing `agent-stream` only unsubscribes. The pinned Agent-o-rama wrapper offers
no public propagation of a provider cancellation handle; the live agent continues
and receives final usage. Production acceptance requiring upstream termination
needs an explicit design/contract revision under
[the parent](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/1)
and [acceptance issue #8](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/8).
This spike does not establish such a guarantee, OAuth refresh behavior, durable
replay, admission limits, or production readiness.

Final validation: seven real IPC tests, 21 assertions; two evidence-boundary
Python tests; three existing direct-spike Python regressions; full live probe.
The live daemon and measurement listener were terminated after the run.
