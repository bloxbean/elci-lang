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

## Generic methods and programmatic exports

A generic `@OnchainLibrary` method is a template. Julc describes it as a
**scheme** (type parameters, constraints and source signature) and compiles it
only for concrete type arguments. Elci imports the scheme as an ordinary generic
function and instantiates it freshly at every use:

```java
@OnchainLibrary
public class Pick {
    public static <T> T firstOr(JulcList<T> items, T fallback) {
        if (items.isEmpty()) return fallback;
        return items.head();
    }
}
```

```elm
import Demo.Pick as Pick

first : Int
first =
    Pick.firstOr [ 5, 6 ] 0          -- specialized at Int

name : String
name =
    Pick.firstOr [] "none"           -- a separate specialization at String
```

How instantiation works:

- **Type arguments:** each concrete use supplies the type arguments, in the
  scheme's order.
- **Constraints:** every type parameter currently carries `DATA_ENCODABLE`. A
  type argument must have a Plutus Data encoding: an integer, bytes, string,
  Bool, Data, a nominal or Elci record or union, or a list, map or Julc optional
  of such types. Functions and unit are rejected with `ELCI3003` before anything
  is compiled.
- **Unsupported templates:** templates Julc cannot describe are reported with
  `ELCI2105` and the provider's reason. Examples are bounded type parameters
  (`<T extends Comparable<T>>`), wildcards, raw containers and calls between
  templates.
- **Evaluation:** calls are strict and curried in declared argument order. Each
  argument is evaluated once, even under partial application.
- **Elci types:** Java-source templates cannot yet be specialized at Elci's own
  records and unions, because Julc's Java provider describes only Java and ledger
  types. Programmatic exports accept them.

Julc also provides **programmatic** exports: implementations built directly in
PIR rather than from Java source. `org.julclang.stdlib.lib.ListsLib` offers
`map`, `filter`, `any`, `all`, `find` and `foldl` this way, next to its Java-source
exports:

```toml
[java-modules]
"Julc.Lists" = "org.julclang.stdlib.lib.ListsLib"
```

```elm
import Julc.Lists as Lists

openTotal : List Order -> Int
openTotal orders =
    Lists.filter orders (\order -> order.open)
        |> Lists.foldl (\sum order -> sum + order.amount) 0
```

The list is the first argument, as in the Java library. Unlike Java inlining, the
function argument is evaluated once, strictly, even for an empty list. These
exports accept Elci records and unions as type arguments. The bundled Elci `List`
module remains available and needs no configuration.

Julc composes the Java-source provider, the programmatic provider and Elci's own
type descriptions with deterministic ownership. A symbol exported by two
providers is rejected rather than ordered. Named validators link each
specialization once per script. `[entrypoint]` projects inline a closed copy at
each use, keeping their existing bytes for concrete exports.

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
for types in different Java packages.

Julc lists the operations it has verified for each imported type, and Elci
enables exactly those:

| Operation | Elci surface |
| --- | --- |
| CONSTRUCT | `Pricing.Quote 5 True`; sum constructors such as `Pricing.Buy 5`, qualified or exposed with `Action(..)` |
| MATCH | Exhaustive `case` over a sum's constructors |
| PROJECT | `quote.amount` |
| EQUALS | `==` / `/=`, with Java `equals` semantics |
| ENCODE, DECODE_STRICT | `Julc.Data.encode` / `Julc.Data.decode` |
| BOUNDARY | Datum, redeemer or parameter type of a named validator |

```elm
import Custom.Pricing as Pricing exposing (Action(..))

amount : Action -> Int
amount action =
    case action of
        Buy value ->
            value

        Cancel ->
            0
```

Record constructors take fields in the Java record's declaration order, which is
also its encoding order. Julc approves operations only for representations it
has verified. Special layouts, such as records with custom codecs or the ledger
`Value`, stay opaque, and so do types that contain them. Java factory functions
remain available for such types.

Concrete lists, maps and Java Optional retain their own representations. Java
Optional is not implicitly interchangeable with Elci Maybe; convert it with
`Julc.Data.fromOptional` (see the [language guide](LANGUAGE.md#explicit-data-conversions)). Raw containers and
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
