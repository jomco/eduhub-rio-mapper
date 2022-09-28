# SURFeduhub RIO mapper Command Line Interface

## Prerequisites

There must be a `keystore.jks` file in the root of the project,
containing the certificates needed to access the RIO API. See
`dev/create-keystore.sh` on how to create a keystore, given the
private key and the certificate. The app currently expects an alias
called `test-surf` and a password `xxxxxx`. This will become
configurable at a later stage.

Leiningen should be installed to call the CLI; https://leiningen.org/

## Commands

### upsert

Updates or inserts an "opleidingseenheid" or "aangeboden opleiding,
specified by the OOAPI endpoint, type and ID.  An example:

```sh
lein mapper upsert uni.nl course 123e4567-e89b-12d3-a456-426655440000
```

### delete

Removes an "opleidingseenheid" or "aangeboden opleiding, specified by
the OOAPI endpoint, type and ID.  An example:

```sh
lein mapper delete uni.nl course 123e4567-e89b-12d3-a456-426655440000
```

### get

The `get` command retrieves data from RIO. The following actions are
supported:

#### aangebodenOpleiding

This action retrieves the "aangeboden opleiding" based on its course
ID or program ID.

Example:

```sh
lein mapper get aangebodenOpleiding 123e4567-e89b-12d3-a456-426655440000
```

#### opleidingseenhedenVanOrganisatie

This action retrieves a page of "opleidingseenheden" for a specific
"onderwijsbestuur". Pages start counting at zero, not one. There is no
way to retrieve a single "opleidingseenheid" based on its ID.

Example:

```sh
lein mapper get opleidingseenhedenVanOrganisatie 110A133
```

An optional page argument can be passed.

#### onderwijsaanbiedersVanOrganisatie

This action retrieves the "onderwijsaanbieders" for a
"onderwijsbestuur" specified by a "onderwijsbestuurcode".  An example:

```sh
lein mapper get aangebodenOpleidingenVanOrganisatie 110A133
```

An optional page argument can be passed.

### resolve

This action retrieves the `opleidingeenheidscode` based on the key that OOAPI uses as primary key for the education specification.

Example:

```sh
lein mapper resolve 123e4567-e89b-12d3-a456-426655440000
```
