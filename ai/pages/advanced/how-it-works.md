# How Elci compiles

> The path from .elm source to a Plutus V3 script — Elci's frontend stages, the boundary with Julc's backend, strict data boundaries and the native executable.

Source: https://elci-lang.dev/advanced/how-it-works/

This page follows your source through the compiler, from `.elm` text to the bytes in
`validator.plutus.json`. Knowing the stages helps you read error messages, predict costs and understand
what Elci guarantees and what it does not.

## The pipeline

Elci is a **frontend** over Julc's backend. It does not translate your code to Java, and it does not
have its own UPLC optimizer. It turns source into typed Plutus IR (PIR), and Julc does the rest:

```text
  .elm sources + elci.toml
          |
          v
  lexer and layout-aware parser        ELCI1xxx
          |
          v
  module and name resolution            ELCI21xx
          |
          v
  type inference and checks             ELCI20xx
          |
          v
  specialization and PIR lowering       ELCI3xxx
          |
  ======= | ======= Julc backend boundary ==========
          v
  validator wrapper, PIR verifier       ELCI4002, ELCI4003
          |
          v
  UPLC optimization and FLAT encoding
          |
          v
  build/validators/<name>/validator.plutus.json, abi.json, ...
```

The diagnostic codes on the right show where each class of error comes from; see
[diagnostics](https://elci-lang.dev/reference/diagnostics/).

## Frontend stages

### Lexing and parsing

The lexer turns text into tokens and records the line and column of each one. It rejects what Elci does
not support at the character level: tabs, `Float` literals such as `1.5`, character literals and
unknown escapes.

The parser is a hand-written, layout-aware recursive-descent parser. Like Elm, it uses indentation:
top-level declarations start in column 1, `let` declarations align with each other, and `case` branches
align with each other. Operators are parsed by precedence, as listed in the
[language reference](https://elci-lang.dev/reference/language/). Features outside Elci's profile, such as extensible records
or refutable patterns in function arguments, fail here with a message that names the feature.

### Modules and names

Module resolution builds the module graph. Each module `A.B` must live at `A/B.elm` under one source
directory, every module has one owner, and import cycles are rejected. The core modules (`Basics`,
`List`, `Maybe`, `Result`, `Tuple`, `String`, `Map`, `Dict`, `Array`, `Value`, `Interval` and `Tx`) are
ordinary Elci source, bundled with the compiler and compiled with your code. Every project and test module gets Elm's default imports of
`Basics`, `List`, `Maybe`, `Result`, `String`, `Tuple` and `Debug`; the core modules themselves import
what they need explicitly, as in Elm. `Bytes`, `Debug` and `Julc.Data` are compiler intrinsics, as are
`not`, `modBy` and `remainderBy`, which `Basics` exposes. Modules listed in `[java-modules]` are resolved
to Julc library classes.

Every name is then qualified with the module that owns it. A name is visible only if its module exposes
it and your module imports it. A module's own declarations shadow its imports, and two imports never
silently shadow each other: a name both expose is an error where it is used.

### Type inference and checks

Elci infers types with Hindley-Milner inference restricted to rank-1 types, then checks them against your
signatures. Every top-level declaration needs a signature. The checker also:

- groups recursive definitions and rejects eager value cycles (recursion must go through functions);
- keeps recursive calls monomorphic, so polymorphic recursion is rejected;
- allows limited local polymorphism: simple local functions and values may be used at several types;
- checks that `==`, ordering and `++` are used only at types that support them, and that a signature
  names such requirements with Elm's `comparable` and `appendable` type variables;
- accepts type variables that nothing determines, as Elm does: no value of such a type can exist, so any
  representation serves;
- proves every `case` exhaustive and every branch reachable;
- requires validator handlers and entrypoints to have concrete types that match their configuration.

### Specialization and lowering

Plutus has no runtime type information, so generic code cannot run as-is. The lowering stage
**specializes** every generic function reachable from your handlers: `List.map` used at `Int` and at
`Bool` becomes two separate functions. Each concrete use of a generic Java method is compiled by Julc in
the same way. A compiler limit of 256 specializations stops runaway growth.

Lowering then produces typed PIR while keeping Elci's evaluation rules:

- **Strict calls.** Arguments and captured variables are evaluated once, before use, from left to right.
  `let` values are evaluated in dependency order, with source order breaking ties, and an unused `let`
  value is still evaluated.
- **Selective branches.** `if`, `case`, `&&` and `||` evaluate only the branch they take.
- **Explicit representations.** Lists are Julc's native `List<Data>`, with each element encoded and
  decoded explicitly. Records, tuples and custom types become Plutus Data constructors.
- **Ordering.** `Int` and byte strings compare with the Plutus builtins, and `String` compares its UTF-8
  bytes. Lists and tuples compare with a function generated once per concrete type, like any other
  specialization.

Nothing is erased to an untyped `Data` value behind your back: a type Elci cannot represent faithfully is
a compile error.

## The Julc backend boundary

Elci hands Julc one of two kinds of program.

**Function programs.** An `[entrypoint]` function, run by `elci eval`, and each `elci test` program is a
closed, fully specialized function. Library exports are inlined at each use. Julc checks that the term is
closed and valid for the target, then compiles it without a validator wrapper.

**Validator programs.** Each `[validators.<name>]` table becomes one validator program with:

- **imports:** every library function the handlers need, linked once per script;
- **definitions:** the specialized top-level definitions reachable from any handler, with their PIR types;
- **handlers:** one reference per configured purpose;
- **parameters:** the deployment parameters, in the order listed in `parameters`;
- **named types:** your records and custom types.

Before compiling, Elci checks that the backend supports every capability the validator needs
(`ELCI4003` otherwise). Julc then runs a structural **PIR verifier** over Elci's definitions and handlers,
checking bindings, types and data-constructor agreement. A rejection is reported at the Elci declaration
it names (`ELCI4002`). If you ever see one, it is a compiler bug worth reporting.

Julc wraps the program in its shared multi-validator wrapper. The deployed script:

1. takes the deployment parameters, then **one** argument: the ledger's script context;
2. dispatches on the script purpose, rejecting purposes without a handler;
3. decodes the selected handler's datum and redeemer strictly;
4. calls the handler and turns `True` into success and `False` into a script failure.

Elci never builds its own dispatcher. Its one addition is a small adapter for `spend-datum = "optional"`:
Julc's optional datum and Elci's `Maybe` use different constructor tags, so the adapter re-tags the value.

Finally, Julc's UPLC pipeline optimizes the program and encodes it as FLAT bytes, and the CLI writes the
artifacts.

### Library imports

Java on-chain libraries reach Elci through a **provider**. The provider parses each library's Java
source (bundled with the compiler, or read from your `library-jars`), compiles the exports you use to
closed PIR with their transitive dependencies, and describes their types. Generic methods are described
as schemes and compiled once per concrete type. Elci never calls library code on the JVM. See
[Java libraries](https://elci-lang.dev/advanced/java-libraries/).

## Strict data boundaries

Everything that enters a validator from outside is `Data`, and Elci decodes it **strictly** before your
code runs. The wrapper checks constructor tags, field counts and nested field kinds of the datum and
redeemer, even the parts your handler ignores. Malformed data fails the transaction; it never becomes a
`Nothing` or a default value. Only the selected handler's datum and redeemer are decoded, so each
handler may use its own redeemer type.

Deployment parameters are decoded once when they are applied to the script but, as with Julc's Java
validators, not validated on-chain. `elci apply` therefore checks each parameter off-chain with the same
strict decoder before applying it. See [parameterized validators](https://elci-lang.dev/contracts/parameters/).

The encoding is deterministic:

| Elci value | Plutus Data |
| --- | --- |
| `Int` | integer |
| `Bytes`, ledger hashes | byte string |
| `String` | byte string holding its UTF-8 encoding |
| `Bool` | `False` is constructor 0, `True` is constructor 1, no fields |
| `()` | constructor 0, no fields |
| record | constructor 0, fields sorted by **name** |
| tuple | constructor 0, fields in position order |
| custom type | constructor tag = declaration order of the variant, fields in order |
| `Maybe a` | `Nothing` is constructor 0, `Just x` is constructor 1 with one field |
| `Result e a` | `Ok x` is constructor 0, `Err e` is constructor 1, each with one field |
| `List a` | list |
| `Map k v` | map |

Each build writes these choices to `abi.json`. Pin that file with `expected-abi` and the build fails if a
change to your types would change the on-chain format. The ledger's own context is decoded by Julc's
existing ledger-context convention, not by a new general decoder.

## What the verifier does and does not prove

Elci owns source typing, name hygiene, specialization and data representation. Julc's verifier checks
that the PIR it receives is well-formed and well-typed; it is not a proof that your contract is correct.
Your tests are. Compilation also has resource guards, such as the specialization limit, that are
separate from Cardano's execution budget.

## The native executable

The `elci` command is built twice from the same code: as a JVM application and as a GraalVM native
executable. The native executable:

- bundles the Elci core modules, Julc's standard-library sources and the JavaParser metadata needed to
  compile Java library source, so it needs no JDK at run time;
- reads your library JARs as source files, exactly like the JVM build;
- produces byte-identical artifacts. Building the `vesting` example with both gave the same file hashes;
- starts fast: that build took about 0.05 seconds natively and about 0.5 seconds on the JVM.

The compiler keeps no global mutable state between compilations, and builds are deterministic: building
twice gives the same bytes.

## What Elci does not do

- It does not run your code on the JVM to compute on-chain results.
- It does not generate Java, and it has no second UPLC pipeline.
- It does not fall back to untyped `Data` when a type is unsupported; it reports an error.
- It does not produce CIP-57 blueprints yet; the ABI manifest is Elci's own format.

To see how these stages show up in practice, read [costs and performance](https://elci-lang.dev/advanced/costs/) and the
[language reference](https://elci-lang.dev/reference/language/).
