#!/bin/sh

set -ex

CERT_DIR=.
CERT_BASE="$CERT_DIR/rio_test_surfeduhub_surf_nl"
KEY_ALIAS="test-surf"
KEYSTORE_DEST_DIR="$(dirname "$0")/.."

TMP_KEYSTORE="/tmp/create-keystore-$$.p12"
trap "rm -f \"$TMP_KEYSTORE\"" 0

openssl pkcs12 -export \
        -in "$CERT_BASE".pem \
        -inkey "$CERT_BASE".key \
        -certfile "$CERT_BASE".pem \
        -password pass:xxxxxx \
        -out "$TMP_KEYSTORE"

keytool -changealias \
        -alias "1" \
        -destalias "$KEY_ALIAS" \
        -storepass xxxxxx \
        -keystore "$TMP_KEYSTORE"

keytool -importkeystore \
        -srckeystore "$TMP_KEYSTORE" \
        -srcstoretype PKCS12 \
        -srcstorepass xxxxxx \
        -destkeystore "$KEYSTORE_DEST_DIR/keystore.jks"
