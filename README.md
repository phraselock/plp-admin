# plp-admin

Combined API + admin UI service (replaces plp-backend and plp-custom). One process, port 7070.

- `/api/mqtt/v1/validate/*` – device API (client certificates)
- `/admin/*` – admin UI (KeePass, SmartCard, config)

---

## Installation (Debian / Raspberry Pi OS)

```sh
curl -sSL https://raw.githubusercontent.com/phraselock/plp-admin/main/install.sh | bash
```

Needs root (or sudo). The installer downloads the JAR of the latest GitHub release, asks for the settings with
`whiptail` (port, allowed IPs, KeePass, peer store, plp-core JWT, MQTT) and installs a systemd service.
Run it again to update: existing settings are offered as defaults, the admin token and the ECC key pair are kept.
`uninstall.sh` (download it by hand, there is deliberately no one-liner) removes service and files.

| What | Where |
|---|---|
| JAR, config, databases, certs | `/opt/phraselock/admin` (owner `phraselock`; the service writes the SQLite databases and the CRL there) |
| Service | `plp-admin` (user `phraselock`), log `/var/log/plp-admin.log` |
| nginx | `/etc/nginx/phraselock.d/plp-admin.conf` (`/admin/` to the plp-admin port, client certificate required) |
| CA keys for issuing client certificates | symlinks in `certs/CA/service` to `/opt/phraselock/pki-scripts` (PhraseLock-Bridge) |
| Summary of the installation | `/opt/phraselock/admin/admin-setup.txt` (root only) |

- **Replaces plp-backend and plp-custom.** They must not run at the same time (plp-custom uses the same port). If the
  installer finds them running or enabled it offers to stop and disable them (`systemctl stop` / `disable`).
  Nothing is deleted. An old nginx include `plp-backend.conf` becomes `plp-admin.conf`: the old file is
  renamed to `plp-backend.orig_con_f` (kept, not included by nginx) and the installer writes the new one.
- **plp-core JWT** is optional in the dialog. Without one the certificate API refuses requests until
  `pl.core.jwt` is set in `application.properties` (restart the service afterwards).
- **Ownership:** files you copy into `/opt/phraselock/admin` as root must be given to the service user again
  (`chown phraselock:phraselock`), otherwise the service stops with an `AccessDeniedException`.
- The SmartCard CA (`certs/CA/smartcard`) is not set up by the installer.
- **Called by PhraseLock-Bridge:** the PLPServer installer runs this installer (from the latest release tag) and
  pre-fills the MQTT URL, user and password through the environment variables `PLP_ADMIN_MQTT_URL`,
  `PLP_ADMIN_MQTT_USER` and `PLP_ADMIN_MQTT_PASS`. The dialogs still appear with these values filled in; an
  existing configuration always wins.

---

## SmartCard certificates

plp-admin issues the certificates for the virtual smartcard on the phone: one per user, with its own card GUID,
delivered to the phone through a QR code and revocable through a CRL. How a domain controller (Samba lab DC)
uses CA, certificates and CRL is described in [docs/smartcard-lab-samba.md](docs/smartcard-lab-samba.md).

### Issuing and revoking cards (admin UI)

`/admin/smartcard-user`, select a user:

1. **Issue new certificate…** (confirmation popup) creates key + certificate and stores both as PEM in
   `smartcard-users.db`. Nothing else happens.
2. **QR code** (per certificate row, QR valid for N minutes) creates an enrollment token for exactly that
   certificate and shows the JSON `{"smartcard":{"token":"…","upn":"…","guid":"…"}}` (the guid is created when the certificate is issued and stored with it) plus the QR code. Only one token
   per user exists: a new one replaces the older. A token is never consumed, it only expires.
3. The phone calls `POST /api/mqtt/v1/validate/smartcard` with `devId`, `qrc` (pairing check against plp-core)
   and `token`. The answer carries `crt` (hex PKCS#12), `pwd` (fresh random password), `upn`, `valid`, `ccrt`.
   The PKCS#12 is built per request; the call changes nothing in the database. Revoked certificates are refused.
4. **P12** (per certificate row) downloads the PKCS#12 with a password you type in; nothing is stored.
5. **Revoke / Reinstate** changes the status and rewrites the CRL files (`card.crl.file`, DER, and
   `card.crl.file`.pem). plp-admin does not copy them anywhere: whoever validates the cards has to get the
   file (see [docs/smartcard-lab-samba.md](docs/smartcard-lab-samba.md) for the Samba lab DC).

Buttons QR code / P12 are shown only for active certificates that have a stored key. Certificates issued
before the key was stored show "–". Revoked is revoked: no QR code, no P12, and the API refuses them.

### Configuration in plp-admin

`smartcard.properties` (defaults in `src/main/resources/`, overridden by the file next to the JAR / working directory):

- `ca.cert` – CA certificate; the key is expected next to it (`ca.key`, PKCS#8 or traditional PEM)
- `card.domain` – default UPN domain
- `card.cert.type`, `card.cert.bits` – rsa / ecc and key size
- `card.crl.file` – where the CRL is written (DER; a PEM copy gets the extra suffix `.pem`)
- `card.crl.url` – optional CRL distribution point written into new card certificates. The Samba KDC does not
  use it (it reads the local file), and Windows only checks the KDC certificate here, so it can stay empty in
  the lab. Set it (e.g. `http://dc.ad.example.test/plc-test-ca.crl`) when cards are also validated by
  something else, e.g. a real Windows DC. Certificates issued before it was set do not have it.
- `card.token.ttl.minutes` – default validity of a QR token (can be overridden per QR code in the UI)

The private key of each card is stored unencrypted in `smartcard-users.db`, so protect that file
(the server generates the key because the phone side is endpoint-agnostic and iOS cannot create RSA CSRs).

---

## Development and release

```sh
./gradlew shadowJar          # build/libs/plp-admin-<version>.jar (version in build.gradle)
java -jar build/libs/plp-admin-<version>.jar
```

Run it from the project folder: `application.properties`, `mqtt.properties`, `keepass.properties` and
`smartcard.properties` next to the JAR or in the working directory override the defaults bundled in the JAR
(these files, the databases and `certs/CA/` are in `.gitignore`).

Release: raise `version` and `archiveVersion` in `build.gradle`, commit and push, build the JAR and publish it
(`gh release create vX.Y.Z build/libs/plp-admin-X.Y.Z.jar`). Both installers use the latest release: `install.sh`
takes the JAR from it, and the PhraseLock-Bridge installer (PLPServer) fetches `install.sh` from the release tag, so
a change to the installer only reaches those installations with a new release.
