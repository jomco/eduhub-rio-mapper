#/bin/sh
TRUSTSTORE_DEST_DIR=.

keytool -import -file dev/staat-der-nederlanden-private-root.pem -alias firstCA -keystore $TRUSTSTORE_DEST_DIR/truststore.jks
