# SmartCard PKI Scripts

Lab tools for creating and testing the SmartCard certificate infrastructure.

## Overview

| Script | Purpose |
|---|---|
| `ca-create.sh` | Creates the SmartCard CA (once); usable as root (`pathlen:1`) |
| `ca-reissue-root.sh` | Re-issues the certificate of an older CA (`pathlen:0`) with `pathlen:1`, same key and name |
| `ca-create-intermediate.sh` | Creates an issuing intermediate CA below the root |
| `ca-issue-kdc.sh` | Issues the KDC certificate for the DC (lab only) |

**These scripts are lab tools for the CA and the KDC certificate.** In production the backend issues card certificates
at runtime via BouncyCastle (Java), using the same extensions documented here.
`ca-issue-kdc.sh` is always a lab tool — in a real domain the enterprise CA handles
KDC certificates automatically.

## Quick Start

Edit `pki.conf` (folders, names, domain, DC), then pass it to every script as its first argument:

```sh
./ca-create.sh pki.conf                      # 1. create the root
./ca-create-intermediate.sh pki.conf         # 2. create the intermediate below it
./ca-issue-kdc.sh pki.conf                   # 3. (lab only) issue the KDC certificate
```

Without an intermediate skip step 2 and set `CA_DIR`/`CA_CRT`/`CA_KEY` in `pki.conf` to the root
(comments there). The `.p12` file in `<CA_DIR>/issued/` is the bundle that goes to the smartphone.

## Two-tier setup (root + intermediate)

`pki.conf` names every folder and file; nothing is searched for or guessed.
`ca-create-intermediate.sh` uses `ROOT_DIR` (its `root-ca.crt`/`root-ca.key`), writes `INTERMEDIATE_DIR`
and takes `INTERMEDIATE_CN` and `ROOT_CRL_URL`. `ca-issue-kdc.sh` then signs with the CA named by
`CA_DIR`, `CA_CRT`, `CA_KEY`. The same CA files are given to plp-admin (`ca.cert`, `ca.key`, `ca.chain`), which issues the cards.
Trust anchor on KDC and Windows is the **root** (`root-ca.crt`); keep the root key offline. For plp-admin set
`ca.cert`, `ca.key` and `ca.chain` in `smartcard.properties` (see the README in the project root).

File names by role: `root-ca.crt/.key` (root), `intermediate-ca.crt/.key` (intermediate), `ca-chain.pem`
(intermediate + root). `ca.cnf` and `db/` exist once per CA folder.

Only for a root made earlier with `pathlen:0`: `./ca-reissue-root.sh <root-crt> <root-key>` writes a new
`root-ca-pathlen1.crt` next to the input (same key and name). Not needed for a root made with the current `ca-create.sh`.

## CA Directory Layout

After `ca-create.sh`:

```
ca/
  root-ca.crt       CA certificate (Windows stores: see "Windows Prerequisites")
  root-ca.key       CA private key — keep secret, never commit
  ca.cnf            openssl CA configuration
  root-ca.crl       CRL of the root (DER) — publish at ROOT_CRL_URL
  db/               issued-cert index, serial, CRL number
  issued/           card and KDC certificates
```

## Certificate Extensions

### Card certificate (issued by plp-admin, `SmartCardCert.java`)

There is no script for cards: plp-admin issues them, and the table is what it puts into every card certificate
(Windows SmartCard logon requires these):

| Extension | Value |
|---|---|
| basicConstraints | `CA:FALSE` |
| subjectAltName | `otherName:1.3.6.1.4.1.311.20.2.3;UTF8:<upn>` (UPN) |
| extendedKeyUsage | `clientAuth`, `1.3.6.1.4.1.311.20.2.2` (Smart Card Logon) |
| keyUsage (critical) | `digitalSignature, keyEncipherment` (RSA) or `digitalSignature, keyAgreement` (ECC) |
| subjectKeyIdentifier, authorityKeyIdentifier | key identifiers of the card key and of the issuing CA |
| crlDistributionPoints | `URI:<card.crl.url>`, only if set in `smartcard.properties` |

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

1. Import the CA certificates on the Windows PC (elevated prompt). **Single CA** (no intermediate): `root-ca.crt`
   goes into **Root** and **NTAuth**. **Two-tier**: the root and the issuing CA go to different places:

   | Certificate | Store | Command (elevated prompt) |
   |---|---|---|
   | root (`root-ca.crt`) | Trusted Root | `certutil -addstore -f Root root-ca.crt` |
   | intermediate (`intermediate-ca.crt`) | Intermediate CAs | `certutil -addstore -f CA intermediate-ca.crt` |
   | intermediate (`intermediate-ca.crt`) | NTAuth (the CA that issues the cards) | `certutil -enterprise -addstore NTAuth intermediate-ca.crt` |

   This is the way for the **Samba lab domain**: it writes into the local store of the PC. For a **Microsoft AD
   domain** the certificates can instead be published to the whole domain with `certutil -dspublish -f <file> RootCA`
   / `SubCA` / `NTAuthCA` (needs Enterprise Admin rights; does not apply to the Samba lab) or distributed by Group Policy.
   To look at a store use `certutil -store` / `certutil -enterprise -store NTAuth`; never `-viewdelstore`, it **deletes**.

   Do this **after** the PC has joined the domain. If the certificates were imported before the join, run the
   NTAuth command again afterwards: on 2026-11-13 the entry was missing or ineffective after the join and the logon
   failed with "untrusted certification authority" (`0xC000038A`); importing it again fixed it. The full PC setup
   order is in `docs/domain-logon.md` of the UMDF project.

   The root does not go into NTAuth. On the Samba KDC `anchors.pem` holds the **root**, and `kdc.pem` should
   contain the KDC certificate followed by the intermediate so that Windows can build the chain.
2. Publish the CRLs (`root-ca.crl` at `ROOT_CRL_URL`, `intermediate-ca.crl` at `CRL_URL`) so Windows can check revocation.
   The web server must serve both file names (see `docs/smartcard-lab-samba.md`, "Revoking: where the CRL lives").
3. The domain controller must have a KDC certificate (either from the enterprise CA or
   from `ca-issue-kdc.sh` in the lab).

See `docs/domain-logon.md` in the UMDF project for the full setup procedure.
