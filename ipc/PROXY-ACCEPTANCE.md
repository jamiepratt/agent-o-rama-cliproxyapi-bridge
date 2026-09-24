# Composed proxy acceptance

`bridge.proxy-acceptance` runs existing public-interface acceptance cases through:

```
packaged adapter + Rama IPC -> CLIProxyAPI -> counted fake provider
```

The fake provider records incoming HTTP requests after CLIProxyAPI. These counts
therefore include any proxy-generated HTTP retries, unlike the adapter's
`bridge_upstream_dispatches_total` metric. They do not measure paid-provider
billing, OAuth behavior, or requests in a provider's own backend.

Use the exact deployed application JAR and the real Rama distribution, with no
`ipc/src` on the classpath. `ipc/test` supplies only the fixture and assertions.
The runner verifies the macOS CLIProxyAPI 7.3.15 binary SHA-256
`7212d39890dac46fac10d75f8029d8c377fdcc75798d5dcecefffc90b987d0a9`.
Run JVM suites serially. No production configuration or credentials are used.
The runner starts a fresh proxy with an empty auth directory, loopback listeners,
a fake compatible-provider key, disabled request/file logging, and commercial
mode. Retry settings match production: `request-retry: 0`,
`max-retry-credentials: 1`, `max-retry-interval: 0`. A ten-minute watchdog bounds the complete run, readiness HTTP calls have
explicit timeouts, and proxy termination is bounded. It terminates the proxy and
fake provider in `finally`; private temporary
fixture configuration and logs remain available for diagnosis. CLIProxyAPI may
fetch its public model catalogs/version metadata at startup; this local harness
is not a network-namespace isolation proof.

```sh
# Verify these inputs before running. Replace paths as appropriate.
shasum -a 256 "$PROXY_BINARY" "$APPLICATION_JAR"
java -Xss6m -Xmx4g -Djdk.attach.allowAttachSelf \
  -cp "$RAMA_DIST/rama.jar:$RAMA_DIST/lib/*:$APPLICATION_JAR:ipc/test" \
  clojure.main -m bridge.proxy-acceptance "$PROXY_BINARY"
```

The cases verify ordered nested chunks and usage, forced post-model Rama retry,
completed text replay with explicitly zero new provider requests, in-flight
duplicate coalescing, in-flight/completed fingerprint conflicts,
completed tool exchange replay, saved model tracing, 10 active plus 50 FIFO
queued calls and immediate overload, logical cancellation while transport slots
remain occupied, closed-subscription callback removal, provider HTTP errors,
model timeout, and request/replay/conflict/retry/dispatch/duration metrics.
`PROVIDER-REQUESTS` lines report each case's observed external HTTP count;
individual existing assertions enforce the relevant counts and ordering. The
runner also asserts each final case count after all results complete, including
exactly one provider request for in-flight duplicate coalescing.

A tool exchange legitimately requires two provider requests, one before and one
after the tool result. Replaying that completed exchange adds zero requests.
Logical cancellation and closing a stream subscription do not promise upstream
transport abortion or stopped billing. Completed replay is subject to the
retention and receipt boundaries in [REPLAY.md](REPLAY.md). This harness does not
prove exactly-once execution across every crash or restart.

The provider-error and timeout fixtures each make three provider HTTP requests.
This matches the existing Rama failure retry contract: terminal errors are not
successful replay entries. The forced post-success Rama retry makes exactly one
request. These are distinct acceptance assertions, not an exactly-once guarantee
for failures.

## Recorded run, 2026-09-24

The final composed suite passed **14 tests / 374 assertions**, zero failures or
errors, using macOS Java 21.0.7, Rama 1.9.0 / Agent-o-rama 0.10.0, the pinned proxy
above, and the exact production application JAR:
`f3145e3596733590b3487dcd4348f94311f821743b2734fb5801ce2dda10289c`.
Production's Java patch release is different; this is a packaged local composed
acceptance run, alongside the separate production evidence.

| Case | Provider HTTP requests |
| --- | ---: |
| Text original plus completed replay | 1 original, 0 new on replay |
| Nested streaming | 1 |
| Forced post-success Rama retry | 1 |
| Concurrent identical in-flight callers | 1 |
| Original plus in-flight fingerprint conflict | 1 original, 0 for conflict |
| Original plus completed fingerprint conflict | 1 original, 0 for conflict |
| Tool exchange plus completed replay | 2 original rounds, 0 new on replay |
| Saved trace and usage | 1 |
| 10 active, 50 FIFO queued, one rejected excess call | 60 |
| Active and queued logical cancellation, with duplicates | 11 |
| Closing stream subscription | 1 |
| Provider HTTP 400 | 3 Rama failure attempts |
| Model timeout | 3 Rama failure attempts |
| Success, replay and conflict metrics case | 1 |

The harness development RED run failed readiness because the proxy's YAML parser
rejected escaped forward slashes in generated JSON configuration. Disabling
slash escaping made the configuration valid; the first composed run passed
13 tests / 354 assertions. The final run added explicit text replay and final
provider-count assertions, bounded readiness/cleanup, and the binary checksum
check. Clojure delimiter repair and lint passed. All owned proxy/JVM processes
exited and the isolated auth directory remained empty. No product artifact or
production state changed.
