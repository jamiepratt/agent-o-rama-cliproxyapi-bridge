# Direct CLIProxyAPI spike

Tracking: [issue #7](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/7).
This stdlib-only Python probe supports macOS arm64 and requires Python 3 and `lsof`.

## Pin

- [CLIProxyAPI v7.3.15](https://github.com/router-for-me/CLIProxyAPI/releases/tag/v7.3.15)
- Source commit: `673131f57484517c3a1eae7e36c4cfa7b9bb4efc`
- Asset: `CLIProxyAPI_7.3.15_darwin_aarch64.tar.gz`
- Archive SHA-256: `c1e49c148a94c476dc43a6a0eed28bca34239d5153ebb7792048d8c18f3b92f0`
- Binary SHA-256: `7212d39890dac46fac10d75f8029d8c377fdcc75798d5dcecefffc90b987d0a9`

The archive checksum matches the release's `checksums.txt`. Setup verifies the
archive before extracting only the binary. Every invocation verifies the binary.

## Run

From the repository root:

```sh
python3 spike/cliproxy.py setup
python3 spike/cliproxy.py login
python3 spike/cliproxy.py run --model gpt-5.4 --evidence spike/evidence.json
python3 -m unittest discover -s spike -v
```

Login requires a human terminal. Complete the displayed Codex device flow there;
do not copy the device code, login output, or auth files into issue comments.
The account owner confirmed permitted use in the issue's coordinating task on
2026-09-23. This records their confirmation, not an independent policy finding.

The probe starts a daemon, runs the checks, terminates it, and repeats with the
same auth directory. It refuses an occupied port or anything other than exactly
one Codex auth file. It terminates its daemon even on failure. The default model
must be listed by the authenticated account; pass another listed Codex model if
necessary. Each run issues several small model requests and one cancelled long
request, consuming account usage.

## Credential and log handling

State lives exclusively in `~/.local/share/cliproxyapi-spike/`: directories use
0700; the generated bearer/config and OAuth files must use 0600. Login runs under
umask 077. No existing Codex or other application's credentials are imported.
The bearer is read from disk in Python and added to an in-memory HTTP header;
no resolved secret goes in process arguments or evidence.

The generated configuration binds `127.0.0.1:18317`, disables management,
control-panel downloads, pprof, discovery, plugins, and request/file logging.
`commercial-mode: true` is essential: in the pinned `internal/api/server.go`,
it bypasses the request logging middleware entirely. `request-log: false` alone
still permits forced error log files. Daemon stdout/stderr go to `/dev/null`.
Retries are set to zero for clear observations of the single account.

Evidence uses an allowlist: statuses, counts, fixed labels, numeric usage,
checksums, booleans and timings. It omits response text, tool arguments, IDs,
headers, credential paths and raw error messages. Tests inject a secret sentinel
into responses and verify it never reaches the evidence.

## What the probe measures

- Missing bearer produces 401; authenticated model listing contains the selected model.
- `lsof` verifies the daemon's only TCP listener is the loopback endpoint.
- Chat Completions SSE produces content, `[DONE]`, and numeric usage.
- Fragmented streamed tool calls reconstruct `spike_echo({"value":"ping"})`.
  The function is a transport fixture and executes no external action.
- Client disconnect closes an active response after content arrives.
- A 1 ms client socket timeout is captured, then the connection closes.
- An unknown model records a proxy routing error separately from an invalid
  function-schema request intended to exercise upstream validation.
- A normal stream after those probes measures continued availability.
- All checks run twice with a process restart and persistent auth between runs.

Client disconnect and timeout do not by themselves prove upstream generation
stopped or that billing stopped. The invalid-schema error's upstream provenance
also requires assessment of actual results. The script deliberately marks it
unverified rather than claiming every HTTP error originated upstream. The
script's successful exit is a protocol smoke-test result, not automatic approval
of the entire architecture gate.

## Observed results, 2026-09-23

Pre-login verification with the pinned binary passed:

- Daemon started and listened only on `127.0.0.1:18317`.
- Missing bearer: HTTP 401.
- Correct bearer: HTTP 200, empty model list before OAuth login.
- No `.log` files were created in the private state directory.
- Three local tests passed: fragmented SSE and evidence filtering, connection
  cleanup on timeout, and rejection of public auth files/multiple accounts.

The human device login and authenticated model probes have not completed.
No live-stream, tool-call, usage, persistence, timeout, upstream-error, or
cancellation success is claimed yet. Gate status remains open in issue #7.
