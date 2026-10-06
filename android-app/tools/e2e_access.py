#!/usr/bin/env python3
"""Source-address restriction checks (allowed_cidrs / restrict_to_tailnet) on the emulator.

Emulator traffic through `adb emu redir` arrives from the host side (10.0.2.2); traffic
through `adb forward` arrives on the device's loopback, which is always allowed. Needs TLS on
(HTTPS on --https-port via `adb emu redir tcp:28443:8443`) to check both listeners.
"""
import argparse
import json
import re
import subprocess
import time
import urllib.request

ap = argparse.ArgumentParser()
ap.add_argument("--http", default="http://127.0.0.1:28080")
ap.add_argument("--http-lo", default="http://127.0.0.1:18080")
ap.add_argument("--https", default="https://127.0.0.1:28443")
ap.add_argument("--token", required=True)
ap.add_argument("--adb", default="adb")
ap.add_argument("--serial", default="emulator-5554")
ap.add_argument("--svc", default="com.javcabr.printerbridge/.service.PrinterService")
ap.add_argument("--out", required=True)
args = ap.parse_args()
results = []


def check(name, cond, detail=""):
    results.append({"check": name, "pass": bool(cond), "detail": detail})
    print(("PASS " if cond else "FAIL ") + name + (" :: " + str(detail)[:250] if detail != "" else ""), flush=True)


def req(url, method="GET", body=None, auth=True):
    cmd = ["curl", "-sk", "-m", "8", "-o", "-", "-w", "\n%{http_code}", "-X", method]
    if auth:
        cmd += ["-H", "Authorization: Bearer " + args.token]
    if body is not None:
        cmd += ["-H", "Content-Type: application/json", "-d", json.dumps(body)]
    r = subprocess.run(cmd + [url], capture_output=True, text=True)
    out = r.stdout.rsplit("\n", 1)
    code = int(out[1]) if len(out) == 2 and out[1].isdigit() else None
    try:
        b = json.loads(out[0])
    except Exception:
        b = out[0][:120]
    return code, b


def codes(base):
    return {"dashboard": req(base + "/", auth=False)[0], "status": req(base + "/api/status", auth=False)[0],
            "mcp": req(base + "/mcp", "POST", {"jsonrpc": "2.0", "id": 1, "method": "ping"})[0]}


def adb_extra(*extra):
    subprocess.run([args.adb, "-s", args.serial, "shell", "am", "start-foreground-service", "-n", args.svc] + list(extra),
                   capture_output=True, text=True)
    time.sleep(1.5)


# baseline: no restriction
c = codes(args.http); ch = codes(args.https)
_, s = req(args.http + "/api/status", auth=False)
check("default_allows_all_both_listeners", set(c.values()) | set(ch.values()) <= {200} and s["service"]["access"]["effective"] == ["any"],
      {"http": c, "https": ch, "access": s["service"]["access"]})
# junk CIDRs -> 400, nothing changed
bad = [req(args.http + "/api/config", "POST", {"allowed_cidrs": v})[0] for v in (["not-a-cidr"], ["10.0.0.0/33"], ["999.1.1.1/8"], ["example.com/24"], "10.0.0.0/8,junk")]
_, cfg = req(args.http + "/api/config", auth=False)
check("junk_cidrs_rejected_400", bad == [400] * 5 and cfg["access"]["allowed_cidrs"] == [], bad)
# self lock-out guard (also reveals the source address the app sees for redir traffic)
code, b = req(args.http + "/api/config", "POST", {"restrict_to_tailnet": True})
m = re.search(r"address (\S+?);", b.get("error", "") if isinstance(b, dict) else "")
src = m.group(1) if m else None
check("self_lockout_refused_409_no_change", code == 409 and src is not None and req(args.http + "/api/status", auth=False)[0] == 200, (code, src))
# allowing the own source keeps access
code, b = req(args.http + "/api/config", "POST", {"allowed_cidrs": [src + "/32", "100.64.0.0/10"]})
c = codes(args.http); ch = codes(args.https)
check("allowed_cidrs_including_self_still_200", code == 200 and set(c.values()) | set(ch.values()) <= {200}, {"src": src, "http": c, "https": ch})
# from loopback: tailnet only -> redir (non-tailnet) gets 403 everywhere, loopback still fine
code, b = req(args.http_lo + "/api/config", "POST", {"restrict_to_tailnet": True, "allowed_cidrs": []})
time.sleep(0.5)
c = codes(args.http); ch = codes(args.https); lo = codes(args.http_lo)
_, s = req(args.http_lo + "/api/status", auth=False)
check("tailnet_only_blocks_other_sources_403_both_listeners", code == 200 and set(c.values()) == {403} and set(ch.values()) == {403}
      and set(lo.values()) <= {200} and s["service"]["access"]["effective"] == ["100.64.0.0/10", "loopback"],
      {"http": c, "https": ch, "loopback": lo, "access": s["service"]["access"]})
# recovery over adb (lock-out escape hatch)
adb_extra("--ez", "restrict_to_tailnet", "false")
c = codes(args.http)
check("adb_extra_clears_restriction", set(c.values()) <= {200}, c)
# explicit list that excludes the emulator host -> 403; cleared with --es allowed_cidrs ,
req(args.http_lo + "/api/config", "POST", {"allowed_cidrs": ["192.0.2.0/24"]})
time.sleep(0.5)
c = codes(args.http); ch = codes(args.https)
check("allowed_cidrs_excluding_source_403", set(c.values()) == {403} and set(ch.values()) == {403}, {"http": c, "https": ch})
adb_extra("--es", "allowed_cidrs", ",")
c = codes(args.http); ch = codes(args.https)
_, s = req(args.http + "/api/status", auth=False)
check("adb_extra_clears_allowed_cidrs", set(c.values()) | set(ch.values()) <= {200} and s["service"]["access"]["effective"] == ["any"], {"http": c, "https": ch})

with open(args.out + "_results.json", "w") as f:
    json.dump(results, f, indent=1)
print("\n%d/%d checks passed" % (sum(r["pass"] for r in results), len(results)))
