#!/usr/bin/env python3
"""Local stand-in for the Telegram Bot API, for testing Printer Bridge's
notifications without sending anything to the real Telegram.

Accepts POST /bot<token>/sendMessage (form-encoded or JSON), records every
request as one JSON line in --log, and answers like Telegram:
  {"ok": true, "result": {"message_id": N, ...}}
A token of "BAD" gets a 401 like Telegram's "Unauthorized".

The Android emulator reaches this laptop's 127.0.0.1 as 10.0.2.2, so point the
app at it with telegram_base_url = http://10.0.2.2:8765
"""
import argparse
import json
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ap = argparse.ArgumentParser()
ap.add_argument("--port", type=int, default=8765)
ap.add_argument("--bind", default="127.0.0.1")
ap.add_argument("--log", default="fake_telegram_requests.jsonl")
args = ap.parse_args()
counter = {"n": 0}


class H(BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n).decode("utf-8", "replace")
        ctype = self.headers.get("Content-Type", "")
        if "json" in ctype:
            params = json.loads(raw or "{}")
        else:
            params = {k: v[0] for k, v in urllib.parse.parse_qs(raw).items()}
        parts = self.path.strip("/").split("/")
        token = parts[0][3:] if parts and parts[0].startswith("bot") else None
        method = parts[1] if len(parts) > 1 else None
        rec = {"received_at": time.strftime("%Y-%m-%d %H:%M:%S"), "t": time.time(),
               "path_method": method, "token": token, "params": params,
               "client": self.client_address[0]}
        if token == "BAD":
            code, resp = 401, {"ok": False, "error_code": 401, "description": "Unauthorized"}
        elif method != "sendMessage":
            code, resp = 404, {"ok": False, "error_code": 404, "description": "Not Found"}
        else:
            counter["n"] += 1
            code, resp = 200, {"ok": True, "result": {"message_id": counter["n"], "date": int(time.time()),
                                                     "chat": {"id": params.get("chat_id")}, "text": params.get("text")}}
        rec["response_code"] = code
        with open(args.log, "a") as f:
            f.write(json.dumps(rec) + "\n")
        print("fake-telegram:", json.dumps(rec), flush=True)
        body = json.dumps(resp).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


ThreadingHTTPServer((args.bind, args.port), H).serve_forever()
