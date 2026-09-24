# Single-map capture experiment

Issue [#21](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/21)
tests prospective function persistence on Rama 1.9.0, Agent-o-rama 0.10.0 and
Clojure 1.12.4. The function-based `change!` API and receipt topology remain intact.
This experiment does not recover old state or establish cross-version compatibility.

## Submitted closure inventory

Each submitted function closes over one `params` map and destructures it inside
its body. The generated fields, rather than source appearance, are checked by
`bridge.capture-test` and the IPC fixture.

| Producer | Map keys |
| --- | --- |
| `admission/join!` registration | candidate, now, domain, limits, identity, call-id |
| `admission/join!` rejection cleanup | candidate |
| `admission/heartbeat!` | candidate, now |
| `admission/mark-dispatched!` | generation |
| `admission/chunk!` | generation, chunk |
| `admission/finish!` | generation, terminal, now |
| `admission/cancel!` | call-id, now |
| `admission/leave!` | candidate, generation |
| `replay-model` observation | observation-id |
| `replay-model` conflict | metric |
| `replay-model` completed replay | metric |
| `replay-model` completion found before dispatch | metric |
| `replay-model` elapsed duration | seconds |

`recover!` and lifecycle error handlers submit through `finish!`; streaming
callbacks submit through `chunk!` and `finish!`. `prune` is a topology function,
not a `change!` producer. Direct topology functions and the completed-command depot
are unchanged. The observation closure still reads wall-clock time when applied,
matching its prior semantics; other captured timestamps retain their original
capture points.

The independent `replay/reservation-transform` captures the map keys `now`,
`digest`, and `candidate`. `bind-call!` still passes it through Rama's `term`
path construction. Its framework-generated wrapper is tested separately.

The structural predicate rejects extra fields, non-map captures, executable
values, metadata, records, and sorted containers with potentially executable
comparators. The IPC fixture collects violations across worker threads and asserts
on the test thread. Full-suite execution also requires all five metrics sites.
The narrow producer test exercises all eight admission sites.

## Reproducing the probes

Use the actual distribution before the packaged application JAR, as in
`deploy/README.md`. Build with `mvn -q -f deploy/pom.xml package`. Example:

```sh
export CAPTURE_CP="$RAMA_DIST/rama.jar:$RAMA_DIST/lib/*:deploy/target/bridge-jar-with-dependencies.jar:ipc/test"
java -Xss6m -Xmx2g -Djdk.attach.allowAttachSelf -cp "$CAPTURE_CP" clojure.main ipc/test/bridge/capture_cold.clj write admission /tmp/admission-capture.nippy
HASH_PERTURB=1000 java -Xss6m -Xmx2g -Djdk.attach.allowAttachSelf -cp "$CAPTURE_CP" clojure.main ipc/test/bridge/capture_cold.clj read admission /tmp/admission-capture.nippy
BRIDGE_LOAD_MODULE_FIRST=1 HASH_PERTURB=10000 java -Xss6m -Xmx2g -Djdk.attach.allowAttachSelf -cp "$CAPTURE_CP" clojure.main ipc/test/bridge/capture_cold.clj read admission /tmp/admission-capture.nippy
```

Repeat `write`/`read` with `reservation` and `path`, using separate fresh files.
Each process exits before the next starts. Do not preload writer-specific class
names in the reader. Admission probes compare actual operation results for live,
completed, cancelled and missing entries, including distinct string identities
and unrelated entries. Reservation probes verify distinct fingerprint and
candidate strings, expiry and unchanged valid bindings. The path probe checks
binding semantics and unrelated keys after thaw.

Run the metrics writer through the same script so writer and baseline reader
compile application namespaces in the same order:

```sh
BRIDGE_METRICS_CORPUS=/tmp/metrics-capture.nippy java -Xss6m -Xmx4g -Djdk.attach.allowAttachSelf -cp "$CAPTURE_CP" clojure.main ipc/test/bridge/capture_cold.clj write metrics /tmp/metrics-capture.nippy
HASH_PERTURB=1000 java -Xss6m -Xmx2g -Djdk.attach.allowAttachSelf -cp "$CAPTURE_CP" clojure.main ipc/test/bridge/capture_cold.clj read metrics /tmp/metrics-capture.nippy
```

The writer runs the full IPC suite and records the five actual emitted metrics
closures. Repeat the reader with `BRIDGE_LOAD_MODULE_FIRST=1`. Observation expiry
is checked against a time interval; its request and retry counters are checked
exactly.

`ipc/test/bridge/legacy_capture_order.clj.txt` preserves the original synthetic
probe byte-for-byte. Run it against the admission source from commit
`fad26a6db5c4d9647c190dc3df587401b9ee5efb`, placed in a separate temporary classpath
root before the JAR. Arguments are `write|read FILE`; the same two environment
variables vary allocation and load order. It prints writer/reader class layouts.
The text suffix keeps this historical standalone script out of namespace discovery.
Allocation perturbation is a probe, not a guaranteed portable failure trigger.

## Observed result, 2026-09-24

**NO-GO for the complete persistence acceptance criterion.** Single-map closures
remove the observed capture-order ambiguity, but this source-packaged artifact
still gives application anonymous classes different names under changed namespace
load order. No production mutation, recovery, reset, migration or deployment was
performed. The standalone data-command layer was not integrated.

Tested application JAR SHA-256:
`48716d7434bf536ae9badaa291eacf252538b9f62f2732d369861dc65f510e80`.
Classpath used the actual Rama 1.9.0 distribution `rama.jar` and `lib/*` before
that JAR, then `ipc/test`; Java 21.0.7, AOR 0.10.0 and Clojure 1.12.4. No AOT
packaging or reader-side reconstruction of writer class names was used.

| Probe | Same namespace order, writer perturb 0 / reader 1000 | Reader loads module first, perturb 10000 |
| --- | --- | --- |
| Eight admission closures, semantic corpus | PASS | Missing writer `join!` anonymous class |
| Direct reservation closure, expiry/identity semantics | PASS | Missing writer reservation anonymous class |
| Complete reservation path, local path application | PASS | `thaw-CombineTwoNavs`: failed to thaw `nav2` |

All eight writer and baseline reader admission closures had the single non-static
field `params java.lang.Object`, containing the expected map. Their generated
suffixes changed as follows under module-first loading:

| Enclosing producer | Writer suffix | Module-first reader suffix |
| --- | --- | --- |
| `join!` registration | `fn__59842` | `fn__59848` |
| `join!` rejection | `fn__59849` | `fn__59855` |
| `heartbeat!` | `fn__59858` | `fn__59864` |
| `mark-dispatched!` | `fn__59881` | `fn__59887` |
| `chunk!` | `fn__59896` | `fn__59902` |
| `finish!` | `fn__59916$fn__59917` | `fn__59922$fn__59923` |
| `cancel!` | `fn__59957` | `fn__59963` |
| `leave!` | `fn__59970` | `fn__59976` |

Admission changed-order thaw explicitly failed with `ClassNotFoundException` for
`bridge.admission$join_BANG_$fn__59842`. Direct reservation used
`bridge.replay$reservation_transform$fn__60098` in the writer, versus `fn__60096`
in the module-first reader; both had just `params java.lang.Object`. Thaw
explicitly failed to find the writer class.

The emitted complete path contained `CombineTwoNavs`, `keypath_STAR_RichNav`,
`term_STAR_RichNav`, `TermObjWrapper`, and the direct reservation closure. Moving
construction to the ordinary factory avoided the previous `bridge.replay$eval...`
inline-generated wrapper in this tested path. That does not solve the direct
closure's class availability. Changed-order path thaw reported only the `nav2`
field failure; the wrapper did not expose its underlying cause. Connecting that
failure to the independently demonstrated missing reservation class is an
inference, not an additional observed exception.

The original synthetic failure also reproduced against historical admission
source on the same pinned distribution classpath:

- Writer: `bridge.admission$cancel_BANG_$fn__59947`, non-static fields
  `call_id java.lang.Object`, then `now long`.
- Reader, perturb 1000: same class name, fields `now long`, then
  `call_id java.lang.Object`; thaw raised `ClassCastException`, String to Number.
- Reader, perturb 10000: original field order, semantics passed.
- Module-first reader, perturb 10000: class suffix `59961`; the saved `59947`
  class was missing.

This confirms why allocation perturbation alone is not a stable failure trigger.
The single-map writer/read comparison demonstrated the mitigation under matching
class availability, not portable persistence across arbitrary loading histories.

The first structural regression failed on the original chunk closure's two
captured fields. The final narrow capture tests passed 6 tests / 42 assertions.
A full source run passed the existing behavioral assertions but failed the new
coverage gate because only four of five metrics sites executed (43 tests / 582
assertions, one coverage failure, zero errors). A focused real completion race
then covered the missing pre-dispatch replay branch: one test / 22 assertions,
zero failures/errors. It pauses the returned pending binding at the external AOR
store boundary, allows the first real fake-upstream call to finish, then verifies
replay with no second upstream request. Product code was unchanged for this test.

These direct closure/path probes and IPC tests do not themselves constitute a
real Rama process restart. The separate fresh-cluster test is recorded below.

## Final combined regression validation

The final actual-distribution packaged suite passed **46 tests / 628 assertions,
zero failures/errors**, including the missing completion race, all thirteen
captured-map sites, concurrency, duplicate delivery, FIFO, cancellation, receipts
and metrics. This supersedes the earlier incomplete capture-coverage result.
The standalone command suite passed **3 tests / 21 assertions**. Seven Python
regressions passed; three Linux/deployed-host checks were skipped as expected.

The packaged suite also wrote the five actual metrics closures to a synthetic
corpus. Fresh readers with perturbation 1000 and with module-first loading plus
perturbation 10000 both preserved metric semantics and unrelated entries. Each
closure retained one `params java.lang.Object` field. These metrics classes kept
the same names in both tested readers; this does not negate the admission and
reservation class-name failures above.

Prospective deterministic class packaging and full-path persistence are tracked
in [issue #22](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/22).
Issue #21 remains open for its original unmet acceptance gates. Legacy recovery
is outside this experiment and does not block the prospective investigation.

## Fresh native cluster restart

A new local macOS cluster used one supervisor, worker and task, the pinned Rama
distribution and ZooKeeper 3.9.6, fresh temporary state, a loopback fake SSE
upstream and a fixture module entrypoint
calling the actual `bridge.module/proxy-module`. The fixture deployment JAR had
SHA-256 `a590fd2e6910eca3d152f35944f9c34776ed855f54d9a6346df0e537960d89ee`.
Its only additional ZIP entry was `bridge/cold_fixture.clj`; every original
application artifact entry was byte-for-byte unchanged.

Warm streaming returned `one two` with chunks `["one" " two"]`, one upstream
request, readable completed/admission PStates and eleven persisted receipts.
The harness then requested normal Rama cluster shutdown and observed
`cluster-shutdown-complete`. The worker exited; ZooKeeper, conductor, supervisor
and fake upstream were stopped. Process inspection found no owned JVMs and all
test listeners were closed before restarting the same retained state.

Warm/cold ZooKeeper logs record Java 21.0.7; Rama and clients resolved the same
Java installation. Individual Rama/client version strings were not recorded.

After restart, completed replay returned identical chunks, text and usage with
the upstream request count still one. A new identity streamed successfully and
increased that count to two. Completed/admission PStates and fourteen receipts
were readable after replay. No generated-class loading failure occurred in this
normal orderly restart. All experimental processes were stopped afterward.

**GO for this observed fresh-state, normal-startup process restart on the tested
artifact; overall NO-GO for #21's broader loading-history requirement.** This
does not establish arbitrary namespace-order safety, legacy-state recovery,
cross-version compatibility, Linux/systemd behavior or production deployment.
Orderly snapshots need not deserialize every historical closure; this was not a
crash-recovery test.
