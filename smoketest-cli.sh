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
EDUSPEC_CHILD_ID=afb435cc-5352-f55f-a548-41c9dfd60001

ENDPOINT="jomco.github.io" # ensure this corresponds to
			   # institution-schac-home for client

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

echo lein mapper resolve "$CLIENT_ID" education-specification $EDUSPEC_CHILD_ID
OPLEIDINGSCODE=$(lein mapper resolve "$CLIENT_ID" education-specification $EDUSPEC_CHILD_ID | tr -d \")
echo $OPLEIDINGSCODE | grep -v nil

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
