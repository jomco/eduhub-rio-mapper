#/bin/sh
PRIVATE_KEY_DIR=.
KEY_ALIAS=test-surf
KEYSTORE_DEST_DIR=.

openssl pkcs12 -export -in $PRIVATE_KEY_DIR/rio_test_surfeduhub_surf_nl.pem -inkey $PRIVATE_KEY_DIR/rio_test_surfeduhub_surf_nl.key -certfile $PRIVATE_KEY_DIR/rio_test_surfeduhub_surf_nl.pem -out keystore.p12
keytool -changealias -alias "1" -destalias $KEY_ALIAS -keystore keystore.p12
keytool -importkeystore -srckeystore keystore.p12 -srcstoretype PKCS12 -destkeystore $KEYSTORE_DEST_DIR/keystore.jks
rm keystore.p12
