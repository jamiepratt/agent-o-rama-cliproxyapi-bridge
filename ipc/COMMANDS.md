# Standalone versioned commands

`bridge.commands` provides a small data-only command API. **Production does not
load or submit these commands.** Existing admission/replay/module source and
the default test runner remain unchanged. Recovery and topology integration are
tracked in [issue #5](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/5).

`(command op args)` creates `{:version 1 :op op :args args}`.
`(apply-command state command)` validates and interprets that data without I/O,
wall-clock reads, generated IDs, or serialized functions. Callers supply all
timestamps and identities explicitly.

| Operation | Arguments | State and behavior |
| --- | --- | --- |
| `:admission/chunk` | `:generation`, `:chunk` | Admission state: append text only to an existing, nonterminal, uncancelled generation. |
| `:admission/leave` | `:generation`, `:candidate` | Admission state: remove that waiter's binding/lease; retire the named generation only when terminal and no waiters remain. |
| `:admission/cancel` | `:call-id`, `:now` | Admission state: retain cancellation for one hour, complete matching queued generations, mark matching active generations cancelled while retaining their slots. |
| `:replay/reserve` | `:fingerprint`, `:generation`, `:now` | One completed-call entry: preserve an unexpired binding, including conflicting fingerprints; create a one-hour binding only when absent or expired. Existing caller conflict handling remains necessary. |

Envelope and argument keys are exact. IDs and fingerprints are nonempty strings;
chunks are strings. Timestamps are nonnegative integers with enough signed-long
range for one-hour retention. Both public functions reject unsupported operations
and malformed input; the interpreter also rejects unsupported versions. Errors
use fixed messages and types without echoing payloads. These four schemas admit
only scalar data. They do not accept custom objects or functions.
The producer normalizes accepted containers to fresh plain maps, stripping
metadata and sorted-map comparators that could otherwise retain executable objects.

The interpreter reuses the pinned admission/replay pure helpers. Changing those
helpers requires preserving version 1 semantics or explicitly introducing a new
version. This API provides no depot routing, atomic application, deduplication,
receipt, command expiry, transport acknowledgement or migration by itself.

Run the independent suite:

```sh
clojure -M:commands-test
```

Its writer freezes commands with the pinned Nippy dependency, exits, and a fresh
JVM with different identity-hash allocation history thaws and applies them.
Assertions cover distinct string roles, cancellation slot retention, waiter
cleanup, replay binding/conflict/expiry behavior and input rejection. This tests
the standalone serialized command layer, not distributed Rama recovery.

Legacy anonymous-function bytes lack original capture names. This namespace
cannot decode, repair or replace them, and passing these tests does not establish
legacy compatibility. It has no migration switch or production enablement flag.
