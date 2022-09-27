# SURFeduhub RIO mapper Command Line Interface

## Prerequisites

There must be a `keystore.jks` file in the root of the project, containing the certificates needed to access the RIO API. See `dev/create-keystore.sh` on how to create a keystore, given the private key and the certificate. The app currently expects an alias called `test-surf` and a password `xxxxxx`. This will become configurable at a later stage.
`leiningen` must be installed. To install on macOS, run `brew install leiningen`. On Linux, `apt-get install -y leiningen`.

## Commands

### resolve

This action retrieves the `opleidingeenheidscode` based on the key that OOAPI uses as primary key for the education specification.

Example:

`lein resolve $SLEUTEL`

### find

The `find` command retrieves data from RIO. The following actions are supported:

#### aangebodenOpleiding

This action retrieves the "aangeboden opleiding" based on its course id or program id.

Example:

`lein find aangebodenOpleiding $SLEUTEL`

#### opleidingseenhedenVanOrganisatie

This action retrieves a page of "opleidingseenheden" for a specific "onderwijsbestuur". Pages start counting at zero, not one. There is no way to retrieve a single "opleidingseenheid" based on its ID.

Example:

`lein find opleidingseenhedenVanOrganisatie $ONDERWIJSBESTUURSCODE $PAGINA`

#### onderwijsaanbiedersVanOrganisatie

This action retrieves the "onderwijsaanbieders" for a "onderwijsbestuur" specified by a "onderwijsbestuurcode".

Example:

`lein find aangebodenOpleidingenVanOrganisatie $ONDERWIJSAANBIEDERCODE $PAGINA`

### rm

#### opleidingseenheid

Removes an "opleidingseenheid", specified by the RIO opleidingseenheidcode.

Example:

`lein rm opleidingseenheid --opleidingseenheidcode 1009O2057`

#### aangebodenOpleiding

Removes an "aangeboden opleiding", specified by  based on its course id or program id.

Example:

`lein rm aangebodenOpleiding --aangebodenOpleidingCode 9d30186a-abd1-d720-a4fa-efba1e5de793`
