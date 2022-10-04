#!/bin/sh

set -e
set -o pipefail

SCHAC_HOME=demo06.test.surfeduhub.nl

EDUCATION_SPECIFICATION_ID=$(curl -s "https://${SCHAC_HOME}/courses" | jq '.items[1].educationSpecification' | sed s/\"//g)

# Run upsert / delete from CLI commands
echo lein mapper upsert "$SCHAC_HOME" education-specification $EDUCATION_SPECIFICATION_ID
lein mapper upsert "$SCHAC_HOME" education-specification $EDUCATION_SPECIFICATION_ID | \
    jq '.aanleveren_opleidingseenheid_response.requestGoedgekeurd' | \
    grep 'true'

echo lein mapper delete "$SCHAC_HOME" education-specification $EDUCATION_SPECIFICATION_ID
lein mapper delete "$SCHAC_HOME" education-specification $EDUCATION_SPECIFICATION_ID | \
    jq '.verwijderen_opleidingseenheid_response.requestGoedgekeurd' | \
    grep 'true'


# Now run the same commands through the web API

export API_PORT=2345
export API_HOSTNAME=localhost

# run with trampoline because otherwise lein will fork a new process
# and then we can't kill the server later
lein trampoline mapper serve-api&
SERVER_PID=$!

kill_server(){
    if [ -n "${SERVER_PID}" ]; then
	kill $SERVER_PID
    fi
}

trap 'kill_server' EXIT

sleep 10

ROOT_URL="http://${API_HOSTNAME}:${API_PORT}/"
set -x
curl --fail-with-body -H "X-Schac-Home: ${SCHAC_HOME}" -X 'POST' "${ROOT_URL}job/upsert/education-specifications/${EDUCATION_SPECIFICATION_ID}"

sleep 5

curl  --fail-with-body -H "X-Schac-Home: ${SCHAC_HOME}" -X 'POST' "${ROOT_URL}job/delete/education-specifications/${EDUCATION_SPECIFICATION_ID}"

sleep 5
