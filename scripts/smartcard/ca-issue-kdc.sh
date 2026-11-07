#!/bin/sh
# LAB ONLY: issues the KDC certificate for the domain controller.
#
# In a real customer domain the domain controller already has a KDC certificate
# issued automatically by the Microsoft enterprise CA. This script is only needed
# in the Samba-based lab environment.
#
# Usage:
#   ./ca-issue-kdc.sh pki.conf
#
# Reads from the config file: CA_DIR, CA_CRT, CA_KEY (the issuing CA), DC_FQDN, REALM, DOMAIN, CRL_URL.
#
# Written into <ca-dir>/issued/:
#   kdc.pem   certificate
#   kdc.key   private key (600)
#
# KDC certificate extensions (required by Kerberos for SmartCard pre-authentication):
#   extendedKeyUsage  1.3.6.1.5.2.3.5 (KDC Authentication) + Smart Card Logon + serverAuth + clientAuth
#   subjectAltName    DNS:<DC_FQDN>, DNS:<DOMAIN>, otherName krbtgt/<REALM> (1.3.6.1.5.2.2)
#   crlDistributionPoints  URI:<CRL_URL>
set -e

CONF="${1:?usage: ca-issue-kdc.sh <config file, e.g. pki.conf>}"
[ -f "$CONF" ] || { echo "config file not found: $CONF" >&2; exit 1; }
case "$CONF" in /*|./*) ;; *) CONF="./$CONF" ;; esac
. "$CONF"
shift

CA="${CA_DIR:?set CA_DIR in $CONF}"
: "${CA_CRT:?set CA_CRT in $CONF}"
: "${CA_KEY:?set CA_KEY in $CONF}"
: "${DC_FQDN:?set DC_FQDN in $CONF}"
: "${REALM:?set REALM in $CONF}"
: "${DOMAIN:?set DOMAIN in $CONF}"
: "${CRL_URL:?set CRL_URL in $CONF}"

[ -f "$CA/ca.cnf" ] || { echo "$CA/ca.cnf missing: not a CA folder" >&2; exit 1; }
mkdir -p "$CA/issued"
cd "$CA"
[ -f "$CA_CRT" ] && [ -f "$CA_KEY" ] || { echo "CA_CRT/CA_KEY not found (relative to $CA): $CA_CRT $CA_KEY" >&2; exit 1; }
if [ -f issued/kdc.key ]; then
  echo "issued/kdc.key exists: KDC certificate was already issued" >&2
  exit 1
fi

cat > issued/kdc.ext <<CNF
basicConstraints = CA:FALSE
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = 1.3.6.1.5.2.3.5, 1.3.6.1.4.1.311.20.2.2, serverAuth, clientAuth
subjectAltName = @alt
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid
crlDistributionPoints = URI:$CRL_URL
[alt]
DNS.1 = $DC_FQDN
DNS.2 = $DOMAIN
otherName.1 = 1.3.6.1.5.2.2;SEQUENCE:kdc_princ
[kdc_princ]
realm = EXP:0, GeneralString:$REALM
principal_name = EXP:1, SEQUENCE:kdc_principal_seq
[kdc_principal_seq]
name_type = EXP:0, INTEGER:1
name_string = EXP:1, SEQUENCE:kdc_principals
[kdc_principals]
princ1 = GeneralString:krbtgt
princ2 = GeneralString:$REALM
CNF

openssl genrsa -out issued/kdc.key 2048 2>/dev/null
openssl req -new -key issued/kdc.key -subj "/CN=$DC_FQDN" -out issued/kdc.csr
openssl ca -batch -config ca.cnf -cert "$CA_CRT" -keyfile "$CA_KEY" \
  -extfile issued/kdc.ext -in issued/kdc.csr -out issued/kdc.pem -notext >/dev/null 2>&1
chmod 600 issued/kdc.key

echo "written in $CA/issued: kdc.pem, kdc.key"
openssl x509 -in issued/kdc.pem -noout -subject -issuer \
  -ext extendedKeyUsage,subjectAltName,crlDistributionPoints
