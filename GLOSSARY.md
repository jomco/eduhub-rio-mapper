# Argument names

action
: unqualified action used in rio, e.g. "aanleveren_aangebodenOpleiding"

credentials
: map with following keys: "keystore", "trust-store", "keystore-pass", "trust-store-pass", "private-key", "certificate"

rio-sexp
: datastructure in hiccup-format representing the part of the API-call to RIO that will be wrapped in a "_request"

soap-action
: qualified name used in SOAP-header, e.g. "http://duo.nl/contract/DUO_RIO_Beheren_OnderwijsOrganisatie_V4/aanleveren_opleidingsrelatie"

target
: name of rio-entity, such as "aangebodenOpleiding", which is part of the "action".
