# Build a vesting contract

> Build a vesting validator from an empty directory, with a typed datum and redeemer, a deadline checked against the validity range, a signature check, scenarios and a property test.

Source: https://elci-lang.dev/tutorials/vesting/

In this tutorial you build a vesting contract from scratch: funds are locked for a beneficiary and can only be claimed after a deadline. You write a typed datum and redeemer, check signatures and the transaction's validity range, cover the rules with transaction scenarios and a property test, and build the script.

You need `elci` on your `PATH` (see [Installation](https://elci-lang.dev/start/installation/)). If you haven't yet, work through [Your first validator](https://elci-lang.dev/start/first-validator/) first. The repository's [vesting example](https://github.com/bloxbean/elci-lang/tree/main/examples/vesting) is the finished contract.

## What you'll build

Someone locks funds at the vesting script's address with a datum that names a **beneficiary** and a **deadline**. Later, the beneficiary spends the output with the redeemer `Claim`. The validator accepts the claim only when both hold:

1. the transaction is signed by the beneficiary, and
2. the transaction can only run after the deadline.

## Create the project

```sh
elci init vesting --validator vesting
cd vesting
```

```text
Created Elci project at .../vesting with initial spend handler in validator vesting.
Run: elci check .../vesting
```

`--validator vesting` names the validator, and so its output directory, `vesting` instead of `main`. The starter project already passes its tests:

```sh
elci test .
```

```text
PASS MainTest.ownerSigns (cpu 7675163, mem 29990)
PASS MainTest.othersAreRejected (cpu 8053596, mem 31922)
2 passed, 0 failed
```

You'll replace the starter validator and its tests with your own, so delete them:

```sh
rm src/Main.elm tests/MainTest.elm
```

## Configure the validator

Open `elci.toml` and replace the validator table. The vesting contract has no deployment parameters, and its spend handler will be `validate` in a module called `Vesting`:

```toml title="elci.toml"
[project]
source-directories = ["src"]
target = "plutus-v3-pv11-uplc-1.1.0"
julc-version = "0.1.0-pre20-ebaf028-SNAPSHOT"

[validators.vesting]
boundary = "julc-strict-v1"
spend = "Vesting.validate"
```

## Write the datum, the redeemer and a first handler

Start with the types and the signature rule. Create `src/Vesting.elm`:

```elm title="src/Vesting.elm"
module Vesting exposing (VestingAction(..), VestingDatum, validate)

import Julc.Ledger exposing (PubKeyHash, ScriptContext)
import Tx

type alias VestingDatum =
    { beneficiary : PubKeyHash
    , deadline : Int
    }

type VestingAction
    = Claim

validate : VestingDatum -> VestingAction -> ScriptContext -> Bool
validate datum action ctx =
    case action of
        Claim ->
            Tx.signedBy datum.beneficiary ctx
```

- `VestingDatum` is the datum: a record with the beneficiary's key hash and the deadline. The module exposes the type so tests can build datums.
- `VestingAction` is the redeemer. It has a single constructor today, but a custom type leaves room for more actions later. `VestingAction(..)` exposes the constructor too.
- `validate` has the spend handler shape: datum, redeemer, script context, `Bool`. There are no parameters, so the datum comes first.
- `case action of` handles each redeemer. The compiler insists that every constructor is covered.
- `Tx.signedBy` asks whether the key is among the transaction's signatories. `Tx` is a core module that answers common questions about the transaction; it needs the import, but no configuration.

```sh
elci check .
```

```text
validator vesting (spend): checked; 393 FLAT bytes; plutus-v3-pv11-uplc-1.1.0
```

## Test the signature rule

Create `tests/VestingTest.elm` with two scenarios: the beneficiary claims, and a stranger tries to:

```elm title="tests/VestingTest.elm"
module VestingTest exposing (beneficiaryClaims, strangersCannotClaim)

import Julc.Data as Data
import Julc.Ledger exposing (PubKeyHash)
import Scenario exposing (Scenario)
import Vesting exposing (VestingAction(..), VestingDatum)

beneficiary : PubKeyHash
beneficiary =
    Scenario.key "beneficiary"

datum : VestingDatum
datum =
    { beneficiary = beneficiary, deadline = 1000 }

claim : Scenario
claim =
    Scenario.spend "vesting" (Scenario.outRef "vesting" 0) (Data.encode datum)
        |> Scenario.redeemer (Data.encode Claim)

beneficiaryClaims : Scenario
beneficiaryClaims =
    claim
        |> Scenario.signedBy beneficiary

strangersCannotClaim : Scenario
strangersCannotClaim =
    claim
        |> Scenario.signedBy (Scenario.key "stranger")
        |> Scenario.expectFailure
```

`Scenario.spend` locks an output at the `vesting` script's address with the encoded datum, then spends it. `Data.encode` turns your Elci values into the on-chain Data the ledger passes to the script.

```sh
elci test .
```

```text
PASS VestingTest.beneficiaryClaims (cpu 10873274, mem 42950)
PASS VestingTest.strangersCannotClaim (cpu 11251707, mem 44882)
2 passed, 0 failed
```

## Add the deadline

A script can't read the clock. Instead, every transaction declares a **validity range**, the time interval in which it may be included in a block, and the ledger enforces it. If every time in the range is after the deadline, the transaction can't possibly run at or before the deadline. So the rule is: the range must lie entirely after the deadline.

Times are POSIX times in milliseconds. The tests use small numbers such as `1000` for readability; a real deadline looks like `1767225600000` (1 January 2026, UTC).

1. **Check the range.** The core `Interval` module reads validity ranges. Update the handler:

   ```elm title="src/Vesting.elm" ins={3,22-23}
   module Vesting exposing (VestingAction(..), VestingDatum, validate)

   import Interval
   import Julc.Ledger exposing (PubKeyHash, ScriptContext)
   import Tx

   type alias VestingDatum =
       { beneficiary : PubKeyHash
       , deadline : Int
       }

   type VestingAction
       = Claim

   validate : VestingDatum -> VestingAction -> ScriptContext -> Bool
   validate datum action ctx =
       case action of
           Claim ->
               Interval.isEntirelyAfter datum.deadline ctx.txInfo.validRange
                   && Tx.signedBy datum.beneficiary ctx
   ```

   `Interval.isEntirelyAfter datum.deadline range` holds when the range has a start and every time in it is after the deadline. A range without a start could run at any time, so it never passes. `&&` only evaluates its right side when the left side is `True`, so a claim before the deadline doesn't pay for the signature search.

2. **Run the old tests.**

   ```sh
   elci test .
   ```

   ```text
   FAIL VestingTest.beneficiaryClaims (cpu 11922589, mem 46110)
       validator vesting rejected the transaction: Error term encountered
   PASS VestingTest.strangersCannotClaim (cpu 11922589, mem 46110)
   1 passed, 1 failed
   ```

   `beneficiaryClaims` fails, as it should: its transaction has no validity range, so it could run before the deadline. Notice that `strangersCannotClaim` now passes for a different reason: it fails the deadline check before the signature is ever looked at. Both scenarios cost exactly the same. A test that expects failure needs a nearly identical test that succeeds, or it proves little.

## Test the deadline

Rewrite the tests around a helper that claims from a given time. `Scenario.validFrom` sets the start of the validity range (inclusive) and `Scenario.validBefore` its end (exclusive):

```elm title="tests/VestingTest.elm"
module VestingTest exposing (..)

import Julc.Data as Data
import Julc.Ledger exposing (PubKeyHash)
import Scenario exposing (Scenario)
import Vesting exposing (VestingAction(..), VestingDatum)

beneficiary : PubKeyHash
beneficiary =
    Scenario.key "beneficiary"

datum : VestingDatum
datum =
    { beneficiary = beneficiary, deadline = 1000 }

claimFrom : Int -> Scenario
claimFrom time =
    Scenario.spend "vesting" (Scenario.outRef "vesting" 0) (Data.encode datum)
        |> Scenario.redeemer (Data.encode Claim)
        |> Scenario.validFrom time

claimsJustAfterTheDeadline : Scenario
claimsJustAfterTheDeadline =
    claimFrom 1001
        |> Scenario.signedBy beneficiary

claimsLongAfterTheDeadline : Scenario
claimsLongAfterTheDeadline =
    claimFrom 5000
        |> Scenario.validBefore 6000
        |> Scenario.signedBy beneficiary

claimsAtTheDeadlineFail : Scenario
claimsAtTheDeadlineFail =
    claimFrom 1000
        |> Scenario.signedBy beneficiary
        |> Scenario.expectFailure

strangersCannotClaim : Scenario
strangersCannotClaim =
    claimFrom 2000
        |> Scenario.signedBy (Scenario.key "stranger")
        |> Scenario.expectFailure

claimsWithoutAStartFail : Scenario
claimsWithoutAStartFail =
    Scenario.spend "vesting" (Scenario.outRef "vesting" 0) (Data.encode datum)
        |> Scenario.redeemer (Data.encode Claim)
        |> Scenario.signedBy beneficiary
        |> Scenario.expectFailure
```

The module now uses `exposing (..)`. Test discovery only runs exposed, zero-argument values of the test types (`Scenario` here), so `beneficiary`, `datum` and the function `claimFrom` stay helpers. A zero-argument `Scenario` helper would run as a test, which is why `claimsWithoutAStartFail` builds its own transaction rather than sharing an exposed base scenario.

The tests pin down the edges: one millisecond after the deadline, exactly at it, a stranger after it, and no start at all. A transaction valid from `1000` could run at `1000`, which is not after the deadline, so it is rejected.

```sh
elci test .
```

```text
PASS VestingTest.claimsJustAfterTheDeadline (cpu 16763911, mem 61637)
PASS VestingTest.claimsLongAfterTheDeadline (cpu 16763911, mem 61637)
PASS VestingTest.claimsAtTheDeadlineFail (cpu 13950292, mem 52228)
PASS VestingTest.strangersCannotClaim (cpu 17142344, mem 63569)
PASS VestingTest.claimsWithoutAStartFail (cpu 11922589, mem 46110)
5 passed, 0 failed
```

## Add a property test

Scenarios check the times you thought of. A **property** states a rule for every time, and `elci test` tries it with many generated values. Add the import and the property to `tests/VestingTest.elm`:

```elm title="tests/VestingTest.elm" ins={5}
module VestingTest exposing (..)

import Julc.Data as Data
import Julc.Ledger exposing (PubKeyHash)
import Property exposing (Property)
import Scenario exposing (Scenario)
import Vesting exposing (VestingAction(..), VestingDatum)
```

```elm title="tests/VestingTest.elm (at the end)"
{-| Whenever a signed claim's validity range starts, it succeeds exactly when that is after
the deadline. The start is narrowed to the 20 milliseconds around the deadline.
-}
claimsSucceedOnlyAfterTheDeadline : Int -> Property
claimsSucceedOnlyAfterTheDeadline raw =
    let
        time =
            datum.deadline - 10 + modBy 20 raw

        signed =
            claimFrom time
                |> Scenario.signedBy beneficiary
    in
    if time > datum.deadline then
        Property.scenario signed

    else
        Property.scenario (Scenario.expectFailure signed)
```

- An exposed function whose result is a `Property` is a [property test](https://elci-lang.dev/testing/properties/). `elci test` generates its `Int` argument, 100 times by default.
- Generated integers are mostly small, so the property narrows them with `modBy` to times between 990 and 1009, where the interesting boundary is.
- `Property.scenario` runs the transaction against the real script. The `if` states the expected outcome for each time.

```sh
elci test . --seed 42
```

```text
PASS VestingTest.claimsJustAfterTheDeadline (cpu 16763911, mem 61637)
PASS VestingTest.claimsLongAfterTheDeadline (cpu 16763911, mem 61637)
PASS VestingTest.claimsAtTheDeadlineFail (cpu 13950292, mem 52228)
PASS VestingTest.strangersCannotClaim (cpu 17142344, mem 63569)
PASS VestingTest.claimsWithoutAStartFail (cpu 11922589, mem 46110)
PASS VestingTest.claimsSucceedOnlyAfterTheDeadline (100 runs, max cpu 16763911, mem 61637)
6 passed, 0 failed (seed 42)
```

The summary reports the seed of the run. Without `--seed`, every run picks a random one; passing a reported seed reproduces that run exactly.

### See it catch a bug

To see what the property is for, break the contract on purpose. Change `Interval.isEntirelyAfter datum.deadline` to `Interval.isEntirelyAfter (datum.deadline - 1)` in `src/Vesting.elm`, so a claim valid from the deadline itself is accepted, and run only the property:

```sh
elci test . --match OnlyAfter --seed 4
```

```text
FAIL VestingTest.claimsSucceedOnlyAfterTheDeadline (38 runs, cpu 16929119, mem 62039)
    counterexample after 38 runs, shrunk 1 time:
        claimsSucceedOnlyAfterTheDeadline 10
    validator vesting accepted a transaction expected to fail
0 passed, 1 failed (seed 4)
```

The counterexample is the generated argument, `raw = 10`, which gives `time = 990 + modBy 20 10 = 1000`: exactly the deadline. The property found the off-by-one without you writing that case. Change the check back before you continue. (`claimsAtTheDeadlineFail` catches this bug too; the property would also catch the ones you didn't think to write a scenario for.)

## Build the script

```sh
elci build .
```

```text
validator vesting (spend): checked; 533 FLAT bytes; plutus-v3-pv11-uplc-1.1.0
```

Your project now looks like this:

- vesting/
  - elci.toml
  - README.md
  - .gitignore
  - src/
    - Vesting.elm
  - tests/
    - VestingTest.elm
  - build/
    - validators/
      - elci-outputs.json
      - vesting/
        - abi.json
        - validator.flat
        - validator.plutus
        - validator.plutus.json

The validator has no parameters, so its artifact already carries the script hash, from which the script address is derived (`cborHex` shortened):

```json title="build/validators/vesting/validator.plutus.json"
{
  "type": "PlutusScriptV3",
  "purpose": "spending",
  "description": "vesting",
  "cborHex": "590218590215010100323232323232323232322323232323...",
  "hash": "cdf09c382832689b016adac7ef1470488e9ff58b8a474b9086c7a0c3",
  "params": "",
  "sizeBytes": 533
}
```

`abi.json` records how the datum and redeemer are encoded. `VestingDatum` is constructor 0 with `beneficiary` then `deadline` (fields in name order), and `Claim` is constructor 0 with no fields. Off-chain code that locks funds must build the datum in exactly that layout, or the output can never be claimed. [Build and deploy](https://elci-lang.dev/contracts/build-and-deploy/) explains the files and shows how to load the script from Java.

## Recap

- The datum and the redeemer are ordinary Elci types. The compiler decodes them strictly before `validate` runs.
- Time checks reason about the transaction's validity range, never a clock. "After the deadline" means every time in the range is after it: `Interval.isEntirelyAfter`.
- The core `Tx` and `Interval` modules answer the common questions about a transaction without any configuration.
- Scenarios pin down the edge cases. A failing scenario needs a passing twin, or it may fail for the wrong reason.
- A property checks a rule over many generated values, and shrinks a failure to a small counterexample with a seed that reproduces it.

Next, build two cooperating scripts with parameters in [A treasury and its badge policy](https://elci-lang.dev/tutorials/treasury/).
