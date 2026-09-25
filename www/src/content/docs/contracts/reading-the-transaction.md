---
title: Reading the transaction
description: What a handler can read from the ScriptContext, the ledger types behind it, and verified recipes for signers, validity ranges, inputs, outputs and minting.
sidebar:
  order: 2
---

The last argument of every handler is the `ScriptContext`: the whole transaction being validated, as the ledger describes it. This page lists what you can read from it, explains the ledger types, and gives small recipes for the checks most contracts need.

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

`Map` fields work with the bundled `Map` module (`import Map`), in the style of Elm's `Dict`.

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

Hashes have their own types: `PubKeyHash`, `ScriptHash`, `ValidatorHash`, `PolicyId`, `TxId` and `DatumHash`. They are byte strings underneath, but the compiler keeps them apart, so a key hash can't be mistaken for arbitrary bytes. This helper takes the key as `Bytes`:

```elm
signedByBytes : Bytes -> ScriptContext -> Bool
signedByBytes key ctx =
    List.any (\signer -> signer == key) ctx.txInfo.signatories
```

```text
elci: .../my-project/elci.toml:9: [validators.main]
.../my-project/src/Main.elm:8:51: ELCI2002: Type mismatch: expected Bytes, found PubKeyHash
```

Take a `PubKeyHash` instead, or use the `.hash` field to get the bytes when you really need them, for example `policy.hash` for a `PolicyId`.

What you can do with a ledger type is decided per type:

- **Fields**: read record fields with `.field`, including nested ones such as `output.address.credential`.
- **Patterns and constructors**: match on custom types in `case`, and build values such as `Ledger.TxOutRef txId 0`.
- **Equality**: `==` and `/=` work on hashes, `TxOutRef`, `Credential`, `Address`, `Interval`, `OutputDatum`, `ScriptInfo` and the other plain ledger types. A `JulcOptional` has no equality: convert it with `Data.fromOptional` and compare the `Maybe`.
- **`Value` is the exception.** The multi-asset `Value` (and records that contain it: `TxOut`, `TxInInfo`, `TxInfo`) has no equality and can't be a datum or redeemer. Read it with the `ValuesLib` Java library, shown below.

## Recipes

Each recipe is a small function to put in a handler's module, next to the imports it needs. Test the rules you build from them with [transaction scenarios](/testing/scenarios/).

### Who signed

`signatories` lists the key hashes that must sign the transaction. A signature check is a list search:

```elm
signedBy : PubKeyHash -> ScriptContext -> Bool
signedBy key ctx =
    List.any (\signer -> signer == key) ctx.txInfo.signatories
```

### When the transaction runs

A script can't read the clock. Instead, the transaction declares a validity range and the ledger only accepts it inside that range, so the script reasons about the range. The `IntervalLib` Java library reads its bounds. Map it in `elci.toml`:

```toml
[java-modules]
"Julc.Interval" = "org.julclang.stdlib.lib.IntervalLib"
```

```elm
import Julc.Interval as Interval
```

| Function | Returns |
| --- | --- |
| `Interval.finiteLowerBound range` | the start time, or `-1` when the range has no start |
| `Interval.finiteUpperBound range` | the end time, or `-1` when the range has no end |
| `Interval.contains range time` | whether `time` lies in the range, honoring inclusive bounds |

`finiteLowerBound` and `finiteUpperBound` return the bound's time whether or not it is inclusive. Always handle the `-1` sentinel: "no end" must not look like "ends at -1". To require that something happens **after** a time, check the start of the range (every moment in the range is then late enough). To require **before** a time, check the end, and require that there is one. The [vesting tutorial](/tutorials/vesting/) builds a deadline check step by step.

### Which outputs are spent

`inputs` holds every spent output with its reference. To require that the transaction spends a particular output:

```elm
spends : TxOutRef -> ScriptContext -> Bool
spends ref ctx =
    List.any (\input -> input.outRef == ref) ctx.txInfo.inputs
```

The [one-shot minting policy](/tutorials/one-shot-policy/) is built on this check.

### The output being spent

When spending, `scriptInfo` says which output is being validated. Find it among the inputs to read its address or value:

```elm
ownRef : ScriptContext -> Maybe TxOutRef
ownRef ctx =
    case ctx.scriptInfo of
        SpendingScript ref _ ->
            Just ref

        _ ->
            Nothing


ownInput : ScriptContext -> Maybe TxInInfo
ownInput ctx =
    case ownRef ctx of
        Just ref ->
            List.foldl
                (\input found ->
                    if input.outRef == ref then
                        Just input

                    else
                        found
                )
                Nothing
                ctx.txInfo.inputs

        Nothing ->
            Nothing
```

### The script's own policy id

When minting, `scriptInfo` carries the policy id, which is the script's own hash:

```elm
ownPolicy : ScriptContext -> Maybe PolicyId
ownPolicy ctx =
    case ctx.scriptInfo of
        MintingScript policy ->
            Just policy

        _ ->
            Nothing
```

### Amounts and tokens

`Value` has no operations of its own in Elci. Map Julc's `ValuesLib` and use its functions:

```toml
[java-modules]
"Julc.Values" = "org.julclang.stdlib.lib.ValuesLib"
```

| Function | Returns |
| --- | --- |
| `Values.lovelaceOf value` | the lovelace in `value` |
| `Values.assetOf value policy name` | the quantity of one token; `policy` and `name` are `Bytes` |
| `Values.flatten value` | every asset as `Data`, each a triple of policy, token name and quantity |

For example, the quantity of `BADGE` tokens this policy mints:

```elm
case ctx.scriptInfo of
    MintingScript policy ->
        Values.assetOf ctx.txInfo.mint policy.hash (Bytes.fromString "BADGE")

    _ ->
        0
```

### Datums on other outputs

An output's datum arrives as `OutputDatum`. An inline datum is raw `Data`; decode it into your own type with `Julc.Data.decode`. Decoding is strict, so a datum of the wrong shape fails the whole script rather than producing `Nothing`:

```elm
type alias Order =
    { owner : PubKeyHash
    , price : Int
    }


inlineOrder : TxOut -> Maybe Order
inlineOrder output =
    case output.datum of
        OutputDatumInline raw ->
            Just (Data.decode raw)

        _ ->
            Nothing
```

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

## A complete example: settling an invoice

This validator locks an invoice. Anyone may spend it, but only in a transaction that pays the payee the full amount and that is guaranteed to run before the due date. It reads outputs, addresses, values and the validity range.

```toml title="elci.toml"
[project]
source-directories = ["src"]
target = "plutus-v3-pv11-uplc-1.1.0"
julc-version = "0.1.0-gb-5a3378b"

[validators.invoice]
boundary = "julc-strict-v1"
spend = "Invoice.spend"

[java-modules]
"Julc.Interval" = "org.julclang.stdlib.lib.IntervalLib"
"Julc.Values" = "org.julclang.stdlib.lib.ValuesLib"
```

```elm title="src/Invoice.elm"
module Invoice exposing (Invoice, spend)

import Julc.Interval as Interval
import Julc.Ledger exposing (Credential(..), PubKeyHash, ScriptContext, TxOut)
import Julc.Values as Values


type alias Invoice =
    { payee : PubKeyHash
    , amount : Int
    , due : Int
    }


{-| Anyone may settle an invoice by paying the payee in full before it is due.
-}
spend : Invoice -> () -> ScriptContext -> Bool
spend invoice _ ctx =
    let
        paysPayee : TxOut -> Bool
        paysPayee output =
            (output.address.credential == PubKeyCredential invoice.payee)
                && (Values.lovelaceOf output.value >= invoice.amount)

        validUntil : Int
        validUntil =
            Interval.finiteUpperBound ctx.txInfo.validRange
    in
    List.any paysPayee ctx.txInfo.outputs
        && (validUntil >= 0)
        && (validUntil <= invoice.due)
```

- `paysPayee` compares the output's payment credential with the payee's key and reads its lovelace. It ignores the staking part of the address, so any address of the payee's key counts.
- `validUntil >= 0` rejects a transaction without an upper bound, which could run at any time.

The tests build the payee's address from its key and add an output paying it:

```elm title="tests/InvoiceTest.elm"
module InvoiceTest exposing (latePaymentsFail, openEndedPaymentsFail, paymentSettles, shortPaymentsFail)

import Invoice exposing (Invoice)
import Julc.Data as Data
import Julc.Ledger as Ledger exposing (Address, PubKeyHash)
import Scenario exposing (Scenario)


payee : PubKeyHash
payee =
    Scenario.key "payee"


payeeAddress : Address
payeeAddress =
    Ledger.Address (Ledger.PubKeyCredential payee) (Data.toOptional Nothing)


invoice : Invoice
invoice =
    { payee = payee, amount = 5000000, due = 10000 }


settle : Int -> Scenario
settle lovelace =
    Scenario.spend "invoice" (Scenario.outRef "invoice" 0) (Data.encode invoice)
        |> Scenario.output (Scenario.toAddress payeeAddress lovelace)


paymentSettles : Scenario
paymentSettles =
    settle 5000000
        |> Scenario.validBefore 9000


shortPaymentsFail : Scenario
shortPaymentsFail =
    settle 4999999
        |> Scenario.validBefore 9000
        |> Scenario.expectFailure


latePaymentsFail : Scenario
latePaymentsFail =
    settle 5000000
        |> Scenario.validBefore 10001
        |> Scenario.expectFailure


{-| Without an upper bound the transaction could run after the due date.
-}
openEndedPaymentsFail : Scenario
openEndedPaymentsFail =
    settle 5000000
        |> Scenario.expectFailure
```

`Data.toOptional Nothing` builds the address's empty staking part: the ledger field is a `JulcOptional`, the reverse of `fromOptional`.

```sh
elci test .
```

```text
PASS InvoiceTest.paymentSettles (cpu 20328461, mem 72523)
PASS InvoiceTest.shortPaymentsFail (cpu 19849630, mem 71693)
PASS InvoiceTest.latePaymentsFail (cpu 20312461, mem 72423)
PASS InvoiceTest.openEndedPaymentsFail (cpu 19396593, mem 69598)
4 passed, 0 failed
```

## Helper libraries

Julc's standard library has more helpers over the context. Map a class under `[java-modules]` and import it with the alias you chose. Some useful ones:

| Class under `org.julclang.stdlib.lib` | Examples |
| --- | --- |
| `IntervalLib` | `finiteLowerBound`, `finiteUpperBound`, `contains`, `between` |
| `ValuesLib` | `lovelaceOf`, `assetOf`, `flatten`, `geq`, `containsPolicy` |
| `OutputLib` | `paidAtLeast`, `lovelacePaidTo`, `outputsAt`, `getInlineDatum` |
| `ContextsLib` | `findOwnInput`, `getContinuingOutputs`, `ownHash`, `valuePaid` |
| `AddressLib` | `paymentCredential`, `isScriptAddress`, `credentialHash` |

Functions that return a Java `Optional`, such as `findOwnInput`, give you a `JulcOptional`; convert it with `Julc.Data.fromOptional`. The [standard library](/advanced/standard-library/) page lists every verified export.

Next, see how your own datum and redeemer types cross the boundary in [Datums and redeemers](/contracts/datums-and-redeemers/).
