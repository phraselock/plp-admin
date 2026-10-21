# SmartCard PKI Scripts

Lab tools for creating and testing the SmartCard certificate infrastructure.

## Overview

| Script | Purpose |
|---|---|
| `ca-create.sh` | Creates the SmartCard CA (once) |
| `ca-issue-card.sh` | Issues a card certificate for a domain user |
| `ca-issue-kdc.sh` | Issues the KDC certificate for the DC (lab only) |

**These scripts are lab tools.** In production the backend issues card certificates
at runtime via BouncyCastle (Java), using the same extensions documented here.
`ca-issue-kdc.sh` is always a lab tool — in a real domain the enterprise CA handles
KDC certificates automatically.

## Quick Start

1. Copy `pki.conf` and adapt it to your environment.
2. Source it and run the scripts:

```sh
cp pki.conf pki.conf.local
# edit pki.conf.local: CA_DIR, CARD_DOMAIN, CRL_URL, DC_FQDN, REALM, DOMAIN

. ./pki.conf.local

./ca-create.sh                          # create the CA
./ca-issue-card.sh mmustermann          # issue card for mmustermann@CARD_DOMAIN
./ca-issue-kdc.sh                       # (lab only) issue KDC certificate
```

The `.p12` file in `<CA_DIR>/issued/` is the bundle that goes to the smartphone.

## CA Directory Layout

After `ca-create.sh`:

```
ca/
  ca.crt            CA certificate (import into Windows Root + NTAuth stores)
  ca.key            CA private key — keep secret, never commit
  ca.cnf            openssl CA configuration
  plc-test-ca.crl   CRL (DER) — publish at CRL_URL
  db/               issued-cert index, serial, CRL number
  issued/           card and KDC certificates
```

## Certificate Extensions

### Card certificate (`ca-issue-card.sh`)

Required by Windows SmartCard logon:

| Extension | Value |
|---|---|
| subjectAltName | `otherName:1.3.6.1.4.1.311.20.2.3;UTF8:<upn>` (UPN) |
| extendedKeyUsage | `clientAuth`, `1.3.6.1.4.1.311.20.2.2` (Smart Card Logon) |
| keyUsage | `digitalSignature, keyEncipherment` (RSA) |
| | `digitalSignature, keyAgreement` (ECC) |
| crlDistributionPoints | `URI:<CRL_URL>` |

> **ECC note:** ECC (P-256) requires `keyAgreement` for the OpenSC minidriver.
> The Windows inbox minidriver does not support ECC cards.

### KDC certificate (`ca-issue-kdc.sh`)

Required by Kerberos for SmartCard pre-authentication:

| Extension | Value |
|---|---|
| extendedKeyUsage | `1.3.6.1.5.2.3.5` (KDC Auth), `1.3.6.1.4.1.311.20.2.2` (SC Logon), `serverAuth`, `clientAuth` |
| subjectAltName | `DNS:<DC_FQDN>`, `DNS:<DOMAIN>`, `otherName:krbtgt/<REALM>` (1.3.6.1.5.2.2) |
| crlDistributionPoints | `URI:<CRL_URL>` |

## Windows Prerequisites

Before SmartCard logon works:

1. Import `ca.crt` into the **Root** and **NTAuth** certificate stores on the domain controller
   (or via Group Policy for the whole domain).
2. Publish `plc-test-ca.crl` at `CRL_URL` so Windows can check revocation.
3. The domain controller must have a KDC certificate (either from the enterprise CA or
   from `ca-issue-kdc.sh` in the lab).

See `docs/domain-logon.md` in the UMDF project for the full setup procedure.
