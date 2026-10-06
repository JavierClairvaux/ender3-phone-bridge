#!/usr/bin/env python3
"""HTTP/HTTPS listener checks for Printer Bridge on the emulator (fake backend).

Ports on the laptop:
  --http   http://127.0.0.1:28080  (`adb emu redir tcp:28080:8080`: arrives via the emulated NIC)
  --http-lo http://127.0.0.1:18080 (`adb forward tcp:18080 tcp:8080`: arrives on the device's loopback)
  --https  127.0.0.1:28443          (`adb emu redir tcp:28443:8443`)

Covers: HTTP-only default (TLS off: no HTTPS listener, no certificate), refusal to turn both
listeners off, TLS on at runtime with a self-signed certificate verified against its CA (curl,
the official MCP SDK), dashboard over HTTPS, HTTPS-only mode, localhost-only HTTP, certificate
hot reload during a running print, and TLS off/on again. Needs `mcp` installed (same venv as
e2e_fake.py), curl, openssl.
"""
import argparse
import asyncio
import json
import subprocess
import time
import urllib.request

ap = argparse.ArgumentParser()
ap.add_argument("--http", default="http://127.0.0.1:28080")
ap.add_argument("--http-lo", default="http://127.0.0.1:18080")
ap.add_argument("--https-port", type=int, default=28443)
ap.add_argument("--domain", default="printer.theconsortio.xyz")
ap.add_argument("--token", required=True)
ap.add_argument("--out", required=True, help="evidence path prefix")
ap.add_argument("--file", default="short.gcode")
args = ap.parse_args()
HTTPS = "https://%s:%d" % (args.domain, args.https_port)
RESOLVE = "%s:%d:127.0.0.1" % (args.domain, args.https_port)
CA = args.out + "_ca.pem"
results = []


def check(name, cond, detail=""):
    results.append({"check": name, "pass": bool(cond), "detail": detail})
    print(("PASS " if cond else "FAIL ") + name + (" :: " + str(detail)[:300] if detail != "" else ""), flush=True)


def rest(base, method, path, body=None, auth=True, timeout=15):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if auth:
        req.add_header("Authorization", "Bearer " + args.token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw[:1] in (b"{", b"[") else raw.decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")
    except Exception as e:
        return None, str(e)


def curl(path, cacert=CA, method="GET", body=None, extra=()):
    cmd = ["curl", "-sS", "-m", "10", "--resolve", RESOLVE, "-o", "-", "-w", "\n%{http_code}"]
    if cacert:
        cmd += ["--cacert", cacert]
    if method != "GET":
        cmd += ["-X", method, "-H", "Authorization: Bearer " + args.token, "-H", "Content-Type: application/json",
                "-d", json.dumps(body or {})]
    cmd += list(extra) + [HTTPS + path]
    r = subprocess.run(cmd, capture_output=True, text=True)
    out = r.stdout.rsplit("\n", 1)
    code = int(out[1]) if len(out) == 2 and out[1].isdigit() else None
    return code, out[0], r.stderr.strip()


def served_serial():
    r = subprocess.run("openssl s_client -connect 127.0.0.1:%d -servername %s </dev/null 2>/dev/null | openssl x509 -noout -serial -issuer -subject -enddate"
                       % (args.https_port, args.domain), shell=True, capture_output=True, text=True)
    return r.stdout.strip()


def https_up():
    return curl("/api/tls", cacert=None, extra=("-k",))[0] == 200


def wait(pred, timeout, what, every=0.5):
    end = time.time() + timeout
    while time.time() < end:
        v = pred()
        if v:
            return v
        time.sleep(every)
    raise TimeoutError(what)


def fetch_ca():
    c, pem = rest(args.http, "GET", "/api/tls/ca.pem", auth=False)
    if c != 200:   # HTTP may be off; fetch it over HTTPS without verification (it is public)
        c2, pem, _ = curl("/api/tls/ca.pem", cacert=None, extra=("-k",))
    with open(CA, "w") as f:
        f.write(pem)
    return pem


async def mcp_over_https():
    import httpx2
    from mcp import ClientSession
    from mcp.client.streamable_http import streamable_http_client
    client = httpx2.AsyncClient(verify=CA, headers={"Authorization": "Bearer " + args.token}, timeout=30)
    url = "https://localhost:%d/mcp" % args.https_port      # the self-signed cert also covers localhost
    async with client, streamable_http_client(url, http_client=client) as (rd, wr), ClientSession(rd, wr) as s:
        init = await s.initialize()
        r = await s.call_tool("get_status", {})
        st = json.loads(r.content[0].text)
        return init.server_info.name, st["connection"]["state"]


def set_cfg(base_kind, body):
    if base_kind == "http":
        return rest(args.http, "POST", "/api/config", body)
    c, b, err = curl("/api/config", method="POST", body=body)
    try:
        return c, json.loads(b)
    except ValueError:
        return c, b or err


def main():
    # ---- A: HTTP-only default
    c, tls = rest(args.http, "GET", "/api/tls", auth=False)
    check("default_tls_disabled_no_https_listener", c == 200 and tls["enabled"] is False and tls["https_running"] is False
          and not https_up(), {k: tls.get(k) for k in ("enabled", "https_running", "source")})
    c, s = rest(args.http, "GET", "/api/status", auth=False)
    check("status_service_has_tls_object", c == 200 and s["service"]["tls"]["enabled"] is False, s["service"]["tls"])
    # ---- B: refuse to turn both listeners off
    c, b = set_cfg("http", {"http_enabled": False})
    c2, _ = rest(args.http, "GET", "/api/status", auth=False)
    _, cfg = rest(args.http, "GET", "/api/config", auth=False)
    check("refuse_http_off_while_tls_off", c == 400 and "refusing" in b.get("error", "") and c2 == 200
          and cfg["tls"]["http_enabled"] is True and cfg["tls"]["tls_enabled"] is False, (c, b.get("error")))
    # ---- C: TLS on at runtime -> self-signed, verified
    c, b = set_cfg("http", {"tls_enabled": True, "tls_domain": args.domain})
    check("enable_tls_via_api", c == 200 and b["tls"]["tls_enabled"] is True, c)
    wait(https_up, 30, "https up")
    fetch_ca()
    c, tls = rest(args.http, "GET", "/api/tls", auth=False)
    check("self_signed_generated", tls["source"] == "self-signed" and tls["subject"] == args.domain and tls["https_running"],
          {k: tls.get(k) for k in ("source", "subject", "issuer", "not_after", "days_left", "san")})
    code, body, err = curl("/api/status")
    check("curl_https_verified_with_cacert", code == 200 and json.loads(body)["connection"]["state"] == "connected", code)
    code, body, err = curl("/api/status", cacert=None)
    check("curl_https_untrusted_without_ca_fails", code in (None, 0), err[:120])
    code, body, err = curl("/")
    check("dashboard_over_https_verified", code == 200 and "Printer Bridge" in body and "setMotionControls" in body, code)
    subprocess.run(["chromium", "--headless=new", "--disable-gpu", "--hide-scrollbars", "--ignore-certificate-errors",
                    "--host-resolver-rules=MAP %s 127.0.0.1" % args.domain, "--screenshot=%s_dashboard_https.png" % args.out,
                    "--window-size=1100,1500", "--virtual-time-budget=6000", HTTPS + "/"],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=90)
    name, state = asyncio.run(mcp_over_https())
    check("mcp_over_https_sdk_verifies_cert", name == "printer-bridge" and state == "connected", (name, state))
    c, _ = rest(args.http, "GET", "/api/status", auth=False)
    check("plain_http_still_works_with_tls_on", c == 200)
    print("served:", served_serial().replace("\n", " | "))
    # ---- D: HTTPS-only
    c, b = set_cfg("https", {"http_enabled": False})
    check("http_off_via_https", c == 200 and b["tls"]["http_enabled"] is False, c)
    time.sleep(2)
    c1, _ = rest(args.http, "GET", "/api/status", auth=False, timeout=4)
    c2, _ = rest(args.http_lo, "GET", "/api/status", auth=False, timeout=4)
    code, body, _ = curl("/api/status")
    check("https_only_mode", c1 is None and c2 is None and code == 200, (c1, c2, code))
    c, b = set_cfg("https", {"tls_enabled": False})
    code2, _, _ = curl("/api/tls")
    c3, cfgb, _ = curl("/api/config")
    cfg = json.loads(cfgb)
    check("refuse_tls_off_while_http_off_no_change", c == 400 and code2 == 200 and cfg["tls"]["tls_enabled"] is True
          and cfg["tls"]["http_enabled"] is False, (c, b.get("error") if isinstance(b, dict) else b))
    c, b = set_cfg("https", {"http_enabled": True})
    time.sleep(2)
    c1, _ = rest(args.http, "GET", "/api/status", auth=False)
    check("http_back_on_via_https", c == 200 and c1 == 200, (c, c1))
    # ---- E: localhost-only HTTP
    set_cfg("http", {"http_bind": "127.0.0.1"})
    time.sleep(2)
    c1, _ = rest(args.http, "GET", "/api/status", auth=False, timeout=4)
    c2, s2 = rest(args.http_lo, "GET", "/api/status", auth=False, timeout=4)
    check("http_bind_localhost", c1 is None and c2 == 200 and s2["service"]["http_bind"] == "127.0.0.1", (c1, c2))
    rest(args.http_lo, "POST", "/api/config", {"http_bind": "0.0.0.0"})
    time.sleep(2)
    c1, _ = rest(args.http, "GET", "/api/status", auth=False)
    check("http_bind_all_again", c1 == 200, c1)
    # ---- F: hot reload during a print
    rest(args.http, "POST", "/api/sim/config", {"line_delay_ms": 40, "time_scale": 5})
    c, j = rest(args.http, "POST", "/api/print", {"file": args.file})
    wait(lambda: (rest(args.http, "GET", "/api/status", auth=False)[1].get("job") or {}).get("lines_done", 0) > 80, 120, "printing")
    _, s0 = rest(args.http, "GET", "/api/status", auth=False)
    before = served_serial()
    c, b, _ = curl("/api/tls/self_signed", method="POST", body={})
    wait(lambda: (lambda x: x and x != before)(served_serial()), 20, "new cert served")
    after = served_serial()
    fetch_ca()
    code, body, _ = curl("/api/status")
    s1 = json.loads(body)
    check("hot_reload_new_cert_served_and_verified", before != after and code == 200, {"before": before.split("\n")[0], "after": after.split("\n")[0]})
    check("hot_reload_print_unaffected_no_restart", s1["job"]["state"] == "printing" and s1["job"]["lines_done"] > s0["job"]["lines_done"]
          and s1["service"]["uptime_s"] >= s0["service"]["uptime_s"] and s1["job"]["id"] == s0["job"]["id"],
          (s0["job"]["lines_done"], s1["job"]["lines_done"], s0["service"]["uptime_s"], s1["service"]["uptime_s"]))
    rest(args.http, "POST", "/api/cancel")
    wait(lambda: rest(args.http, "GET", "/api/status", auth=False)[1]["job"]["state"] == "cancelled", 60, "cancelled")
    # ---- G: TLS off -> listener closed; on again
    c, b = set_cfg("http", {"tls_enabled": False})
    time.sleep(2)
    _, tls = rest(args.http, "GET", "/api/tls", auth=False)
    check("tls_off_closes_https", c == 200 and not https_up() and tls["enabled"] is False and tls["https_running"] is False, c)
    set_cfg("http", {"tls_enabled": True})
    wait(https_up, 30, "https up again")
    code, _, _ = curl("/api/status")
    check("tls_on_again_same_cert", code == 200, code)

    # ---- H: ACME issuance guards (no network needed: refused before anything starts)
    rest(args.http, "POST", "/api/sim/config", {"line_delay_ms": 40, "time_scale": 5})
    rest(args.http, "POST", "/api/print", {"file": args.file})
    wait(lambda: (rest(args.http, "GET", "/api/status", auth=False)[1].get("job") or {}).get("state") == "printing", 60, "printing H")
    c, b = rest(args.http, "POST", "/api/tls/issue")
    check("acme_issue_refused_during_print_or_unconfigured", c == 409 and ("print job is active" in b.get("error", "") or "not set" in b.get("error", "")), (c, b.get("error")))
    rest(args.http, "POST", "/api/cancel")
    wait(lambda: rest(args.http, "GET", "/api/status", auth=False)[1]["job"]["state"] == "cancelled", 60, "cancelled H")
    set_cfg("http", {"tls_enabled": False})
    time.sleep(2)
    c, b = rest(args.http, "POST", "/api/tls/issue")
    _, tls = rest(args.http, "GET", "/api/tls", auth=False)
    check("acme_issue_refused_with_tls_off_no_renewal_due", c == 409 and "TLS is disabled" in b.get("error", "") and tls["renewal_due"] is None, (c, b.get("error")))
    set_cfg("http", {"tls_enabled": True})
    wait(https_up, 30, "https up (end)")

    with open(args.out + "_results.json", "w") as f:
        json.dump(results, f, indent=1)
    print("\n%d/%d checks passed" % (sum(r["pass"] for r in results), len(results)))


main()
