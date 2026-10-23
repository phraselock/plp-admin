#!/bin/bash
# plp-admin installer
# Downloads the latest release from GitHub and installs it as a systemd service.
# plp-admin replaces plp-backend and plp-custom (one process, one port).
#
# Usage:
#   curl -sSL https://raw.githubusercontent.com/phraselock/plp-admin/main/install.sh | bash
#
set -euo pipefail

# Root is checked here rather than assumed via a hardcoded "sudo" in the
# documented one-liner: plenty of real servers already have root logged in
# by default and never had sudo installed at all.
NEED_SUDO=false
if [[ "$(id -u)" -ne 0 ]]; then
  if command -v sudo >/dev/null 2>&1; then
    NEED_SUDO=true
  else
    echo "Error: this installer must run as root, and 'sudo' is not installed on this system. Log in as root directly and re-run." >&2
    exit 1
  fi
fi

# Also re-exec when piped straight into bash ("curl | bash"): bash reads
# this script from stdin while whiptail wants the same stdin, which breaks
# keyboard input in the dialogs. Re-running from a file frees stdin up.
if [[ "$NEED_SUDO" == true || ! -t 0 ]]; then
  TMP_SELF=$(mktemp)
  curl -fsSL "https://raw.githubusercontent.com/phraselock/plp-admin/main/install.sh" -o "$TMP_SELF"
  chmod +x "$TMP_SELF"
  # < /dev/tty keeps this guard from re-triggering on every re-exec.
  if [[ "$NEED_SUDO" == true ]]; then
    exec sudo bash "$TMP_SELF" "$@" < /dev/tty
  else
    exec bash "$TMP_SELF" "$@" < /dev/tty
  fi
fi

GITHUB_REPO="phraselock/plp-admin"
SERVICE_NAME="plp-admin"
INSTALL_DIR="/opt/phraselock/admin"
SERVICE_USER="phraselock"
SUMMARY_FILE="${INSTALL_DIR}/admin-setup.txt"
PKI_BASE="/opt/phraselock/pki-scripts"

# ---------------------------------------------------------------------------
# Dialog tool
# ---------------------------------------------------------------------------
DIALOG=$(command -v whiptail 2>/dev/null || command -v dialog 2>/dev/null || true)
if [[ -z "$DIALOG" ]]; then
  echo "Installing whiptail..." >&2
  DEBIAN_FRONTEND=noninteractive apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq whiptail
  DIALOG=$(command -v whiptail)
fi

# ---------------------------------------------------------------------------
# curl
# ---------------------------------------------------------------------------
if ! command -v curl >/dev/null 2>&1; then
  DEBIAN_FRONTEND=noninteractive apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq curl
fi

# ---------------------------------------------------------------------------
# Fetch latest release from GitHub
# ---------------------------------------------------------------------------
echo "Fetching latest release from GitHub (${GITHUB_REPO})..."
RELEASE_JSON=$(curl -fsSL "https://api.github.com/repos/${GITHUB_REPO}/releases/latest")
VERSION=$(echo "$RELEASE_JSON" \
  | grep '"tag_name"' \
  | sed -E 's/.*"tag_name"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/')
JAR_URL=$(echo "$RELEASE_JSON" \
  | grep '"browser_download_url"' \
  | grep '\.jar"' \
  | sed -E 's/.*"browser_download_url"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/')

if [[ -z "$VERSION" || -z "$JAR_URL" ]]; then
  echo "Error: could not parse GitHub release info. Check your internet connection." >&2
  exit 1
fi

JAR_NAME=$(basename "$JAR_URL")
DEMO_KDBX_URL="https://raw.githubusercontent.com/${GITHUB_REPO}/main/keepass-phraselock.kdbx"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

# Reads a key from a properties file. tr -d '\r' guards against CRLF line
# endings (e.g. a file edited on Windows).
_get() {
  local key="$1" file="$2" default="${3:-}"
  grep "^${key}=" "$file" 2>/dev/null | cut -d= -f2- | tr -d '\r' || echo "$default"
}

# Strips a trailing \r some terminals/whiptail leave on captured input
# ("$()" strips \n but not a \r right before it).
strip_cr() { printf '%s' "${1%$'\r'}"; }

TITLE="plp-admin ${VERSION} Setup"

# ---------------------------------------------------------------------------
# plp-custom / plp-backend are replaced by plp-admin. plp-custom listens on
# the same port (7070); both would also serve the same /admin and API routes.
# They have to be stopped and disabled first. Nothing is deleted.
# ---------------------------------------------------------------------------
OLD_RUNNING=()
for U in plp-custom plp-backend; do
  if systemctl cat "${U}.service" >/dev/null 2>&1; then
    if systemctl is-active --quiet "$U" || systemctl is-enabled --quiet "$U" 2>/dev/null; then
      OLD_RUNNING+=("$U")
    fi
  fi
done

if [[ ${#OLD_RUNNING[@]} -gt 0 ]]; then
  if "$DIALOG" --title "$TITLE" --yesno \
"plp-admin replaces plp-backend and plp-custom.
These services are still running or enabled:

  ${OLD_RUNNING[*]}

They must be stopped and disabled first
(systemctl stop / disable — nothing is deleted).

Stop and disable them now?" 16 65; then
    for U in "${OLD_RUNNING[@]}"; do
      systemctl stop    "$U" 2>/dev/null || true
      systemctl disable "$U" >/dev/null 2>&1 || true
    done
  else
    echo "Aborted: stop and disable ${OLD_RUNNING[*]} first (systemctl stop/disable), then re-run." >&2
    exit 1
  fi
fi

# nginx: plp-backend.conf becomes plp-admin.conf. The old include defines the same
# "location /admin/" and would make nginx reject the configuration, so it is renamed
# (kept as plp-backend.orig_con_f, which nginx does not include).
OLD_NGINX_BACKEND="/etc/nginx/phraselock.d/plp-backend.conf"
if [[ -f "$OLD_NGINX_BACKEND" ]]; then
  mv "$OLD_NGINX_BACKEND" "/etc/nginx/phraselock.d/plp-backend.orig_con_f"
fi

# ---------------------------------------------------------------------------
# Read existing config (upgrade-friendly)
# ---------------------------------------------------------------------------
APP_PROPS="$INSTALL_DIR/application.properties"
MQTT_PROPS="$INSTALL_DIR/mqtt.properties"
KEEPASS_PROPS="$INSTALL_DIR/keepass.properties"

E_PORT="7070"
E_IPS="127.0.0.1,::1,[0:0:0:0:0:0:0:1]"
E_MIN_THREADS="2"
E_MAX_THREADS="10"
E_ADMIN_TOKEN=""
E_KEEPASS_ENABLED="true"
E_PEER_STORE="sqlite"
E_JWT=""
E_CORE_URL=""
E_CORE_PUB_X=""
E_CORE_PUB_Y=""

E_MQTT_URL="ssl://localhost:8883"
E_MQTT_USER="plpadmin"
E_MQTT_PASS=""
E_MQTT_KEY="${INSTALL_DIR}/certs/mqtt_8883.pkcs8.key"
E_MQTT_CERT="${INSTALL_DIR}/certs/mqtt_8883.crt"

E_KP_FILE="${INSTALL_DIR}/keepass-phraselock.kdbx"
E_KP_PASS=""
E_KP_UUID="$(cat /proc/sys/kernel/random/uuid 2>/dev/null || echo "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")"

if [[ -f "$APP_PROPS" ]]; then
  E_PORT=$(_get server.port "$APP_PROPS" "$E_PORT")
  E_IPS=$(_get server.allowedIps "$APP_PROPS" "$E_IPS")
  E_MIN_THREADS=$(_get jetty.minThreads "$APP_PROPS" "$E_MIN_THREADS")
  E_MAX_THREADS=$(_get jetty.maxThreads "$APP_PROPS" "$E_MAX_THREADS")
  E_ADMIN_TOKEN=$(_get admin.token "$APP_PROPS" "")
  E_KEEPASS_ENABLED=$(_get bes.keepass.enabled "$APP_PROPS" "true")
  E_PEER_STORE=$(_get peer.config.store "$APP_PROPS" "sqlite")
  E_JWT=$(_get pl.core.jwt "$APP_PROPS" "")
  E_CORE_URL=$(_get pl.core.url "$APP_PROPS" "")
  E_CORE_PUB_X=$(_get pl.core.jwt.ec.pub.x "$APP_PROPS" "")
  E_CORE_PUB_Y=$(_get pl.core.jwt.ec.pub.y "$APP_PROPS" "")
fi

if [[ -f "$MQTT_PROPS" ]]; then
  E_MQTT_URL=$(_get broker.url "$MQTT_PROPS" "$E_MQTT_URL")
  E_MQTT_USER=$(_get auth.username "$MQTT_PROPS" "$E_MQTT_USER")
  E_MQTT_PASS=$(_get auth.password "$MQTT_PROPS" "")
  E_MQTT_KEY=$(_get tls.client.key "$MQTT_PROPS" "$E_MQTT_KEY")
  E_MQTT_CERT=$(_get tls.client.cert "$MQTT_PROPS" "$E_MQTT_CERT")
fi

if [[ -f "$KEEPASS_PROPS" ]]; then
  E_KP_FILE=$(_get keepass.file "$KEEPASS_PROPS" "$E_KP_FILE")
  E_KP_PASS=$(_get keepass.password "$KEEPASS_PROPS" "")
  E_KP_UUID=$(_get service.uuid "$KEEPASS_PROPS" "$E_KP_UUID")
fi

# ---------------------------------------------------------------------------
# Dialog: General settings
# ---------------------------------------------------------------------------
if ! PORT=$("$DIALOG" --title "$TITLE" \
    --inputbox "HTTP port for plp-admin (API and admin UI):" 10 55 "$E_PORT" \
    3>&1 1>&2 2>&3); then echo "Aborted." >&2; exit 1; fi
PORT=$(strip_cr "$PORT")

if ! ALLOWED_IPS=$("$DIALOG" --title "$TITLE" \
    --inputbox "Allowed IPs (comma-separated):" 10 70 "$E_IPS" \
    3>&1 1>&2 2>&3); then echo "Aborted." >&2; exit 1; fi
ALLOWED_IPS=$(strip_cr "$ALLOWED_IPS")

if ! MAX_THREADS=$("$DIALOG" --title "$TITLE" \
    --inputbox "Jetty max threads:" 10 55 "$E_MAX_THREADS" \
    3>&1 1>&2 2>&3); then echo "Aborted." >&2; exit 1; fi
MAX_THREADS=$(strip_cr "$MAX_THREADS")

# Admin token — generate once, never overwrite
if [[ -z "$E_ADMIN_TOKEN" ]]; then
  ADMIN_TOKEN=$(openssl rand -hex 32)
  TOKEN_STATUS="Admin token newly generated."
  TOKEN_IS_NEW=true
else
  ADMIN_TOKEN="$E_ADMIN_TOKEN"
  TOKEN_STATUS="Admin token preserved from existing installation."
  TOKEN_IS_NEW=false
fi

# KeePass enabled?
KP_DEFAULT_FLAG=""
[[ "$E_KEEPASS_ENABLED" == "true" ]] || KP_DEFAULT_FLAG="--defaultno"
if "$DIALOG" --title "$TITLE" $KP_DEFAULT_FLAG --yesno "Enable KeePass integration?" 8 50; then
  KEEPASS_ENABLED=true
else
  KEEPASS_ENABLED=false
fi

# Peer config store
PEER_DEFAULT_FLAG="--defaultno"
[[ "$E_PEER_STORE" == "redis" ]] && PEER_DEFAULT_FLAG=""
if "$DIALOG" --title "$TITLE" $PEER_DEFAULT_FLAG --yesno \
    "Use Redis as peer config store?\n(No = SQLite, recommended for single-host setups)" 9 60; then
  PEER_STORE=redis
else
  PEER_STORE=sqlite
fi

# ---------------------------------------------------------------------------
# Dialog: plp-core JWT. Optional: without one the certificate API refuses
# requests until a JWT is set.
# ---------------------------------------------------------------------------
if ! PL_CORE_JWT=$("$DIALOG" --title "$TITLE (plp-core)" \
    --inputbox "plp-core JWT (leave empty if you do not have one yet):" 10 78 "$E_JWT" \
    3>&1 1>&2 2>&3); then echo "Aborted." >&2; exit 1; fi
PL_CORE_JWT=$(strip_cr "$PL_CORE_JWT")

JWT_STATUS="plp-core JWT set."
if [[ -z "$PL_CORE_JWT" ]]; then
  JWT_STATUS="WARNING: no plp-core JWT set. A JWT must be requested from PhraseLock; until then
  the certificate API (/api/mqtt/v1/validate/*) refuses requests. Enter it later as
  pl.core.jwt in ${APP_PROPS} (then restart plp-admin) or re-run this installer."
  "$DIALOG" --title "$TITLE (plp-core)" --msgbox \
"No plp-core JWT entered.

A JWT must be requested from PhraseLock. Until one is
set, the certificate API refuses requests.

Enter it later as pl.core.jwt in
  ${APP_PROPS}
and restart plp-admin, or re-run this installer." 16 62
fi

# ---------------------------------------------------------------------------
# Dialog: MQTT settings
# ---------------------------------------------------------------------------
if ! MQTT_URL=$("$DIALOG" --title "$TITLE (MQTT)" \
    --inputbox "MQTT broker URL (e.g. ssl://your.host:8883):" 10 65 "$E_MQTT_URL" \
    3>&1 1>&2 2>&3); then echo "Aborted." >&2; exit 1; fi
MQTT_URL=$(strip_cr "$MQTT_URL")

if ! MQTT_USER=$("$DIALOG" --title "$TITLE (MQTT)" \
    --inputbox "MQTT username:" 10 55 "$E_MQTT_USER" \
    3>&1 1>&2 2>&3); then echo "Aborted." >&2; exit 1; fi
MQTT_USER=$(strip_cr "$MQTT_USER")

if ! MQTT_PASS=$("$DIALOG" --title "$TITLE (MQTT)" \
    --passwordbox "MQTT password:" 10 55 "$E_MQTT_PASS" \
    3>&1 1>&2 2>&3); then echo "Aborted." >&2; exit 1; fi
MQTT_PASS=$(strip_cr "$MQTT_PASS")

if ! MQTT_KEY=$("$DIALOG" --title "$TITLE (MQTT)" \
    --inputbox "Path to mTLS client private key (.pkcs8.key):" 10 70 "$E_MQTT_KEY" \
    3>&1 1>&2 2>&3); then echo "Aborted." >&2; exit 1; fi
MQTT_KEY=$(strip_cr "$MQTT_KEY")

if ! MQTT_CERT=$("$DIALOG" --title "$TITLE (MQTT)" \
    --inputbox "Path to mTLS client certificate (.crt):" 10 70 "$E_MQTT_CERT" \
    3>&1 1>&2 2>&3); then echo "Aborted." >&2; exit 1; fi
MQTT_CERT=$(strip_cr "$MQTT_CERT")

# ---------------------------------------------------------------------------
# Dialog: KeePass settings (only when enabled)
# ---------------------------------------------------------------------------
KP_FILE="$E_KP_FILE"
KP_PASS="$E_KP_PASS"
KP_UUID="$E_KP_UUID"

if [[ "$KEEPASS_ENABLED" == true ]]; then
  if ! KP_FILE=$("$DIALOG" --title "$TITLE (KeePass)" \
      --inputbox "Path to .kdbx file:" 10 70 "$E_KP_FILE" \
      3>&1 1>&2 2>&3); then echo "Aborted." >&2; exit 1; fi
  KP_FILE=$(strip_cr "$KP_FILE")

  # Pre-fill demo password on fresh install and warn the user
  KP_PASS_DEFAULT="$E_KP_PASS"
  if [[ -z "$KP_PASS_DEFAULT" ]]; then
    KP_PASS_DEFAULT='my$ecretPa$$w0rd'
    "$DIALOG" --title "$TITLE (KeePass)" --msgbox \
'The demo KeePass database uses the default password:

  my$ecretPa$$w0rd

CHANGE IT immediately after installation!
Use KeePassXC: Database → Change Master Password,
then update keepass.properties.' 14 60
  fi

  if ! KP_PASS=$("$DIALOG" --title "$TITLE (KeePass)" \
      --passwordbox "KeePass master password:" 10 55 "$KP_PASS_DEFAULT" \
      3>&1 1>&2 2>&3); then echo "Aborted." >&2; exit 1; fi
  KP_PASS=$(strip_cr "$KP_PASS")

  if ! KP_UUID=$("$DIALOG" --title "$TITLE (KeePass)" \
      --inputbox "Service UUID (shown in QR codes on mobile app):" 10 70 "$E_KP_UUID" \
      3>&1 1>&2 2>&3); then echo "Aborted." >&2; exit 1; fi
  KP_UUID=$(strip_cr "$KP_UUID")
fi

# ---------------------------------------------------------------------------
# Java 21
# ---------------------------------------------------------------------------
JAVA_MAJOR=0
if command -v java >/dev/null 2>&1; then
  JAVA_MAJOR=$(java -version 2>&1 | head -1 | grep -oE '"[0-9]+' | tr -d '"')
fi

if [[ "$JAVA_MAJOR" -lt 21 ]]; then
  echo "Installing OpenJDK 21..."
  DEBIAN_FRONTEND=noninteractive apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-21-jre-headless
  JAVA_STATUS="OpenJDK 21 (headless JRE) installed."
else
  JAVA_STATUS="Java ${JAVA_MAJOR} already present — meets the minimum of 21."
fi

# ---------------------------------------------------------------------------
# System user
# ---------------------------------------------------------------------------
id -u "$SERVICE_USER" >/dev/null 2>&1 \
  || useradd -r -m -s /usr/sbin/nologin "$SERVICE_USER"

# ---------------------------------------------------------------------------
# Download and install JAR
# ---------------------------------------------------------------------------
mkdir -p "$INSTALL_DIR"
echo "Downloading ${JAR_NAME} (${VERSION})..."
curl -fsSL "$JAR_URL" -o "$INSTALL_DIR/$JAR_NAME"
ln -sf "$JAR_NAME" "$INSTALL_DIR/plp-admin.jar"

# ---------------------------------------------------------------------------
# certs/ directory. Symlinks to the PhraseLock-Bridge PKI (clients-mqtt):
#  - the MQTT client certificate this service uses towards the broker (mTLS)
#  - certs/CA/service: the CA keys plp-admin needs to issue certificates at
#    runtime (ca.directory). Symlinked, not copied, so plp-admin always sees
#    what is actually under pki-scripts.
# If the PKI does not exist yet, the symlinks stay dangling and a warning
# is shown.
# ---------------------------------------------------------------------------
PKI_MQTT_DIR="${PKI_BASE}/clients-mqtt/mqtt_8883"
PKI_MQTT_CA_DIR="${PKI_BASE}/clients-mqtt/CA"
PKI_API_CA_DIR="${PKI_BASE}/clients-api/CA"
CERTS_DIR="${INSTALL_DIR}/certs"
CA_SERVICE_DIR="${CERTS_DIR}/CA/service"
mkdir -p "$CERTS_DIR" "$CA_SERVICE_DIR"

ln -sf "${PKI_MQTT_DIR}/mqtt_8883.crt"       "${CERTS_DIR}/mqtt_8883.crt"
ln -sf "${PKI_MQTT_DIR}/mqtt_8883.key"       "${CERTS_DIR}/mqtt_8883.key"
ln -sf "${PKI_MQTT_DIR}/mqtt_8883.pkcs8.key" "${CERTS_DIR}/mqtt_8883.pkcs8.key"

if [[ -f "${PKI_MQTT_CA_DIR}/ca.mqtt_8883.pem" ]]; then
  if [[ ! -f "${PKI_MQTT_DIR}/mqtt_8883.crt" ]]; then
    echo "Generating MQTT client certificate..."
    (cd "${PKI_BASE}/clients-mqtt" && bash make_client.sh 8883)
    CERT_STATUS="MQTT client certificate generated."
  else
    CERT_STATUS="MQTT client certificate already exists — preserved."
  fi

  # CA keys: group-readable by the service user, symlinked into certs/CA/service.
  CA_FILES=("${PKI_MQTT_CA_DIR}/ca.mqtt_8883.key" "${PKI_MQTT_CA_DIR}/ca.mqtt_8883.pem")
  for F in "${PKI_API_CA_DIR}"/ca.*.key; do
    [[ -e "$F" ]] && CA_FILES+=("$F") || true
  done
  for F in "${CA_FILES[@]}"; do
    [[ -e "$F" ]] || continue
    if [[ "$F" == *.key ]]; then
      chgrp "$SERVICE_USER" "$F" 2>/dev/null || true
      chmod 640 "$F" 2>/dev/null || true
    fi
    ln -sf "$F" "${CA_SERVICE_DIR}/"
  done
  CA_STATUS="CA keys linked into ${CA_SERVICE_DIR}."
else
  CERT_STATUS="WARNING: PhraseLock-Bridge MQTT CA not found under ${PKI_BASE}.
  Run pki-scripts/clients-mqtt/make_ca.sh and make_client.sh 8883 first,
  then re-run this installer."
  CA_STATUS="WARNING: no CA keys linked — certificate issuance will not work yet."
fi

# Demo KeePass database — only downloaded when the configured file does not
# exist, never overwritten
if [[ "$KEEPASS_ENABLED" == true && ! -f "$KP_FILE" && "$KP_FILE" == "${INSTALL_DIR}/keepass-phraselock.kdbx" ]]; then
  echo "Downloading demo KeePass database..."
  curl -fsSL "$DEMO_KDBX_URL" -o "$KP_FILE" \
    || echo "WARNING: could not download the demo KeePass database. Provide ${KP_FILE} yourself." >&2
fi

# ---------------------------------------------------------------------------
# application.properties
# ---------------------------------------------------------------------------
{
cat << EOF
# HTTP port. Both the PLP API and the admin UI run on this port.
server.port=${PORT}

# IP addresses allowed to call this service. Comma-separated.
server.allowedIps=${ALLOWED_IPS}

# Jetty thread pool.
jetty.minThreads=${E_MIN_THREADS}
jetty.maxThreads=${MAX_THREADS}

# Shared secret for /admin/* routes.
admin.token=${ADMIN_TOKEN}

# Credential sources.
bes.keepass.enabled=${KEEPASS_ENABLED}
bes.psono.enabled=false

# Peer config store backend: redis | sqlite
peer.config.store=${PEER_STORE}

# CA files used to sign client certificates (ca.mqtt_<port>.pem / .key).
ca.directory=./certs/CA/service

# plp-core: JWT of this installation (request one from PhraseLock if empty).
pl.core.jwt=${PL_CORE_JWT}
EOF
# Not asked for; kept if present, otherwise the defaults bundled in the JAR apply.
[[ -n "$E_CORE_URL"   ]] && echo "pl.core.url=${E_CORE_URL}"
[[ -n "$E_CORE_PUB_X" ]] && echo "pl.core.jwt.ec.pub.x=${E_CORE_PUB_X}"
[[ -n "$E_CORE_PUB_Y" ]] && echo "pl.core.jwt.ec.pub.y=${E_CORE_PUB_Y}"
true
} > "$APP_PROPS"

# ---------------------------------------------------------------------------
# mqtt.properties
# Generate ECC key pair on first install; preserve on upgrade.
# ---------------------------------------------------------------------------
EXISTING_DPRIV=$(_get bes.id.dpriv "$MQTT_PROPS" "")
EXISTING_XPUBL=$(_get bes.id.xpubl "$MQTT_PROPS" "")
EXISTING_YPUBL=$(_get bes.id.ypubl "$MQTT_PROPS" "")

if [[ -z "$EXISTING_DPRIV" || "$EXISTING_DPRIV" == "changeme" ]]; then
  echo "Generating ECC key pair for message signing..."
  TMP_KEY=$(mktemp)
  openssl ecparam -name prime256v1 -genkey -noout -out "$TMP_KEY"
  RAW=$(openssl ec -in "$TMP_KEY" -text -noout 2>/dev/null)
  EXISTING_DPRIV=$(echo "$RAW" | awk '/^priv:/{found=1; next} found && /^pub:/{found=0} found{print}' | tr -d ' :\n')
  PUB=$(echo "$RAW"           | awk '/^pub:/{found=1; next}  found && /^ASN1/{found=0}  found{print}' | tr -d ' :\n')
  PUB_CLEAN="${PUB:2}"
  LEN=${#PUB_CLEAN}; HALF=$((LEN / 2))
  EXISTING_XPUBL="${PUB_CLEAN:0:$HALF}"
  EXISTING_YPUBL="${PUB_CLEAN:$HALF}"
  rm -f "$TMP_KEY"
  ECC_STATUS="ECC key pair generated automatically."
else
  ECC_STATUS="ECC key pair preserved from existing installation."
fi

cat > "$MQTT_PROPS" << EOF
# MQTT Broker Configuration
broker.url=${MQTT_URL}
client.id=plp-admin-client

# Connection params
keepalive.seconds=45
connect.retry.seconds=5

# Broker authentication
auth.username=${MQTT_USER}
auth.password=${MQTT_PASS}

# ECC key pair for message signing.
# Set these values through the admin UI at /admin/mqttconfig if needed.
bes.id.dpriv=${EXISTING_DPRIV}
bes.id.xpubl=${EXISTING_XPUBL}
bes.id.ypubl=${EXISTING_YPUBL}

# mTLS client certificate (issued by PhraseLock-Bridge PKI).
tls.client.key=${MQTT_KEY}
tls.client.cert=${MQTT_CERT}
EOF

# ---------------------------------------------------------------------------
# keepass.properties
# ---------------------------------------------------------------------------
cat > "$KEEPASS_PROPS" << EOF
# KeePass Configuration

# Service UUID shown in QR codes on the mobile app.
service.uuid=${KP_UUID}

# Path to the .kdbx file.
keepass.file=${KP_FILE}

# Master password.
keepass.password=${KP_PASS}
EOF

# Secure all config files — they contain credentials. The service user must
# own the directory: it writes the SQLite databases and the CRL there.
chown -R "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR"
chmod 600 "$APP_PROPS" "$MQTT_PROPS" "$KEEPASS_PROPS"

# ---------------------------------------------------------------------------
# nginx include block
# ---------------------------------------------------------------------------
if [[ -d /etc/nginx/phraselock.d ]]; then
  cat > /etc/nginx/phraselock.d/plp-admin.conf << EOF
location /admin/ {
    if (\$ssl_client_verify != SUCCESS) { return 403; }
    proxy_pass          http://localhost:${PORT}/admin/;
    proxy_set_header    Host              \$host;
    proxy_set_header    X-Real-IP         \$remote_addr;
    proxy_set_header    X-Forwarded-For   \$proxy_add_x_forwarded_for;
    proxy_set_header    X-Forwarded-Proto \$scheme;
    proxy_set_header    X-Client-Verify   \$ssl_client_verify;
    proxy_set_header    X-Client-DN       \$ssl_client_s_dn;
}
EOF
  nginx -t 2>/dev/null && systemctl reload nginx 2>/dev/null || true
fi

# ---------------------------------------------------------------------------
# systemd service
# ---------------------------------------------------------------------------
cat > "${INSTALL_DIR}/plp-admin.service" << EOF
[Unit]
Description=Phrase-Lock Port Admin-Service
After=network.target

[Service]
Type=simple
WorkingDirectory=${INSTALL_DIR}
ExecStart=/usr/bin/java -jar plp-admin.jar
Restart=on-failure
TimeoutStopSec=1
KillSignal=SIGKILL

User=${SERVICE_USER}

StandardOutput=append:/var/log/plp-admin.log
StandardError=append:/var/log/plp-admin.log

[Install]
WantedBy=multi-user.target
EOF
ln -sf "${INSTALL_DIR}/plp-admin.service" /etc/systemd/system/plp-admin.service

systemctl daemon-reload
systemctl enable plp-admin >/dev/null 2>&1 || true
systemctl reset-failed plp-admin 2>/dev/null || true
systemctl restart plp-admin

sleep 2
if systemctl is-active --quiet plp-admin; then
  SERVICE_STATUS="plp-admin is running."
else
  SERVICE_STATUS="WARNING: plp-admin did not start — check: journalctl -u plp-admin and /var/log/plp-admin.log"
fi

# ---------------------------------------------------------------------------
# Summary text file
# ---------------------------------------------------------------------------
TOKEN_DISPLAY="(preserved — see ${INSTALL_DIR}/application.properties)"
if [[ "$TOKEN_IS_NEW" == true ]]; then
  TOKEN_DISPLAY="${ADMIN_TOKEN}"
fi

cat > "$SUMMARY_FILE" << EOF
plp-admin ${VERSION} — Installation Summary
$(date)
============================================================

${JAVA_STATUS}
${SERVICE_STATUS}
${TOKEN_STATUS}
${JWT_STATUS}
${ECC_STATUS}
${CERT_STATUS}
${CA_STATUS}

Admin token:
  ${TOKEN_DISPLAY}

Admin UI:
  http://localhost:${PORT}/admin/config?token=${ADMIN_TOKEN}

KeePass integration: ${KEEPASS_ENABLED}
Peer config store:   ${PEER_STORE}
MQTT broker:         ${MQTT_URL}

Config files (chmod 600):
  ${INSTALL_DIR}/application.properties
  ${INSTALL_DIR}/mqtt.properties
  ${INSTALL_DIR}/keepass.properties

Log: /var/log/plp-admin.log

============================================================
nginx: /admin/ block written to /etc/nginx/phraselock.d/plp-admin.conf
(only if PhraseLock-Bridge PLPServer is installed)
============================================================

To update: sudo bash install.sh
To remove:  sudo bash uninstall.sh
EOF

chmod 600 "$SUMMARY_FILE"

# ---------------------------------------------------------------------------
# Summary dialog
# ---------------------------------------------------------------------------
TOKEN_LINE=""
if [[ "$TOKEN_IS_NEW" == true ]]; then
  TOKEN_LINE="
Admin token (save this!):
  ${ADMIN_TOKEN}"
fi

"$DIALOG" --title "plp-admin ${VERSION} Setup — Done" --msgbox \
"${JAVA_STATUS}

plp-admin ${VERSION} installed to:
  ${INSTALL_DIR}

${SERVICE_STATUS}
${TOKEN_STATUS}${TOKEN_LINE}
${JWT_STATUS}
${ECC_STATUS}
${CERT_STATUS}
${CA_STATUS}
KeePass: ${KEEPASS_ENABLED}
Peer store: ${PEER_STORE}
MQTT broker: ${MQTT_URL}

Admin UI:
  http://localhost:${PORT}/admin/config?token=<token>

Config files (chmod 600):
  ${INSTALL_DIR}/application.properties
  ${INSTALL_DIR}/mqtt.properties
  ${INSTALL_DIR}/keepass.properties

Full summary saved to:
  ${SUMMARY_FILE}

To update: sudo bash install.sh
To remove:  sudo bash uninstall.sh" 40 80
