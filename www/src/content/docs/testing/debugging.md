---
title: Debugging and budgets
description: Read elci test failures, find out why a validator rejects a transaction with Debug.trace, and keep execution budgets in check with Scenario.withinBudget.
sidebar:
  order: 5
---

When a test fails, `elci test` tells you what it expected and what happened. In this page you learn to read those reports, to add traces that explain a rejection, and to use the CPU and memory numbers to keep your contracts cheap.

## Read a failure

Every failing test prints a `FAIL` line, then indented details, then any traces. The details depend on the kind of test:

| Kind of test | Typical detail |
| --- | --- |
| `Bool` | `evaluated to False` |
| `Expect.equal` | `expected:` and `actual:`, as Data |
| other `Expect` functions | the function name or your `onFail` message, then `values:` |
| runtime error in a test | `evaluation failed:` and the VM's error |
| scenario | `validator <name> rejected the transaction: ...` or `validator <name> accepted a transaction expected to fail` |
| scenario with `withinBudget` | `Budget exceeded: ...` |
| property | `counterexample after N runs, shrunk M times:`, the shrunk call, and the failure of that run |

[Unit tests](/testing/unit-tests/) explains how values are printed as Data, and [Property-based tests](/testing/properties/) how to read a counterexample.

Errors inside a test are reported with the VM's own message. Two examples from a test module `TraceTest` that imports `Julc.Data as Data`:

```elm
splitsEvenly : Bool
splitsEvenly =
    1000 // 0 == 0


decodesAnAmount : Expectation
decodesAnAmount =
    let
        amount : Int
        amount =
            Data.decode (Data.encode "ten")
    in
    Expect.equal 10 amount
```

```text
FAIL TraceTest.splitsEvenly (cpu 276441, mem 1001)
    evaluation failed: QuotientInteger: division by zero
FAIL TraceTest.decodesAnAmount (cpu 933360, mem 5469)
    evaluation failed: UnIData: not IntData
```

A failed `Data.decode` never becomes a `Maybe`: like a malformed datum on-chain, it stops evaluation.

## Why a validator rejected a transaction

These are the messages you meet most often in a failing scenario, and what they usually mean:

| Message | Usual cause |
| --- | --- |
| `rejected the transaction: Error term encountered` | a handler returned `False`, or the validator has no handler for the scenario's purpose |
| `rejected the transaction: UnConstrData: not ConstrData` | a datum or redeemer that does not match the handler's type, such as `Data.encode 5` for a union |
| `rejected the transaction: UnBData: not BytesData` | a value that should be bytes is something else, such as `Data.encode 5` given as a `PubKeyHash` parameter |
| `accepted a transaction expected to fail` | a check is missing or too loose |
| `cannot prepare validator 'markt': no [validators.markt] in elci.toml` | a misspelled validator name |
| `cannot prepare validator 'market': it takes 1 parameter [operator], but the scenario gives 0; set them with Scenario.parameters` | `Scenario.parameters` is missing or incomplete |

The first message is the common one, and the least helpful: a validator that returns `False` fails with an error, whichever check was false. Traces tell you which one.

## Debug.trace

`Debug.trace : String -> a -> a` returns its second argument unchanged and emits the message as a Plutus trace. As in Elm, every module imports `Debug`, so it needs no import.

Put a trace in the branch that rejects, so it only fires when that check fails. For the marketplace from [Transaction scenarios](/testing/scenarios/), move the deadline check of `Buy` in `src/Market.elm` into a function that traces when it fails:

```elm
        Buy ->
            sellerPaid listing ctx && endsBy listing.expires ctx
```

```elm
endsBy : Int -> ScriptContext -> Bool
endsBy time ctx =
    if Interval.isEntirelyBefore time ctx.txInfo.validRange then
        True

    else
        Debug.trace "the validity range does not end by the listing's expiry" False
```

Now remove `Scenario.expectFailure` from `buyingAfterExpiryFails` to see what the validator says about that late purchase:

```sh
elci test --match buyingAfter
```

```text
FAIL MarketTest.buyingAfterExpiryFails (cpu 33817513, mem 119115)
    validator market rejected the transaction: Error term encountered
    trace: the validity range does not end by the listing's expiry
0 passed, 1 failed
```

Traces are printed for failing tests only. Passing tests, including scenarios that are rejected as expected, stay quiet.

`Debug.trace` works in test modules too. There, it helps you see how far a computation got. The expected value below is wrong on purpose, so the test fails and prints its trace:

```elm
feeOfTwoThousand : Expectation
feeOfTwoThousand =
    let
        fee =
            Debug.trace "computing the fee of 2000" (Pricing.fee 2000)
    in
    Expect.equal 7 fee
```

```text
FAIL TraceTest.feeOfTwoThousand (cpu 2178706, mem 8698)
    Expect.equal
        expected: 7
        actual:   6
    trace: computing the fee of 2000
```

:::caution[Remove traces before you deploy]
Traces are part of the compiled script. They make it larger and cost budget whenever they run, and `elci build` does not strip them. The trace in `endsBy` adds 61 bytes to the `market` script: `elci check` reports 1672 FLAT bytes, against 1611 with `False` in its place. Remove your traces before you build for deployment, and rerun your tests.
:::

## Execution budgets

Every `PASS` and `FAIL` line shows the CPU steps and memory units of a run. What the numbers measure depends on the test:

- **`Bool` and `Expectation` tests:** the whole test program, including computing the expected value.
- **Scenarios:** the validator alone, running against the scenario's transaction. This is the cost you pay for on-chain.
- **Properties:** the costliest passing run, shown as `max cpu`; for a failure, the counterexample's run.

Protocol parameters cap the execution units of a transaction (on mainnet, currently 10 billion CPU steps and 14 million memory units), and the units you use add to the transaction fee. So scenario budgets are worth watching as your contract grows. [Costs and performance](/advanced/costs/) explains where the cost comes from and how to reduce it.

### Guard a budget with withinBudget

`Scenario.withinBudget cpu memory` makes a scenario fail when the validator needs more than the given CPU steps or memory units, whether it accepts or rejects the transaction:

```elm
delistingStaysCheap : Scenario
delistingStaysCheap =
    settle Delist
        |> Scenario.signedBy seller
        |> Scenario.withinBudget 17000000 70000
```

```text
PASS MarketTest.delistingStaysCheap (cpu 15573596, mem 65977)
```

Set the limits a little above today's numbers. A change that makes the path noticeably more expensive then fails the test instead of slipping through. With limits of `10000000` and `50000`, the same scenario fails:

```text
FAIL MarketTest.delistingStaysCheap (cpu 15573596, mem 65977)
    Budget exceeded: CPU steps 15573596 exceeds maximum 10000000; Memory units 65977 exceeds maximum 50000 (consumed: ExBudget{cpu=15573596, mem=65977})
```

The check is julc-testkit's `BudgetAssertions.assertBudgetUnder`, the same assertion you can use in [Java tests](/testing/java/).

## A debugging routine

When a scenario fails and the reason is not obvious:

1. Run only that test with `--match`, so the output is short.
2. Read the reason. An `accepted` failure means a check is missing; a decoding error means the data you built does not match the handler's types; `Error term encountered` means a check returned `False`.
3. Add a `Debug.trace` to each branch that can reject, and run the test again. The trace lines show which check failed.
4. If a helper computes the wrong value, move it into a module you can unit-test, and test it directly with `Expect.equal`.
5. Remove the traces and rerun the whole suite before you build for deployment.
