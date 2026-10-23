#!/bin/bash
# plp-admin uninstaller
#
# Usage:
#   curl -sSL https://raw.githubusercontent.com/phraselock/plp-admin/main/uninstall.sh -o uninstall.sh
#   bash uninstall.sh
#
set -euo pipefail

# Root is checked here rather than assumed via a hardcoded "sudo" in the
# usage above. $0 is a real file here (never piped), so re-exec'ing through
# sudo needs no re-download.
if [[ "$(id -u)" -ne 0 ]]; then
  if command -v sudo >/dev/null 2>&1; then
    exec sudo bash "$0" "$@"
  else
    echo "Error: this uninstaller must run as root, and 'sudo' is not installed on this system. Log in as root directly and re-run." >&2
    exit 1
  fi
fi

INSTALL_DIR="/opt/phraselock/admin"
SERVICE_NAME="plp-admin"
SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
NGINX_CONF="/etc/nginx/phraselock.d/plp-admin.conf"

# ---------------------------------------------------------------------------
# Dialog tool
# ---------------------------------------------------------------------------
DIALOG=$(command -v whiptail 2>/dev/null || command -v dialog 2>/dev/null || true)
if [[ -z "$DIALOG" ]]; then
  echo "Error: whiptail or dialog is required." >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Confirmation
# ---------------------------------------------------------------------------
if ! "$DIALOG" --title "plp-admin Uninstall" --yesno \
    "This will stop and remove the plp-admin service and all its files in:\n\n  ${INSTALL_DIR}\n\nThis includes application.properties, mqtt.properties,\nkeepass.properties, all databases, issued SmartCard\ncertificates and the CRL.\n\nContinue?" \
    16 65; then
  echo "Aborted." >&2
  exit 0
fi

# ---------------------------------------------------------------------------
# Stop and disable service
# ---------------------------------------------------------------------------
echo "Stopping plp-admin..."
systemctl stop    "$SERVICE_NAME" 2>/dev/null || true
systemctl disable "$SERVICE_NAME" 2>/dev/null || true

# ---------------------------------------------------------------------------
# Remove service file, nginx include and install directory
# ---------------------------------------------------------------------------
rm -f "$SERVICE_FILE"
systemctl daemon-reload

if [[ -f "$NGINX_CONF" ]]; then
  rm -f "$NGINX_CONF"
  nginx -t 2>/dev/null && systemctl reload nginx 2>/dev/null || true
fi

rm -rf "$INSTALL_DIR"

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------
"$DIALOG" --title "plp-admin Uninstall — Done" --msgbox \
"plp-admin has been removed.

Removed:
  - systemd service (${SERVICE_NAME})
  - nginx include (${NGINX_CONF}), if present
  - ${INSTALL_DIR}

Kept: the 'phraselock' system user and the log
/var/log/plp-admin.log. The old services plp-backend and
plp-custom are not touched (they stay disabled).
Remove the user manually if no longer needed:
  userdel phraselock" 20 65
