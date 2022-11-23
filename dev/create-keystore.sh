#!/bin/sh

# This file is part of eduhub-rio-mapper
#
# Copyright (C) 2022 SURFnet B.V.
#
# This program is free software: you can redistribute it and/or
# modify it under the terms of the GNU Affero General Public License
# as published by the Free Software Foundation, either version 3 of
# the License, or (at your option) any later version.
#
# This program is distributed in the hope that it will be useful, but
# WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
# Affero General Public License for more details.
#
# You should have received a copy of the GNU Affero General Public
# License along with this program.  If not, see
# <https://www.gnu.org/licenses/>.

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
