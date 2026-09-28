---
title: Test library
description: Complete API of the Expect, Scenario and Property modules that elci test provides to test modules.
sidebar:
  order: 4
---

`elci test` provides three modules, written in Elci, to your test modules: `Expect` for expectations, `Scenario` for transactions against your validators, and `Property` for property-based tests. This page lists every type and function they expose. For a guided introduction, start with [Unit tests](/testing/unit-tests/).

Only test modules can import these modules. An import from a source module is an error at the import, `ELCI2101`: `elci test` reports `Only test modules can import Expect; it is part of the test library, not available to elci build`. A test module cannot be named `Expect`, `Scenario` or `Property`.

## Test discovery

A test is an exposed, top-level declaration of a test module that is either:

- a zero-argument value of type `Bool`, `Expect.Expectation`, `Scenario.Scenario` or `Property.Property`; or
- a function whose result type is `Property.Property`, which runs as a property with generated arguments.

Every other declaration is a helper. Modules run in alphabetical order and the tests of a module in source order.

| `elci test` option | Effect |
| --- | --- |
| `--match <substring>` | runs only the tests whose `Module.name` contains the substring, ignoring case |
| `--seed <n>` | runs properties with seed `n`; without it, a random seed is used and printed |
| `--fuzz <runs>` | runs each property `runs` times (default 100; must be positive) |

The command exits with status 1 when a test fails or when no test is found or matches. See also the [CLI reference](/reference/cli/).

## Expect

```elm
import Expect exposing (Expectation)
```

### Types

```elm
type Expectation
    = Pass
    | Fail String (List Data)
```

A failure carries a message and the Data encodings of the values involved, which the test runner prints. The constructors are exposed, so a helper can match on an expectation with `import Expect exposing (Expectation(..))`.

### Functions

| Function | Signature | Passes when |
| --- | --- | --- |
| `pass` | `Expectation` | always |
| `fail` | `String -> Expectation` | never |
| `equal` | `a -> a -> Expectation` | `expected == actual`, for `equal expected actual` |
| `notEqual` | `a -> a -> Expectation` | `unexpected /= actual`, for `notEqual unexpected actual` |
| `atMost` | `comparable -> comparable -> Expectation` | `actual <= bound`, for `atMost bound actual` |
| `atLeast` | `comparable -> comparable -> Expectation` | `actual >= bound`, for `atLeast bound actual` |
| `lessThan` | `comparable -> comparable -> Expectation` | `actual < bound`, for `lessThan bound actual` |
| `greaterThan` | `comparable -> comparable -> Expectation` | `actual > bound`, for `greaterThan bound actual` |
| `every` | `List Expectation -> Expectation` | every expectation in the list passes; otherwise the first failure is the result |
| `onFail` | `String -> Expectation -> Expectation` | the expectation passes; otherwise its message is replaced, keeping its values |

`equal` and `notEqual` require the same structural equality as `==`. Types without it, such as `Map`, functions and imported types without approved equality, are rejected at compile time with `ELCI2003`. `Data` has it: `Expect.equal (Data.encode expected) datum` compares structure. `atMost`, `atLeast`, `lessThan` and `greaterThan` take any [comparable type](/reference/core-modules/#comparable-types), as in elm-test: integers, strings, bytes, ledger hashes, and lists and tuples of these.

### Failure reports

Values are printed as their Plutus Data encoding: integers as numbers, bytes and strings as `#hex`, lists as `[...]`, and records, tuples, `Bool`, `Maybe` and custom types as `Constr tag [fields]`.

| Expectation | Report |
| --- | --- |
| `equal` | `Expect.equal`, then `expected:` and `actual:` |
| `notEqual` | `Expect.notEqual`, then `values:` with the actual value |
| `atMost`, `atLeast`, `lessThan`, `greaterThan` | the function name, then `values:` with the bound and the actual value |
| `fail message` | the message |
| `onFail message e` | the message, then the values of `e`, if any |

## Scenario

```elm
import Scenario exposing (Scenario)
```

A scenario is data describing one transaction against a validator of the project. The runner compiles the named validator as `elci build` does, applies the scenario's parameters, builds the complete ledger context with julc-testkit and runs the deployed script. The validator's own address, minting policy and staking credential are derived from the applied script's hash. See [Transaction scenarios](/testing/scenarios/).

### Types

```elm
type Scenario
    = Scenario Spec


type alias Spec =
    { validator : String
    , purpose : Purpose
    , redeemer : Data
    , parameters : List Data
    , signers : List PubKeyHash
    , inputs : List Input
    , referenceInputs : List Input
    , outputs : List Output
    , minted : List Token
    , fee : Int
    , validFrom : Maybe Int
    , validBefore : Maybe Int
    , ownLovelace : Int
    , expectSuccess : Bool
    , budget : Maybe ( Int, Int )
    }


type Purpose
    = Spend TxOutRef (Maybe Data)
    | Mint
    | Withdraw
    | Certify Int TxCert
    | Vote Voter
    | Propose Int ProposalProcedure


type Policy
    = Own
    | Policy Bytes


type alias Token =
    { policy : Policy
    , name : Bytes
    , quantity : Int
    }


type Destination
    = ToScript
    | ToAddress Address
    | ToStakedScript StakingCredential


type alias Output =
    { destination : Destination
    , lovelace : Int
    , tokens : List Token
    , datum : OutputDatum
    , hashedDatum : Maybe Data
    }


type alias Input =
    { ref : TxOutRef
    , output : Output
    }
```

`Policy` is the policy of a token: `Own` is the validator's own script hash, `Policy bytes` any other policy id. `Destination` is where an output is paid: the validator's own address, any address, or the validator's own payment credential with a staking part. An output's `datum` is its inline datum or none; `hashedDatum` holds a datum by hash, which the runner turns into the datum's hash on the output and the datum among the transaction's witnesses. The ledger types `TxOutRef`, `TxCert`, `Voter`, `ProposalProcedure`, `Address`, `StakingCredential`, `OutputDatum` and `PubKeyHash` come from `Julc.Ledger`. Build scenarios with the functions below rather than the `Spec` record.

### Start a scenario

The first argument is the name of a `[validators.<name>]` table.

| Function | Signature | Transaction |
| --- | --- | --- |
| `spend` | `String -> TxOutRef -> Data -> Scenario` | spends the output at `ref`, held at the validator's address with an inline datum |
| `spendWithoutDatum` | `String -> TxOutRef -> Scenario` | spends the output at `ref`, held at the validator's address without a datum |
| `mint` | `String -> Scenario` | mints or burns under the validator's own policy; add tokens with `minting` |
| `withdraw` | `String -> Scenario` | withdraws from the validator's own staking credential |
| `certify` | `String -> Int -> TxCert -> Scenario` | publishes a certificate at an index |
| `vote` | `String -> Voter -> Scenario` | votes as the given voter |
| `propose` | `String -> Int -> ProposalProcedure -> Scenario` | submits a proposal at an index |

A new scenario has these defaults:

| Field | Default |
| --- | --- |
| redeemer | `unit`, the Data of `()` |
| parameters, signers, inputs, reference inputs, outputs, minted tokens | none |
| fee | 200000 lovelace |
| validity range | unbounded on both sides |
| lovelace in the spent output | 2000000 |
| expectation | the validator accepts; no budget limit |

### Describe the transaction

Each function takes the scenario last, so they chain with `|>`. Functions that set a value replace any earlier value; functions that add to a list append in call order.

| Function | Signature | Effect |
| --- | --- | --- |
| `redeemer` | `Data -> Scenario -> Scenario` | sets the redeemer |
| `parameters` | `List Data -> Scenario -> Scenario` | sets the deployment parameters, in the validator's declared order; all of them are required |
| `signedBy` | `PubKeyHash -> Scenario -> Scenario` | adds a required signer |
| `input` | `TxOutRef -> Output -> Scenario -> Scenario` | adds an input; an input at the spent reference replaces the default spent output, and must hold the datum the spend gives |
| `referenceInput` | `TxOutRef -> Output -> Scenario -> Scenario` | adds a reference input |
| `output` | `Output -> Scenario -> Scenario` | adds an output |
| `minting` | `Bytes -> Int -> Scenario -> Scenario` | mints (positive quantity) or burns (negative) a token under the own policy |
| `fee` | `Int -> Scenario -> Scenario` | sets the fee in lovelace |
| `validFrom` | `Int -> Scenario -> Scenario` | sets the inclusive lower bound of the validity range, in POSIX time |
| `validBefore` | `Int -> Scenario -> Scenario` | sets the exclusive upper bound of the validity range, in POSIX time |
| `worth` | `Int -> Scenario -> Scenario` | sets the lovelace held by the default spent output |

### Set the expectation

| Function | Signature | Effect |
| --- | --- | --- |
| `expectFailure` | `Scenario -> Scenario` | the validator must reject the transaction |
| `withinBudget` | `Int -> Int -> Scenario -> Scenario` | the execution must cost at most this many CPU steps and memory units, whether it succeeds or fails |

### Build outputs

| Function | Signature | Result |
| --- | --- | --- |
| `toScript` | `Int -> Output` | an output of this many lovelace to the validator's own address |
| `toStakedScript` | `StakingCredential -> Int -> Output` | an output of this many lovelace to the validator's own payment credential with a staking part, an address other than `toScript`'s |
| `toAddress` | `Address -> Int -> Output` | an output of this many lovelace to an address |
| `toKey` | `PubKeyHash -> Int -> Output` | an output of this many lovelace to the key's address, without a staking part |
| `withDatum` | `Data -> Output -> Output` | the output with an inline datum; replaces a datum set before |
| `withDatumHash` | `Data -> Output -> Output` | the output with the datum's hash, and the datum among the transaction's witnesses, where `Tx.datumOf` finds it; replaces a datum set before |
| `withToken` | `Policy -> Bytes -> Int -> Output -> Output` | the output with an added token: policy, name and quantity |

### Test data

| Function | Signature | Result |
| --- | --- | --- |
| `key` | `String -> PubKeyHash` | the BLAKE2b-224 hash of the name's UTF-8 bytes: a deterministic 28-byte test key |
| `outRef` | `String -> Int -> TxOutRef` | an output reference whose transaction id is the BLAKE2b-256 hash of the name's UTF-8 bytes |
| `unit` | `Data` | the Data of `()`, `Constr 0 []` |

### Scenario failure reports

| Report | Meaning |
| --- | --- |
| `validator <name> rejected the transaction: <error>` | the validator failed, and the scenario expected success |
| `validator <name> accepted a transaction expected to fail` | the validator succeeded after `expectFailure` |
| `Budget exceeded: ...` | the execution went over the `withinBudget` limits |
| `cannot prepare validator '<name>': ...` | the validator does not exist, or the scenario does not give all of its parameters; the scenario fails before it runs, even with `expectFailure` |
| `cannot build the transaction: ...` | the ledger could not produce the scenario's transaction, such as one with a token name longer than 32 bytes, or a listed spent output whose datum differs from the one `spend` gives; the scenario fails before it runs, even with `expectFailure` |

## Property

```elm
import Property exposing (Property)
```

### Types

```elm
type Property
```

`Property` is opaque: build it with the functions below.

### Functions

| Function | Signature | The property holds when |
| --- | --- | --- |
| `holds` | `Bool -> Property` | the condition is `True`; otherwise it fails with `the property does not hold` |
| `expect` | `Expectation -> Property` | the expectation passes; otherwise it fails with the expectation's report |
| `scenario` | `Scenario -> Property` | the scenario has its expected outcome: accepted, or rejected after `Scenario.expectFailure` |

### Property reports

A passing property reports its number of runs and the budget of its costliest run, as `(100 runs, max cpu ..., mem ...)`. A failing property reports the runs until the failure and the shrunk counterexample, printed as a call in Elci syntax, followed by the failure of that run:

```text
FAIL PricingProperties.quotesNeverLoseValue (15 runs, cpu 5409589, mem 21823)
    counterexample after 15 runs, shrunk 4 times:
        quotesNeverLoseValue (-334)
    the property does not hold
```

A property's parameters must be concrete, Data-encodable types. Functions, arrays, `ScriptContext` and type variables are rejected with `ELCI2009`. [Property-based tests](/testing/properties/) describes the generated values for each type.
