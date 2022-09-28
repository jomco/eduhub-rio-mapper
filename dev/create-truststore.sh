#!/bin/sh

set -ex

PEM_FILE="$(dirname "$0")/staat-der-nederlanden-private-root.pem"
TRUSTSTORE_DEST_DIR="$(dirname "$0")/../resources"

keytool -import \
        -file "$PEM_FILE" \
        -alias firstCA \
        -keystore "$TRUSTSTORE_DEST_DIR/truststore.jks"
