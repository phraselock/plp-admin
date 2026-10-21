#!/bin/sh
# Creates the SmartCard CA.
#
# Usage:
#   . ./pki.conf && ./ca-create.sh
#   ./ca-create.sh <ca-dir> [rsa|ec]
#
# Reads from pki.conf (when sourced): CA_DIR, CA_TYPE
# Falls back to positional arguments if the variables are not set.
#
# Written into <ca-dir>:
#   ca.crt / ca.key     the CA certificate (10 years) and its key
#   ca.cnf              openssl CA configuration (used by ca-issue-card.sh and ca-issue-kdc.sh)
#   db/                 issued certificates, serial numbers, CRL number
#   plc-test-ca.crl     initial empty CRL (DER), valid 10 years — publish at CRL_URL
#   plc-test-ca.crl.pem the same as PEM
#
# The script refuses to run if <ca-dir>/ca.key already exists.
# The key is unencrypted — test material only; never commit <ca-dir>.
set -e
CA="${1:-${CA_DIR:?set CA_DIR in pki.conf or pass <ca-dir> as first argument}}"
TYPE="${2:-${CA_TYPE:-rsa}}"
case "$TYPE" in rsa|ec) ;; *) echo "type must be rsa or ec" >&2; exit 1 ;; esac

if [ -f "$CA/ca.key" ]; then
  echo "$CA/ca.key exists: CA was already created (delete the folder to start over)" >&2
  exit 1
fi
mkdir -p "$CA/db"
cd "$CA"
: > db/index.txt
echo 01 > db/crlnumber
echo 1000 > db/serial

cat > ca.cnf <<CNF
[ca]
default_ca = plc
[plc]
dir = .
database = db/index.txt
new_certs_dir = db
serial = db/serial
crlnumber = db/crlnumber
default_md = sha256
default_days = 3650
default_crl_days = 3650
policy = anything
unique_subject = no
[anything]
commonName = optional
[req]
distinguished_name = dn
prompt = no
[dn]
CN = PLC Test CA
[v3_ca]
basicConstraints = critical, CA:TRUE, pathlen:0
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash
CNF

if [ "$TYPE" = ec ]; then
  openssl ecparam -name prime256v1 -genkey -noout -out ca.key
else
  openssl genrsa -out ca.key 3072 2>/dev/null
fi
chmod 600 ca.key
openssl req -new -x509 -key ca.key -sha256 -days 3650 -config ca.cnf -extensions v3_ca -out ca.crt

openssl ca -config ca.cnf -cert ca.crt -keyfile ca.key -gencrl -out plc-test-ca.crl.pem 2>/dev/null
openssl crl -in plc-test-ca.crl.pem -outform DER -out plc-test-ca.crl

echo "created $TYPE CA in $CA: ca.crt, ca.key, ca.cnf, db/, plc-test-ca.crl"
openssl x509 -in ca.crt -noout -subject -issuer -dates -ext basicConstraints,keyUsage
