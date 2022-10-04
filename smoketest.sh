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
lein trampoline mapper serve-api &
API_SERVER_PID=$!
trap "kill $API_SERVER_PID" EXIT

lein trampoline mapper worker &
WORKER_SERVER_PID=$!
# Note: traps get overwritten
trap "kill $API_SERVER_PID $WORKER_SERVER_PID" EXIT

# Give api server some time to startup
sleep 20

ROOT_URL="http://${API_HOSTNAME}:${API_PORT}"

URL="${ROOT_URL}/job/upsert/education-specifications/${EDUCATION_SPECIFICATION_ID}"
echo Post upsert
UPSERT_TOKEN=$(curl -s --fail-with-body -X POST -H "X-Schac-Home: ${SCHAC_HOME}" "$URL" | jq -r .token)
echo "  token=$UPSERT_TOKEN"
echo

URL="${ROOT_URL}/job/delete/education-specifications/${EDUCATION_SPECIFICATION_ID}"
echo Post delete
DELETE_TOKEN=$(curl -s --fail-with-body -X POST -H "X-Schac-Home: ${SCHAC_HOME}" "$URL" | jq -r .token)
echo "  token=$DELETE_TOKEN"
echo

UPSERT_DONE=
DELETE_DONE=

while [ -z "$UPSERT_DONE" ] || [ -z "$DELETE_DONE" ]; do
    sleep 2

    if [ -z "$UPSERT_DONE" ]; then
        URL="$ROOT_URL/status/$UPSERT_TOKEN"
        echo Status upsert
        UPSERT_STATE=$(curl -s --fail-with-body "$URL")
        UPSERT_STATUS="$(echo "$UPSERT_STATE" | jq -r .status)"
        echo "$UPSERT_STATE" | jq
        echo
        [ "$UPSERT_STATUS" = 'done' ] || [ "$UPSERT_STATUS" = 'error' ] \
            && UPSERT_DONE=t
    fi

    if [ -z "$DELETE_DONE" ]; then
        URL="$ROOT_URL/status/$DELETE_TOKEN"
        echo Status delete
        DELETE_STATE=$(curl -s --fail-with-body "$URL")
        DELETE_STATUS="$(echo "$DELETE_STATE" | jq -r .status)"
        echo "$DELETE_STATE" | jq
        echo
        [ "$DELETE_STATUS" = 'done' ] || [ "$DELETE_STATUS" = 'error' ] \
            && DELETE_DONE=t
    fi
done
