#!/usr/bin/env python3
"""Helpers that handle the GoDaddy API credentials WITHOUT ever printing them.

The credentials ("KEY:SECRET") are read from an env file (default variable name
GODADDY_API_TOKEN) or the environment, kept in memory only, and:

  push    POST them to the app (/api/config godaddy_credentials); prints only the HTTP
          status and godaddy_credentials_set.
  txt     read-only GoDaddy GET of the TXT records at <name>; prints only the record count.
  scan    count occurrences of the credentials (whole, key part, secret part) in files/dirs;
          prints only counts.

Never add a print of the value, the response body of a request carrying it, or its length.
"""
import argparse
import json
import os
import ssl
import subprocess
import sys
import urllib.request


def load(env_file, var):
    v = os.environ.get(var)
    if not v and env_file:
        with open(env_file) as f:
            for line in f:
                line = line.strip()
                if line.startswith(var + "="):
                    v = line.split("=", 1)[1].strip().strip('"').strip("'")
    if not v or ":" not in v:
        sys.exit("credentials not found (expected %s=KEY:SECRET)" % var)
    return v


def push(a, creds):
    ctx = None
    if a.base.startswith("https"):
        ctx = ssl.create_default_context(cafile=a.cacert) if a.cacert else ssl._create_unverified_context()
    req = urllib.request.Request(a.base + "/api/config", data=json.dumps({"godaddy_credentials": creds}).encode(),
                                 method="POST", headers={"Content-Type": "application/json", "Authorization": "Bearer " + a.token})
    try:
        with urllib.request.urlopen(req, timeout=20, context=ctx) as r:
            code, body = r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        code, body = e.code, {}
    print("push: HTTP %s, godaddy_credentials_set=%s" % (code, (body.get("tls") or {}).get("godaddy_credentials_set")))


def txt(a, creds):
    req = urllib.request.Request("https://api.godaddy.com/v1/domains/%s/records/TXT/%s" % (a.zone, a.name),
                                 headers={"Authorization": "sso-key " + creds, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as r:
        recs = json.loads(r.read())
    print("godaddy TXT %s.%s: %d record(s)" % (a.name, a.zone, len(recs)))


def scan(a, creds):
    total = 0
    for label, needle in (("full", creds), ("key part", creds.split(":", 1)[0]), ("secret part", creds.split(":", 1)[1])):
        r = subprocess.run(["grep", "-rFcH", "--binary-files=text", "--", needle] + a.paths, capture_output=True, text=True)
        n = sum(int(line.rsplit(":", 1)[1]) for line in r.stdout.splitlines() if line.rsplit(":", 1)[-1].isdigit())
        total += n
        print("scan %-11s: %d occurrence(s)" % (label, n))
    print("scan total: %d" % total)


ap = argparse.ArgumentParser()
ap.add_argument("cmd", choices=["push", "txt", "scan"])
ap.add_argument("--env-file", default=None)
ap.add_argument("--var", default="GODADDY_API_TOKEN")
ap.add_argument("--base"); ap.add_argument("--token"); ap.add_argument("--cacert")
ap.add_argument("--zone", default="theconsortio.xyz"); ap.add_argument("--name", default="_acme-challenge.printer")
ap.add_argument("paths", nargs="*")
a = ap.parse_args()
c = load(a.env_file, a.var)
{"push": push, "txt": txt, "scan": scan}[a.cmd](a, c)
