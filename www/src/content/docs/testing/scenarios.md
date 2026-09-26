---
title: Transaction scenarios
description: Test validators with whole transactions. Describe a spend, mint or withdrawal in Elci and elci test runs the deployed script in a complete ledger context.
sidebar:
  order: 2
---

A scenario describes one transaction against one of your validators, and `elci test` checks that the validator accepts or rejects it. In this page you test a small marketplace: spending with a payment, a validity range and a continuing output, minting, and withdrawing staking rewards.

Unit tests check functions. Scenarios check what actually goes on-chain: the compiled script, its purpose dispatch, the strict decoding of the datum and redeemer, and your logic, all against a realistic `ScriptContext`.

## How a scenario runs

A `Scenario` is plain data: a record that names a validator and lists the transaction's details. You build it with a pipeline of functions from the `Scenario` module, starting with the purpose. These two come from the test module that `elci init` generates:

```elm
transaction : Scenario
transaction =
    Scenario.spend "main" (Scenario.outRef "main" 0) Scenario.unit
        |> Scenario.parameters [ Data.encode owner ]


ownerSigns : Scenario
ownerSigns =
    transaction
        |> Scenario.signedBy owner
```

`transaction` spends an output of the validator `main`, and `ownerSigns` is the same transaction signed by the owner. Only `ownerSigns` is a test: `transaction` is not exposed.

When `elci test` finds an exposed `Scenario` value, it:

1. compiles the named validator exactly as `elci build` does, and applies the scenario's `parameters` in the order the validator declares them;
2. computes the applied script's hash, which gives the validator its own address, minting policy and staking credential;
3. builds the complete ledger context with julc-testkit's `ScriptContextTestBuilder`: purpose, redeemer, signers, inputs, outputs, minted tokens, fee and validity range;
4. runs the **deployed script** against that context, so purpose dispatch and strict datum and redeemer decoding happen exactly as on-chain;
5. compares the outcome with the expectation: success by default, or rejection after `Scenario.expectFailure`, plus an optional budget.

Because the script is the one `elci build` publishes, a passing scenario is evidence about your real artifact, not about a simulation of it.

## The example project

The marketplace has two validators. `market` holds listings and earns staking rewards: its `spend` handler lets anyone buy a listing before it expires by paying the seller, and lets the seller change the price or delist. Its `withdraw` handler lets only the operator withdraw rewards. `ticket` is a minting policy for event tickets.

```toml title="elci.toml"
[project]
source-directories = ["src"]
target = "plutus-v3-pv11-uplc-1.1.0"
julc-version = "0.1.0-gb-5a3378b"

# The market script: holds listings and earns staking rewards.
[validators.market]
boundary = "julc-strict-v1"
parameters = ["operator"]
spend = "Market.spend"
withdraw = "Market.withdraw"

# The ticket minting policy.
[validators.ticket]
boundary = "julc-strict-v1"
parameters = ["organizer"]
mint = "Ticket.mint"
```

```elm title="src/Market.elm"
module Market exposing (Action(..), Listing, spend, withdraw)

import Interval
import Julc.Data as Data
import Julc.Ledger exposing (Credential(..), PubKeyHash, ScriptContext, TxOut)
import Tx
import Value


type alias Listing =
    { seller : PubKeyHash
    , price : Int
    , expires : Int
    }


type Action
    = Buy
    | Reprice Int
    | Delist


{-| Anyone may buy before the listing expires by paying the seller the price.
The seller may change the price or delist at any time.
-}
spend : PubKeyHash -> Listing -> Action -> ScriptContext -> Bool
spend _ listing action ctx =
    case action of
        Buy ->
            sellerPaid listing ctx
                && Interval.isEntirelyBefore listing.expires ctx.txInfo.validRange

        Reprice newPrice ->
            Tx.signedBy listing.seller ctx
                && List.any (holds { listing | price = newPrice }) (Tx.continuingOutputs ctx)

        Delist ->
            Tx.signedBy listing.seller ctx


{-| Only the operator withdraws the market's staking rewards.
-}
withdraw : PubKeyHash -> () -> ScriptContext -> Bool
withdraw operator _ ctx =
    Tx.signedBy operator ctx


{-| An output pays the seller the price and carries this listing's output reference, so
that it can't also pay for another listing in the same transaction.
-}
sellerPaid : Listing -> ScriptContext -> Bool
sellerPaid listing ctx =
    case Tx.ownRef ctx of
        Just ref ->
            List.any
                (\output ->
                    (Value.lovelaceOf output.value >= listing.price)
                        && (Tx.inlineDatum output == Just (Data.encode ref))
                )
                (Tx.outputsTo (PubKeyCredential listing.seller) ctx)

        Nothing ->
            False


holds : Listing -> TxOut -> Bool
holds expected output =
    Tx.inlineDatum output == Just (Data.encode expected)
```

```elm title="src/Ticket.elm"
module Ticket exposing (mint)

import Bytes
import Julc.Ledger exposing (PubKeyHash, ScriptContext, TokenName(..))
import Tx
import Value


{-| The organizer mints exactly one TICKET per transaction.
-}
mint : PubKeyHash -> () -> ScriptContext -> Bool
mint organizer _ ctx =
    case Tx.ownPolicy ctx of
        Just policy ->
            (Value.quantityOf policy (TokenName (Bytes.fromString "TICKET")) ctx.txInfo.mint == 1)
                && Tx.signedBy organizer ctx

        Nothing ->
            False
```

The validators read the transaction with the core [`Tx` and `Interval`](/reference/core-modules/#tx)
modules. A buyer's payment must carry the listing's output reference as its inline datum, so one payment
can't pay for two listings bought in the same transaction; [reading the
transaction](/contracts/reading-the-transaction/#one-payment-per-input) explains why.

## Test keys and a helper

Start the test module with the people involved and helpers that build the common parts of every spend.
`Scenario.key` derives a deterministic 28-byte key hash from a name, so `Scenario.key "seller"` is the same
in every test and every run:

```elm title="tests/MarketTest.elm"
module MarketTest exposing
    ( buyerPaysTheSeller
    , buyingAfterExpiryFails
    , buyingWithoutADeadlineFails
    , operatorWithdrawsRewards
    , othersCannotWithdraw
    , paymentForAnotherListingFails
    , sellerDelists
    , sellerReprices
    , strangerCannotDelist
    , underpayingFails
    )

import Julc.Data as Data
import Julc.Ledger exposing (PubKeyHash)
import Market exposing (Action(..), Listing)
import Scenario exposing (Output, Scenario)


operator : PubKeyHash
operator =
    Scenario.key "operator"


seller : PubKeyHash
seller =
    Scenario.key "seller"


listing : Listing
listing =
    { seller = seller, price = 50000000, expires = 2000 }


{-| Spend the listed output with an action.
-}
settle : Action -> Scenario
settle action =
    Scenario.spend "market" (Scenario.outRef "listing" 0) (Data.encode listing)
        |> Scenario.parameters [ Data.encode operator ]
        |> Scenario.redeemer (Data.encode action)


{-| A payment to the seller, tagged with the listing's output reference.
-}
payment : Int -> Output
payment lovelace =
    Scenario.toKey seller lovelace
        |> Scenario.withDatum (Data.encode (Scenario.outRef "listing" 0))
```

`settle` returns a `Scenario` but takes an argument, so it is a helper, not a test. The same goes for `operator`, `seller`, `listing` and `payment`, whose types are not test types. `payment` builds an output with `Scenario.toKey`, which pays a key's address without a staking part, and tags it with the listing's reference.

Everything that crosses into the script is `Data`, so datums, redeemers and parameters are encoded with `Data.encode`. `Scenario.parameters` must give every parameter the validator declares, in order; `market` declares one, `operator`. `Scenario.outRef "listing" 0` makes a deterministic output reference from a name and an index; `settle` spends that output, and `payment` tags with the same reference.

## Spending

`Scenario.spend validator ref datum` spends `ref`. The runner adds the spent output to the transaction's inputs, at the validator's own address, holding 2 ADA and the datum inline. Then you add what the transaction does. A buyer pays the seller and the transaction must end before the listing expires:

```elm
buyerPaysTheSeller : Scenario
buyerPaysTheSeller =
    settle Buy
        |> Scenario.output (payment 50000000)
        |> Scenario.validBefore 1500


underpayingFails : Scenario
underpayingFails =
    settle Buy
        |> Scenario.output (payment 49999999)
        |> Scenario.validBefore 1500
        |> Scenario.expectFailure
```

`Scenario.expectFailure` turns a scenario into a negative test: it passes only when the validator rejects the transaction. Write one next to each rule: a validator that accepts everything passes every positive test.

Test the attacks too. A payment tagged with another listing's reference must not pay for this one:

```elm
{-| A payment tagged for another listing can't also pay for this one.
-}
paymentForAnotherListingFails : Scenario
paymentForAnotherListingFails =
    settle Buy
        |> Scenario.output
            (Scenario.toKey seller 50000000
                |> Scenario.withDatum (Data.encode (Scenario.outRef "listing" 1))
            )
        |> Scenario.validBefore 1500
        |> Scenario.expectFailure
```

Signatures work the same way. `Scenario.signedBy` adds a required signer, and you can call it more than once:

```elm
sellerDelists : Scenario
sellerDelists =
    settle Delist
        |> Scenario.signedBy seller


strangerCannotDelist : Scenario
strangerCannotDelist =
    settle Delist
        |> Scenario.signedBy (Scenario.key "stranger")
        |> Scenario.expectFailure
```

For a spend without a datum, as with `spend-datum = "optional"` or `"absent"`, use `Scenario.spendWithoutDatum validator ref`.

## Validity ranges

A transaction without `validFrom` or `validBefore` is valid at any time: both bounds are infinite. The two functions set the bounds in POSIX time:

- `Scenario.validFrom time` sets an **inclusive** lower bound: the transaction is valid from `time`.
- `Scenario.validBefore time` sets an **exclusive** upper bound: the transaction is valid before `time`.

```elm
buyingAfterExpiryFails : Scenario
buyingAfterExpiryFails =
    settle Buy
        |> Scenario.output (payment 50000000)
        |> Scenario.validFrom 1800
        |> Scenario.validBefore 2500
        |> Scenario.expectFailure


buyingWithoutADeadlineFails : Scenario
buyingWithoutADeadlineFails =
    settle Buy
        |> Scenario.output (payment 50000000)
        |> Scenario.expectFailure
```

The second test matters: a transaction without an upper bound could run long after the listing expired. `Interval.isEntirelyBefore` needs a finite end, so the validator rejects it.

## Outputs, datums and tokens

An output is built with one of four functions and then extended:

- `Scenario.toKey key lovelace` pays a key's address, without a staking part.
- `Scenario.toAddress address lovelace` pays any address, such as one with a staking part.
- `Scenario.toScript lovelace` pays the validator's own script address, which has no staking part.
- `Scenario.toStakedScript stake lovelace` pays the validator's own payment credential with a staking part. That is another address: `Tx.continuingOutputs` of an output spent from `toScript`'s address doesn't include it.
- `Scenario.withDatum data` attaches an inline datum.
- `Scenario.withDatumHash data` holds the datum by hash instead: the output carries the datum's hash, and the transaction carries the datum among its witnesses. `Tx.datumOf` finds it there; `Tx.inlineDatum` returns `Nothing` for such an output.
- `Scenario.withToken policy name quantity` adds tokens. The policy is `Scenario.Own`, the validator's own policy, or `Scenario.Policy bytes` for any other.

The runner builds every value the ledger's way, as the script expects a [`Value`](/reference/core-modules/#value) to be: policies and token names in ascending order, quantities of a repeated token added up, and zero quantities left out. So the order of your `withToken` and `Scenario.minting` calls never matters, and `Scenario.minting name 0` mints nothing. The ledger limits policy ids and token names to 32 bytes, and so does the runner: a longer one fails the scenario before the validator runs, even with `expectFailure`, with `cannot build the transaction: A scenario value has a policy id or token name longer than 32 bytes, which the ledger does not allow`.

Repricing requires a continuing output, one of `Tx.continuingOutputs`: an output back at the market's address that carries the updated listing:

```elm
sellerReprices : Scenario
sellerReprices =
    settle (Reprice 40000000)
        |> Scenario.signedBy seller
        |> Scenario.output
            (Scenario.toScript 2000000
                |> Scenario.withDatum (Data.encode { listing | price = 40000000 })
            )
```

To give the spent output tokens or a different value, list it yourself with `Scenario.input` and the same reference. The runner then uses your input instead of the default one. As on the ledger, it must hold the datum that `Scenario.spend` gives, inline or by hash; otherwise the scenario fails before the validator runs with `cannot build the transaction: the spent output ... is listed with a different datum from the one Scenario.spend gives`. This test also needs `import Bytes`:

```elm
listingHoldsAnNft : Scenario
listingHoldsAnNft =
    settle Delist
        |> Scenario.signedBy seller
        |> Scenario.input (Scenario.outRef "listing" 0)
            (Scenario.toScript 2000000
                |> Scenario.withDatum (Data.encode listing)
                |> Scenario.withToken (Scenario.Policy (Bytes.replicate 28 7)) (Bytes.fromString "ART") 1
            )
```

`Scenario.referenceInput ref output` adds a reference input, such as an oracle output your validator reads, and `Scenario.worth lovelace` changes the lovelace of the default spent output.

## Minting

`Scenario.mint validator` runs the validator as a minting policy. Its policy id is the applied script's hash, so the tokens you add with `Scenario.minting name quantity` are minted under that policy. Token names are bytes, usually written with `Bytes.fromString`:

```elm title="tests/TicketTest.elm"
module TicketTest exposing
    ( burningIsRejected
    , organizerMintsOneTicket
    , twoTicketsAreRejected
    , unsignedMintIsRejected
    )

import Bytes
import Julc.Data as Data
import Julc.Ledger exposing (PubKeyHash)
import Scenario exposing (Scenario)


organizer : PubKeyHash
organizer =
    Scenario.key "organizer"


tickets : Int -> Scenario
tickets quantity =
    Scenario.mint "ticket"
        |> Scenario.parameters [ Data.encode organizer ]
        |> Scenario.minting (Bytes.fromString "TICKET") quantity


organizerMintsOneTicket : Scenario
organizerMintsOneTicket =
    tickets 1
        |> Scenario.signedBy organizer


twoTicketsAreRejected : Scenario
twoTicketsAreRejected =
    tickets 2
        |> Scenario.signedBy organizer
        |> Scenario.expectFailure


burningIsRejected : Scenario
burningIsRejected =
    tickets -1
        |> Scenario.signedBy organizer
        |> Scenario.expectFailure


unsignedMintIsRejected : Scenario
unsignedMintIsRejected =
    tickets 1
        |> Scenario.expectFailure
```

A negative quantity burns, so `tickets -1` burns one ticket, which this policy rejects. As in Elm, `tickets -1` passes the argument `-1`, while `quantity - 1` subtracts.

```sh
elci test --match TicketTest
```

```text
PASS TicketTest.organizerMintsOneTicket (cpu 11788207, mem 41590)
PASS TicketTest.twoTicketsAreRejected (cpu 9157880, mem 32745)
PASS TicketTest.burningIsRejected (cpu 9157880, mem 32745)
PASS TicketTest.unsignedMintIsRejected (cpu 11483010, mem 39957)
4 passed, 0 failed
```

## Withdrawing rewards

`Scenario.withdraw validator` runs the validator for a withdrawal from its own staking credential:

```elm
operatorWithdrawsRewards : Scenario
operatorWithdrawsRewards =
    Scenario.withdraw "market"
        |> Scenario.parameters [ Data.encode operator ]
        |> Scenario.signedBy operator


othersCannotWithdraw : Scenario
othersCannotWithdraw =
    Scenario.withdraw "market"
        |> Scenario.parameters [ Data.encode operator ]
        |> Scenario.signedBy seller
        |> Scenario.expectFailure
```

`market` is one script with two handlers, so these scenarios and the spending ones above run the same compiled script. The purpose in the context selects the handler.

## Certificates, votes and proposals

The remaining purposes take what the ledger supplies for them, built with `Julc.Ledger` constructors. These are the transactions that `elci init --purpose certify`, `vote` and `propose` generate, with `import Julc.Data as Data`, `import Julc.Ledger as Ledger` and a test key `owner`:

```elm
Scenario.certify "main" 0 (Ledger.RegStaking (Ledger.PubKeyCredential owner) (Data.toOptional Nothing))

Scenario.vote "main" (Ledger.DRepVoter (Ledger.PubKeyCredential owner))

Scenario.propose "main" 0 (Ledger.ProposalProcedure 0 (Ledger.PubKeyCredential owner) Ledger.InfoAction)
```

The integer after the validator name is the index of the certificate or proposal in the transaction.

## Run the scenarios

```sh
elci test --match MarketTest
```

```text
PASS MarketTest.buyerPaysTheSeller (cpu 33518015, mem 117583)
PASS MarketTest.underpayingFails (cpu 24050900, mem 93018)
PASS MarketTest.paymentForAnotherListingFails (cpu 27935021, mem 101832)
PASS MarketTest.sellerDelists (cpu 15573596, mem 65977)
PASS MarketTest.strangerCannotDelist (cpu 15952029, mem 67909)
PASS MarketTest.buyingAfterExpiryFails (cpu 33502015, mem 117483)
PASS MarketTest.buyingWithoutADeadlineFails (cpu 31346102, mem 111033)
PASS MarketTest.sellerReprices (cpu 36080596, mem 124349)
PASS MarketTest.operatorWithdrawsRewards (cpu 7879274, mem 35433)
PASS MarketTest.othersCannotWithdraw (cpu 8257707, mem 37365)
10 passed, 0 failed
```

For a scenario, the CPU and memory are what the **validator** consumed for that transaction, including a rejected one. These execution units are what the script adds to your transaction's fee.

When a scenario does not get the outcome it expects, the report says why. Here, `strangerDelists` is `strangerCannotDelist` without `expectFailure`, `sellerCannotDelist` is `sellerDelists` with it, `typo` names a validator that does not exist, and `unparameterized` is `operatorWithdrawsRewards` without `Scenario.parameters`:

```text
FAIL MarketTest.strangerDelists (cpu 15952029, mem 67909)
    validator market rejected the transaction: Error term encountered
FAIL MarketTest.sellerCannotDelist (cpu 15573596, mem 65977)
    validator market accepted a transaction expected to fail
FAIL MarketTest.typo
    cannot prepare validator 'markt': no [validators.markt] in elci.toml
FAIL MarketTest.unparameterized
    cannot prepare validator 'market': it takes 1 parameter(s) [operator], but the scenario gives 0; set them with Scenario.parameters
```

The last two fail before the validator runs, so they fail even with `Scenario.expectFailure`: a scenario cannot pass just because it was set up wrongly.

A validator that returns `False` fails with `Error term encountered`. [Debugging and budgets](/testing/debugging/) shows how to find out which check failed.

:::caution[Set the redeemer]
The default redeemer is `Scenario.unit`, which encodes as `Constr 0 []`. That is also the encoding of the first constructor of a union without fields, such as `Buy`. A scenario that forgets `Scenario.redeemer` can therefore run a different action than you meant. Set the redeemer explicitly.
:::

## All builder functions

The complete signatures are in the [test library reference](/reference/test-library/).

**Start a scenario** with the purpose. `validator` is the name of a `[validators.<name>]` table.

| Function | Transaction |
| --- | --- |
| `spend validator ref datum` | spends `ref`, held at the script address with an inline `datum` |
| `spendWithoutDatum validator ref` | spends `ref`, held at the script address without a datum |
| `mint validator` | mints or burns under the validator's own policy |
| `withdraw validator` | withdraws from the validator's own staking credential |
| `certify validator index certificate` | publishes a `TxCert` at an index |
| `vote validator voter` | votes as `voter` |
| `propose validator index procedure` | submits a `ProposalProcedure` at an index |

**Describe the transaction.**

| Function | Effect | Default |
| --- | --- | --- |
| `redeemer data` | the redeemer | `Scenario.unit` |
| `parameters [ data, ... ]` | deployment parameters, in declared order; required when the validator has parameters | none |
| `signedBy key` | adds a required signer | no signers |
| `fee lovelace` | the transaction fee | 200000 |
| `validFrom time` | inclusive lower bound of the validity range | unbounded |
| `validBefore time` | exclusive upper bound of the validity range | unbounded |

**Add inputs, outputs and tokens.**

| Function | Effect |
| --- | --- |
| `input ref output` | adds an input; with the spent `ref`, replaces the default spent output, and must hold the spend's datum |
| `referenceInput ref output` | adds a reference input |
| `output out` | adds an output |
| `minting name quantity` | mints (positive) or burns (negative) under the own policy |
| `worth lovelace` | lovelace held by the default spent output (default 2000000) |
| `toKey key lovelace` | an output to the key's address, without a staking part |
| `toScript lovelace` | an output to the validator's own address |
| `toStakedScript stake lovelace` | an output to the validator's own payment credential with the staking credential `stake` |
| `toAddress address lovelace` | an output to `address` |
| `withDatum data` | gives an output an inline datum |
| `withDatumHash data` | gives an output the datum's hash, and the transaction the datum as a witness |
| `withToken policy name quantity` | adds tokens to an output; `policy` is `Own` or `Policy bytes` |

**Set the expectation.**

| Function | Effect |
| --- | --- |
| `expectFailure` | the validator must reject the transaction |
| `withinBudget cpu memory` | the execution must cost at most this CPU and memory |

**Make test data.**

| Function | Result |
| --- | --- |
| `key name` | a deterministic 28-byte `PubKeyHash` derived from `name` |
| `outRef name index` | a deterministic `TxOutRef` derived from `name` and `index` |
| `unit` | the `Data` of `()` |

## Limits

Scenarios cover single and multiple signatures, validity ranges, lovelace, tokens of the own policy and of other policies, inline datums, datums by hash, reference inputs and every purpose. The validator's own address has no staking part. The following need a [Java test suite](/testing/java/), where you build the context yourself:

- reference scripts;
- other staking credentials and additional withdrawals;
- more than one script in a transaction.

## Next steps

- Run a scenario for many generated inputs with [property-based tests](/testing/properties/).
- Find out why a validator rejects a transaction in [Debugging and budgets](/testing/debugging/).
