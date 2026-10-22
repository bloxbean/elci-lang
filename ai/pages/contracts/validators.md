# Validators and purposes

> Declare validators in elci.toml, give each purpose a handler with the right signature, and let one script serve several purposes.

Source: https://elci-lang.dev/contracts/validators/

This page shows how a validator is declared, which purposes it can handle, and the exact signature each handler needs. You also build one script that both holds funds and mints tokens.

## Purposes

The Cardano ledger runs a script whenever a transaction does something the script guards. That reason is the script's **purpose**. Elci supports all six Plutus V3 purposes, and each has a handler key in `elci.toml`:

| Handler key | The ledger runs the script when a transaction... |
| --- | --- |
| `spend` | spends an output locked at the script's address |
| `mint` | mints or burns tokens under the script's policy id |
| `withdraw` | withdraws staking rewards from the script's stake credential |
| `certify` | publishes a certificate for the script's credential, such as a stake registration |
| `vote` | casts a governance vote with the script as the voter |
| `propose` | submits a governance proposal that the script guards |

A script is identified by its **hash**. The same hash serves as the payment credential of its address, as its minting policy id and as its stake credential, so one script can guard all of these at once.

## Declaring a validator

A validator is a `[validators.<name>]` table in `elci.toml`. Each table becomes **one script** with one hash. Its handler keys point at exposed functions, written as `Module.function`:

```toml title="elci.toml"
[validators.vault]
boundary = "julc-strict-v1"
parameters = ["owner"]
spend = "Vault.spend"
mint = "Vault.mint"
```

| Key | Required | Meaning |
| --- | --- | --- |
| `boundary` | yes | How data entering the script is decoded. `"julc-strict-v1"` is the only policy: every datum and redeemer is checked strictly before the handler runs. |
| `spend`, `mint`, `withdraw`, `certify`, `vote`, `propose` | at least one | The handler for that purpose, as `"Module.function"`. Each key may appear once. |
| `parameters` | no | Deployment parameter names, in the order the handlers receive them. See [Parameterized validators](https://elci-lang.dev/contracts/parameters/). |
| `spend-datum` | no | `"required"` (the default), `"optional"` or `"absent"`. Only with a `spend` handler. See [Datums and redeemers](https://elci-lang.dev/contracts/datums-and-redeemers/). |
| `expected-abi` | no | A committed `abi.json` to compare against before anything is written. See [Build and deploy](https://elci-lang.dev/contracts/build-and-deploy/). |

The name after `validators.` is also the output directory, `build/validators/<name>/`. It must start with a lowercase letter and use only lowercase letters, digits, `-` and `_`, up to 64 characters. The source code never says how a function is deployed; only the configuration does.

## Handler signatures

Each handler is an ordinary function that returns `Bool`. Its arguments come in a fixed order: the deployment parameters, then the datum (spending only), then the redeemer, then the `ScriptContext`.

| Handler key | Required type |
| --- | --- |
| `spend` | `parameters -> datum -> redeemer -> ScriptContext -> Bool` |
| `spend` with `spend-datum = "optional"` | `parameters -> Maybe datum -> redeemer -> ScriptContext -> Bool` |
| `spend` with `spend-datum = "absent"` | `parameters -> redeemer -> ScriptContext -> Bool` |
| `mint`, `withdraw`, `certify`, `vote`, `propose` | `parameters -> redeemer -> ScriptContext -> Bool` |

`parameters` stands for zero or more leading arguments, one per name in `parameters = [...]`. You choose the datum and redeemer types; use `()` when you don't need one. Returning `True` approves the transaction. Returning `False`, or failing during evaluation (a failed decode, a division by zero), rejects it.

The handler must be exposed by its module, and must be a concrete function: its signature can't keep type variables such as `a`.

## One script, several purposes

Here is a vault that holds funds and mints tokens under its own policy id. Both handlers live in one module and share a helper:

```toml title="elci.toml"
[project]
source-directories = ["src"]
target = "plutus-v3-pv11-uplc-1.1.0"
julc-version = "0.1.0-pre20-ebaf028-SNAPSHOT"

# One script: it holds funds and mints receipts under its own policy.
[validators.vault]
boundary = "julc-strict-v1"
parameters = ["owner"]
spend = "Vault.spend"
mint = "Vault.mint"
```

```elm title="src/Vault.elm"
module Vault exposing (mint, spend)

import Julc.Ledger exposing (PubKeyHash, ScriptContext)

{-| Spending: the owner may take anything held at the vault's address.
-}
spend : PubKeyHash -> () -> () -> ScriptContext -> Bool
spend owner _ _ ctx =
    signedBy owner ctx

{-| Minting: the owner may mint or burn tokens under the vault's policy id.
-}
mint : PubKeyHash -> () -> ScriptContext -> Bool
mint owner _ ctx =
    signedBy owner ctx

signedBy : PubKeyHash -> ScriptContext -> Bool
signedBy key ctx =
    List.any (\signer -> signer == key) ctx.txInfo.signatories
```

The two signatures differ: `spend` takes a datum and a redeemer, `mint` only a redeemer. Both start with the `owner` parameter, because every handler of a validator receives all of its parameters.

```sh
elci check .
```

```text
validator vault (mint, spend): checked; 389 FLAT bytes; plutus-v3-pv11-uplc-1.1.0
```

The check line lists the purposes the script serves. Test each purpose with a [transaction scenario](https://elci-lang.dev/testing/scenarios/), including one the vault doesn't handle:

```elm title="tests/VaultTest.elm"
module VaultTest exposing (ownerMints, ownerSpends, strangerCannotSpend, withdrawalsAreNotHandled)

import Bytes
import Julc.Data as Data
import Julc.Ledger exposing (PubKeyHash)
import Scenario exposing (Scenario)

owner : PubKeyHash
owner =
    Scenario.key "owner"

ownerSpends : Scenario
ownerSpends =
    Scenario.spend "vault" (Scenario.outRef "vault" 0) Scenario.unit
        |> Scenario.parameters [ Data.encode owner ]
        |> Scenario.signedBy owner

strangerCannotSpend : Scenario
strangerCannotSpend =
    Scenario.spend "vault" (Scenario.outRef "vault" 0) Scenario.unit
        |> Scenario.parameters [ Data.encode owner ]
        |> Scenario.signedBy (Scenario.key "stranger")
        |> Scenario.expectFailure

ownerMints : Scenario
ownerMints =
    Scenario.mint "vault"
        |> Scenario.parameters [ Data.encode owner ]
        |> Scenario.minting (Bytes.fromString "RECEIPT") 1
        |> Scenario.signedBy owner

{-| The vault has no withdraw handler, so the script rejects every withdrawal.
-}
withdrawalsAreNotHandled : Scenario
withdrawalsAreNotHandled =
    Scenario.withdraw "vault"
        |> Scenario.parameters [ Data.encode owner ]
        |> Scenario.signedBy owner
        |> Scenario.expectFailure
```

```sh
elci test .
```

```text
PASS VaultTest.ownerSpends (cpu 8015496, mem 31791)
PASS VaultTest.strangerCannotSpend (cpu 8393929, mem 33723)
PASS VaultTest.ownerMints (cpu 5954941, mem 23732)
PASS VaultTest.withdrawalsAreNotHandled (cpu 2341260, mem 9622)
4 passed, 0 failed
```

### How dispatch works

On-chain, a Plutus V3 script receives a single argument: the whole script context. Elci generates the code around your handlers that turns it into the arguments you declared:

1. The deployment parameters are decoded once, when the script is applied to them.
2. The script checks the purpose and selects the matching handler. A purpose without a handler fails immediately, which is why the withdrawal above is rejected even though the owner signed it.
3. It decodes the selected handler's datum and redeemer. Decoding is strict: data of the wrong shape fails the script.
4. It calls the handler and turns `False` into a failure.

Because only the selected handler's datum and redeemer are decoded, different handlers can use different redeemer types. The purpose check runs even for a single handler. It costs roughly 1.1M CPU units and 35 bytes of script, and it means a script never runs a handler for a purpose you didn't configure.

Adding, removing or changing a handler changes the script, and so its hash, address and policy id.

### Top-level values run for every purpose

Top-level values that any handler uses are evaluated once, when the script starts and before dispatch. A top-level value that fails therefore fails **every** purpose. Suppose `mint` used this limit:

```elm
mint : PubKeyHash -> () -> ScriptContext -> Bool
mint owner _ ctx =
    (limit > 0) && signedBy owner ctx

limit : Int
limit =
    100 // 0
```

Spending breaks too, although `spend` never reads `limit`:

```text
FAIL VaultTest.ownerSpends (cpu 372441, mem 1601)
    validator vault rejected the transaction: QuotientInteger: division by zero
PASS VaultTest.strangerCannotSpend (cpu 372441, mem 1601)
FAIL VaultTest.ownerMints (cpu 372441, mem 1601)
    validator vault rejected the transaction: QuotientInteger: division by zero
PASS VaultTest.withdrawalsAreNotHandled (cpu 372441, mem 1601)
2 passed, 2 failed
```

The two tests that expect a rejection still pass, but only because every transaction now fails. Keep work that belongs to one purpose inside its handler. Top-level values also cost budget in every transaction, so keep them cheap.

## Several validators in one project

Add more tables for independent scripts. Each has its own hash, its own outputs under `build/validators/<name>/` and its own parameters. Modules can be shared freely; each script includes only the code its handlers reach.

```toml
[validators.treasury]
boundary = "julc-strict-v1"
parameters = ["admin"]
spend = "Treasury.spend"
withdraw = "Treasury.withdraw"

[validators.badge]
boundary = "julc-strict-v1"
parameters = ["admin"]
mint = "Badge.mint"
```

`elci check` and `elci build` process every validator in name order. Select some with `--validator`, which you can repeat:

```sh
elci build . --validator badge
```

The [treasury tutorial](https://elci-lang.dev/tutorials/treasury/) builds this project step by step.

## When a handler doesn't fit

The compiler checks every handler against its table. The errors name the `elci.toml` line and table, then the source location. If the signature doesn't match the purpose, you get `ELCI2014`. Here `mint` was given a datum argument, which mint handlers don't receive:

```elm
mint : PubKeyHash -> () -> () -> ScriptContext -> Bool
mint owner _ _ ctx =
    signedBy owner ctx
```

```text
.../vault/src/Vault.elm:15:1: ELCI2014: Handler Vault.mint does not have the mint signature owner -> redeemer -> ScriptContext -> Bool that [validators.vault] needs (1 parameter); its type is PubKeyHash -> () -> () -> ScriptContext -> Bool; it has `()` where the configuration needs `ScriptContext`

15| mint : PubKeyHash -> () -> () -> ScriptContext -> Bool
    ^^^^

In .../vault/elci.toml:7: [validators.vault]
```

The message gives the signature the table needs, with its parameter count, and the handler's actual type. A mint handler takes the parameters, a redeemer and the context, so the extra `()` puts a `()` where the `ScriptContext` should be. A handler that its module doesn't expose is `ELCI2103`:

```text
.../vault/src/Vault.elm:15:1: ELCI2103: Handler Vault.mint must be exposed by its module

15| mint : PubKeyHash -> () -> ScriptContext -> Bool
    ^^^^

In .../vault/elci.toml:7: [validators.vault]
```

And a handler key that names a missing function:

```text
elci: ELCI4001: .../vault/elci.toml:7: [validators.vault]: Handler Vault.spent for spend: module Vault does not declare spent
```

The [diagnostics reference](https://elci-lang.dev/reference/diagnostics/) lists every error code.

## Starting from a template

`elci init` can generate a starter project for any purpose. The template accepts transactions signed by an `owner` parameter and comes with two passing scenarios:

```sh
elci init my-policy --purpose mint
elci init rewards --validator rewards --purpose withdraw
```

`--purpose` accepts `spend` (the default), `mint`, `withdraw`, `certify`, `vote` and `propose`; `function` creates an `[entrypoint]` project for `elci eval` instead of a validator. `--validator` names the table (default `main`). The purpose only selects the starter code: add handler keys to the table at any time.

Next, see what a handler can read from the transaction in [Reading the transaction](https://elci-lang.dev/contracts/reading-the-transaction/).
