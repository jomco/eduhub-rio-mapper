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

COURSE_ID=8fca6e9e-4eb6-43da-9e78-4e1fad290001

ENDPOINT="jomco.github.io" # ensure this corresponds to
			   # institution-schac-home for client

ACCESS_TOKEN=$(curl -s --request POST \
  --url "${TOKEN_ENDPOINT}" \
  --header 'content-type: application/x-www-form-urlencoded' \
  --data grant_type=client_credentials \
  --data "audience=${SURF_CONEXT_CLIENT_ID}" \
  --user "${CLIENT_ID}:${CLIENT_SECRET}" |jq .access_token |tr -d \")

EDUCATION_SPECIFICATION_ID="badded00-8ca1-c991-8d39-9a85d09cbcf5"

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

URL="${ROOT_URL}/job/delete/education-specifications/${EDUCATION_SPECIFICATION_ID}"
echo Post delete eduspec
DELETE_EDUSPEC_TOKEN=$(curl -sf -X POST -H "Authorization: Bearer ${ACCESS_TOKEN}" "$URL" | jq -r .token)
echo "  token=$DELETE_EDUSPEC_TOKEN"
echo


UPSERT_EDUSPEC_DONE=
DELETE_EDUSPEC_DONE=

while [ -z "$UPSERT_EDUSPEC_DONE" ] || [ -z "$DELETE_EDUSPEC_DONE" ]; do
    sleep 2

    if [ -z "$UPSERT_EDUSPEC_DONE" ]; then
        URL="$ROOT_URL/status/$UPSERT_EDUSPEC_TOKEN"
        echo Status eduspec upsert
        UPSERT_EDUSPEC_STATE=$(curl -sf -H "Authorization: Bearer ${ACCESS_TOKEN}" "$URL")
        UPSERT_EDUSPEC_STATUS="$(echo "$UPSERT_EDUSPEC_STATE" | jq -r .status)"
        echo "$UPSERT_EDUSPEC_STATE" | jq
        echo
        [ "$UPSERT_EDUSPEC_STATUS" = 'done' ] \
            || [ "$UPSERT_EDUSPEC_STATUS" = 'error' ] \
            || [ "$UPSERT_EDUSPEC_STATUS" = 'time-out' ] \
            && UPSERT_EDUSPEC_DONE=t
    fi

    if [ -z "$DELETE_EDUSPEC_DONE" ]; then
        URL="$ROOT_URL/status/$DELETE_EDUSPEC_TOKEN"
        echo Status eduspec delete
        DELETE_EDUSPEC_STATE=$(curl -sf -H "Authorization: Bearer ${ACCESS_TOKEN}" "$URL")
        DELETE_EDUSPEC_STATUS="$(echo "$DELETE_EDUSPEC_STATE" | jq -r .status)"
        echo "$DELETE_EDUSPEC_STATE" | jq
        echo
        [ "$DELETE_EDUSPEC_STATUS" = 'done' ] \
            || [ "$DELETE_EDUSPEC_STATUS" = 'error' ] \
            || [ "$DELETE_EDUSPEC_STATUS" = 'time-out' ] \
            && DELETE_EDUSPEC_DONE=t
    fi
done
