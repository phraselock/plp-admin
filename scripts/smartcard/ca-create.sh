#!/bin/sh
# Creates the SmartCard root CA.
#
# Usage:
#   ./ca-create.sh pki.conf
#
# Reads from the config file: ROOT_DIR (where the root is created), CA_TYPE (rsa|ec),
# ROOT_CN (name of the root, cannot be changed later).
#
# Written into ROOT_DIR:
#   root-ca.crt / root-ca.key     the CA certificate (10 years) and its key
#   ca.cnf              openssl CA configuration (used by ca-issue-kdc.sh)
#   db/                 issued certificates, serial numbers, CRL number
#   root-ca.crl     initial empty CRL (DER), valid 10 years — publish at ROOT_CRL_URL
#   root-ca.crl.pem the same as PEM
#
# The script refuses to run if $ROOT_DIR/root-ca.key already exists.
# The key is unencrypted — test material only; never commit $ROOT_DIR.
set -e
CONF="${1:?usage: ca-create.sh <config file, e.g. pki.conf>}"
[ -f "$CONF" ] || { echo "config file not found: $CONF" >&2; exit 1; }
case "$CONF" in /*|./*) ;; *) CONF="./$CONF" ;; esac
. "$CONF"
shift
CA="${ROOT_DIR:?set ROOT_DIR in $CONF}"
TYPE="${CA_TYPE:-rsa}"
case "$TYPE" in rsa|ec) ;; *) echo "type must be rsa or ec" >&2; exit 1 ;; esac

if [ -f "$CA/root-ca.key" ]; then
  echo "$CA/root-ca.key exists: CA was already created (delete the folder to start over)" >&2
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
CN = ${ROOT_CN:-PLC Test CA}
[v3_ca]
basicConstraints = critical, CA:TRUE, pathlen:1
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash
CNF

if [ "$TYPE" = ec ]; then
  openssl ecparam -name prime256v1 -genkey -noout -out root-ca.key
else
  openssl genrsa -out root-ca.key 3072 2>/dev/null
fi
chmod 600 root-ca.key
openssl req -new -x509 -key root-ca.key -sha256 -days 3650 -config ca.cnf -extensions v3_ca -out root-ca.crt

openssl ca -config ca.cnf -cert root-ca.crt -keyfile root-ca.key -gencrl -out root-ca.crl.pem 2>/dev/null
openssl crl -in root-ca.crl.pem -outform DER -out root-ca.crl

echo "created $TYPE root CA in $CA: root-ca.crt, root-ca.key, ca.cnf, db/, root-ca.crl"
openssl x509 -in root-ca.crt -noout -subject -issuer -dates -ext basicConstraints,keyUsage
echo
echo "To issue cards directly from this root (no intermediate) set in pki.conf:"
echo "  CA_DIR=$ROOT_DIR  CA_CRT=root-ca.crt  CA_KEY=root-ca.key"
