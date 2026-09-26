---
title: elci.toml
description: Every table and key of elci.toml — project settings, validators, entrypoints, Java modules and tests — with the rules the parser enforces and complete examples.
sidebar:
  order: 2
---

Every Elci project has an `elci.toml` file at its root. This page lists every table and key, the values
each one accepts and the rules the compiler enforces. Unknown tables and keys are errors, so a typo
never goes unnoticed.

## Format

`elci.toml` uses a small, strict subset of TOML:

- A table header is a line like `[project]` or `[validators.treasury]`. Each table may appear once.
- Each setting is `key = value` on **one line**. A key may be quoted, as in `"Julc.Math" = "..."`.
- A value is either a quoted string, such as `"src"`, or an array of quoted strings on the same line,
  such as `["src", "lib"]`.
- Strings cannot contain escapes (`\`) or embedded quotes.
- Comments are whole lines starting with `#`. A comment after a value is an error.
- A key may appear only once per table.

## Project kinds

A project configures **either** one or more `[validators.<name>]` tables **or** one `[entrypoint]`
function, never both:

- **Validator projects** produce deployable scripts. Use `elci check`, `build`, `test` and `apply`.
- **Function projects** have an `[entrypoint]` for `elci eval`, which runs a function and prints its
  value and cost. They are for experiments and helper code, not for deployment.

## `[project]`

Required in every project.

| Key | Required | Value |
| --- | --- | --- |
| `source-directories` | yes | Array of directories holding source modules, relative to the project. At least one; each must exist. |
| `target` | yes | Must be `"plutus-v3-pv11-uplc-1.1.0"`, the only supported target. |
| `julc-version` | yes | Must equal the Julc version the compiler is pinned to, currently `"0.1.0-gb-5a3378b"`. `elci --version` prints it. |
| `library-jars` | no | Array of JAR paths, relative to the project, that contain on-chain Java library sources. See [Java libraries](/advanced/java-libraries/). |
| `expected-abi` | no | Path of a committed `abi.json` to compare with the build. **Function projects only**; validators set it in their own table. |

A module `A.B` must live at `A/B.elm` inside exactly one source directory. Two files defining the same
module are an error.

## `[validators.<name>]`

One table per deployed script. The name becomes the output directory `build/validators/<name>/` and the
name you use in `elci apply --validator`, in tests and in `--validator` options. It must be 1 to 64
characters: lowercase letters, digits, `-` or `_`, starting with a letter.

| Key | Required | Value |
| --- | --- | --- |
| `boundary` | yes | Must be `"julc-strict-v1"`, the strict data boundary. |
| `spend` | at least one handler | Handler for spending, as `"Module.function"`. |
| `mint` | | Handler for minting. |
| `withdraw` | | Handler for reward withdrawals. |
| `certify` | | Handler for certificates. |
| `vote` | | Handler for governance votes. |
| `propose` | | Handler for governance proposals. |
| `parameters` | no | Array of deployment parameter names, in application order. |
| `spend-datum` | no | `"required"` (default), `"optional"` or `"absent"`. Needs a `spend` handler. |
| `expected-abi` | no | Path of a committed `abi.json`; `check`, `build` and `apply` fail if the new ABI differs. |

Rules:

- **Handlers** name an exposed function as `"Module.function"`, for example `"Treasury.spend"`. Each
  handler key adds a purpose to the **same** script, so one table is always one script hash.
- **Parameter names** start with a lowercase letter, followed by letters, digits or `_`, and must be
  unique. Every handler receives all parameters as its leading arguments, in this order, so their types
  must agree across handlers.
- **Handler signatures** depend on the purpose and `spend-datum`:

| Handler | Required type |
| --- | --- |
| `spend` | `parameters -> datum -> redeemer -> ScriptContext -> Bool` |
| `spend` with `spend-datum = "optional"` | `parameters -> Maybe datum -> redeemer -> ScriptContext -> Bool` |
| `spend` with `spend-datum = "absent"` | `parameters -> redeemer -> ScriptContext -> Bool` |
| `mint`, `withdraw`, `certify`, `vote`, `propose` | `parameters -> redeemer -> ScriptContext -> Bool` |

Adding, removing or changing a handler changes the script and its hash. See
[validators and purposes](/contracts/validators/) and [datums and redeemers](/contracts/datums-and-redeemers/).

## `[entrypoint]`

The single function of a function project.

| Key | Required | Value |
| --- | --- | --- |
| `module` | yes | The module name, such as `"Main"`. |
| `function` | yes | An exposed top-level function or value with a concrete type, such as `"main"`. |
| `purpose` | no | Only `"function"`, the default. |
| `boundary` | no | Only `"julc-strict-v1"`. |

Validator purposes in `[entrypoint]` were removed. `purpose = "spend"` fails with a message telling you to
move the handler into a `[validators.<name>]` table.

## `[java-modules]`

Maps Elm-style module names to on-chain Java library classes:

```toml
[java-modules]
"Julc.Math" = "org.julclang.stdlib.lib.MathLib"
"Acme.Pricing" = "acme.Pricing"
```

The key is the module name you import; the value is the fully qualified class name. A mapping is only
read when a module imports it. A module name cannot be both a source module and a Java module.
`Julc.TypedMap` and `Julc.TypedArray` are reserved by the core `Map` and `Array` modules and cannot be
remapped. The core module names `Basics`, `List`, `Maybe`, `Result`, `String`, `Tuple`, `Map`, `Dict` and
`Array`, the reserved names `Set`, `Value` and `Elci`, and names starting with `Elci.` cannot be keys. See
[standard library](/advanced/standard-library/) for the bundled classes.

## `[tests]`

| Key | Required | Value |
| --- | --- | --- |
| `source-directories` | no | Array of directories holding test modules. Default `["tests"]`. |

A missing default `tests` directory simply means there are no tests. Test modules are never part of
`check`, `build` or the deployed scripts. See [unit tests](/testing/unit-tests/).

## Complete examples

### A validator project

This project uses every validator setting: two scripts, a shared parameter, an optional datum, a pinned
ABI, a Java library JAR, a second source directory and a custom test directory.

```toml title="elci.toml"
# Settings for the whole project.
[project]
source-directories = ["src", "lib"]
target = "plutus-v3-pv11-uplc-1.1.0"
julc-version = "0.1.0-gb-5a3378b"
library-jars = ["libs/pricing.jar"]

# One script with two purposes and a deployment parameter.
[validators.treasury]
boundary = "julc-strict-v1"
parameters = ["admin"]
spend = "Treasury.spend"
withdraw = "Treasury.withdraw"
spend-datum = "optional"
expected-abi = "abi/treasury.json"

# A second, independent script.
[validators.badge]
boundary = "julc-strict-v1"
parameters = ["admin"]
mint = "Badge.mint"

[java-modules]
"Julc.Math" = "org.julclang.stdlib.lib.MathLib"
"Acme.Pricing" = "acme.Pricing"

[tests]
source-directories = ["spec"]
```

```text
$ elci check
validator badge (mint): checked; 229 FLAT bytes; plutus-v3-pv11-uplc-1.1.0
validator treasury (spend, withdraw): checked; 603 FLAT bytes; plutus-v3-pv11-uplc-1.1.0
```

With `spend-datum = "optional"`, the spend handler's datum is a `Maybe`:

```elm title="src/Treasury.elm"
spend : PubKeyHash -> Maybe Int -> () -> ScriptContext -> Bool
spend admin datum _ ctx =
    case datum of
        Just limit ->
            ctx.txInfo.fee <= Math.max limit (Pricing.fee limit 100)

        Nothing ->
            signedBy admin ctx
```

### A function project

This is what `elci init my-project --purpose function` creates:

```toml title="elci.toml"
[project]
source-directories = ["src"]
target = "plutus-v3-pv11-uplc-1.1.0"
julc-version = "0.1.0-gb-5a3378b"

[entrypoint]
module = "Main"
function = "main"
purpose = "function"
```

## Common errors

| Mistake | Message |
| --- | --- |
| Wrong `julc-version` | `Julc version does not match this compiler; expected julc-version = "0.1.0-gb-5a3378b"` |
| Wrong `target` | `Unsupported target; expected plutus-v3-pv11-uplc-1.1.0` |
| Misspelled key | `Unknown configuration key: [project] name` |
| Unknown table | `elci.toml:6: unknown configuration section: dependencies` |
| Missing `boundary` | `Missing configuration key: [validators.main] boundary` |
| Bad validator name | `elci.toml:6: invalid validator name 'Main'; use lowercase letters, digits, '-' or '_', starting with a letter (at most 64 characters)` |
| No handler | `elci.toml:6: [validators.main] configures no handler; add one of mint, spend, withdraw, certify, vote, propose` |
| Handler without module | `elci.toml:9: [validators.main] spend = "spend" must name an exposed function as "Module.function"` |
| `spend-datum` without `spend` | `elci.toml:6: [validators.main] spend-datum requires a spend handler` |
| Both project kinds | `ELCI4001: elci.toml mixes [entrypoint] with [validators.*]; use one form` |
| Neither project kind | `ELCI4001: elci.toml must configure at least one [validators.<name>], or an [entrypoint] function` |
| `expected-abi` in `[project]` of a validator project | `ELCI4001: [project] expected-abi applies to [entrypoint] projects; set expected-abi in each [validators.<name>] table` |
| Unquoted value | `Expected a quoted string without escapes: julc-strict-v1` |
| Comment after a value | `Expected a quoted string without escapes: "0.1.0-gb-5a3378b" # pinned` |
| Multi-line array | `elci.toml:3: expected key = value` |
| Missing source directory | `Source directory not found: my-project/lib` |
| Remapping a core library | `ELCI4001: [java-modules] may not remap Julc.TypedMap, which the Elci core library uses for org.julclang.stdlib.lib.TypedMapLib` |
| Mapping a core module name | `ELCI4001: [java-modules] may not map Value, a module name of Elci's core library` |
| ABI changed | `ELCI4001: my-project/elci.toml:9: [validators.main]: ABI drift from abi/main.json; compare constructor tags, field order, handler roles and parameters` |

Each message is printed after `elci: ` and the command exits with status 1.
