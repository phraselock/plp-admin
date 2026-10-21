#!/bin/sh
# Issues a SmartCard certificate for a domain user.
# This script documents the exact certificate structure the product needs —
# the backend replicates the same extensions in Java (BouncyCastle) at runtime.
#
# Usage:
#   . ./pki.conf && ./ca-issue-card.sh <username> [common name] [name]
#   ./ca-issue-card.sh <ca-dir> <upn> [common name] [p12 password] [bits|ecc] [name]
#
# When sourced from pki.conf the UPN is built as <username>@CARD_DOMAIN and the
# remaining parameters are taken from CARD_BITS, CARD_P12_PASSWORD, CRL_URL.
# In the explicit form all six arguments must be provided.
#
# Written into <ca-dir>/issued/:
#   <name>.pem   certificate
#   <name>.key   private key (600)
#   <name>.p12   PKCS#12 bundle for the phone (key + cert + CA cert, 600)
#
# Certificate extensions required by Windows SmartCard logon:
#   subjectAltName    otherName:1.3.6.1.4.1.311.20.2.3;UTF8:<upn>   (UPN)
#   extendedKeyUsage  clientAuth + 1.3.6.1.4.1.311.20.2.2           (Smart Card Logon)
#   keyUsage          critical, digitalSignature, keyEncipherment     (RSA)
#                     critical, digitalSignature, keyAgreement        (ECC)
#   crlDistributionPoints  URI:<CRL_URL>
set -e

# Detect call form: sourced from pki.conf vs. explicit arguments
if [ -n "${CA_DIR}" ] && [ $# -ge 1 ] && [ $# -le 3 ]; then
  # pki.conf form: ca-issue-card.sh <username> [common name] [name]
  USERNAME="${1:?username required}"
  UPN="${USERNAME}@${CARD_DOMAIN:?set CARD_DOMAIN in pki.conf}"
  CN="${2:-${USERNAME}}"
  NAME="${3:-card_${USERNAME}}"
  CA="${CA_DIR}"
  PWD_P12="${CARD_P12_PASSWORD:-changeit}"
  BITS="${CARD_BITS:-3072}"
  CRL_URL="${CRL_URL:-http://pki.example.com/plc-test-ca.crl}"
else
  # Explicit form (for use without pki.conf)
  CA="${1:?usage: ca-issue-card.sh <ca-dir> <upn> [common name] [p12 password] [bits|ecc] [name]}"
  UPN="${2:?usage: ca-issue-card.sh <ca-dir> <upn> [common name] [p12 password] [bits|ecc] [name]}"
  CN="${3:-PLC CA Card}"
  PWD_P12="${4:-changeit}"
  BITS="${5:-3072}"
  NAME="${6:-card}"
  CRL_URL="${PLC_CRL_URL:-http://hmx2.ad.phraselock.test/plc-test-ca.crl}"
fi

case "$BITS" in
  2048|3072|4096) KU="digitalSignature, keyEncipherment" ;;
  ecc)            KU="digitalSignature, keyAgreement" ;;
  *) echo "bits must be 2048, 3072, 4096 or ecc" >&2; exit 1 ;;
esac
[ -f "$CA/ca.key" ] && [ -f "$CA/ca.cnf" ] || { echo "$CA is not a CA folder made by ca-create.sh" >&2; exit 1; }

mkdir -p "$CA/issued"
cd "$CA"
if [ -f "issued/$NAME.key" ]; then
  echo "issued/$NAME.key exists: choose another name" >&2
  exit 1
fi

cat > "issued/$NAME.ext" <<CNF
basicConstraints = CA:FALSE
keyUsage = critical, $KU
extendedKeyUsage = clientAuth, 1.3.6.1.4.1.311.20.2.2
subjectAltName = otherName:1.3.6.1.4.1.311.20.2.3;UTF8:$UPN
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid
crlDistributionPoints = URI:$CRL_URL
CNF

if [ "$BITS" = ecc ]; then
  openssl ecparam -name prime256v1 -genkey -noout -out "issued/$NAME.key"
else
  openssl genrsa -out "issued/$NAME.key" "$BITS" 2>/dev/null
fi
openssl req -new -key "issued/$NAME.key" -subj "/CN=$CN" -out "issued/$NAME.csr"
openssl ca -batch -config ca.cnf -cert ca.crt -keyfile ca.key \
  -extfile "issued/$NAME.ext" -in "issued/$NAME.csr" -out "issued/$NAME.pem" -notext >/dev/null 2>&1
openssl pkcs12 -export -inkey "issued/$NAME.key" -in "issued/$NAME.pem" -certfile ca.crt \
  -name "$CN" -out "issued/$NAME.p12" -passout "pass:$PWD_P12"
chmod 600 "issued/$NAME.key" "issued/$NAME.p12"

echo "written in $CA/issued: $NAME.pem, $NAME.key, $NAME.p12 (password: $PWD_P12)"
openssl x509 -in "issued/$NAME.pem" -noout -subject -issuer \
  -ext keyUsage,extendedKeyUsage,subjectAltName,crlDistributionPoints
