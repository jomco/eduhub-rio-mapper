# SURFeduhub RIO mapper Command Line Interface

## Prerequisites - keystores

There must be a Java keystore available containing the client
certificate needed to access the RIO API.  See
`dev/create-keystore.sh` on how to create a keystore, given the
private key and the certificate. 

The application also depends on a JAVA truststore for validating the
certificate of the RIO API itself.  A truststore.jks is provided at
the root of this project. See `dev/create-truststore.sh` on how
to create a truststore yourself.

## Running commands

Commands can be run in development mode, from leiningen, or from the compiled uberjar.

In development mode, commands can be executed directly from the source repository using `leiningen`.  Leiningen should be installed; https://leiningen.org/

Mapper commands in leiningen take the form:

```sh
lein mapper COMMAND [ARGS]
```

For instance

```sh
lein mapper upsert uni-nl course 123e4567-e89b-12d3-a456-426655440000
```


In production mode, the commands can be run as arguments to the
compiled jar:

```sh
java -jar target/eduhub-rio-mapper.jar COMMAND [ARGS]
````

For instance

```sh
java -jar target/edhuhub-rio-mapper.jar upsert uni-id course 123e4567-e89b-12d3-a456-426655440000
```

## Commands

### upsert

Updates or inserts an "opleidingseenheid" or "aangeboden opleiding,
specified by the OOAPI endpoint, type and ID.  An example:

```sh
lein mapper upsert uni-id course 123e4567-e89b-12d3-a456-426655440000
```

### delete

Removes an "opleidingseenheid" or "aangeboden opleiding, specified by
the OOAPI endpoint, type and ID.  An example:

```sh
lein mapper delete uni-id course 123e4567-e89b-12d3-a456-426655440000
```

### delete-by-code

Removes an "opleidingseenheid", specified by the OOAPI endpoint, type and RIO opleidingscode.  An example:

```sh
lein mapper delete-by-code uni-id education-specification 1234O1234
```

### show

The `show` command retrieves data from OOAPI. The following entities are supported:

#### course

Example:

```sh
lein mapper show uni-id course 123e4567-e89b-12d3-a456-426655440000
```

#### program

Example:

```sh
lein mapper show uni-id program 123e4567-e89b-12d3-a456-426655440000
```

#### education-specification

Example:

```sh
lein mapper show uni-id education-specification 123e4567-e89b-12d3-a456-426655440000
```

### get

The `get` command retrieves data from RIO. The following actions are
supported:

#### aangebodenOpleiding

This action retrieves the "aangeboden opleiding" based on its course
ID or program ID.

Example:

```sh
lein mapper get uni-id aangebodenOpleiding 123e4567-e89b-12d3-a456-426655440000
```

#### opleidingseenhedenVanOrganisatie

This action retrieves a page of "opleidingseenheden" for a specific
"onderwijsbestuur". Pages start counting at zero, not one. There is no
way to retrieve a single "opleidingseenheid" based on its ID.

Example:

```sh
lein mapper get uni-id opleidingseenhedenVanOrganisatie 110A133
```

An optional page argument can be passed.

#### aangebodenOpleidingenVanOrganisatie

This action retrieves a page of "aangeboden opleidingen" for a 
specific organization, specified by a "onderwijsaanbiedercode".

```sh
lein mapper get uni-id aangebodenOpleidingenVanOrganisatie 110A133
```

An optional page argument can be passed.

### resolve

This action retrieves the `opleidingeenheidscode` based on type and
the key that OOAPI uses as primary key for the education
specification.  Type is one of: `course`, `program` and `education-specification`.

Example:

```sh
lein mapper resolve uni-id course 123e4567-e89b-12d3-a456-426655440000
```

### dry-run-upsert

This action simulates an upsert of an ooapi object and returns a diff of the fields that such an upsert would change in RIO.

Example:

```sh
lein mapper dry-run-upsert uni-id course 4c358c84-dfc3-4a30-874e-0b70db15638a
```

### link

This action changes the ooapi id of an object in rio specified by a given rio id and a type.

Example:

```sh
lein mapper link uni-id rio-id course ooapi-id
```

### serve-api

This action starts the API HTTP server at the configured port and
hostname (default localhost, port 80).

### worker

This action starts a worker.
