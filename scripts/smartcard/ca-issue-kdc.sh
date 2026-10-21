#!/bin/sh
# LAB ONLY: issues the KDC certificate for the domain controller.
#
# In a real customer domain the domain controller already has a KDC certificate
# issued automatically by the Microsoft enterprise CA. This script is only needed
# in the Samba-based lab environment.
#
# Usage:
#   . ./pki.conf && ./ca-issue-kdc.sh
#   ./ca-issue-kdc.sh <ca-dir>   (with PLC_* environment variables set)
#
# When sourced from pki.conf the variables DC_FQDN, REALM, DOMAIN, CRL_URL
# and CA_DIR are used directly. In the explicit form set PLC_DC_FQDN, PLC_REALM,
# PLC_DOMAIN, PLC_CRL_URL in the environment.
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

if [ -n "${CA_DIR}" ] && [ $# -eq 0 ]; then
  CA="${CA_DIR:?set CA_DIR in pki.conf}"
  DC_FQDN="${DC_FQDN:?set DC_FQDN in pki.conf}"
  REALM="${REALM:?set REALM in pki.conf}"
  DOMAIN="${DOMAIN:?set DOMAIN in pki.conf}"
  CRL_URL="${CRL_URL:?set CRL_URL in pki.conf}"
else
  CA="${1:?usage: ca-issue-kdc.sh <ca-dir>}"
  DC_FQDN="${PLC_DC_FQDN:-hmx2.ad.phraselock.test}"
  REALM="${PLC_REALM:-AD.PHRASELOCK.TEST}"
  DOMAIN="${PLC_DOMAIN:-ad.phraselock.test}"
  CRL_URL="${PLC_CRL_URL:-http://hmx2.ad.phraselock.test/plc-test-ca.crl}"
fi

[ -f "$CA/ca.key" ] && [ -f "$CA/ca.cnf" ] || { echo "$CA is not a CA folder made by ca-create.sh" >&2; exit 1; }
mkdir -p "$CA/issued"
cd "$CA"
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
openssl ca -batch -config ca.cnf -cert ca.crt -keyfile ca.key \
  -extfile issued/kdc.ext -in issued/kdc.csr -out issued/kdc.pem -notext >/dev/null 2>&1
chmod 600 issued/kdc.key

echo "written in $CA/issued: kdc.pem, kdc.key"
openssl x509 -in issued/kdc.pem -noout -subject -issuer \
  -ext extendedKeyUsage,subjectAltName,crlDistributionPoints
