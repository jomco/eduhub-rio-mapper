# SURFeduhub RIO mapper Command Line Interface

## Prerequisites

There must be a `keystore.jks` file in the root of the project, containing the certificates needed to access the RIO API. See `dev/create-keystore.sh` on how to create a keystore, given the private key and the certificate. The app currently expects an alias called `test-surf` and a password `xxxxxx`. This will become configurable at a later stage.
`leiningen` must be installed. To install on macOS, run `brew install leiningen`. On Linux, `apt-get install -y leiningen`.

## Commands

### ls

The `ls` command retrieves data from RIO. The following actions are supported:

#### rioIdentificatiecode

This action retrieves the `opleidingeenheidscode` based on the key that OOAPI uses as primary key for the education specification.

Example:

`lein ls rioIdentificatiecode --eigenOpleidingseenheidSleutel $SLEUTEL`

#### aangebodenOpleiding

This action retrieves the "aangeboden opleiding" based on its course id or program id.

Example:

`lein ls aangebodenOpleiding --aangebodenOpleidingCode $SLEUTEL`

#### opleidingseenhedenVanOrganisatie

This action retrieves a page of "opleidingseenheden" for a specific "onderwijsbestuur". Pages start counting at zero, not one. There is no way to retrieve a single "opleidingseenheid" based on its ID.

Example:

`lein ls opleidingseenhedenVanOrganisatie --pagina 10 --onderwijsbestuurcode 100B490`

#### opleidingsrelatiesBijOpleidingseenheid

This action retrieves the relations of a single "opleidingseenheid" specified by its RIO primary key.

Example:

`lein ls opleidingsrelatiesBijOpleidingseenheid --opleidingseenheidcode 1007O5629`

#### onderwijsaanbiedersVanOrganisatie

This action retrieves the "onderwijsaanbieders" for a "onderwijsbestuur" specified by a "onderwijsbestuurcode".

Example:

`lein ls onderwijsaanbiedersVanOrganisatie --onderwijsbestuurCode 100B490`

### rm

#### opleidingseenheid

Removes an "opleidingseenheid", specified by the RIO opleidingseenheidcode.

Example:

`lein rm opleidingseenheid --opleidingseenheidcode 1009O2057`

#### aangebodenOpleiding

Removes an "aangeboden opleiding", specified by  based on its course id or program id.

Example:

`lein rm aangebodenOpleiding --aangebodenOpleidingCode 9d30186a-abd1-d720-a4fa-efba1e5de793`

#### opleidingsrelatie

Removes an "opleidingsrelatie" between two "opleidingseenheden". Specified json file should contain map with `opleidingseenheidcode1`, `opleidingseenheidcode2`, and `begindatum`.

Example:

`lein rm opleidingsrelatie --opleidingsrelatieForRemoval dev/fixtures/opleidingsrelatie.json`

### cr

#### opleidingseenheid

Creates an "opleidingseenheid" from given json file (which is the output of the OOAPI for a single education specification).

Example:

`lein cr opleidingseenheid --educationspecification eduspec.json`

#### aangebodenOpleiding

Creates an "aangebodenOpleiding" from given json file (which is the output of the OOAPI for a single education specification). Name of option determines the subclass of aangebodenOpleiding.

Examples:

`lein cr aangebodenOpleiding --course course.json`
`lein cr aangebodenOpleiding --programCourse program.json`
`lein cr aangebodenOpleiding --program program.json`
`lein cr aangebodenOpleiding --privateProgram program.json`

#### opleidingsrelatie

Creates an "opleidingsrelatie" from the information in the given json file, which contains a begin- and end date, and the ids of two "opleidingseenheden". See `dev/fixtures/opleidingsrelatie.json`

Example: 

`lein cr opleidingsrelatie --opleidingsrelatie dev/fixtures/opleidingsrelatie.json`

### Updated

The "updated" commands simulates the situation where an education spec, program or course has been updated, and should be synced with RIO. This will perform the entire flow, including querying the education specification type, the RIO id of the "opleidingseenheid", and the offerings of the program or course. It will build a request from all collected data, and send it to rio.

The format of the command is as follows:

`lein updated $OOAPI-SOURCE $MODE $ENTITY $ID`

These options for OOAPI-SOURCE are currently available:

file
: Use json files in local file system (dev/fixtures)]

local
: Use a local webserver listening at port 8080

demo04,demo05,demo06
: Servers that return demo data simulating plausible json output. There are 3 servers available, each with different seeds.

dev
: Ooapi server at demo01.eduapi.nl

These modes are available:

dry-run
: Don't execute the command, print the request xml file to standard output.

show-ooapi
: Show the ooapi object as json

execute
: Sends the request to RIO so that it will be executed

execute-verbose
: Sends the request to RIO so that it will be executed, and also prints the request xml file to standard output.

These entities are available: "education-specification", "program", and "course".
