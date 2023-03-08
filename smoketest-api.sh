#!/usr/bin/env bash

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

set -e
set -o pipefail

PROGRAM_ID=49ca7998-74b1-f44a-1ec1-000000000002

ENDPOINT="jomco.github.io" # ensure this corresponds to
			   # institution-schac-home for client

ACCESS_TOKEN=$(curl -s --request POST \
  --url "${TOKEN_ENDPOINT}" \
  --header 'content-type: application/x-www-form-urlencoded' \
  --data grant_type=client_credentials \
  --data "audience=${SURF_CONEXT_CLIENT_ID}" \
  --user "${CLIENT_ID}:${CLIENT_SECRET}" |jq .access_token |tr -d \")

EDUCATION_SPECIFICATION_ID=$(./dev/ooapi-get.sh $ENDPOINT programs/$PROGRAM_ID | jq '.educationSpecification' | tr -d \")

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

echo "serve-api is online.."

# Before upsert
URL="${ROOT_URL}/job/dry-run-upsert/education-specifications/${EDUCATION_SPECIFICATION_ID}"
echo Post dry run eduspec
DRYRUN_EDUSPEC_TOKEN=$(curl -sf -X POST -H "Authorization: Bearer ${ACCESS_TOKEN}" -H "X-Callback: ${ROOT_URL}/webhook" "$URL" | jq -r .token)
echo "  token=DRYRUN_EDUSPEC_TOKEN"
echo

URL="${ROOT_URL}/job/upsert/education-specifications/${EDUCATION_SPECIFICATION_ID}"
echo Post upsert eduspec
UPSERT_EDUSPEC_TOKEN=$(curl -sf -X POST -H "Authorization: Bearer ${ACCESS_TOKEN}" -H "X-Callback: ${ROOT_URL}/webhook" "$URL" | jq -r .token)
echo "  token=$UPSERT_EDUSPEC_TOKEN"
echo

URL="${ROOT_URL}/job/upsert/programs/${PROGRAM_ID}"
echo Post upsert program
UPSERT_PROGRAM_TOKEN=$(curl -sf -X POST -H "Authorization: Bearer ${ACCESS_TOKEN}" "$URL" | jq -r .token)
echo "  token=$UPSERT_PROGRAM_TOKEN"
echo

# After upsert
URL="${ROOT_URL}/job/dry-run-upsert/programs/${PROGRAM_ID}"
echo Post dry run program
DRYRUN_PROGRAM_TOKEN=$(curl -sf -X POST -H "Authorization: Bearer ${ACCESS_TOKEN}" "$URL" | jq -r .token)
echo "  token=$DRYRUN_PROGRAM_TOKEN"
echo

URL="${ROOT_URL}/job/link/${PROGRAM_ID}/programs/4c358c84-dfc3-4a30-874e-000000000000"
echo Link program
LINK_PROGRAM_TOKEN=$(curl -sf -X POST -H "Authorization: Bearer ${ACCESS_TOKEN}" "$URL" | jq -r .token)
echo "  token=$LINK_PROGRAM_TOKEN"
echo

URL="${ROOT_URL}/job/delete/programs/${PROGRAM_ID}"
echo Post delete program
DELETE_PROGRAM_TOKEN=$(curl -sf -X POST -H "Authorization: Bearer ${ACCESS_TOKEN}" "$URL" | jq -r .token)
echo "  token=$DELETE_PROGRAM_TOKEN"

URL="${ROOT_URL}/job/delete/education-specifications/${EDUCATION_SPECIFICATION_ID}"
echo Post delete eduspec
DELETE_EDUSPEC_TOKEN=$(curl -sf -X POST -H "Authorization: Bearer ${ACCESS_TOKEN}" "$URL" | jq -r .token)
echo "  token=$DELETE_EDUSPEC_TOKEN"
echo

DRYRUN_EDUSPEC_DONE=
UPSERT_EDUSPEC_DONE=
UPSERT_PROGRAM_DONE=
DRYRUN_PROGRAM_DONE=
LINK_PROGRAM_DONE=
DELETE_PROGRAM_DONE=
DELETE_EDUSPEC_DONE=

HTTP_MESSAGES=false

while [ -z "$UPSERT_EDUSPEC_DONE" ] || [ -z "$DELETE_EDUSPEC_DONE" ]; do
    sleep 5

    if [ -z "$DRYRUN_EDUSPEC_DONE" ]; then
        URL="$ROOT_URL/status/$DRYRUN_EDUSPEC_TOKEN?http-messages=$HTTP_MESSAGES"
        echo Status eduspec dry run
        DRYRUN_EDUSPEC_STATE=$(curl -sf -H "Authorization: Bearer ${ACCESS_TOKEN}" "$URL")
        DRYRUN_EDUSPEC_STATUS="$(echo "$DRYRUN_EDUSPEC_STATE" | jq -r .status)"
        echo "$DRYRUN_EDUSPEC_STATE" | jq
        echo
        [ "$DRYRUN_EDUSPEC_STATUS" = 'done' ] || [ "$DRYRUN_EDUSPEC_STATUS" = 'error' ] \
            && DRYRUN_EDUSPEC_DONE=t
    fi

    if [ -z "$UPSERT_EDUSPEC_DONE" ]; then
        URL="$ROOT_URL/status/$UPSERT_EDUSPEC_TOKEN?http-messages=$HTTP_MESSAGES"
        echo Status eduspec upsert
        UPSERT_EDUSPEC_STATE=$(curl -sf -H "Authorization: Bearer ${ACCESS_TOKEN}" "$URL")
        UPSERT_EDUSPEC_STATUS="$(echo "$UPSERT_EDUSPEC_STATE" | jq -r .status)"
        echo "$UPSERT_EDUSPEC_STATE" | jq
        echo
        [ "$UPSERT_EDUSPEC_STATUS" = 'done' ] || [ "$UPSERT_EDUSPEC_STATUS" = 'error' ] \
            && UPSERT_EDUSPEC_DONE=t
    fi

    if [ -z "$UPSERT_PROGRAM_DONE" ]; then
        URL="$ROOT_URL/status/$UPSERT_PROGRAM_TOKEN?http-messages=$HTTP_MESSAGES"
        echo Status program upsert
        UPSERT_PROGRAM_STATE=$(curl -sf -H "Authorization: Bearer ${ACCESS_TOKEN}" "$URL")
        UPSERT_PROGRAM_STATUS="$(echo "$UPSERT_PROGRAM_STATE" | jq -r .status)"
        echo "$UPSERT_PROGRAM_STATE" | jq
        echo
        [ "$UPSERT_PROGRAM_STATUS" = 'done' ] || [ "$UPSERT_PROGRAM_STATUS" = 'error' ] \
            && UPSERT_PROGRAM_DONE=t
    fi

    if [ -z "$DRYRUN_PROGRAM_DONE" ]; then
        URL="$ROOT_URL/status/$DRYRUN_PROGRAM_TOKEN?http-messages=$HTTP_MESSAGES"
        echo Status program dry run
        DRYRUN_PROGRAM_STATE=$(curl -sf -H "Authorization: Bearer ${ACCESS_TOKEN}" "$URL")
        DRYRUN_PROGRAM_STATUS="$(echo "$DRYRUN_PROGRAM_STATE" | jq -r .status)"
        echo "$DRYRUN_PROGRAM_STATE" | jq
        echo
        [ "$DRYRUN_PROGRAM_STATUS" = 'done' ] || [ "$DRYRUN_PROGRAM_STATUS" = 'error' ] \
            && DRYRUN_PROGRAM_DONE=t
    fi

    if [ -z "$LINK_PROGRAM_DONE" ]; then
        URL="$ROOT_URL/status/$LINK_PROGRAM_TOKEN?http-messages=$HTTP_MESSAGES"
        echo Status program dry run
        LINK_PROGRAM_STATE=$(curl -sf -H "Authorization: Bearer ${ACCESS_TOKEN}" "$URL")
        LINK_PROGRAM_STATUS="$(echo "$LINK_PROGRAM_STATE" | jq -r .status)"
        echo "$LINK_PROGRAM_STATE" | jq
        echo
        [ "$LINK_PROGRAM_STATUS" = 'done' ] || [ "$LINK_PROGRAM_STATUS" = 'error' ] \
            && LINK_PROGRAM_DONE=t
    fi

    if [ -z "$DELETE_PROGRAM_DONE" ]; then
        URL="$ROOT_URL/status/$DELETE_PROGRAM_TOKEN?http-messages=$HTTP_MESSAGES"
        echo Status program delete
        DELETE_PROGRAM_STATE=$(curl -sf -H "Authorization: Bearer ${ACCESS_TOKEN}" "$URL")
        DELETE_PROGRAM_STATUS="$(echo "$DELETE_PROGRAM_STATE" | jq -r .status)"
        echo "$DELETE_PROGRAM_STATE" | jq
        [ "$DELETE_PROGRAM_STATUS" = 'done' ] || [ "$DELETE_PROGRAM_STATUS" = 'error' ] \
            && DELETE_PROGRAM_DONE=t
    fi

    if [ -z "$DELETE_EDUSPEC_DONE" ]; then
        URL="$ROOT_URL/status/$DELETE_EDUSPEC_TOKEN?http-messages=$HTTP_MESSAGES"
        echo Status eduspec delete
        DELETE_EDUSPEC_STATE=$(curl -sf -H "Authorization: Bearer ${ACCESS_TOKEN}" "$URL")
        DELETE_EDUSPEC_STATUS="$(echo "$DELETE_EDUSPEC_STATE" | jq -r .status)"
        echo "$DELETE_EDUSPEC_STATE" | jq
        echo
        [ "$DELETE_EDUSPEC_STATUS" = 'done' ] || [ "$DELETE_EDUSPEC_STATUS" = 'error' ] \
            && DELETE_EDUSPEC_DONE=t
    fi
done
