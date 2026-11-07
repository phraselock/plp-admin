#!/bin/sh
# Re-issues the self-signed certificate of an existing CA with pathlen:1,
# so that the CA can act as root above an intermediate CA.
#
# Usage:
#   ./ca-reissue-root.sh <root-crt> <root-key> [out-file]
#
# Same key, same subject, new self-signed certificate. Written to <out-file>
# (default <root-crt without .crt>-pathlen1.crt); <root-crt> itself is NOT touched.
#
# Certificates issued so far stay valid: they are signed with the key, and the
# key does not change. The new file is a different certificate (different
# fingerprint), so it has to be distributed wherever the root is a trust anchor
# (e.g. anchors.pem on the KDC, the Windows Root store).
#
# New CAs made with ca-create.sh already have pathlen:1; this script is only for
# CAs created earlier with pathlen:0.
set -e
CRT="${1:?usage: $0 <root-crt> <root-key> [out-file]}"
KEY="${2:?usage: $0 <root-crt> <root-key> [out-file]}"
OUT="${3:-${CRT%.crt}-pathlen1.crt}"

[ -f "$CRT" ] && [ -f "$KEY" ] || { echo "$CRT or $KEY missing" >&2; exit 1; }
[ -e "$OUT" ] && { echo "$OUT exists, not overwriting" >&2; exit 1; }

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
cat > "$TMP/ext.cnf" <<CNF
[v3_ca]
basicConstraints = critical, CA:TRUE, pathlen:1
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash
CNF

# CSR carrying the subject of the existing certificate, signed with the same key
openssl x509 -in "$CRT" -x509toreq -signkey "$KEY" -out "$TMP/ca.csr" 2>/dev/null
openssl x509 -req -in "$TMP/ca.csr" -signkey "$KEY" -sha256 -days 3650 \
  -extfile "$TMP/ext.cnf" -extensions v3_ca -out "$OUT" 2>/dev/null

echo "written $OUT"
openssl x509 -in "$OUT" -noout -subject -issuer -dates -fingerprint -sha256 -ext basicConstraints
