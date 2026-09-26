---
title: CLI
description: Every elci command and option — init, check, build, eval, test and apply — with outputs, exit codes and error formats.
sidebar:
  order: 1
---

This page documents every `elci` command and option, what each one prints and writes, and how it
reports errors. The examples use the native executable; the JVM launcher accepts exactly the same
arguments.

## Synopsis

```text
$ elci --help
Elci 0.1.0-dev — Elm-syntax compiler powered by Julc
Usage: elci <check|build> [project-directory] [--validator <name>]...
       elci eval [project-directory] [--builtins]
       elci test [project-directory] [--match <substring>] [--seed <n>] [--fuzz <runs>]
       elci apply [project-directory] --validator <name> --param <name>=<plutus-data-json|@file> [--output <file>]
       elci init <project-directory> [--validator <name>] [--purpose spend|mint|withdraw|certify|vote|propose|function]
       elci --version
A build writes build/validators/<name>/validator.flat, validator.plutus, validator.plutus.json (Julc format) and abi.json (Elci schema)
for each [validators.<name>] group; an [entrypoint] function project writes build/validator.flat, validator.plutus and abi.json.
```

`elci`, `elci help`, `elci --help` and `--help` after any command (such as `elci eval --help`) print this
text and exit with status 0.

```text
$ elci --version
elci 0.1.0-dev / julc 0.1.0-gb-5a3378b
```

The second version is the Julc build the compiler is pinned to. A project's `julc-version` in
[`elci.toml`](/reference/configuration/) must match it exactly.

## Common behavior

**Project directory.** Every command except `init` takes an optional project directory: the directory
that holds `elci.toml`. It defaults to the current directory. Giving two directories is an error.

**Two kinds of project.** A project configures either `[validators.<name>]` tables or one `[entrypoint]`
function. `check`, `build` and `test` work with both; `eval` needs an `[entrypoint]`; `apply` and
`--validator` need validators.

**Output streams.** Results go to standard output, including test results and a failed `eval` run.
Compile, configuration and command-line errors go to standard error.

**Exit status.**

| Status | Meaning |
| --- | --- |
| `0` | The command succeeded. |
| `1` | Any error: invalid arguments or configuration, a compile error, an `eval` that failed at run time, a failing test, or no tests found. |

**Error format.** A compile error names the source location:

```text
my-project/src/Main.elm:6:5: ELCI2005: Unknown name 'lenght'
```

A problem inside a bundled module such as `List` is reported where your code uses it, or otherwise as
`<bundled>/List.elm`. For a validator, the `elci.toml` table comes first, on its own line:

```text
elci: my-project/elci.toml:6: [validators.main]
my-project/src/Main.elm:6:1: ELCI2014: Handler Main.spend does not have the spend signature datum -> redeemer -> ScriptContext -> Bool that [validators.main] needs (0 parameters); its type is () -> ScriptContext -> Bool (Type mismatch: expected Bool, found ScriptContext -> Bool)
```

Configuration and command-line errors start with `elci:`. Many carry the code `ELCI4001`:

```text
elci: ELCI4001: unknown validator 'treasury'; configured: main
```

The [diagnostics](/reference/diagnostics/) page lists every code.

## `elci init`

Creates a new project in an empty or missing directory.

```text
elci init <project-directory> [--validator <name>] [--purpose <purpose>]
```

| Option | Default | Meaning |
| --- | --- | --- |
| `--purpose` | `spend` | The first handler's purpose: `spend`, `mint`, `withdraw`, `certify`, `vote` or `propose`. `function` creates an `[entrypoint]` project for `elci eval` instead. |
| `--validator` | `main` | The validator's name: lowercase letters, digits, `-` or `_`, starting with a letter. Not allowed with `--purpose function`. |

```text
$ elci init my-project
Created Elci project at /path/to/my-project with initial spend handler in validator main.
Run: elci check /path/to/my-project
```

A validator project gets `elci.toml`, `src/Main.elm`, `tests/MainTest.elm`, `README.md` and a
`.gitignore` that ignores `build/`. The starter handler accepts transactions signed by an `owner`
deployment parameter, and the two tests check that the owner passes and anyone else is rejected. A
`--purpose function` project gets `elci.toml`, `src/Main.elm` (returning `42`), `README.md` and
`.gitignore`, and no tests.

Errors: `Project directory is not empty: ...`, `Unsupported purpose: ...` and `Invalid validator name ...`.

## `elci check`

Compiles the project and reports each result without writing files.

```text
elci check [project-directory] [--validator <name>]...
```

| Option | Meaning |
| --- | --- |
| `--validator <name>` | Check only this validator. Repeat it to select several. Validator projects only. |

For a validator project, it prints one line per validator, sorted by name, with the purposes it
handles and its size in FLAT bytes:

```text
$ elci check
validator badge (mint): checked; 198 FLAT bytes; plutus-v3-pv11-uplc-1.1.0
validator treasury (spend, withdraw): checked; 540 FLAT bytes; plutus-v3-pv11-uplc-1.1.0
```

For an `[entrypoint]` project it prints the function instead:

```text
$ elci check
Main.main: checked; 6 FLAT bytes; plutus-v3-pv11-uplc-1.1.0
```

`check` compiles the source directories only; test modules are compiled by `elci test`. When `expected-abi` is set,
`check` also compares the ABI and fails on drift, exactly like `build`.

## `elci build`

Compiles the project and writes its artifacts under `build/`. It prints the same lines as `check`.

```text
elci build [project-directory] [--validator <name>]...
```

For each validator it writes `build/validators/<name>/`:

| File | Contents |
| --- | --- |
| `validator.flat` | the script's FLAT bytes |
| `validator.plutus` | a `PlutusScriptV3` text envelope with the CBOR hex |
| `validator.plutus.json` | Julc's artifact: type, purpose, CBOR hex, script hash, parameters and `sizeBytes` |
| `abi.json` | Elci's ABI manifest: handlers, datum and redeemer schemas, parameters |

`build/validators/elci-outputs.json` records which files Elci owns. The `hash` in
`validator.plutus.json` is empty for a validator with parameters, because applying parameters changes
the hash. Its `purpose` is `multi` when the validator has several handlers.

Publishing is all-or-nothing: every selected validator is compiled and ABI-checked before any file is
written, and files are replaced atomically. A build of all validators also removes the output
directories of validators you deleted from `elci.toml`. A build with `--validator` touches only the
selected validators. Rebuilding a validator removes the `applied.plutus.json` that `elci apply` left in
its directory, since it belonged to the previous script.

An `[entrypoint]` project writes `build/validator.flat`, `build/validator.plutus` and `build/abi.json`.

See [build and deploy](/contracts/build-and-deploy/) for using these files.

## `elci eval`

Runs an `[entrypoint]` project's function on Julc's evaluator and prints the result.

```text
elci eval [project-directory] [--builtins]
```

| Option | Meaning |
| --- | --- |
| `--builtins` | Also print the builtin calls of the run, one per line, with arguments and result. The log keeps the last 20 calls. |

The first line is the value, written in Elci syntax. The second is the CPU and memory consumed. Each
`Debug.trace` message follows on its own line:

```elm title="src/Main.elm"
module Main exposing (main)


type alias Quote =
    { price : Int, approved : Bool }


main : Quote
main =
    Debug.trace "pricing" { price = 25 * 1000000, approved = True }
```

```text
$ elci eval --builtins
{ approved = True, price = 25000000 }
cpu 1026458, mem 4557
trace: pricing
builtin: IfThenElse(True, <Delay>, <Delay>) → <Delay>
builtin: MkNilData(()) → [0 elems]
builtin: ConstrData(1, [0 elems]) → <Data>
builtin: IData(25000000) → <Data>
builtin: MkNilData(()) → [0 elems]
builtin: MkCons(<Data>, [0 elems]) → [1 elems]
builtin: MkCons(<Data>, [1 elems]) → [2 elems]
builtin: ConstrData(0, [2 elems]) → <Data>
builtin: Trace("pricing", <Data>) → <Data>
```

Values print as you would write them: `True`, `"text"`, `[1, -2]`, `Just 5`, `( 1, "a" )`, records with
their fields in name order, byte strings as `#474f4c44` (and `#(empty)`), maps as `Map.fromList [...]`
and arrays as `Array.fromList [...]`. `eval` passes no arguments, so an entrypoint of function type
prints `<function>`. If evaluation fails, it prints `evaluation failed:` with the reason, then the
budget, and exits with status 1:

```text
$ elci eval
evaluation failed: QuotientInteger: division by zero
cpu 276441, mem 1001
```

`eval` rejects validator projects and the `--validator` option; test validators with `elci test`.

## `elci test`

Runs the tests in the project's test directories (`tests/` by default) and reports each with its budget.

```text
elci test [project-directory] [--match <substring>] [--seed <n>] [--fuzz <runs>]
```

| Option | Default | Meaning |
| --- | --- | --- |
| `--match <substring>` | all tests | Run tests whose full name (`Module.test`) contains the substring, ignoring case. |
| `--seed <n>` | random | Seed for property tests. The seed used is printed, so any run can be repeated. |
| `--fuzz <runs>` | `100` | Number of runs for each property test. Must be positive. |

```text
$ elci test --match deadline --seed 42
PASS VestingTest.claimsAtTheDeadline (cpu 14681744, mem 54515)
PASS VestingTest.claimsAfterTheDeadline (cpu 14681744, mem 54515)
PASS VestingTest.claimsSucceedExactlyFromTheDeadline (100 runs, max cpu 14681744, mem 54515)
3 passed, 0 failed (seed 42)
```

A failing test prints its details and traces, indented, and the command exits with status 1:

```text
$ elci test
FAIL FeeTest.feeIsRounded (cpu 2130706, mem 8398)
    Expect.equal
        expected: 30
        actual:   31
    trace: computing fee
PASS FeeTest.halves (cpu 424774, mem 1602)
PASS MainTest.ownerSigns (cpu 7675163, mem 29990)
PASS MainTest.othersAreRejected (cpu 8053596, mem 31922)
3 passed, 1 failed
```

The `(seed ...)` suffix appears only when a property test ran. If nothing runs, `elci test` prints
`No tests found in tests` or `No tests match '<substring>'` and exits with status 1, so it is safe as a
CI gate. What counts as a test is described in [unit tests](/testing/unit-tests/).

## `elci apply`

Applies deployment parameters to a validator and writes the applied script.

```text
elci apply [project-directory] --validator <name> --param <name>=<value> [--param ...] [--output <file>]
```

| Option | Meaning |
| --- | --- |
| `--validator <name>` | Required. Exactly one validator. |
| `--param <name>=<value>` | Required once for every name in the validator's `parameters`, and only for those. |
| `--output <file>` | Where to write the applied script. Default: `build/validators/<name>/applied.plutus.json`. |

Each value is Plutus Data in the detailed JSON schema that cardano-cli uses:

| JSON | Data |
| --- | --- |
| `{"int": 5}` | integer |
| `{"bytes": "a2c2..."}` | byte string, in hex |
| `{"list": [ ... ]}` | list |
| `{"map": [{"k": ..., "v": ...}]}` | map |
| `{"constructor": 0, "fields": [ ... ]}` | constructor |

Write `@path` instead of JSON to read the value from a file. That path is relative to the project
directory, while `--output` is relative to the current directory. JSON strings may not contain escapes.

```text
$ elci apply --validator main --param 'owner={"bytes": "a2c20c77887ace1cd986193e4e75babd8993cfd56995cd5cfce609c2"}'
validator main: applied 1 parameter(s); hash f050dee3352fb4863e471b360e9d9a1121d81e6705580d1cbc3b831f; wrote /path/to/my-project/build/validators/main/applied.plutus.json
```

`apply` compiles the validator, checks its ABI when `expected-abi` is set, and checks every value
off-chain with the strict decoder for the parameter's type, because the script itself does not validate
parameters. A value of the wrong shape is rejected before anything is written:

```text
$ elci apply --validator main --param 'owner={"int": 5}'
elci: ELCI4001: parameter owner is not a well-formed org.julclang.ledger.PubKeyHash value
```

Other errors name the problem: `missing --param owner=<plutus-data-json>`, `parameter owner is given more
than once`, `validator main has no parameter 'nope'; declared: owner`, and
`validator main declares no parameters`. See [parameterized validators](/contracts/parameters/).
