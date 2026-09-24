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
build/install/elci/bin/elci init my-contract --purpose spend
build/install/elci/bin/elci build examples/threshold

# Set GRAALVM_HOME to a GraalVM installation containing native-image.
./gradlew nativeCompile
build/native/elci build examples/threshold
build/native/elci eval examples/functional
```

Validator builds include `build/validator.plutus.json` in Julc’s format for Cardano
Client Lib, plus `abi.json` for Elci schema and ABI drift checks. Named validators
(`[validators.<name>]`) combine spend, mint, withdraw, certify, vote and propose
handlers in one script, with deployment parameters and datum profiles. They
publish per-group artifacts under `build/validators/<name>/`. CIP-57 blueprint
generation is not implemented yet.

- [Release workflow](docs/RELEASING.md): versioning, native binaries and GitHub Releases.
- [Getting started](docs/GETTING_STARTED.md): build, first project, JVM/native CLI.
- [Standard library guide](docs/STDLIB.md): verified Java exports, limitations and writing Elci libraries.
- [Language guide](docs/LANGUAGE.md): syntax, semantics, types, libraries and limits.
- [Compiler architecture](docs/ARCHITECTURE.md): frontend/backend API and invariants.
- [Testing and review](docs/VALIDATION.md): reproducible checks and evidence.
- [Diagnostic catalogue](docs/DIAGNOSTICS.md): stable compiler error categories.
- [Implementation log](docs/IMPLEMENTATION.md): decisions and milestone status.
- [Remaining integration roadmap](adr/005-remaining-frontend-and-backend-integration.md): planned Elci work and shared backend dependencies.

Examples are semantic fixtures. `threshold` demonstrates compilation, `signed`
demonstrates nominal ledger hashes and transaction fields, `functional`
demonstrates custom types and list traversal, and `core-completion` demonstrates
nested patterns, generic aliases, destructuring and structural equality.
`vesting` demonstrates a typed datum and redeemer, transaction validity ranges,
and beneficiary authorization. `treasury` demonstrates named validators: one
parameterized script with spending and withdrawal handlers plus a separate
minting policy. They are not complete application contracts.
