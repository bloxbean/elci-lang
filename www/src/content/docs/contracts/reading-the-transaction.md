---
title: Reading the transaction
description: What a handler can read from the ScriptContext, the ledger types behind it, and verified recipes with the Tx and Interval modules for signers, validity ranges, inputs, payments, datums and minting, including the defence against double satisfaction.
sidebar:
  order: 2
---

The last argument of every handler is the `ScriptContext`: the whole transaction being validated, as the ledger describes it. This page lists what you can read from it, explains the ledger types, and gives small recipes for the checks most contracts need, written with the core `Tx` and `Interval` modules.

## The script context

`ScriptContext` is a record with three fields:

| Field | Type | Contents |
| --- | --- | --- |
| `ctx.txInfo` | `TxInfo` | The transaction: inputs, outputs, signers, validity range, minted value and more |
| `ctx.scriptInfo` | `ScriptInfo` | Why this script is running, with the purpose's details (the spent output reference, the policy id, ...) |
| `ctx.redeemer` | `Data` | The raw redeemer. You rarely need it: the handler already receives it decoded |

Import the types you mention by name from `Julc.Ledger`:

```elm
import Julc.Ledger exposing (Credential(..), PubKeyHash, ScriptContext, TxOut)
```

Exposing a type with `(..)`, as in `Credential(..)`, also exposes its constructors. `import Julc.Ledger exposing (..)` exposes the types only; you then write constructors qualified, such as `Ledger.PubKeyCredential` after `import Julc.Ledger as Ledger`.

## Transaction fields

These are the fields of `ctx.txInfo`, with the Elci types the compiler gives them:

| Field | Type | Contents |
| --- | --- | --- |
| `inputs` | `List TxInInfo` | The outputs this transaction spends |
| `referenceInputs` | `List TxInInfo` | Outputs it reads without spending |
| `outputs` | `List TxOut` | The outputs it creates, in order |
| `fee` | `Int` | The fee, in lovelace |
| `mint` | `Value` | Tokens minted (positive) and burned (negative) |
| `certificates` | `List TxCert` | Certificates it publishes |
| `withdrawals` | `Map Credential Int` | Staking rewards withdrawn, in lovelace |
| `validRange` | `Interval` | The POSIX time range (milliseconds) in which the transaction is valid |
| `signatories` | `List PubKeyHash` | The key hashes of its required signers |
| `redeemers` | `Map ScriptPurpose Data` | The redeemer of every script it runs |
| `datums` | `Map DatumHash Data` | Datums supplied in the witness set |
| `id` | `TxId` | The transaction's id |
| `votes` | `Map Voter (Map GovernanceActionId Vote)` | Governance votes |
| `proposalProcedures` | `List ProposalProcedure` | Governance proposals |
| `currentTreasuryAmount` | `JulcOptional Int` | The treasury amount it states, if any |
| `treasuryDonation` | `JulcOptional Int` | Its treasury donation, if any |

`JulcOptional` is Julc's optional value. Convert it to a `Maybe` with `Julc.Data.fromOptional` before matching on it:

```elm
import Julc.Data as Data

donation : ScriptContext -> Maybe Int
donation ctx =
    Data.fromOptional ctx.txInfo.treasuryDonation
```

`Map` fields work with the bundled `Map` module (`import Map`). They are the ledger's own maps: their keys,
such as `Credential` and `ScriptPurpose`, are not comparable, so they are not `Dict`s.

## Ledger types

The records and custom types you meet while walking the context:

| Type | Shape |
| --- | --- |
| `TxInInfo` | `{ outRef : TxOutRef, resolved : TxOut }`: a spent output and where it came from |
| `TxOut` | `{ address : Address, value : Value, datum : OutputDatum, referenceScript : JulcOptional ScriptHash }` |
| `TxOutRef` | `{ txId : TxId, index : Int }`: a transaction id and an output index |
| `Address` | `{ credential : Credential, stakingCredential : JulcOptional StakingCredential }` |
| `Credential` | `PubKeyCredential PubKeyHash` or `ScriptCredential ScriptHash` |
| `StakingCredential` | `StakingHash Credential` or `StakingPtr Int Int Int` |
| `OutputDatum` | `NoOutputDatum`, `OutputDatumHash DatumHash` or `OutputDatumInline Data` |
| `Interval` | `{ from : IntervalBound, to : IntervalBound }` |
| `IntervalBound` | `{ boundType : IntervalBoundType, isInclusive : Bool }` |
| `IntervalBoundType` | `NegInf`, `Finite Int` or `PosInf` |
| `ScriptInfo` | `MintingScript PolicyId`, `SpendingScript TxOutRef (JulcOptional Data)`, `RewardingScript Credential`, `CertifyingScript Int TxCert`, `VotingScript Voter` or `ProposingScript Int ProposalProcedure` |

Hashes have their own types: `PubKeyHash`, `ScriptHash`, `ValidatorHash`, `PolicyId`, `TokenName`, `TxId` and `DatumHash`. They are byte strings underneath, but the compiler keeps them apart, so a key hash can't be mistaken for arbitrary bytes. This helper takes the key as `Bytes`:

```elm
signedByBytes : Bytes -> ScriptContext -> Bool
signedByBytes key ctx =
    List.any (\signer -> signer == key) ctx.txInfo.signatories
```

```text
elci: .../my-project/elci.toml:9: [validators.main]
.../my-project/src/Main.elm:8:51: ELCI2002: Type mismatch: expected Bytes, found PubKeyHash
```

Take a `PubKeyHash` instead, or use the `.hash` field to get the bytes when you really need them: `policy.hash` for a `PolicyId`, and `token.hash` for a `TokenName`, which has no other field.

What you can do with a ledger type is decided per type:

- **Fields**: read record fields with `.field`, including nested ones such as `output.address.credential`.
- **Patterns and constructors**: match on custom types in `case`, and build values such as `Ledger.TxOutRef txId 0`.
- **Equality**: `==` and `/=` work on hashes, `TxOutRef`, `Credential`, `Address`, `Interval`, `OutputDatum`, `ScriptInfo`, `Value`, `Data` and the other plain ledger types. A `JulcOptional` has no equality: convert it with `Data.fromOptional` and compare the `Maybe`.
- **Records that contain a `Value`** (`TxOut`, `TxInInfo`, `TxInfo`) have no equality: compare their fields. Neither they nor a `Value` can be a datum, redeemer or parameter.
- **`Value` and `Interval` have their own modules.** Read, build and compare values with the core [`Value`](/reference/core-modules/#value) module and time ranges with the [`Interval`](/reference/core-modules/#interval) module, both shown below.

## Recipes

The core `Tx` and `Interval` modules answer the questions most contracts ask. They need an import and no
configuration:

```elm
import Interval
import Tx
```

Each recipe is a check to use in a handler, or a small function to put next to it. The
[core modules](/reference/core-modules/#tx) reference lists every function with its cost. Test the rules you
build from them with [transaction scenarios](/testing/scenarios/).

### Who signed

`signatories` lists the key hashes that must sign the transaction. `Tx.signedBy` searches it:

```elm
Tx.signedBy owner ctx
```

### When the transaction runs

A script can't read the clock. Instead, the transaction declares a validity range and the ledger only
accepts it inside that range, so the script reasons about the range with the `Interval` module:

| Check | Holds when |
| --- | --- |
| `Interval.isEntirelyAfter deadline ctx.txInfo.validRange` | the transaction can't run at or before `deadline` |
| `Interval.isEntirelyBefore deadline ctx.txInfo.validRange` | the transaction can't run at or after `deadline` |
| `Interval.contains time ctx.txInfo.validRange` | `time` lies in the range |

The two deadline checks never hold on an infinite bound. A transaction without a validity range could run
at any time, so it fails both. "After" and "before" are strict, and the checks honor inclusive and
exclusive bounds. A scenario's `Scenario.validFrom t` is inclusive, so `isEntirelyAfter deadline` accepts
`validFrom (deadline + 1)` but not `validFrom deadline`; check `isEntirelyAfter (deadline - 1)` to accept a
transaction from the deadline itself. `Scenario.validBefore t` is exclusive, so `isEntirelyBefore deadline`
accepts `validBefore deadline`. The [vesting tutorial](/tutorials/vesting/) builds a deadline check step by
step.

### Which outputs are spent

`inputs` holds every spent output with its reference. `Tx.spends` asks whether the transaction spends a
particular output, and `Tx.findInput` returns it as a `Maybe TxInInfo`:

```elm
Tx.spends ref ctx
```

Reference inputs are read, not spent, so they don't count; they are in `ctx.txInfo.referenceInputs`. The
[one-shot minting policy](/tutorials/one-shot-policy/) is built on this check.

### The output being spent

When spending, `Tx.ownRef ctx` is the reference of the output being validated, and `Tx.ownInput ctx` is that
output with its address, value and datum. For other purposes both are `Nothing`:

```elm
ownLovelace : ScriptContext -> Int
ownLovelace ctx =
    case Tx.ownInput ctx of
        Just own ->
            Value.lovelaceOf own.resolved.value

        Nothing ->
            0
```

`Tx.continuingOutputs ctx` lists the outputs paid back to the spent output's address, staking part
included: the outputs that keep funds at the script.

### Payments

`Tx.outputsTo credential ctx` lists the outputs paid to a key or a script, in order, and
`Tx.valuePaidTo credential ctx` adds up their value:

```elm
Tx.outputsTo (PubKeyCredential seller) ctx
```

Both match the address's payment credential and ignore its staking part: whoever builds the transaction
chooses the payee's staking part. So a payment to the seller's key counts whatever staking credential it
carries. When the staking part matters, store the payee's full `Address` and compare `output.address`.
Before you check a total, read [one payment per input](#one-payment-per-input).

### The script's own policy id

When minting, `Tx.ownPolicy ctx` is the policy id, which is the script's own hash, and
`Tx.mintedOf policy ctx` holds the tokens minted (positive) and burned (negative) under it, as a
`Dict TokenName Int`:

```elm
{-| The transaction mints exactly one BADGE under this policy, and nothing else under it.
-}
mintsOneBadge : ScriptContext -> Bool
mintsOneBadge ctx =
    case Tx.ownPolicy ctx of
        Just policy ->
            Dict.toList (Tx.mintedOf policy ctx) == [ ( TokenName (Bytes.fromString "BADGE"), 1 ) ]

        Nothing ->
            False
```

Comparing the whole dictionary also rejects a transaction that mints a second token name under the policy.
Build a token name from bytes after `import Julc.Ledger exposing (TokenName(..))`. Like the other ledger
hashes, a token name exposes its bytes as its `.hash` field.

### Amounts and tokens

An output's `value` and the transaction's `mint` are `Value`s: lovelace and quantities of native tokens.
Read them with the core `Value` module (`import Value`). A token is named by a `PolicyId` and a `TokenName`.

| Function | Returns |
| --- | --- |
| `Value.lovelaceOf value` | the lovelace in `value` |
| `Value.quantityOf policy name value` | the quantity of one token; negative in a mint that burns it, `0` if absent |
| `Value.tokens policy value` | the tokens of one policy and their quantities, as a `Dict TokenName Int` |
| `Value.geq value required` | whether `value` holds at least as much of every token as `required` |
| `Value.isZero value` | whether `value` holds nothing |

Values have `==`, and it is exact: two values are equal when they hold the same quantities. So
`output.value == Value.lovelace 2000000` checks that an output holds exactly 2 ada and nothing else.
`Value.positive` and `Value.negative` split a mint into what it mints and what it burns. The
[core modules](/reference/core-modules/#value) reference lists every function.

### Datums on other outputs

An output's datum arrives as `OutputDatum`. `Tx.inlineDatum output` is its inline datum, as `Maybe Data`.
`Tx.datumOf output ctx` also finds a datum given by its hash among the datums the transaction supplies.
Decode the `Data` into your own type with `Julc.Data.decode`, annotating the result type. Decoding is
strict, so a datum of the wrong shape fails the whole script rather than producing `Nothing`:

```elm
type alias Order =
    { owner : PubKeyHash
    , price : Int
    }


orderOf : TxOut -> Maybe Order
orderOf output =
    Maybe.map Data.decode (Tx.inlineDatum output)
```

To check that an output carries a datum you expect, compare encodings. `==` on `Data` compares structure,
and nothing is decoded, so a datum of another shape makes the check `False`:

```elm
Tx.inlineDatum output == Just (Data.encode expected)
```

Off-chain code must then build the datum exactly as Elci encodes it, with a record's fields in name order.
The validator's `abi.json` records the layout.

### Withdrawals

`withdrawals` is a map from credentials to lovelace:

```elm
withdrawn : Credential -> ScriptContext -> Int
withdrawn credential ctx =
    case Map.get credential ctx.txInfo.withdrawals of
        Just amount ->
            amount

        Nothing ->
            0
```

### One payment per input

A check such as "some output pays the seller the price" does not say which input the output pays for. When
a transaction spends two outputs of your script that are both for the same seller, the script runs once for
each, and both runs find the same payment. The seller is paid once for two sales. This is the
**double-satisfaction** attack. There are two defences.

**Tag each payment** with the output reference of the input it pays for, as its inline datum. An output has
one datum, so each input needs its own payment:

```elm
paidFor : PubKeyHash -> Int -> ScriptContext -> Bool
paidFor seller price ctx =
    case Tx.ownRef ctx of
        Just ref ->
            List.any
                (\output ->
                    (Value.lovelaceOf output.value >= price)
                        && (Tx.inlineDatum output == Just (Data.encode ref))
                )
                (Tx.outputsTo (PubKeyCredential seller) ctx)

        Nothing ->
            False
```

**Allow one input of the script per transaction** when an output can't carry a tag, for example an output
that returns funds to the script under the script's own datum:

```elm
onlyScriptInput : TxInInfo -> ScriptContext -> Bool
onlyScriptInput own ctx =
    List.length
        (List.filter (\input -> input.resolved.address.credential == own.resolved.address.credential)
            ctx.txInfo.inputs
        )
        == 1
```

Call it with the input from `Tx.ownInput ctx`. When funds return to the script, check the whole value that
stays, not only the token you care about: an output that keeps the tokens but not the lovelace lets the
spender take the rest. `Value.geq output.value expected` requires at least `expected`. To also stop a
spender from padding the output with other tokens, require the tokens exactly:
`Value.withoutLovelace output.value == Value.withoutLovelace expected`, with `Value.lovelaceOf` compared
by `>=`. The [escrow, marketplace and order-swap examples](/reference/examples/)
use these defences, and each has a scenario that shows the attack failing.

## A complete example: settling an invoice

This validator locks an invoice. Anyone may spend it, but only in a transaction that pays the payee the full
amount and that is guaranteed to run before the due date. The payment carries the invoice's output
reference, so one payment can't settle two invoices.

```toml title="elci.toml"
[project]
source-directories = ["src"]
target = "plutus-v3-pv11-uplc-1.1.0"
julc-version = "0.1.0-gb-5a3378b"

[validators.invoice]
boundary = "julc-strict-v1"
spend = "Invoice.spend"
```

```elm title="src/Invoice.elm"
module Invoice exposing (Invoice, spend)

import Interval
import Julc.Data as Data
import Julc.Ledger exposing (Credential(..), PubKeyHash, ScriptContext, TxOut, TxOutRef)
import Tx
import Value


type alias Invoice =
    { payee : PubKeyHash
    , amount : Int
    , due : Int
    }


{-| Anyone may settle an invoice by paying the payee in full before it is due.
-}
spend : Invoice -> () -> ScriptContext -> Bool
spend invoice _ ctx =
    case Tx.ownRef ctx of
        Just ref ->
            Interval.isEntirelyBefore invoice.due ctx.txInfo.validRange
                && List.any (settles invoice ref) (Tx.outputsTo (PubKeyCredential invoice.payee) ctx)

        Nothing ->
            False


{-| The output pays the full amount and carries the invoice's output reference.
-}
settles : Invoice -> TxOutRef -> TxOut -> Bool
settles invoice ref output =
    (Value.lovelaceOf output.value >= invoice.amount)
        && (Tx.inlineDatum output == Just (Data.encode ref))
```

- `Tx.ownRef` is the invoice's output reference. It is `Nothing` only when the script runs for another
  purpose, which this validator doesn't handle.
- `Interval.isEntirelyBefore` rejects a transaction without an end to its validity range, which could run
  at any time.
- `Tx.outputsTo` ignores the staking part of the address, so any address of the payee's key counts.

The tests pay the payee with `Scenario.toKey`, an output to the key's address without a staking part, and
tag the payment with `Scenario.withDatum`:

```elm title="tests/InvoiceTest.elm"
module InvoiceTest exposing
    ( latePaymentsFail
    , openEndedPaymentsFail
    , paymentForAnotherInvoiceFails
    , paymentSettles
    , shortPaymentsFail
    , untaggedPaymentsFail
    )

import Invoice exposing (Invoice)
import Julc.Data as Data
import Julc.Ledger exposing (PubKeyHash)
import Scenario exposing (Output, Scenario)


payee : PubKeyHash
payee =
    Scenario.key "payee"


invoice : Invoice
invoice =
    { payee = payee, amount = 5000000, due = 10000 }


{-| A payment to the payee, tagged with the output reference of invoice `index`.
-}
payment : Int -> Int -> Output
payment index lovelace =
    Scenario.toKey payee lovelace
        |> Scenario.withDatum (Data.encode (Scenario.outRef "invoice" index))


settle : Output -> Scenario
settle paid =
    Scenario.spend "invoice" (Scenario.outRef "invoice" 0) (Data.encode invoice)
        |> Scenario.output paid


paymentSettles : Scenario
paymentSettles =
    settle (payment 0 5000000)
        |> Scenario.validBefore 9000


shortPaymentsFail : Scenario
shortPaymentsFail =
    settle (payment 0 4999999)
        |> Scenario.validBefore 9000
        |> Scenario.expectFailure


latePaymentsFail : Scenario
latePaymentsFail =
    settle (payment 0 5000000)
        |> Scenario.validBefore 10001
        |> Scenario.expectFailure


{-| Without an end to its validity range, the transaction could run after the due date.
-}
openEndedPaymentsFail : Scenario
openEndedPaymentsFail =
    settle (payment 0 5000000)
        |> Scenario.expectFailure


untaggedPaymentsFail : Scenario
untaggedPaymentsFail =
    settle (Scenario.toKey payee 5000000)
        |> Scenario.validBefore 9000
        |> Scenario.expectFailure


{-| A payment tagged for another invoice can't settle this one too.
-}
paymentForAnotherInvoiceFails : Scenario
paymentForAnotherInvoiceFails =
    settle (payment 1 5000000)
        |> Scenario.validBefore 9000
        |> Scenario.expectFailure
```

```sh
elci test .
```

```text
PASS InvoiceTest.paymentSettles (cpu 31704477, mem 108622)
PASS InvoiceTest.shortPaymentsFail (cpu 28198789, mem 101740)
PASS InvoiceTest.latePaymentsFail (cpu 18812622, mem 71468)
PASS InvoiceTest.openEndedPaymentsFail (cpu 16656709, mem 65018)
PASS InvoiceTest.untaggedPaymentsFail (cpu 31025075, mem 108726)
PASS InvoiceTest.paymentForAnotherInvoiceFails (cpu 32082910, mem 110554)
6 passed, 0 failed
```

`validBefore 10000` would also settle: its end is exclusive, so the transaction can't run at the due time.

## Julc's helper libraries

The core modules cover what Julc's `ContextsLib`, `IntervalLib` and most of `OutputLib` are used for. Julc's
libraries still help in a few cases. Map a class under `[java-modules]` and import it with the alias you
chose:

| Class under `org.julclang.stdlib.lib` | Still useful for |
| --- | --- |
| `OutputLib` | matching outputs by whole address, staking part included: `outputsAt`, `lovelacePaidTo`, `paidAtLeast` |
| `AddressLib` | classifying an address: `isPubKeyAddress`, `isScriptAddress`, `paymentCredential`, `credentialHash` |

Prefer the `Interval` module to `IntervalLib`: `IntervalLib.finiteLowerBound` and `finiteUpperBound` return
`-1` for an infinite bound, a sentinel that makes "no end" look like "ends at -1". Don't alias a Java module
as `Interval` or `Tx`; those names belong to the core modules. Functions that return a Java `Optional`, such
as `ContextsLib.findOwnInput`, give you a `JulcOptional`; convert it with `Julc.Data.fromOptional`.
`ContextsLib` and `OutputLib` may return the ledger's own values, as `valuePaid` does, but no other Java
library may hand Elci a `Value`: `ValuesLib.add` and the other exports that build one are rejected
(`ELCI2105`), because Java libraries don't keep values in the ledger's order. Use the `Value` module for
those. The [standard library](/advanced/standard-library/) page lists every verified export.

Next, see how your own datum and redeemer types cross the boundary in [Datums and redeemers](/contracts/datums-and-redeemers/).
