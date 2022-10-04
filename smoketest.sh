#!/bin/sh

set -e

SCHAC_HOME=demo06.test.surfeduhub.nl

EDUCATION_SPECIFICATION_ID=$(curl -s "https://${SCHAC_HOME}/courses" | jq '.items[1].educationSpecification' | sed s/\"//g)

echo lein mapper upsert "$SCHAC_HOME" education-specification $EDUCATION_SPECIFICATION_ID
lein mapper upsert "$SCHAC_HOME" education-specification $EDUCATION_SPECIFICATION_ID | \
    jq '.aanleveren_opleidingseenheid_response.requestGoedgekeurd' | \
    grep 'true'

echo lein mapper delete "$SCHAC_HOME" education-specification $EDUCATION_SPECIFICATION_ID
lein mapper delete "$SCHAC_HOME" education-specification $EDUCATION_SPECIFICATION_ID | \
    jq '.verwijderen_opleidingseenheid_response.requestGoedgekeurd' | \
    grep 'true'
