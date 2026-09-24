# Testing Elci contracts

`elci test` runs tests written in Elci. Each test compiles to its own program and
runs on Julc's VM, which reports its CPU and memory. Transaction scenarios run the
same script `elci build` publishes, inside a complete ledger context that Julc's
testkit builds. The design is recorded in
[ADR-006](../adr/006-source-level-tests.md).

```sh
elci test examples/treasury
elci test examples/treasury --match badge    # case-insensitive substring of the test name
```

```text
PASS TreasuryTest.beneficiaryReleases (cpu 11746810, mem 46979)
PASS TreasuryTest.strangerCannotRelease (cpu 12125243, mem 48911)
...
9 passed, 0 failed
```

The exit status is 1 when a test fails or no test matches, so the command can
gate CI.

## Where tests live

Put test modules under `tests/`. To use other directories, configure them:

```toml
[tests]
source-directories = ["tests", "spec"]
```

- **Imports:** test modules can import the project's modules, its `[java-modules]`
  and the test library (`Expect`, `Scenario`). They never become part of `check`,
  `build` or published artifacts.
- **Naming:** a test module may not share a name with a source module, `Expect` or
  `Scenario`.
- **Discovery:** a test is an **exposed, zero-argument, top-level declaration**
  whose type is `Bool`, `Expect.Expectation` or `Scenario.Scenario`. Anything else
  is a helper. Expose only the tests to keep helpers private; with `exposing (..)`,
  helpers of other types are skipped.

## Unit tests

A `Bool` test passes when it evaluates to `True`. An `Expectation` test gives a
readable failure:

```elm
module PricingTest exposing (roundsDown, scalesFees)

import Expect exposing (Expectation)
import Pricing


roundsDown : Bool
roundsDown =
    Pricing.fee 999 == 0


scalesFees : Expectation
scalesFees =
    Expect.equal 30 (Pricing.fee 10000)
```

```text
FAIL PricingTest.scalesFees (cpu 3174066, mem 12095)
    Expect.equal
        expected: 30
        actual:   31
```

| `Expect` function | Passes when |
| --- | --- |
| `equal expected actual` | the values are structurally equal |
| `notEqual unexpected actual` | they differ |
| `atMost`, `atLeast`, `lessThan`, `greaterThan` bound actual | integer comparison with the bound holds |
| `pass` / `fail message` | always / never |
| `onFail message expectation` | the expectation passes, and replaces its failure message otherwise |
| `every [ e1, e2, ... ]` | all pass; the first failure is reported |

Values in failure reports are shown as their Plutus Data encoding, such as `50`,
`#1f2a` or `Constr 1 [5]`, because Elci cannot turn arbitrary values into text
on-chain. `Expect.equal` needs the same structural equality as `==`, so it
rejects `Data`, functions and imported types without approved equality.

A test that fails at runtime, for example by dividing by zero or on a failed
`Julc.Data.decode`, is reported with the VM's error.

## Transaction scenarios

A `Scenario` describes one transaction against a validator of the project.

```elm
module TreasuryTest exposing (beneficiaryReleases, strangerCannotRelease)

import Julc.Data as Data
import Julc.Ledger exposing (PubKeyHash)
import Scenario exposing (Scenario)
import Treasury exposing (Action(..), Payout)


admin : PubKeyHash
admin =
    Scenario.key "admin"


beneficiary : PubKeyHash
beneficiary =
    Scenario.key "beneficiary"


claim : Action -> Scenario
claim action =
    Scenario.spend "treasury" (Scenario.outRef "treasury" 0) (Data.encode { beneficiary = beneficiary, amount = 50 })
        |> Scenario.parameters [ Data.encode admin ]
        |> Scenario.redeemer (Data.encode action)


beneficiaryReleases : Scenario
beneficiaryReleases =
    claim Release
        |> Scenario.signedBy beneficiary
        |> Scenario.withinBudget 20000000 100000


strangerCannotRelease : Scenario
strangerCannotRelease =
    claim Release
        |> Scenario.signedBy admin
        |> Scenario.expectFailure
```

For each scenario, the runner does the following:

1. It compiles the named validator exactly as `elci build` does and applies
   `parameters` in the declared order. A project with an `[entrypoint]` spending or
   minting validator names it `"entrypoint"`.
2. It computes the applied script's hash. The validator's own address, minting
   policy and staking credential derive from it.
3. It builds the ledger context with julc-testkit's `ScriptContextTestBuilder`. For
   `spend`, the spent output is added as an input at the script's address, with the
   datum inline.
4. It evaluates the **deployed script**, so the purpose dispatch and strict
   datum/redeemer decoding run exactly as on-chain.
5. It compares the outcome with the expectation. A budget set with
   `withinBudget` is checked by julc-testkit's `BudgetAssertions`.

### Starting a scenario

| Function | Transaction |
| --- | --- |
| `spend validator ref datum` | spends `ref`, held at the script address with an inline `datum` |
| `spendWithoutDatum validator ref` | spends an output without a datum |
| `mint validator` | mints under the validator's own policy (add tokens with `minting`) |
| `withdraw validator` | withdraws the validator's own staking rewards |
| `certify validator index certificate` | a certificate (`TxCert`) at an index |
| `vote validator voter` | a governance vote by `voter` |
| `propose validator index procedure` | a governance proposal |

### Adding transaction details

| Function | Effect |
| --- | --- |
| `redeemer data` | the redeemer (default: `Scenario.unit`, the Data of `()`) |
| `parameters [ data, ... ]` | deployment parameters, in declared order |
| `signedBy key` | adds a required signer |
| `input ref output`, `referenceInput ref output` | adds an input or a reference input |
| `output out` | adds an output |
| `minting name quantity` | mints (positive) or burns (negative) under the own policy |
| `fee lovelace` | the fee (default 200000) |
| `validFrom time`, `validBefore time` | the validity interval: inclusive start, exclusive end |
| `worth lovelace` | lovelace in the spent output (default 2000000) |
| `expectFailure` | the validator must reject the transaction |
| `withinBudget cpu memory` | the execution must stay within the budget |

Outputs are built with `toScript lovelace` (the validator's own address) or
`toAddress address lovelace`, and extended with `withDatum data` and
`withToken policy name quantity`. A policy is `Own` or `Policy bytes`.

`Scenario.key name` and `Scenario.outRef name index` derive deterministic test key
hashes and output references from a name. `Bytes.fromString` makes token names.
`Scenario.unit` is the Data of `()`.

### Limits

A scenario covers single-signature and multi-signature checks, validity ranges,
lovelace, the own policy's tokens and other given policies, inline datums, and
every purpose. The following need the JVM facade below:

- datum hashes with witness tables;
- reference scripts;
- other staking credentials and additional withdrawals;
- more than one script in a transaction.

## Debugging

`Debug.trace message value` returns `value` and emits `message` as a Plutus trace:

```elm
import Debug

checked : Bool
checked =
    Debug.trace "checking the deadline" (deadline <= now)
```

Failing tests print their traces. Traces also work in contracts but cost budget
on-chain, so remove them before deployment.

## Testing from Java

`org.elci.testkit.ElciTestkit` compiles a project's scripts for JUnit suites. The
suites then use julc-testkit directly, as Julc's Java validators do:

```java
import org.elci.testkit.ElciTestkit;
import org.julclang.testkit.ScriptContextTestBuilder;
import org.julclang.testkit.ValidatorTest;

var treasury = ElciTestkit.validator(Path.of("examples/treasury"), "treasury", admin.toPlutusData());
var ctx = ScriptContextTestBuilder.rewarding(credential)
        .signer(admin)
        .redeemer(PlutusData.UNIT)
        .buildPlutusData();
ValidatorTest.assertValidates(treasury, ctx);
```

The facade has three entry points:

- `ElciTestkit.validator(project, name, parameters...)`: a named validator with its
  parameters applied;
- `ElciTestkit.entrypoint(project)`: the `[entrypoint]` script;
- `ElciTestkit.value(project, module, name)`: any exposed zero-argument declaration,
  including those in test modules.

The scripts are the ones `elci build` publishes.
