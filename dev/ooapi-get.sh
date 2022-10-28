#!/usr/bin/env bash

# Basic script to fetch data from OOAPI endpoint through the
# configured eduhub gateway.

set -e
set -o pipefail

if [ -z "$1" ] && [ -z "$2" ]; then
    echo "Usage: $0 SCHAC_HOME RESOURCE" >&2
    exit 1
fi

if [ -z "$GATEWAY_USER" ] || [ -z "$GATEWAY_PASSWORD" ] || [ -z "$GATEWAY_ROOT_URL" ]; then
    echo "Gateway environment variables missing" >&2
    exit 1
fi

ENDPOINT="$1"
RESOURCE="$2"

curl -s \
     -u "$GATEWAY_USER:$GATEWAY_PASSWORD" \
     -H "Accept: application/json; version=5" \
     -H "X-Route: endpoint=$ENDPOINT" \
     "$GATEWAY_ROOT_URL$RESOURCE" \
    | jq ".responses[\"$ENDPOINT\"]"
