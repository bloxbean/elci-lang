# Unit tests

> Write Bool and Expect tests for your Elci code, run them with elci test, read the results and choose which tests run.

Source: https://elci-lang.dev/testing/unit-tests/

In this page you write your first tests in Elci, run them with `elci test` and learn to read what it prints. Unit tests are the quickest way to check the pure functions your validators rely on, such as fee calculations or datum helpers.

Every test compiles to its own small program and runs on the same Plutus VM that evaluates your contracts. So a test tells you both whether your code is right and what it costs in CPU and memory.

## A function to test

Start from a project made by `elci init shop`. It already contains a validator, `src/Main.elm`, and two tests for it in `tests/MainTest.elm`. Add a module that computes a 0.3% trading fee:

```elm title="src/Pricing.elm"
module Pricing exposing (Quote, fee, quote)

type alias Quote =
    { amount : Int
    , fee : Int
    , total : Int
    }

{-| A 0.3% fee (30 basis points), rounded down.
-}
fee : Int -> Int
fee amount =
    amount * 30 // 10000

quote : Int -> Quote
quote amount =
    { amount = amount
    , fee = fee amount
    , total = amount + fee amount
    }
```

`//` is integer division that truncates toward zero, so small amounts pay no fee at all. That is exactly the kind of detail worth pinning down with a test.

## Your first tests

Tests live in modules under `tests/`. Create `tests/PricingTest.elm`:

```elm title="tests/PricingTest.elm"
module PricingTest exposing
    ( feeOnTenThousand
    , feesRoundDown
    , feesStayUnderOnePercent
    , quotesAddTheFee
    , smallAmountsAreFree
    )

import Expect exposing (Expectation)
import Pricing

smallAmountsAreFree : Bool
smallAmountsAreFree =
    Pricing.fee 300 == 0

feeOnTenThousand : Expectation
feeOnTenThousand =
    Expect.equal 30 (Pricing.fee 10000)

quotesAddTheFee : Expectation
quotesAddTheFee =
    Expect.equal { amount = 10000, fee = 30, total = 10030 } (Pricing.quote 10000)

feesRoundDown : Expectation
feesRoundDown =
    Expect.every
        [ Expect.equal 0 (Pricing.fee 333)
        , Expect.equal 1 (Pricing.fee 334)
        , Expect.equal 1 (Pricing.fee 666)
        ]

feesStayUnderOnePercent : Expectation
feesStayUnderOnePercent =
    Expect.lessThan 10000 (Pricing.fee 1000000)
        |> Expect.onFail "a fee must stay under 1% of the amount"
```

There are two kinds of unit test here:

- A **`Bool` test** passes when it evaluates to `True`. It is the simplest possible check.
- An **`Expectation` test** uses the `Expect` module. When it fails, it tells you what it expected and what it got, which a `Bool` cannot.

The project now looks like this:

- shop/
  - elci.toml
  - src/
    - Main.elm the validator from elci init
    - Pricing.elm
  - tests/
    - MainTest.elm scenarios from elci init
    - PricingTest.elm

## Run the tests

Run `elci test` from the project directory, or pass the directory as an argument:

```sh
elci test
```

```text
PASS MainTest.ownerSigns (cpu 7675163, mem 29990)
PASS MainTest.othersAreRejected (cpu 8053596, mem 31922)
PASS PricingTest.smallAmountsAreFree (cpu 675727, mem 2604)
PASS PricingTest.feeOnTenThousand (cpu 929121, mem 4068)
PASS PricingTest.quotesAddTheFee (cpu 4498493, mem 12685)
PASS PricingTest.feesRoundDown (cpu 5947066, mem 24719)
PASS PricingTest.feesStayUnderOnePercent (cpu 1794288, mem 8097)
7 passed, 0 failed
```

Each line names the test as `Module.name` and shows the CPU steps and memory units its evaluation consumed. Modules run in alphabetical order, and the tests of a module in source order. The two `MainTest` tests are [transaction scenarios](https://elci-lang.dev/testing/scenarios/): for those, the numbers are the cost of the validator itself.

`elci test` only reads your project. It never writes `build/` and its tests never become part of `elci check`, `elci build` or the published scripts.

## When a test fails

To see failures, break three tests on purpose. Check `Pricing.fee 400 == 0` in the `Bool` test, expect `31` in `feeOnTenThousand`, and expect a `total` of `10031` in `quotesAddTheFee`:

```text
FAIL PricingTest.smallAmountsAreFree (cpu 675727, mem 2604)
    evaluated to False
FAIL PricingTest.feeOnTenThousand (cpu 1991208, mem 7866)
    Expect.equal
        expected: 31
        actual:   30
FAIL PricingTest.quotesAddTheFee (cpu 5657982, mem 17219)
    Expect.equal
        expected: Constr 0 [10000, 30, 10031]
        actual:   Constr 0 [10000, 30, 10030]
PASS PricingTest.feesRoundDown (cpu 5947066, mem 24719)
PASS PricingTest.feesStayUnderOnePercent (cpu 1794288, mem 8097)
2 passed, 3 failed
```

The `Bool` test can only say that it evaluated to `False`. The expectations show both values.

Values in a failure are printed as their **Plutus Data encoding**, because that is the form the test program returns from the VM. The encoding follows the rules of your datums and redeemers:

| Elci value | Printed as |
| --- | --- |
| `30`, `-5` | `30`, `-5` |
| `Bytes.fromString "GOLD"` and the `String` `"GOLD"` | `#474f4c44` (hex of the UTF-8 bytes) |
| `False`, `True` | `Constr 0`, `Constr 1` |
| `Nothing`, `Just 5` | `Constr 0`, `Constr 1 [5]` |
| `[ 1, 2 ]` | `[1, 2]` |
| `( 1, True )` | `Constr 0 [1, Constr 1]` |
| a record | `Constr 0 [...]`, with the fields in alphabetical order of their names |
| a custom type | `Constr tag [...]`, where the tag is the constructor's position, from 0 |

So `Constr 0 [10000, 30, 10031]` is the `Quote` record with fields `amount`, `fee` and `total`.

A test can also fail at runtime, for example by dividing by zero. It is then reported with the VM's error, such as `evaluation failed: QuotientInteger: division by zero`. [Debugging and budgets](https://elci-lang.dev/testing/debugging/) covers these messages and how to add traces.

## The Expect module

`Expect` has a small set of functions in the style of elm-test. Import it with `import Expect exposing (Expectation)`.

| Function | Passes when |
| --- | --- |
| `Expect.equal expected actual` | the two values are structurally equal |
| `Expect.notEqual unexpected actual` | the two values differ |
| `Expect.atMost bound actual` | `actual <= bound` |
| `Expect.atLeast bound actual` | `actual >= bound` |
| `Expect.lessThan bound actual` | `actual < bound` |
| `Expect.greaterThan bound actual` | `actual > bound` |
| `Expect.pass` | always |
| `Expect.fail message` | never; the failure shows `message` |
| `Expect.every [ e1, e2 ]` | every expectation passes; the first failure is reported |
| `Expect.onFail message expectation` | `expectation` passes; otherwise its message is replaced |

The comparisons work on any comparable values, such as integers, strings and bytes. They take the bound first and the actual value last, so they read well in a pipeline. When one fails, the report lists the bound and then the actual value:

```elm
feesAreCapped : Expectation
feesAreCapped =
    Expect.atMost 100 (Pricing.fee 50000)
```

```text
FAIL PricingTest.feesAreCapped (cpu 2126712, mem 8766)
    Expect.atMost
        values: 100, 150
```

`Expect.onFail` puts your own explanation in place of the function name:

```elm
feesAreCappedExplained : Expectation
feesAreCappedExplained =
    Expect.atMost 100 (Pricing.fee 50000)
        |> Expect.onFail "fees are capped at 100 lovelace"
```

```text
FAIL PricingTest.feesAreCappedExplained (cpu 5046289, mem 18138)
    fees are capped at 100 lovelace
        values: 100, 150
```

`Expect.every` combines several checks into one test and reports the first that fails. Its argument is a list of evaluated expectations: Elci lists hold data, not functions, so there is no elm-test style `Expect.all` that takes checks as functions.

:::note
`Expect.equal` and `Expect.notEqual` need the same structural equality as `==`. They accept numbers, bytes, strings, `Data`, lists, tuples, records and custom types, and reject `Map`, functions and imported types without approved equality at compile time.
:::

## How tests are discovered

`elci test` treats a declaration in a test module as a test when it is:

1. **exposed** by its module,
2. a **top-level** declaration with **no arguments**, and
3. of type `Bool`, `Expect.Expectation`, `Scenario.Scenario` or `Property.Property`.

An exposed **function** whose result is a `Property` is also a test: a [property-based test](https://elci-lang.dev/testing/properties/) that runs many times with generated arguments.

Everything else is a helper. That includes functions that return an `Expectation`, a `Scenario` or a `Bool`, such as a function that builds a transaction for a given signer. With an explicit `exposing` list you choose exactly which declarations are tests. With `exposing (..)`, every zero-argument declaration of a test type runs, and declarations of other types, such as a test key of type `PubKeyHash`, are skipped.

## Choose which tests run

`--match` runs only the tests whose full name contains a substring. The match ignores case and includes the module name:

```sh
elci test --match fee
```

```text
PASS PricingTest.feeOnTenThousand (cpu 929121, mem 4068)
PASS PricingTest.quotesAddTheFee (cpu 4498493, mem 12685)
PASS PricingTest.feesRoundDown (cpu 5947066, mem 24719)
PASS PricingTest.feesStayUnderOnePercent (cpu 1794288, mem 8097)
4 passed, 0 failed
```

`quotesAddTheFee` matches because its name contains `Fee`. Use `--match PricingTest` to run one module.

## Where tests live

By default, test modules are the `.elm` files under `tests/`. To use other directories, list them in `elci.toml`:

```toml title="elci.toml"
[tests]
source-directories = ["tests", "spec"]
```

A few rules apply to test modules:

- **Imports.** A test module can import your project's modules, the Java modules configured in `[java-modules]`, the test library (`Expect`, `Scenario` and `Property`) and other test modules. A shared module such as `tests/Fixtures.elm` is a good home for test keys and sample datums. Source modules cannot import the test library: `elci test` rejects such an import with `ELCI2101`.
- **Names.** A test module cannot have the same name as a source module, or as `Expect`, `Scenario` or `Property`. `elci test` stops with `ELCI4001: module Pricing is both a source and a test module` if you try.
- **Isolation.** Test modules are compiled only by `elci test`. They cannot change what `elci build` produces.

## Use elci test in CI

`elci test` exits with status `1` when any test fails, and also when no test is found or none matches `--match`:

```text
No tests match 'nothing-here'
```

Otherwise it exits with `0`. You can run it as a step in any CI pipeline without extra configuration.

## Next steps

- Test whole transactions against your validators with [transaction scenarios](https://elci-lang.dev/testing/scenarios/).
- Check a rule for many generated inputs with [property-based tests](https://elci-lang.dev/testing/properties/).
- Look up every function in the [test library reference](https://elci-lang.dev/reference/test-library/).
