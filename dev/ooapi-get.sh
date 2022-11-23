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
