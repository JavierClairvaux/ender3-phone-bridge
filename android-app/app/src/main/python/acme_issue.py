"""ACME (RFC 8555) issuance for Printer Bridge: Let's Encrypt, DNS-01 via the GoDaddy API.

Called from Kotlin (TlsManager.issue) on a background thread, never on the printer thread.
Uses the `acme` + `josepy` libraries (certbot's client library) on top of `cryptography`.

Flow: account key (per directory, kept in tls/) -> account (new, or reuse) -> order ->
DNS-01: add the TXT record at _acme-challenge.<host> through GoDaddy, preserving any TXT
records already at that name -> wait until public resolvers (DNS-over-HTTPS) see it ->
answer the challenge -> finalize with a CSR for a fresh ECDSA P-256 key -> install the
chain into the server PKCS12 (tls_tool.install_pem) -> ALWAYS restore the TXT name to its
previous content (also on failure), and verify that.

Secrets: the GoDaddy "KEY:SECRET" string arrives as an argument and is used only in the
Authorization header. It, the ACME account key and the certificate key are never logged,
returned or written anywhere except (account key) tls/acme_account_<env>.pem, mode 0600.
Progress messages go to `progress.step(str)` and must stay secret-free.
"""

import datetime
import json
import os
import time

import josepy as jose
import requests
from acme import challenges, client as acme_client, crypto_util, errors as acme_errors, messages
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec, rsa

import tls_tool

DIRECTORIES = {
    "staging": "https://acme-staging-v02.api.letsencrypt.org/directory",
    "production": "https://acme-v02.api.letsencrypt.org/directory",
}
GODADDY_API = "https://api.godaddy.com/v1"
DOH = [("google", "https://dns.google/resolve"), ("cloudflare", "https://cloudflare-dns.com/dns-query")]
USER_AGENT = "printer-bridge-acme/1.0"


class IssueError(Exception):
    pass


class _GoDaddy:
    def __init__(self, credentials, zone):
        self._h = {"Authorization": "sso-key " + credentials, "Accept": "application/json",
                   "Content-Type": "application/json", "User-Agent": USER_AGENT}
        self.zone = zone

    def _url(self, name):
        return "%s/domains/%s/records/TXT/%s" % (GODADDY_API, self.zone, name)

    @staticmethod
    def _check(r, what):
        if r.status_code >= 300:
            # GoDaddy error bodies are JSON {code, message}; they never contain the credentials
            raise IssueError("GoDaddy %s failed: HTTP %d %s" % (what, r.status_code, r.text[:200]))

    def get(self, name):
        r = requests.get(self._url(name), headers=self._h, timeout=30)
        self._check(r, "GET TXT")
        return [{"data": x["data"], "ttl": max(600, int(x.get("ttl", 600)))} for x in r.json()]

    def put(self, name, records):
        r = requests.put(self._url(name), headers=self._h, data=json.dumps(records), timeout=30)
        self._check(r, "PUT TXT")

    def delete(self, name):
        r = requests.delete(self._url(name), headers=self._h, timeout=30)
        if r.status_code not in (204, 404):
            self._check(r, "DELETE TXT")


def _doh_txt(url, fqdn):
    r = requests.get(url, params={"name": fqdn, "type": "TXT"},
                     headers={"Accept": "application/dns-json", "User-Agent": USER_AGENT}, timeout=15)
    r.raise_for_status()
    out = []
    for a in r.json().get("Answer", []) or []:
        if a.get("type") == 16:
            out.append("".join(part for part in a.get("data", "").split('"') if part.strip() and part != " "))
    return out


def _wait_propagation(fqdn, values, progress, timeout_s, first_delay_s=20, every_s=15):
    """Poll DNS-over-HTTPS resolvers until one of them returns all `values` (a hint that the
    authoritative servers have them; Let's Encrypt queries the authoritative servers itself)."""
    time.sleep(first_delay_s)   # avoid caching a negative answer before GoDaddy publishes
    deadline = time.time() + timeout_s
    while True:
        for name, url in DOH:
            try:
                seen = _doh_txt(url, fqdn)
                if all(v in seen for v in values):
                    progress.step("TXT visible via %s DNS-over-HTTPS" % name)
                    time.sleep(10)
                    return
            except Exception as e:
                progress.step("DoH %s query failed: %s" % (name, type(e).__name__))
        if time.time() > deadline:
            raise IssueError("TXT record for %s not visible via DNS-over-HTTPS after %d s" % (fqdn, timeout_s))
        time.sleep(every_s)


def _account(workdir, env, email, directory_url, progress):
    key_path = os.path.join(workdir, "acme_account_%s.pem" % env)
    kid_path = os.path.join(workdir, "acme_account_%s.json" % env)
    if os.path.exists(key_path):
        with open(key_path, "rb") as f:
            priv = serialization.load_pem_private_key(f.read(), password=None)
    else:
        priv = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        tls_tool._write_atomic(key_path, priv.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                                             serialization.NoEncryption()))
    jwk = jose.JWKRSA(key=priv)
    net = acme_client.ClientNetwork(jwk, user_agent=USER_AGENT)
    directory = acme_client.ClientV2.get_directory(directory_url, net)
    acme = acme_client.ClientV2(directory, net=net)
    kid = None
    if os.path.exists(kid_path):
        with open(kid_path) as f:
            meta = json.load(f)
        if meta.get("directory") == directory_url:
            kid = meta.get("kid")
    if kid:
        net.account = messages.RegistrationResource(uri=kid, body=messages.Registration())
        progress.step("using existing ACME account")
    else:
        try:
            regr = acme.new_account(messages.NewRegistration.from_data(email=email, terms_of_service_agreed=True))
            kid = regr.uri
            progress.step("created ACME account (terms of service agreed)")
        except acme_errors.ConflictError as e:
            kid = e.location
            net.account = messages.RegistrationResource(uri=kid, body=messages.Registration())
            progress.step("ACME account already existed for this key")
        tls_tool._write_atomic(kid_path, json.dumps({"directory": directory_url, "kid": kid, "email": email}).encode())
    return acme, jwk


def issue(workdir, env, email, domain, zone, credentials, p12_path, p12_password, progress,
          propagation_timeout_s=600):
    """Issue a certificate for `domain` and install it. Returns a JSON string with the
    certificate info and the cleanup result. Raises IssueError/acme errors on failure
    (after cleaning up the TXT record)."""
    if env not in DIRECTORIES:
        raise IssueError("unknown ACME directory %r" % env)
    directory_url = DIRECTORIES[env]
    if not domain.endswith("." + zone) and domain != zone:
        raise IssueError("domain %s is not inside DNS zone %s" % (domain, zone))
    started = time.time()
    progress.step("ACME %s: account" % env)
    acme, jwk = _account(workdir, env, email, directory_url, progress)

    key = ec.generate_private_key(ec.SECP256R1())
    key_pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                serialization.NoEncryption())
    csr_pem = crypto_util.make_csr(key_pem, [domain])
    progress.step("new order for %s" % domain)
    order = acme.new_order(csr_pem)

    todo = []
    for authz in order.authorizations:
        if authz.body.status == messages.STATUS_VALID:
            continue
        dns = [c for c in authz.body.challenges if isinstance(c.chall, challenges.DNS01)]
        if not dns:
            raise IssueError("no dns-01 challenge offered for %s" % authz.body.identifier.value)
        response, validation = dns[0].response_and_validation(jwk)
        todo.append((dns[0], response, validation))

    gd = _GoDaddy(credentials, zone)
    fqdn = "_acme-challenge." + domain
    rel = fqdn[: -(len(zone) + 1)]
    cleanup = {"name": fqdn, "restored": None, "previous_records": None}
    previous = None
    try:
        if todo:
            previous = gd.get(rel)
            cleanup["previous_records"] = len(previous)
            values = [v for _, _, v in todo]
            gd.put(rel, previous + [{"data": v, "ttl": 600} for v in values])
            progress.step("TXT record added at %s (%d existing record(s) preserved)" % (fqdn, len(previous)))
            _wait_propagation(fqdn, values, progress, propagation_timeout_s)
            for challb, response, _ in todo:
                acme.answer_challenge(challb, response)
            progress.step("challenge answered; waiting for validation")
        deadline = datetime.datetime.now() + datetime.timedelta(seconds=300)
        order = acme.poll_and_finalize(order, deadline=deadline)
        progress.step("order finalized; installing certificate")
        info = json.loads(tls_tool.install_pem(p12_path, p12_password, key_pem.decode("ascii"), order.fullchain_pem))
    finally:
        if previous is not None:
            try:
                if previous:
                    gd.put(rel, previous)
                else:
                    gd.delete(rel)
                left = gd.get(rel)
                cleanup["restored"] = sorted(x["data"] for x in left) == sorted(x["data"] for x in previous)
                cleanup["records_now"] = len(left)
                progress.step("TXT name restored (%d record(s) now)" % len(left))
            except Exception as e:
                cleanup["restored"] = False
                cleanup["error"] = "%s: %s" % (type(e).__name__, str(e)[:200])
                progress.step("TXT cleanup FAILED: %s" % type(e).__name__)
    return json.dumps({"cert": info, "cleanup": cleanup, "directory": env, "seconds": round(time.time() - started, 1)})
