#!/bin/sh
# Creates an intermediate (issuing) CA below an existing root CA.
#
# Usage:
#   ./ca-create-intermediate.sh pki.conf
#
# Reads from the config file: ROOT_DIR (the root made by ca-create.sh: root-ca.crt, root-ca.key,
# ca.cnf, db/), INTERMEDIATE_DIR (where the intermediate is created), INTERMEDIATE_CN (its name)
# and ROOT_CRL_URL (optional: written into the intermediate certificate as CRL distribution
# point, where Windows finds the root's CRL).
#
# Written into INTERMEDIATE_DIR:
#   intermediate-ca.crt / intermediate-ca.key   the intermediate certificate (5 years) and its key
#   ca.cnf, db/             openssl CA configuration and issuing database
#   ca-chain.pem            intermediate + root (for the PKCS#12 and for clients)
#   root-ca.crt             copy of the root certificate
#   intermediate-ca.crl(.pem)   initial empty CRL of the intermediate
#
# Afterwards it prints the lines for pki.conf that make ca-issue-kdc.sh use it.
#
# Keep the root offline after this. The intermediate key is unencrypted — test
# material only; never commit either folder.
set -e
CONF="${1:?usage: ca-create-intermediate.sh <config file, e.g. pki.conf>}"
[ -f "$CONF" ] || { echo "config file not found: $CONF" >&2; exit 1; }
case "$CONF" in /*|./*) ;; *) CONF="./$CONF" ;; esac
. "$CONF"
shift
ROOT="${ROOT_DIR:?set ROOT_DIR in $CONF}"
NEW="${INTERMEDIATE_DIR:?set INTERMEDIATE_DIR in $CONF}"
RCRT=root-ca.crt
RKEY=root-ca.key
TYPE="${CA_TYPE:-rsa}"
CN="${INTERMEDIATE_CN:-PLC Test Intermediate CA}"
case "$TYPE" in rsa|ec) ;; *) echo "type must be rsa or ec" >&2; exit 1 ;; esac

[ -f "$ROOT/ca.cnf" ] || { echo "$ROOT/ca.cnf missing: not a CA folder" >&2; exit 1; }
[ -f "$ROOT/$RCRT" ] && [ -f "$ROOT/$RKEY" ] || { echo "$RCRT / $RKEY not found in $ROOT" >&2; exit 1; }
[ -f "$NEW/intermediate-ca.key" ] && { echo "$NEW/intermediate-ca.key exists: already created" >&2; exit 1; }

PATHLEN=$(openssl x509 -in "$ROOT/$RCRT" -noout -ext basicConstraints | grep -o 'pathlen:[0-9]*' | cut -d: -f2 || true)
if [ "$PATHLEN" = "0" ]; then
  echo "$RCRT has pathlen:0 and cannot sign an intermediate; run ca-reissue-root.sh first" >&2
  exit 1
fi

ROOT=$(cd "$ROOT" && pwd)
# A root copied without its db/ gets an empty one. The serial starts at a random
# value so it cannot collide with serials the same root key issued earlier elsewhere.
if [ ! -f "$ROOT/db/index.txt" ]; then
  mkdir -p "$ROOT/db"
  : > "$ROOT/db/index.txt"
  openssl rand -hex 8 > "$ROOT/db/serial"
  echo 01 > "$ROOT/db/crlnumber"
  echo "created empty issuing database in $ROOT/db"
fi
mkdir -p "$NEW/db"
NEW=$(cd "$NEW" && pwd)
cd "$NEW"
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
default_days = 1825
default_crl_days = 1825
policy = anything
unique_subject = no
[anything]
commonName = optional
[req]
distinguished_name = dn
prompt = no
[dn]
CN = $CN
[v3_ca]
basicConstraints = critical, CA:TRUE, pathlen:0
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid
${ROOT_CRL_URL:+crlDistributionPoints = URI:$ROOT_CRL_URL}
CNF

if [ "$TYPE" = ec ]; then
  openssl ecparam -name prime256v1 -genkey -noout -out intermediate-ca.key
else
  openssl genrsa -out intermediate-ca.key 3072 2>/dev/null
fi
chmod 600 intermediate-ca.key
openssl req -new -key intermediate-ca.key -sha256 -config ca.cnf -out intermediate-ca.csr

# Signed by the root, using the root's database (paths in its ca.cnf are relative)
(cd "$ROOT" && openssl ca -batch -notext -config ca.cnf -cert "$RCRT" -keyfile "$RKEY" \
  -days 1825 -extfile "$NEW/ca.cnf" -extensions v3_ca \
  -in "$NEW/intermediate-ca.csr" -out "$NEW/intermediate-ca.crt")
rm -f intermediate-ca.csr

cp "$ROOT/$RCRT" root-ca.crt
cat intermediate-ca.crt root-ca.crt > ca-chain.pem
openssl verify -CAfile root-ca.crt intermediate-ca.crt

openssl ca -config ca.cnf -cert intermediate-ca.crt -keyfile intermediate-ca.key -gencrl -out intermediate-ca.crl.pem 2>/dev/null
openssl crl -in intermediate-ca.crl.pem -outform DER -out intermediate-ca.crl

echo "created $TYPE intermediate CA in $NEW: intermediate-ca.crt, intermediate-ca.key, ca-chain.pem, root-ca.crt"
openssl x509 -in intermediate-ca.crt -noout -subject -issuer -dates -ext basicConstraints,keyUsage
echo
echo "To issue the KDC certificate from it set in pki.conf:"
echo "  CA_DIR=$NEW  CA_CRT=intermediate-ca.crt  CA_KEY=intermediate-ca.key"
echo "and in plp-admin's smartcard.properties: ca.cert, ca.key (the same two files) and ca.chain=$NEW/ca-chain.pem"
