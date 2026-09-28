---
title: Property-based tests
description: State a rule that must hold for every input, let elci test generate hundreds of inputs, and get the smallest counterexample when the rule breaks.
sidebar:
  order: 3
---

A property is a rule that should hold for **every** input, such as "a buyer who pays less than the price is rejected". In this page you write properties for pure functions and for whole transactions, read a shrunk counterexample, and learn to shape generated inputs so they find real bugs.

## Why properties

A unit test or a scenario checks one input you thought of. The bugs that hurt in smart contracts usually sit at inputs nobody thought of: a negative amount, an empty byte string, a validity range that ends exactly at the deadline, a 64-bit number. A property describes the rule once. `elci test` then:

1. generates arguments from the property's parameter types;
2. runs the property 100 times, starting with small values;
3. when a run fails, shrinks the arguments to a small counterexample and reports it.

## Your first property

Take the `Pricing` module from [Unit tests](/testing/unit-tests/). Adding the fee to an amount should never give a total below the amount. Write that as a property in `tests/PricingProperties.elm`:

```elm title="tests/PricingProperties.elm"
module PricingProperties exposing (quotesNeverLoseValue)

import Pricing
import Property exposing (Property)


quotesNeverLoseValue : Int -> Property
quotesNeverLoseValue amount =
    Property.holds ((Pricing.quote amount).total >= amount)
```

A property is an exposed function whose result type is `Property`. Its parameters are the inputs to generate: here one `Int`. Run it:

```sh
elci test --seed 42 --match PricingProperties
```

```text
FAIL PricingProperties.quotesNeverLoseValue (15 runs, cpu 5409589, mem 21823)
    counterexample after 15 runs, shrunk 4 times:
        quotesNeverLoseValue (-334)
    the property does not hold
0 passed, 1 failed (seed 42)
```

The 15th run failed on a negative number, and the runner shrank it four times to `-334`. That is the negative amount closest to zero that breaks the rule: `-334 * 30 // 10000` is `-1`, because `//` truncates toward zero, so the total is `-335`. No amount between `-333` and `0` fails, which is why shrinking stopped there.

The counterexample is printed as an Elci call, so you can reproduce it directly in a unit test.

### State the real rule

The property found a real question: what should a quote for a negative amount be? A lovelace amount can never be negative, so the honest fix is to state that in the property. Use `modBy` to turn any generated integer into a valid amount:

```elm title="tests/PricingProperties.elm"
module PricingProperties exposing (quotesNeverLoseValue)

import Pricing
import Property exposing (Property)


{-| Any amount of lovelace up to the 45 billion ADA supply.
-}
quotesNeverLoseValue : Int -> Property
quotesNeverLoseValue raw =
    let
        amount =
            modBy 45000000000000000 raw
    in
    Property.holds ((Pricing.quote amount).total >= amount)
```

```text
PASS PricingProperties.quotesNeverLoseValue (100 runs, max cpu 4641008, mem 18984)
1 passed, 0 failed (seed 42)
```

For a passing property, the budget is the costliest of its runs. For a failing one, it is the counterexample's run.

## The Property module

`Property` has one type and three ways to build it:

| Function | The property holds when |
| --- | --- |
| `Property.holds condition` | the `Bool` is `True` |
| `Property.expect expectation` | the `Expectation` passes, so failures show `expected` and `actual` |
| `Property.scenario transaction` | the `Scenario` has its expected outcome, so a property can run your deployed validator |

The result type is what makes a function a property. An exposed function that returns a `Bool`, an `Expectation` or a `Scenario` is a helper, never a test, however many arguments it takes. So you can write helpers freely. The only helpers to keep unexposed are those that themselves return a `Property`, because an exposed function returning `Property` is run as a property.

A zero-argument value of type `Property` is allowed too. It runs once, like a unit test.

## Properties over transactions

`Property.scenario` runs a transaction against the deployed validator on every run. Here are four properties for the marketplace from [Transaction scenarios](/testing/scenarios/). The module repeats the scenario helpers; in a larger project, put them in a shared test module.

```elm title="tests/MarketProperties.elm"
module MarketProperties exposing
    ( buyersMustPayThePrice
    , buyingClosesAtExpiry
    , listingsRoundTrip
    , onlyTheSellerDelists
    )

import Expect
import Julc.Data as Data
import Julc.Ledger exposing (PubKeyHash)
import Market exposing (Action(..), Listing)
import Property exposing (Property)
import Scenario exposing (Output, Scenario)


seller : PubKeyHash
seller =
    Scenario.key "seller"


listing : Listing
listing =
    { seller = seller, price = 50000000, expires = 2000 }


settle : Action -> Scenario
settle action =
    Scenario.spend "market" (Scenario.outRef "listing" 0) (Data.encode listing)
        |> Scenario.parameters [ Data.encode (Scenario.key "operator") ]
        |> Scenario.redeemer (Data.encode action)


payment : Int -> Output
payment lovelace =
    Scenario.toKey seller lovelace
        |> Scenario.withDatum (Data.encode (Scenario.outRef "listing" 0))


{-| Succeeds when `ok`, and must be rejected otherwise.
-}
expecting : Bool -> Scenario -> Property
expecting ok transaction =
    if ok then
        Property.scenario transaction

    else
        Property.scenario (Scenario.expectFailure transaction)


listingsRoundTrip : Listing -> Property
listingsRoundTrip generated =
    Property.expect (Expect.equal generated (Data.decode (Data.encode generated)))


{-| Whatever a listing holds, only its seller can delist it.
-}
onlyTheSellerDelists : Listing -> Bool -> Property
onlyTheSellerDelists generated bySeller =
    let
        signer =
            if bySeller then
                generated.seller

            else
                Scenario.key "stranger"
    in
    Scenario.spend "market" (Scenario.outRef "listing" 0) (Data.encode generated)
        |> Scenario.parameters [ Data.encode (Scenario.key "operator") ]
        |> Scenario.redeemer (Data.encode Delist)
        |> Scenario.signedBy signer
        |> expecting bySeller


buyersMustPayThePrice : Int -> Property
buyersMustPayThePrice shortBy =
    settle Buy
        |> Scenario.output (payment (listing.price - shortBy))
        |> Scenario.validBefore 1500
        |> expecting (shortBy <= 0)


buyingClosesAtExpiry : Int -> Property
buyingClosesAtExpiry offset =
    let
        end =
            listing.expires + offset
    in
    settle Buy
        |> Scenario.output (payment listing.price)
        |> Scenario.validBefore end
        |> expecting (end <= listing.expires)
```

- `listingsRoundTrip` checks that any `Listing` survives encoding and strict decoding, the path every datum takes into your script.
- `onlyTheSellerDelists` generates whole listings and signs either with the listing's own seller or with a stranger. The validator must accept exactly the first case.
- `buyersMustPayThePrice` pays the seller `shortBy` lovelace less than the price. Any shortfall above zero must be rejected, and paying the price or more must succeed.
- `buyingClosesAtExpiry` ends the validity range `offset` milliseconds from the listing's expiry.

`expecting` is the helper that turns "should this succeed?" into the right scenario. It returns a `Property`, so it stays unexposed.

```sh
elci test --seed 7 --match MarketProperties
```

```text
PASS MarketProperties.listingsRoundTrip (100 runs, max cpu 11509152, mem 46989)
PASS MarketProperties.onlyTheSellerDelists (100 runs, max cpu 15953522, mem 67909)
PASS MarketProperties.buyersMustPayThePrice (100 runs, max cpu 33518015, mem 117583)
PASS MarketProperties.buyingClosesAtExpiry (100 runs, max cpu 33518015, mem 117583)
4 passed, 0 failed (seed 7)
```

### A property catches a bug

Suppose someone changes `sellerPaid` in `src/Market.elm` to accept payments 100 lovelace short of the price, `(listing.price - 100)`. The property finds the boundary on its own:

```text
FAIL MarketProperties.buyersMustPayThePrice (1 run, cpu 33683223, mem 117985)
    counterexample after 1 run, shrunk 5 times:
        buyersMustPayThePrice 1
    validator market accepted a transaction expected to fail
0 passed, 1 failed (seed 7)
```

Paying 1 lovelace short is accepted. The `underpayingFails` scenario catches this particular bug as well, but only because it underpays by exactly 1 lovelace. The property does not depend on you picking the right example.

### A property finds an edge case

The first version of `buyingClosesAtExpiry` stated the rule as "succeed when the range ends before the expiry", a negative offset:

```elm
buyingClosesAtExpiry : Int -> Property
buyingClosesAtExpiry offset =
    settle Buy
        |> Scenario.output (payment listing.price)
        |> Scenario.validBefore (listing.expires + offset)
        |> expecting (offset < 0)
```

```text
FAIL MarketProperties.buyingClosesAtExpiry (1 run, cpu 33518015, mem 117583)
    counterexample after 1 run:
        buyingClosesAtExpiry 0
    validator market accepted a transaction expected to fail
0 passed, 1 failed (seed 7)
```

An offset of `0` ends the range at the expiry, and the validator accepts it. The validator is right: `validBefore` is exclusive, so a transaction valid before `2000` can't run at `2000`, and every time it can run at is before the expiry. The final version states the complete rule: `end <= listing.expires`.

## Seeds and runs

The summary line of a run with properties shows its seed. Without `--seed`, the seed is random, so each run explores new inputs:

```text
8 passed, 0 failed (seed -2828489748881525870)
```

Two options control properties:

- `--seed <n>` reproduces a run exactly. Each property derives its own seed from `n` and its name, so a property sees the same inputs whatever `--match` selects. Pass the seed from a failing run to reproduce the failure.
- `--fuzz <runs>` changes the number of runs per property. The default is 100.

```sh
elci test --seed 42 --fuzz 1000 --match PricingProperties
```

```text
PASS PricingProperties.quotesNeverLoseValue (1000 runs, max cpu 4641008, mem 18984)
1 passed, 0 failed (seed 42)
```

## Generated values

Every generated argument is a valid value of its type. It is produced as Plutus Data and decoded by the same strict decoder a validator uses for its datum and redeemer, so a property sees exactly what the ledger could send.

| Type | Generated values | Shrinks toward |
| --- | --- | --- |
| `Int` | mostly small values around 0, the edge cases 0, 1 and -1, and some values up to 64 bits | `0` |
| `Bytes`, `String` | up to 64 bytes, sometimes exactly 28 or 32 (hash sizes) | `#(empty)`, `""` |
| `PubKeyHash`, `ScriptHash`, `ValidatorHash`, `PolicyId` parameter | exactly 28 bytes | 28 zero bytes |
| `TxId`, `DatumHash` parameter | exactly 32 bytes | 32 zero bytes |
| `TokenName` parameter | 0 to 32 bytes | `#(empty)` |
| `List a`, `Map k v` | grow with the run; map keys are distinct | shorter lists and maps |
| `Dict k v` | grow with the run; keys are sorted and distinct, as decoding requires | smaller dictionaries, still sorted |
| `Value` | canonical values: often some lovelace, and a few tokens under 28-byte policies; quantities may be negative, never zero | fewer policies, down to the empty value |
| records, tuples | built from their fields | each field shrinks |
| unions, `Maybe` | every constructor is used | earlier constructors, such as `Nothing` |
| recursive unions | always finite; the first runs use the shallowest constructors | their own subterms |

The exact lengths apply to a **parameter** of a hash type. A hash inside a record, such as a listing's `seller`, follows its Data representation and can have any length, including zero.

Counterexamples are printed in Elci syntax. Byte strings are hex, record fields appear in alphabetical order, and constructors appear without their module name. Some shrunk counterexamples from failing properties:

| Property parameters | Counterexample |
| --- | --- |
| `Listing -> Bool` | `onlyTheSellerDelists { expires = 0, price = 0, seller = #(empty) } False` |
| `Action -> Maybe Int -> ( Int, Bool )` | `unions Buy Nothing ( 0, False )` |
| `Map Int Bytes`, failing for two or more entries | `maps (Map.fromList [( 0, #(empty) ), ( 1, #(empty) )])` |
| `Shape`, a recursive union with constructors `Dot`, `Line Int` and `Pair Shape Shape` | `smallShapes (Pair (Pair Dot Dot) Dot)` |

## Shape the inputs

Generated integers cluster around 0, and shrinking moves toward 0. Design your parameters so that 0 sits where the interesting behavior is.

**Generate an offset from the boundary.** `buyersMustPayThePrice` takes a shortfall and `buyingClosesAtExpiry` an offset from the expiry, rather than a price or a time. The boundary between success and failure is then at 0, where most generated values fall, and a counterexample shrinks to the smallest step across it, such as `buyersMustPayThePrice 1`.

**Bound values with `modBy`.** When the rule only makes sense in a range, such as non-negative amounts, transform the raw argument with `modBy` as in `quotesNeverLoseValue`. `modBy n` always gives a result from 0 to `n - 1`.

Prefer offsets when a boundary matters. With `end = modBy 4000 raw` instead of an offset, a validator bug that accepted purchases up to 50 ms after the expiry passed in 9 of 10 seeds: the generated values rarely land in a 50-wide window. The offset version reported `buyingClosesAtExpiry 1` on its first failing run.

:::note[Counterexamples show the raw argument]
Arguments are shrunk before your transformation runs, and the report shows the generated argument, not the transformed value. For a property that computes `amount = 1000 + modBy 1000000 raw`, a failure is reported as `bigOrdersPayAtLeastFour 0` although the property checked `amount = 1000`. Apply the same transformation to read it.
:::

## Limits

A property's parameters must be concrete, Data-encodable types. `elci test` rejects others before running anything. For `applies : (Int -> Int) -> Property` it reports:

```text
tests/Lim.elm:6:1: ELCI2009: Property applies needs Data-encodable parameters to generate; functions, arrays and ScriptContext cannot be generated (take a List and use Array.fromList)

6| applies : (Int -> Int) -> Property
   ^^^^^^^
```

- **Functions** cannot be generated.
- **Arrays** have no Data encoding. Take a `List` and convert it with `Array.fromList` inside the property.
- **`ScriptContext`** cannot be generated. Use `Property.scenario` to run the validator in a complete context instead.
- **Type variables** must be concrete: `anything : a -> Property` is rejected with `ELCI2009: Property anything needs concrete parameter types; add a type annotation`.

Generation and shrinking come from julc-testkit's `DataGenerators` and `PropertyCheck`. They are seeded and need no reflection, so the native `elci` binary runs properties too. For property tests driven from Java with jqwik, see [Testing from Java](/testing/java/).
