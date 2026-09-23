# Private readiness and metrics

Implemented for [issue #4](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/4).
The observer is an explicitly started, loopback-only HTTP listener outside Rama
workers. Start one observer per module, with a snapshot of that module's global
admission controller. Scrape that observer once; two observers of one module
export the same counters and must not be summed.

```clojure
(require '[bridge.observability :as obs]
         '[com.rpl.rama :as rama])
(def state (rama/foreign-pstate cluster "bridge.module/ProxyModule" "$$admission"))
(def observer
  (obs/start! {:port 0 ; ephemeral port, returned as :port
               :snapshot #(rama/foreign-select-one ["admission"] state)
               :provider :openai-compatible
               :configured? true
               :base-url "http://127.0.0.1:18317/v1"
               :model "gpt-6-luna"
               :bearer-file "/absolute/private/path/to/bearer"
               :timeout-ms 15000
               :refresh-ms 60000
               :verification-ttl-ms 300000}))
;; Explicit live traffic, never triggered by scraping:
(obs/refresh! observer)
;; Stop listener, HTTP client and owned executor:
(obs/stop! observer)
```

`:snapshot` is required. A nil returned state means the module has not yet
recorded any calls. A throwing or timed-out snapshot returns HTTP 503, never
fabricated zero counters. Snapshot callbacks must honor interruption; the
observer cancels their future at its deadline. Use the async Rama selector with
its own timeout if a custom client does not honor interruption.

The listener always binds numeric `127.0.0.1`; there is no public bind option.
No listener is created automatically inside a worker. Keep the configured
CLIProxyAPI endpoint private too. No credentials, model names, account IDs,
prompts, response text or raw exception messages appear in observer responses.
The optional bearer file uses the adapter's private-POSIX-permissions reader;
its contents remain in memory. No built-in CLIProxyAPI usage-statistics endpoint
is assumed or queried.

## Readiness and refresh

- `GET /ready` returns `available`, `configured`, `verified`, `checked-at` and
  `reason`. `checked-at` is the last finished refresh's Unix milliseconds, or
  null before refresh. This is status information, always HTTP 200 when read.
- `available` identifies adapter support for the OpenAI-compatible route
  (`:provider :openai-compatible`, also the default). Unsupported provider kinds
  remain unavailable and never send a probe.
- `configured` requires explicit non-secret `:configured? true`, an endpoint
  and model, and that model's visibility in authenticated `GET /models`.
  Visibility alone does not prove that the provider can execute a request.
- `verified` additionally requires a successful bounded non-streaming
  `POST /chat/completions` with a fixed small prompt and at most 128 completion
  tokens. The returned first message must have nonblank text. This probe can
  consume provider quota. It does not test tools, streaming or all models.
- `POST /refresh` or `refresh!` explicitly refreshes. Only one probe runs per
  observer. Concurrent refreshes return the cached state; completed refreshes
  are rate-limited by `:refresh-ms` (default 60 seconds). GETs never probe.
- One monotonic network deadline covers both calls and complete response bodies
  (`:timeout-ms`, default 1 second); an outer cancellable deadline also bounds
  credential reads and JSON parsing. HTTP failures and malformed bodies are
  categorized. A failed completion clears verification while preserving the
  visibility just established. A visibility failure clears configuration too.
- Categories are `not-configured`, `not-visible`, `ok`, `authentication`,
  `rate-limited`, `upstream`, `invalid-response`, `timeout`, `unreachable`, and
  `expired`. No external error text is reflected. After `:verification-ttl-ms`
  (default five minutes), reads clear verification and report `expired`.
  Configuration still describes the last refresh, not continuously monitored
  account health. Timestamp remains available for judging freshness.

Do not expose the refresh endpoint through a public reverse proxy. Multiple
observers have independent refresh caches and can each generate probe traffic.

## Prometheus contract

`GET /metrics` returns Prometheus text format 0.0.4. Every metric is unlabeled
except the fixed latency bucket boundary `le`. No request-derived label can be
created. Histogram buckets are cumulative, ending with `+Inf = count`.

| Metric | Meaning |
| --- | --- |
| `bridge_requests_total` | Entered logical model-round attempts, including replay, conflict, overload and repeated Rama attempts |
| `bridge_request_duration_seconds` | Histogram of finished wrapper attempts, including admission wait, replay and error delivery |
| `bridge_upstream_dispatches_total` | Generations durably marked immediately before SDK dispatch; not independently confirmed HTTP receipts or provider executions |
| `bridge_upstream_errors_total` | Terminal SDK error callbacks, once per shared generation |
| `bridge_upstream_timeouts_total` | Those errors with a timeout in the bounded exception cause chain |
| `bridge_observed_retries_total` | Repeated model-node execution for the same durable generated invocation/round observation ID within one hour |
| `bridge_replay_hits_total` | Attempts served directly from completed replay |
| `bridge_conflicts_total` | Attempts rejected by identity/fingerprint conflict |
| `bridge_coalescing_total` | Admission registrations joining an existing in-flight generation |
| `bridge_overloads_total` | Distinct calls rejected by a full queue |
| `bridge_active_calls` | Module-wide active reservations, including logically cancelled transports still running |
| `bridge_queue_depth` | Module-wide FIFO reservations waiting for capacity |
| `bridge_verification_status` | This observer's fresh verified state, 0 or 1 |

Ordinary independent caller duplicates have distinct generated observation IDs;
they do not increment observed retries. Tool rounds have separate IDs. The
caller cannot supply the observation ID. Pending inputs from an older module
version without that ID still count requests but do not claim observed retries. IDs expire after one hour without an
attempt and idle ticks remove them. Counts are module lifetime aggregates; they
survive worker replacement in the same PState. Dropping module state resets them.

Aggregates live inside the same durable atomic controller as admission. Its
stable command receipt suppresses event redelivery, including counter and
histogram mutations. Completion guards also suppress duplicate terminal errors.
Gauges read the same global state used for admission, across both model objects
and all workers. They are not JVM-local estimates. A worker crash can omit a
latency observation if it never reaches its finalizer; dispatch reservations can
also outlive the process that marked them. Counters cannot establish exactly-once
execution or billing. Hidden CLIProxyAPI/provider retries are not counted.

Probe HTTP calls bypass the model wrapper, so they do not alter these adapter
counters. Prometheus verification status is the observer's local probe result.
Rama traces, token usage and replayed usage remain separate from these aggregates.

## Validation

Tests scrape the actual listener around real two-worker Rama IPC calls: success,
replay, conflict, forced Rama retry, coalescing, active/queued capacity, overload,
shared upstream errors and wrapped timeouts. Controller redelivery increments a
coalescing counter once. Histogram count and cumulative buckets are checked.
Fake HTTP tests cover support/configuration/verification transitions, visibility
failure, authentication failure, malformed completion, bounded completion timeout,
refresh coalescing and cache TTL, and bounded unavailable-controller 503.

TDD evidence: missing observer and refresh API failed before implementation;
request/replay/conflict scrape counters each read zero before instrumentation;
wrapped SDK timeouts failed their metric assertion before cause-chain handling;
missing explicit snapshot configuration failed before startup validation. The
live evidence schema rejected the new observability record before its strict
boolean/numeric schema was implemented.

`python3 ipc/run_live.py` retains the seven prior live scenarios and adds a
separate readiness probe and scrape. It verifies retries, replay, error counts,
probe separation and SDK-versus-forwarder count relationships. The forwarder
counts chat-completion POSTs; model-visibility GETs are excluded. Its fixed evidence
schema rejects unknown fields and textual payloads.

Validation on 2026-09-23: the complete Clojure suite passed **36 tests, 536
assertions**, with zero failures or errors. All five Python regressions passed;
Clojure repair and lint were clean.

The authenticated live harness passed all seven compatibility scenarios plus
observability at **2026-09-23T12:11:29Z**. The [redacted evidence](adapter-evidence.json)
records available/configured/verified all true, a timestamped result and a
successful scrape: **13 model attempts, 12 SDK dispatches, 1 replay, 5 observed
retries and 6 upstream errors**. The separate verification probe made one
chat-completion request and did not increment adapter counters. Forced Rama retry
used one proxy request, two model traces and one stream reset. The daemon and
test JVM were stopped after validation.
