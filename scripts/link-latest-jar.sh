#!/bin/bash
# Points plp-<name>.jar to the plp-<name>-<version>.jar with the highest version
# in a folder and restarts the systemd service plp-<name>, if there is one.
#
# Usage: link-latest-jar.sh [folder] [name]
#   folder  default: /opt/phraselock/admin
#   name    default: taken from the folder's JARs (plp-admin, plp-fido2, ...);
#           must be given if the folder holds JARs of more than one program
#
# Examples:
#   sudo ./link-latest-jar.sh                                  # plp-admin
#   sudo ./link-latest-jar.sh /opt/phraselock/fido2            # plp-fido2
#   sudo ./link-latest-jar.sh /opt/phraselock/admin plp-admin
set -euo pipefail

DIR="${1:-/opt/phraselock/admin}"
NAME="${2:-}"

cd "$DIR"

if [[ -z "$NAME" ]]; then
  # program names of all versioned JARs in the folder, e.g. plp-admin-1.0.4.jar -> plp-admin
  NAMES=$(ls -1 plp-*-[0-9]*.jar 2>/dev/null | sed -E 's/-[0-9][^/]*\.jar$//' | sort -u || true)
  if [[ -z "$NAMES" ]]; then
    echo "No plp-<name>-<version>.jar in $DIR" >&2
    exit 1
  fi
  if [[ $(echo "$NAMES" | wc -l) -gt 1 ]]; then
    echo "More than one program in $DIR ($(echo $NAMES)); give the name as second argument." >&2
    exit 1
  fi
  NAME="$NAMES"
fi

# highest version by version sort (1.0.10 > 1.0.9)
LATEST=$(ls -1 "$NAME"-[0-9]*.jar 2>/dev/null | sort -V | tail -1)
if [[ -z "$LATEST" ]]; then
  echo "No $NAME-<version>.jar in $DIR" >&2
  exit 1
fi

LINK="$NAME.jar"
CURRENT=$(readlink "$LINK" 2>/dev/null || true)
if [[ "$CURRENT" == "$LATEST" ]]; then
  echo "$LINK already points to $LATEST"
  exit 0
fi

ln -sfn "$LATEST" "$LINK"
echo "$LINK -> $LATEST (before: ${CURRENT:-none})"

if command -v systemctl >/dev/null 2>&1 && systemctl list-unit-files "$NAME.service" >/dev/null 2>&1 \
   && systemctl list-unit-files "$NAME.service" | grep -q "^$NAME.service"; then
  systemctl restart "$NAME"
  echo "Service $NAME restarted"
else
  echo "No service $NAME found; start it yourself"
fi
