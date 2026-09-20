# Elci

**An Elm-syntax functional language for Cardano, powered by Julc.**
Pronounced “el-see”.

Elci compiles `.elm` source directly to typed PIR, then uses Julc's validator
wrappers, UPLC pipeline and evaluator. The compiler is a Java 25 Gradle project
with a JVM CLI and a GraalVM native executable.

This is an experimental compiler implementation. It is not browser Elm, does not
consume Elm packages, and is not a production-ready contract toolchain. See the
[feature matrix](docs/LANGUAGE.md#implemented-profile) before writing a contract.

```elm
module Example.Threshold exposing (validate)

import Julc.Ledger exposing (ScriptContext)
import Julc.Math as Math


validate : Int -> Int -> ScriptContext -> Bool
validate minimum offered _ =
    offered >= Math.max minimum 0
```

`Math.max` is compiled from the actual bundled Julc Java on-chain library source.
The source's three logical arguments become **one Plutus V3 ledger context
argument**, with strict datum/redeemer decoding and Bool-to-success/failure wrapping.

```sh
# After publishing the pinned local Julc dependencies; see Getting Started.
./gradlew test installDist
build/install/elci/bin/elci build examples/threshold

# Set GRAALVM_HOME to a GraalVM installation containing native-image.
./gradlew nativeCompile
build/native/elci build examples/threshold
build/native/elci eval examples/functional
```

- [Getting started](docs/GETTING_STARTED.md): build, first project, JVM/native CLI.
- [Standard library guide](docs/STDLIB.md): verified Java exports, limitations and writing Elci libraries.
- [Language guide](docs/LANGUAGE.md): syntax, semantics, types, libraries and limits.
- [Compiler architecture](docs/ARCHITECTURE.md): frontend/backend API and invariants.
- [Testing and review](docs/VALIDATION.md): reproducible checks and evidence.
- [Diagnostic catalogue](docs/DIAGNOSTICS.md): stable compiler error categories.
- [Implementation log](docs/IMPLEMENTATION.md): decisions and milestone status.

Examples are semantic fixtures. `threshold` demonstrates compilation, `signed`
demonstrates nominal ledger hashes and transaction fields, `functional`
demonstrates custom types and list traversal, and `core-completion` demonstrates
nested patterns, generic aliases, destructuring and structural equality.
`vesting` demonstrates a typed datum and redeemer, transaction validity ranges,
and beneficiary authorization. They are not complete application contracts.
