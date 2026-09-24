#!/usr/bin/env python3
"""Loopback SSE fixture for the approved stopped-proxy load drill.

Run as bridge-rama with a new private ROOT shared with load_probe.clj. Reads
only the deployed bearer; never contacts a provider or logs request bodies.
"""
import hmac
import http.server
import json
import os
import pathlib
import stat
import sys
import time


def private_root(value):
    root = pathlib.Path(value)
    if (not root.is_absolute() or root.is_symlink() or not root.is_dir()
            or stat.S_IMODE(root.stat().st_mode) != 0o700
            or root.stat().st_uid != os.geteuid()):
        raise ValueError("Private root required")
    return root


def make_server(root, bearer, port=18317):
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def do_POST(self):
            self.connection.settimeout(5)
            if (self.path != '/v1/chat/completions'
                    or not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + bearer)):
                self.send_error(403)
                return
            size = int(self.headers.get('Content-Length', 0))
            if not 0 <= size <= 1048576:
                self.send_error(413)
                return
            self.rfile.read(size)
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.end_headers()
            try:
                def send(choices, usage=None):
                    data = {'id': 'load-fixture', 'object': 'chat.completion.chunk',
                            'created': 1, 'model': 'fixture', 'choices': choices}
                    if usage:
                        data['usage'] = usage
                    self.wfile.write(('data: ' + json.dumps(data) + '\n\n').encode())
                    self.wfile.flush()
                send([{'index': 0, 'delta': {'role': 'assistant', 'content': 'OK'}, 'finish_reason': None}])
                until = time.monotonic() + 25
                while not (root / 'release').exists() and time.monotonic() < until:
                    time.sleep(.05)
                send([{'index': 0, 'delta': {}, 'finish_reason': 'stop'}])
                send([], {'prompt_tokens': 1, 'completion_tokens': 1, 'total_tokens': 2})
                self.wfile.write(b'data: [DONE]\n\n')
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError, TimeoutError):
                pass

    class Server(http.server.ThreadingHTTPServer):
        request_queue_size = 128
        daemon_threads = True

        def handle_error(self, _request, _client_address):
            pass  # No payload-bearing traceback output.

    return Server(('127.0.0.1', port), Handler)


def main():
    os.umask(0o077)
    if len(sys.argv) != 2:
        raise ValueError('Usage: load_fixture.py ROOT')
    root = private_root(sys.argv[1])
    bearer = pathlib.Path('/var/lib/bridge-rama/bearer').read_text().strip()
    with make_server(root, bearer) as server:
        (root / 'fixture-ready').touch(exist_ok=False)
        server.serve_forever()


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({'ok': False, 'error_class': type(error).__name__}))
        sys.exit(1)
