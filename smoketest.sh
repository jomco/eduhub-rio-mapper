#!/usr/bin/env bash

set -e
set -o pipefail

COURSE_ID=8fca6e9e-4eb6-43da-9e78-4e1fad29abf0
EDUSPEC_CHILD_ID=afb435cc-5352-f55f-a548-41c9dfd6596d

ENDPOINT="jomco.github.io" # ensure this corresponds to
			   # institution-schac-home for client

ACCESS_TOKEN=$(curl -s --request POST \
  --url "${TOKEN_ENDPOINT}" \
  --header 'content-type: application/x-www-form-urlencoded' \
  --data grant_type=client_credentials \
  --data "audience=${SURF_CONEXT_CLIENT_ID}" \
  --user "${CLIENT_ID}:${CLIENT_SECRET}" |jq .access_token |tr -d \")

EDUCATION_SPECIFICATION_ID=$(./dev/ooapi-get.sh $ENDPOINT courses/$COURSE_ID | jq '.educationSpecification' | tr -d \")

# Run upsert / delete from CLI commands
echo lein mapper upsert "$CLIENT_ID" education-specification $EDUCATION_SPECIFICATION_ID
lein mapper upsert "$CLIENT_ID" education-specification $EDUCATION_SPECIFICATION_ID | \
    jq '.aanleveren_opleidingseenheid_response.requestGoedgekeurd' | \
    grep 'true'

echo lein mapper upsert "$CLIENT_ID" education-specification $EDUSPEC_CHILD_ID
lein mapper upsert "$CLIENT_ID" education-specification $EDUSPEC_CHILD_ID | \
    jq '.aanleveren_opleidingseenheid_response.requestGoedgekeurd' | \
    grep 'true'

OPLEIDINGSCODE=$(lein mapper resolve "$CLIENT_ID" $EDUSPEC_CHILD_ID | tr -d \")

# ASSERT NR RELATIONS OF $EDUCATION_SPECIFICATION_ID IS 1
echo lein mapper get "$CLIENT_ID" edn:opleidingsrelatiesBijOpleidingseenheid "$OPLEIDINGSCODE"
lein mapper get "$CLIENT_ID" edn:opleidingsrelatiesBijOpleidingseenheid "$OPLEIDINGSCODE" | \
    grep parent-opleidingseenheidcode

# Run upsert / delete from CLI commands
echo lein mapper delete "$CLIENT_ID" education-specification "$EDUSPEC_CHILD_ID"
lein mapper delete "$CLIENT_ID" education-specification "$EDUSPEC_CHILD_ID" | \
    jq '.verwijderen_opleidingseenheid_response.requestGoedgekeurd' | \
    grep 'true'

# ASSERT NR RELATIONS OF $EDUCATION_SPECIFICATION_ID IS 0
echo lein mapper get "$CLIENT_ID" edn:opleidingsrelatiesBijOpleidingseenheid "$OPLEIDINGSCODE"
lein mapper get "$CLIENT_ID" edn:opleidingsrelatiesBijOpleidingseenheid "$OPLEIDINGSCODE" | \
    grep nil

# Run upsert / delete from CLI commands
echo lein mapper upsert "$CLIENT_ID" course $COURSE_ID
lein mapper upsert "$CLIENT_ID" course $COURSE_ID | \
    jq '.aanleveren_aangebodenOpleiding_response.requestGoedgekeurd' | \
    grep 'true'

echo lein mapper delete "$CLIENT_ID" course $COURSE_ID
lein mapper delete "$CLIENT_ID" course $COURSE_ID | \
    jq '.verwijderen_aangebodenOpleiding_response.requestGoedgekeurd' | \
    grep 'true'

echo lein mapper delete "$CLIENT_ID" education-specification $EDUCATION_SPECIFICATION_ID
lein mapper delete "$CLIENT_ID" education-specification $EDUCATION_SPECIFICATION_ID | \
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

ROOT_URL="http://${API_HOSTNAME}:${API_PORT}"

# Give api server some time to startup
WAIT_SECS=120
echo "Waiting for serve-api to come online.."
while ! curl -s "$ROOT_URL"; do
    WAIT_SECS=$((WAIT_SECS - 1))
    if [ $WAIT_SECS = 0 ]; then
        echo "Timeout waiting for serve-api to come online.." >&2
        exit 1
    fi
    sleep 1
done

URL="${ROOT_URL}/job/upsert/education-specifications/${EDUCATION_SPECIFICATION_ID}"
echo Post upsert eduspec
UPSERT_EDUSPEC_TOKEN=$(curl -sf -X POST -H "Authorization: Bearer ${ACCESS_TOKEN}" "$URL" | jq -r .token)
echo "  token=$UPSERT_EDUSPEC_TOKEN"
echo

URL="${ROOT_URL}/job/upsert/courses/${COURSE_ID}"
echo Post upsert course
UPSERT_COURSE_TOKEN=$(curl -sf -X POST -H "Authorization: Bearer ${ACCESS_TOKEN}" "$URL" | jq -r .token)
echo "  token=$UPSERT_COURSE_TOKEN"
echo

URL="${ROOT_URL}/job/delete/courses/${COURSE_ID}"
echo Post delete course
DELETE_COURSE_TOKEN=$(curl -sf -X POST -H "Authorization: Bearer ${ACCESS_TOKEN}" "$URL" | jq -r .token)
echo "  token=$DELETE_COURSE_TOKEN"
echo

URL="${ROOT_URL}/job/delete/education-specifications/${EDUCATION_SPECIFICATION_ID}"
echo Post delete eduspec
DELETE_EDUSPEC_TOKEN=$(curl -sf -X POST -H "Authorization: Bearer ${ACCESS_TOKEN}" "$URL" | jq -r .token)
echo "  token=$DELETE_EDUSPEC_TOKEN"
echo


UPSERT_EDUSPEC_DONE=
DELETE_EDUSPEC_DONE=
UPSERT_COURSE_DONE=
DELETE_COURSE_DONE=

while [ -z "$UPSERT_EDUSPEC_DONE" ] || [ -z "$DELETE_EDUSPEC_DONE" ]; do
    sleep 2

    if [ -z "$UPSERT_EDUSPEC_DONE" ]; then
        URL="$ROOT_URL/status/$UPSERT_EDUSPEC_TOKEN"
        echo Status eduspec upsert
        UPSERT_EDUSPEC_STATE=$(curl -sf -H "Authorization: Bearer ${ACCESS_TOKEN}" "$URL")
        UPSERT_EDUSPEC_STATUS="$(echo "$UPSERT_EDUSPEC_STATE" | jq -r .status)"
        echo "$UPSERT_EDUSPEC_STATE" | jq
        echo
        [ "$UPSERT_EDUSPEC_STATUS" = 'done' ] || [ "$UPSERT_EDUSPEC_STATUS" = 'error' ] \
            && UPSERT_EDUSPEC_DONE=t
    fi

    if [ -z "$DELETE_EDUSPEC_DONE" ]; then
        URL="$ROOT_URL/status/$DELETE_EDUSPEC_TOKEN"
        echo Status eduspec delete
        DELETE_EDUSPEC_STATE=$(curl -sf -H "Authorization: Bearer ${ACCESS_TOKEN}" "$URL")
        DELETE_EDUSPEC_STATUS="$(echo "$DELETE_EDUSPEC_STATE" | jq -r .status)"
        echo "$DELETE_EDUSPEC_STATE" | jq
        echo
        [ "$DELETE_EDUSPEC_STATUS" = 'done' ] || [ "$DELETE_EDUSPEC_STATUS" = 'error' ] \
            && DELETE_EDUSPEC_DONE=t
    fi

    if [ -z "$UPSERT_COURSE_DONE" ]; then
        URL="$ROOT_URL/status/$UPSERT_COURSE_TOKEN"
        echo Status course upsert
        UPSERT_COURSE_STATE=$(curl -sf -H "Authorization: Bearer ${ACCESS_TOKEN}" "$URL")
        UPSERT_COURSE_STATUS="$(echo "$UPSERT_COURSE_STATE" | jq -r .status)"
        echo "$UPSERT_COURSE_STATE" | jq
        echo
        [ "$UPSERT_COURSE_STATUS" = 'done' ] || [ "$UPSERT_COURSE_STATUS" = 'error' ] \
            && UPSERT_COURSE_DONE=t
    fi

    if [ -z "$DELETE_COURSE_DONE" ]; then
        URL="$ROOT_URL/status/$DELETE_COURSE_TOKEN"
        echo Status course delete
        DELETE_COURSE_STATE=$(curl -sf -H "Authorization: Bearer ${ACCESS_TOKEN}" "$URL")
        DELETE_COURSE_STATUS="$(echo "$DELETE_COURSE_STATE" | jq -r .status)"
        echo "$DELETE_COURSE_STATE" | jq
        echo
        [ "$DELETE_COURSE_STATUS" = 'done' ] || [ "$DELETE_COURSE_STATUS" = 'error' ] \
            && DELETE_COURSE_DONE=t
    fi

done
