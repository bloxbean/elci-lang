# Importing on-chain Java libraries

For the checked stdlib export inventory and libraries written in Elci, see the
[standard library guide](STDLIB.md).

Elci imports public static methods from Julc `@OnchainLibrary` source. Julc owns
Java parsing, source type descriptions, representation planning and compilation.
Elci consumes the descriptions to type-check calls and uses the materialized PIR.
No Elci code needs to recognize a library method name.

## Bundled libraries

Map an Elm-style module name to its Java library class:

```toml
[java-modules]
"Julc.Interval" = "org.julclang.stdlib.lib.IntervalLib"
```

```elm
import Julc.Interval as Interval
```

Bundled sources are read from `META-INF/plutus-sources/` in the compiler's
classpath. This also works in the native executable.

## Custom library JARs

Add paths relative to the project directory:

```toml
[project]
# Keep the usual source-directories, target and julc-version settings.
library-jars = ["libs/pricing.jar", "libs/shared-types.jar"]

[java-modules]
"Custom.Pricing" = "example.Pricing"
```

Each JAR must contain Java source resources, for example:

```text
META-INF/plutus-sources/example/Pricing.java
META-INF/plutus-sources/example/Quote.java
```

Bundle all required custom library and data-type sources, including dependencies
in other JARs. Files are read in deterministic order. A compiled `.class` file
alone is insufficient. Duplicate source ownership, collisions with bundled source,
missing JARs and JARs with no bundled on-chain source are errors. These JARs are
source inputs; their classes, static initializers and service providers are not
loaded or executed. This mechanism is also available to the native CLI.

See [the runnable example](../examples/custom-library/README.md) for packaging
commands and an Elci call into a custom library.

## Types and supported operations

The boundary preserves fully qualified source identities alongside concrete PIR
representations. It describes records, sealed sums, newtypes, constructor tags,
field order and nested supported containers. Recursive definitions use named
references. Two newtypes remain distinct even when both compile to integers or
bytes; an imported record is not an Elci structural record.

Types nested directly in a library class, and top-level types in its package,
can be named through the library alias or explicitly exposed:

```elm
import Custom.Pricing as Pricing exposing (Quote)

amount : Quote -> Int
amount quote =
    quote.amount
```

Same-name type exports are rejected as ambiguous. Use separate module mappings
for types in different Java packages. Create values through exported Java factory
functions. Supported record and newtype fields can be read; special or opaque
layouts do not acquire record projection merely because Java declares a record.
Sealed sums can be passed to and returned from library functions; direct Elci
construction and pattern matching of imported sums are not enabled by this change.
Imported types do not automatically gain structural equality or datum/redeemer
serialization.

Concrete lists, maps and Java Optional retain their own representations. Java
Optional is not implicitly interchangeable with Elci Maybe. Raw containers and
wildcards have no implicit Data fallback. Unsupported public signatures are
reported when referenced. The provider checks that the source signature's
representation matches the compiled PIR signature.

This is a generic description mechanism for the supported on-chain Java subset,
not support for arbitrary JVM libraries. Polymorphic Java method specialization,
parameterized custom Java records, arbitrary object/function types and programmatic
PIR export catalogues remain separate work under ADR-003. Their absence is not
worked around by pretending their signatures are concrete Data.

## Development dependency

This milestone was introduced against Julc's library-description boundary
(`d6e716e2`, ADR-050), which is now merged. Elci currently pins the Julc revision
in `gradle.properties` (see [Getting started](GETTING_STARTED.md)); publish that
exact version to Maven local before building Elci. Source JARs are compiled using
the target and compiler selected by Elci.

Validation for this milestone: 81 Elci tests and 2,276 Julc compiler, stdlib,
testkit and annotation-processor tests passed. The GraalVM native executable
successfully evaluated the custom JAR example and compiled the vesting and
transitive ContextsLib fixtures. Vesting remains 457 FLAT bytes.
