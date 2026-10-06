"""TLS helpers for Printer Bridge (run under Chaquopy, called from Kotlin TlsManager).

- make_self_signed(): a throwaway local CA + a server certificate signed by it, so
  HTTPS works immediately before (or without) an ACME certificate. Clients that
  want to verify it trust the CA certificate (GET /api/tls/ca.pem).
- install_pem(): builds the server PKCS12 from a private key + certificate chain
  (used by the ACME issuer).
- p12_info() / chain_pem(): public certificate details for /api/tls.

The PKCS12 uses the legacy PBE-SHA1-3DES + SHA1-MAC encoding because Android's
PKCS12 KeyStore (BouncyCastle) reliably reads it. The file is written to a temp
name and atomically renamed, so a reader never sees a half-written keystore.

Never log private keys or passwords.
"""

import datetime
import ipaddress
import json
import os

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

FRIENDLY_NAME = b"printer-bridge"


def _encryption(password):
    return (serialization.PrivateFormat.PKCS12.encryption_builder()
            .kdf_rounds(50000)
            .key_cert_algorithm(pkcs12.PBES.PBESv1SHA1And3KeyTripleDESCBC)
            .hmac_hash(hashes.SHA1())
            .build(password.encode("utf-8")))


def _write_atomic(path, data, mode=0o600):
    tmp = path + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, mode)
    with os.fdopen(fd, "wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def write_p12(path, key, cert, chain, password):
    data = pkcs12.serialize_key_and_certificates(FRIENDLY_NAME, key, cert, list(chain) or None,
                                                 _encryption(password))
    _write_atomic(path, data)


def _now():
    return datetime.datetime.now(datetime.timezone.utc)


def make_self_signed(path, password, domain, days=397):
    """New local CA + server cert for `domain` (plus localhost / 127.0.0.1)."""
    domain = (domain or "").strip() or "printer-bridge.local"
    now = _now()
    ca_key = ec.generate_private_key(ec.SECP256R1())
    ca_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Printer Bridge local CA"),
                         x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Printer Bridge (self-signed)")])
    ca_ski = x509.SubjectKeyIdentifier.from_public_key(ca_key.public_key())
    ca = (x509.CertificateBuilder()
          .subject_name(ca_name).issuer_name(ca_name)
          .public_key(ca_key.public_key())
          .serial_number(x509.random_serial_number())
          .not_valid_before(now - datetime.timedelta(hours=1))
          .not_valid_after(now + datetime.timedelta(days=days + 1))
          .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
          .add_extension(x509.KeyUsage(digital_signature=True, content_commitment=False, key_encipherment=False,
                                       data_encipherment=False, key_agreement=False, key_cert_sign=True,
                                       crl_sign=True, encipher_only=False, decipher_only=False), critical=True)
          .add_extension(ca_ski, critical=False)
          .sign(ca_key, hashes.SHA256()))

    key = ec.generate_private_key(ec.SECP256R1())
    sans = [x509.DNSName(domain), x509.DNSName("localhost"), x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]
    leaf = (x509.CertificateBuilder()
            .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, domain)]))
            .issuer_name(ca_name)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(hours=1))
            .not_valid_after(now + datetime.timedelta(days=days))
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .add_extension(x509.KeyUsage(digital_signature=True, content_commitment=False, key_encipherment=False,
                                         data_encipherment=False, key_agreement=False, key_cert_sign=False,
                                         crl_sign=False, encipher_only=False, decipher_only=False), critical=True)
            .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
            .add_extension(x509.SubjectAlternativeName(sans), critical=False)
            .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()), critical=False)
            .add_extension(x509.AuthorityKeyIdentifier.from_issuer_subject_key_identifier(ca_ski), critical=False)
            .sign(ca_key, hashes.SHA256()))
    write_p12(path, key, leaf, [ca], password)
    return p12_info(path, password)   # the CA private key is discarded on purpose


def install_pem(path, password, key_pem, fullchain_pem):
    """Build the server PKCS12 from a PEM private key and a PEM chain (leaf first)."""
    key = serialization.load_pem_private_key(key_pem.encode("ascii"), password=None)
    certs = x509.load_pem_x509_certificates(fullchain_pem.encode("ascii"))
    if not certs:
        raise ValueError("empty certificate chain")
    write_p12(path, key, certs[0], certs[1:], password)
    return p12_info(path, password)


def _load(path, password):
    with open(path, "rb") as f:
        key, cert, chain = pkcs12.load_key_and_certificates(f.read(), password.encode("utf-8"))
    return key, cert, chain or []


def _name(n, oid):
    v = n.get_attributes_for_oid(oid)
    return v[0].value if v else None


def p12_info(path, password):
    _, cert, chain = _load(path, password)
    try:
        san = cert.extensions.get_extension_for_class(x509.SubjectAlternativeName).value
        dns = san.get_values_for_type(x509.DNSName)
        ips = [str(i) for i in san.get_values_for_type(x509.IPAddress)]
    except x509.ExtensionNotFound:
        dns, ips = [], []
    nb, na = cert.not_valid_before_utc, cert.not_valid_after_utc
    info = {
        "subject_cn": _name(cert.subject, NameOID.COMMON_NAME),
        "issuer_cn": _name(cert.issuer, NameOID.COMMON_NAME),
        "issuer_o": _name(cert.issuer, NameOID.ORGANIZATION_NAME),
        "san_dns": dns, "san_ip": ips,
        "not_before": nb.isoformat(), "not_after": na.isoformat(),
        "days_left": round((na - _now()).total_seconds() / 86400, 1),
        "serial": format(cert.serial_number, "x"),
        "sha256": cert.fingerprint(hashes.SHA256()).hex(),
        "chain_length": 1 + len(chain),
        "chain_issuers": [_name(c.issuer, NameOID.COMMON_NAME) for c in chain],
    }
    return json.dumps(info)


def chain_pem(path, password):
    """Public certificate chain (leaf first) as PEM."""
    _, cert, chain = _load(path, password)
    return "".join(c.public_bytes(serialization.Encoding.PEM).decode("ascii") for c in [cert] + list(chain))


def ca_pem(path, password):
    """Last certificate of the chain: the local CA for self-signed certs (what clients should trust)."""
    _, cert, chain = _load(path, password)
    last = chain[-1] if chain else cert
    return last.public_bytes(serialization.Encoding.PEM).decode("ascii")
