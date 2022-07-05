# Run in directory with pem and key files
openssl pkcs12 -export -in rio_test_surfeduhub_surf_nl.pem -inkey rio_test_surfeduhub_surf_nl.key -certfile rio_test_surfeduhub_surf_nl.pem -out keystore.p12
keytool -changealias -alias "1" -destalias "test-surf" -keystore ./keystore.p12
keytool -importkeystore -srckeystore keystore.p12 -srcstoretype PKCS12 -destkeystore keystore.jks