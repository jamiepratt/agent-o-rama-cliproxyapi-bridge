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
python3 spike/cliproxy.py run --model gpt-6-luna --evidence spike/evidence.json
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
is inferred from the upstream schema-error response and the pinned source:
`internal/translator/codex/openai/chat-completions/codex_openai_request.go`
forwards the function parameters; `internal/runtime/executor/codex_executor_execute.go`
returns upstream non-2xx status and body. Raw error text is never saved.
The script's successful exit is a protocol smoke-test result, not proof of
production reliability.

## Observed results, 2026-09-23

Pre-login verification with the pinned binary passed:

- Daemon started and listened only on `127.0.0.1:18317`.
- Missing bearer: HTTP 401.
- Correct bearer: HTTP 200, empty model list before OAuth login.
- No `.log` files were created in the private state directory.
- Three local tests passed: fragmented SSE and evidence filtering, connection
  cleanup on timeout, and rejection of public auth files/multiple accounts.

The human completed device login. Live validation using `gpt-6-luna` passed
twice with a daemon restart between runs; [redacted evidence](evidence.json)
records both rounds. The original `gpt-5.4` default was not in this account's
model list, so the probe now defaults to the verified model.

| Check | Both rounds |
| --- | --- |
| Bearer protection | 401 without bearer |
| Model discovery | 200, 13 models |
| Listener | Only `127.0.0.1:18317` |
| Stream | 200 SSE, content and `[DONE]` |
| Stream usage | 307 input, 5 output, 312 total tokens |
| Tool call | Valid reconstructed `spike_echo` arguments |
| Tool usage | 338 input, 19 output, 357 total tokens |
| Auth persistence | Same single private Codex auth file after restart |
| Client cancellation | Closed after first content, before `[DONE]` |
| Client timeout | 1 ms socket timeout observed |
| Unknown model | 400 with error object (proxy routing) |
| Invalid function schema | 400 with upstream schema-error message |
| Recovery | Normal stream succeeds after error/cancellation probes |

Decision: direct API compatibility gate passes; proceed to the Agent-o-rama
integration gate tracked in [issue #2](https://github.com/jamiepratt/agent-o-rama-cliproxyapi-bridge/issues/2).
Cancellation evidence covers local client disconnect and recovery only; upstream
termination/billing is not observable from this endpoint. OAuth refresh was not
forced; persistence across restart is verified. No daemon is left running.
