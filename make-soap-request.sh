curl -v --cert 'certs/rio_test_surfeduhub_surf_nl.pem:$PASSPHRASE' \
                --key certs/rio_test_surfeduhub_surf_nl.key \
                --cacert staat-der-nederlanden-private-root.pem \
                -H "Content-Type: text/xml" \
                -H 'SOAPAction: http://duo.nl/contract/DUO_RIO_Raadplegen_OnderwijsOrganisatie_V4/opvragen_aangebodenOpleidingenVanOrganisatie' \
                -d @../eduhub-rio-mapper/signed.xml \
                https://vt-webservice.duo.nl:6977/RIO/services/raadplegen4.0
