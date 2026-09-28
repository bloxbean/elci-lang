---
title: Datums and redeemers
description: Which types can be datums, redeemers and parameters, how strict decoding protects a validator, the three spending-datum profiles, and exactly how Elci values are encoded as Plutus Data.
sidebar:
  order: 3
---

A validator receives its datum and redeemer as Plutus **Data**, the ledger's universal format, and Elci decodes them into your types before your handler runs. This page covers which types you can use, how strict decoding behaves, how to accept outputs with or without a datum, and how each Elci type is encoded.

## Datum, redeemer and parameters

- The **datum** is stored with an output when it is locked at a script address. It records the state of that output: who may claim it, a price, a deadline.
- The **redeemer** is supplied by the transaction that runs the script. It says what the transaction wants to do: claim, cancel, refund.
- **Parameters** are fixed once per deployed script. See [Parameterized validators](/contracts/parameters/).

All three use the same types and the same encoding. The difference is when they are checked: the script checks the datum and redeemer on every run, while parameters are checked once, off-chain, when you apply them. The key order of a `Dict` is the exception: the script checks it in parameters too.

## Types you can use

| Type | At the boundary |
| --- | --- |
| `Int`, `Bool`, `String`, `Bytes` | yes |
| `()` | yes, as a whole datum or redeemer; not yet inside records, lists or other containers (`ELCI2013`) |
| Records and custom types | yes, when their fields are allowed types |
| `Maybe a`, tuples, `List a`, `Dict k v`, `Map k v` | yes, when their contents are allowed types |
| Ledger types such as `PubKeyHash`, `TxOutRef`, `Credential`, `Address` | yes |
| `Data` | yes: any data is accepted unchecked; decode it yourself with `Julc.Data.decode` |
| `Value` | not yet (`ELCI2013`): store a `List ( PolicyId, TokenName, Int )` and rebuild the value with `Value.fromList` |
| `TxOut`, `TxInInfo`, `TxInfo` | no: the compiler can't check their layout (`ELCI2013`) |
| `ScriptContext`, functions | no (`ELCI2013`) |
| `Array a` | no: arrays have no Data encoding (`ELCI2013`); keep a `List` and convert it with `Array.fromList` |

The compiler checks this when it compiles a handler, so an unsupported type is a compile error, never a runtime surprise.

## Strict decoding

Decoding is **strict**. Before your handler runs, the script checks the whole datum and redeemer: every constructor tag, every field count and every field's kind, even the fields your handler never reads. Data of the wrong shape fails the script.

This escrow shows the effect. The datum is a record and the redeemer a custom type:

```toml title="elci.toml"
[project]
source-directories = ["src"]
target = "plutus-v3-pv11-uplc-1.1.0"
julc-version = "0.1.0-gb-5a3378b"

[validators.escrow]
boundary = "julc-strict-v1"
spend = "Escrow.spend"

[validators.locker]
boundary = "julc-strict-v1"
parameters = ["admin"]
spend-datum = "optional"
spend = "Locker.spend"

[validators.donations]
boundary = "julc-strict-v1"
parameters = ["admin"]
spend-datum = "absent"
spend = "Donations.spend"

[validators.ballot]
boundary = "julc-strict-v1"
spend = "Ballot.spend"
```

```elm title="src/Auth.elm"
module Auth exposing (signedBy)

import Julc.Ledger exposing (PubKeyHash, ScriptContext)


signedBy : PubKeyHash -> ScriptContext -> Bool
signedBy key ctx =
    List.any (\signer -> signer == key) ctx.txInfo.signatories
```

```elm title="src/Escrow.elm"
module Escrow exposing (Action(..), Deal, spend)

import Auth exposing (signedBy)
import Julc.Ledger exposing (PubKeyHash, ScriptContext)


type alias Deal =
    { seller : PubKeyHash
    , buyer : PubKeyHash
    , price : Int
    }


type Action
    = Complete
    | Refund Int


spend : Deal -> Action -> ScriptContext -> Bool
spend deal action ctx =
    case action of
        Complete ->
            signedBy deal.buyer ctx

        Refund amount ->
            amount <= deal.price && signedBy deal.seller ctx
```

The tests lock outputs with good and bad datums. Scenarios take the datum and redeemer as `Data`, so each value goes through `Data.encode`:

```elm title="tests/EscrowTest.elm"
module EscrowTest exposing (buyerCompletes, dealsRoundTrip, incompleteDealsAreRejected, missingDatumsAreRejected, sellerRefunds, wrongRedeemersAreRejected)

import Escrow exposing (Action(..), Deal)
import Expect exposing (Expectation)
import Julc.Data as Data
import Julc.Ledger exposing (PubKeyHash)
import Property exposing (Property)
import Scenario exposing (Scenario)


seller : PubKeyHash
seller =
    Scenario.key "seller"


buyer : PubKeyHash
buyer =
    Scenario.key "buyer"


deal : Deal
deal =
    { seller = seller, buyer = buyer, price = 100 }


spendDeal : Data -> Scenario
spendDeal datum =
    Scenario.spend "escrow" (Scenario.outRef "escrow" 0) datum


buyerCompletes : Scenario
buyerCompletes =
    spendDeal (Data.encode deal)
        |> Scenario.redeemer (Data.encode Complete)
        |> Scenario.signedBy buyer


sellerRefunds : Scenario
sellerRefunds =
    spendDeal (Data.encode deal)
        |> Scenario.redeemer (Data.encode (Refund 30))
        |> Scenario.signedBy seller


{-| `Complete` never reads `price`, but the whole datum is still decoded.
-}
incompleteDealsAreRejected : Scenario
incompleteDealsAreRejected =
    spendDeal (Data.encode { seller = seller, buyer = buyer })
        |> Scenario.redeemer (Data.encode Complete)
        |> Scenario.signedBy buyer
        |> Scenario.expectFailure


missingDatumsAreRejected : Scenario
missingDatumsAreRejected =
    Scenario.spendWithoutDatum "escrow" (Scenario.outRef "escrow" 0)
        |> Scenario.redeemer (Data.encode Complete)
        |> Scenario.signedBy buyer
        |> Scenario.expectFailure


wrongRedeemersAreRejected : Scenario
wrongRedeemersAreRejected =
    spendDeal (Data.encode deal)
        |> Scenario.redeemer (Data.encode 0)
        |> Scenario.signedBy buyer
        |> Scenario.expectFailure


dealsRoundTrip : Deal -> Property
dealsRoundTrip generated =
    Property.expect (Expect.equal generated (Data.decode (Data.encode generated)))
```

```sh
elci test . --match escrow --seed 42
```

```text
PASS EscrowTest.buyerCompletes (cpu 12087950, mem 48649)
PASS EscrowTest.sellerRefunds (cpu 15076849, mem 59803)
PASS EscrowTest.incompleteDealsAreRejected (cpu 5999806, mem 24913)
PASS EscrowTest.missingDatumsAreRejected (cpu 3001320, mem 10881)
PASS EscrowTest.wrongRedeemersAreRejected (cpu 7930169, mem 33878)
PASS EscrowTest.dealsRoundTrip (100 runs, max cpu 11543229, mem 46989)
6 passed, 0 failed (seed 42)
```

`incompleteDealsAreRejected` deserves a closer look. The buyer signs and the redeemer is `Complete`, which only checks the buyer's signature, yet the script rejects the transaction. Without `expectFailure` you would see why:

```text
FAIL EscrowTest.incompleteDealsAreRejected (cpu 5999806, mem 24913)
    validator escrow rejected the transaction: UnIData: not IntData
```

The datum has two fields where `Deal` has three, so decoding fails before `spend` is called. Strictness means your handler only ever sees well-formed values, and an attacker can't slip in data of an unexpected shape.

:::caution[A bad datum locks funds]
Strictness also applies to the outputs you create. If an output is locked at the escrow address with a datum that isn't a valid `Deal`, **no** transaction can ever spend it. Encode datums from the same types the validator uses (`Data.encode` in Elci, or the layout in `abi.json` off-chain), and test them.
:::

### Dictionaries in data

A `Dict` crosses the boundary as a Plutus map with its keys in ascending order, and decoding checks that order. The check matters because `Dict.member` and `Dict.get` stop at the first key that isn't smaller than the one they look for: in a map whose keys are out of order, they could stop before a key that is there. This ballot, the `ballot` validator in the `elci.toml` above, lets each key holder vote once:

```elm title="src/Ballot.elm"
module Ballot exposing (Ballot, spend)

import Dict exposing (Dict)
import Julc.Ledger exposing (PubKeyHash, ScriptContext)


type alias Ballot =
    { votes : Dict PubKeyHash Int
    }


{-| The redeemer names the voter, who must sign and must not have voted yet.
-}
spend : Ballot -> PubKeyHash -> ScriptContext -> Bool
spend ballot voter ctx =
    not (Dict.member voter ballot.votes)
        && List.member voter ctx.txInfo.signatories
```

The scenarios vote with a fresh key, vote twice, and try to vote twice with a forged datum:

```elm title="tests/BallotTest.elm"
module BallotTest exposing (forgedOrderIsRejected, keysAscend, newVotersVote, votersVoteOnce)

import Ballot exposing (Ballot)
import Dict
import Julc.Data as Data
import Julc.Ledger exposing (PubKeyHash)
import Map exposing (Map)
import Scenario exposing (Scenario)


alice : PubKeyHash
alice =
    Scenario.key "alice"


bob : PubKeyHash
bob =
    Scenario.key "bob"


carol : PubKeyHash
carol =
    Scenario.key "carol"


keysAscend : Bool
keysAscend =
    Dict.keys (Dict.fromList [ ( carol, 1 ), ( alice, 1 ), ( bob, 1 ) ]) == [ alice, bob, carol ]


vote : Data -> PubKeyHash -> Scenario
vote ballot voter =
    Scenario.spend "ballot" (Scenario.outRef "ballot" 0) ballot
        |> Scenario.redeemer (Data.encode voter)
        |> Scenario.signedBy voter


newVotersVote : Scenario
newVotersVote =
    vote (Data.encode (Ballot (Dict.fromList [ ( alice, 1 ) ]))) bob


votersVoteOnce : Scenario
votersVoteOnce =
    vote (Data.encode (Ballot (Dict.fromList [ ( alice, 1 ), ( bob, 1 ) ]))) bob
        |> Scenario.expectFailure


{-| `Ballot`'s layout, but a `Map` keeps its own order: the newest key first.
-}
type alias Forged =
    { votes : Map PubKeyHash Int
    }


{-| Bob has voted, but his key comes after Carol's larger one.
-}
forgedOrderIsRejected : Scenario
forgedOrderIsRejected =
    vote (Data.encode (Forged (Map.fromList [ ( bob, 1 ), ( carol, 1 ) ]))) bob
        |> Scenario.expectFailure
```

```sh
elci test . --match ballot
```

```text
PASS BallotTest.keysAscend (cpu 35117763, mem 132891)
PASS BallotTest.newVotersVote (cpu 15827728, mem 64830)
PASS BallotTest.votersVoteOnce (cpu 18258644, mem 76709)
PASS BallotTest.forgedOrderIsRejected (cpu 14523407, mem 60626)
4 passed, 0 failed
```

`keysAscend` shows that the test keys order as Alice, Bob, Carol. `Forged` has `Ballot`'s layout, but a `Map` keeps its own order, so the forged datum lists Carol's key before Bob's. Bob has already voted, yet `Dict.member` would stop at Carol's key, the first that isn't smaller than his, and answer `False`. The script never gets that far: a datum whose keys are out of order or repeated fails to decode, whatever the handler would have decided. The check reads each key once, at about 1.1 million CPU units per entry.

When you build such a datum off-chain, write the keys in Elci's order: integers numerically, byte strings and strings by their bytes with a proper prefix first, lists and tuples element by element. This is not CBOR's canonical key order, which puts shorter byte strings first and negative integers after positive ones. Build test datums with `Dict.fromList` and `Data.encode`, which always produce the right order.

## Spending-datum profiles

By default a spending handler requires a datum, and an output without one can't be spent (`missingDatumsAreRejected` above). Set `spend-datum` in the validator's table to change that. It changes the handler's signature:

| `spend-datum` | Handler type | An output without a datum |
| --- | --- | --- |
| `"required"` (default) | `params -> datum -> redeemer -> ScriptContext -> Bool` | is rejected |
| `"optional"` | `params -> Maybe datum -> redeemer -> ScriptContext -> Bool` | arrives as `Nothing` |
| `"absent"` | `params -> redeemer -> ScriptContext -> Bool` | is accepted; no datum is ever read |

### Optional

With `"optional"`, the handler receives `Just datum` or `Nothing`. A datum that is present is still decoded strictly. This locker lets each output name its owner, and lets the admin recover outputs sent without a datum:

```elm title="src/Locker.elm"
module Locker exposing (spend)

import Auth exposing (signedBy)
import Julc.Ledger exposing (PubKeyHash, ScriptContext)


{-| An output's datum names its owner. Outputs sent without a datum can be recovered by the admin.
-}
spend : PubKeyHash -> Maybe PubKeyHash -> () -> ScriptContext -> Bool
spend admin datum _ ctx =
    case datum of
        Just owner ->
            signedBy owner ctx

        Nothing ->
            signedBy admin ctx
```

```elm title="tests/LockerTest.elm"
module LockerTest exposing (adminCannotTakeOwnedOutputs, adminRecoversUnlabelled, malformedDatumsAreRejected, ownerSpends)

import Julc.Data as Data
import Julc.Ledger exposing (PubKeyHash, TxOutRef)
import Scenario exposing (Scenario)


admin : PubKeyHash
admin =
    Scenario.key "admin"


owner : PubKeyHash
owner =
    Scenario.key "owner"


ref : TxOutRef
ref =
    Scenario.outRef "locker" 0


ownerSpends : Scenario
ownerSpends =
    Scenario.spend "locker" ref (Data.encode owner)
        |> Scenario.parameters [ Data.encode admin ]
        |> Scenario.signedBy owner


adminCannotTakeOwnedOutputs : Scenario
adminCannotTakeOwnedOutputs =
    Scenario.spend "locker" ref (Data.encode owner)
        |> Scenario.parameters [ Data.encode admin ]
        |> Scenario.signedBy admin
        |> Scenario.expectFailure


adminRecoversUnlabelled : Scenario
adminRecoversUnlabelled =
    Scenario.spendWithoutDatum "locker" ref
        |> Scenario.parameters [ Data.encode admin ]
        |> Scenario.signedBy admin


malformedDatumsAreRejected : Scenario
malformedDatumsAreRejected =
    Scenario.spend "locker" ref (Data.encode 42)
        |> Scenario.parameters [ Data.encode admin ]
        |> Scenario.signedBy admin
        |> Scenario.expectFailure
```

```sh
elci test . --match locker
```

```text
PASS LockerTest.ownerSpends (cpu 10898424, mem 43407)
PASS LockerTest.adminCannotTakeOwnedOutputs (cpu 11276857, mem 45339)
PASS LockerTest.adminRecoversUnlabelled (cpu 9203608, mem 36654)
PASS LockerTest.malformedDatumsAreRejected (cpu 4502607, mem 19149)
4 passed, 0 failed
```

`malformedDatumsAreRejected` shows that "optional" doesn't mean "anything goes": the integer `42` is not a `PubKeyHash`, so it is not treated as a missing datum.

### Absent

With `"absent"`, the handler has no datum argument and the datum is never inspected. Use it when outputs may arrive from wallets that attach no datum, or any datum:

```elm title="src/Donations.elm"
module Donations exposing (spend)

import Auth exposing (signedBy)
import Julc.Ledger exposing (PubKeyHash, ScriptContext)


{-| Anyone can donate, with or without a datum; only the admin can spend.
-}
spend : PubKeyHash -> () -> ScriptContext -> Bool
spend admin _ ctx =
    signedBy admin ctx
```

```elm title="tests/DonationsTest.elm"
module DonationsTest exposing (adminSpendsAnyDatum, adminSpendsWithoutDatum, strangersCannotSpend)

import Julc.Data as Data
import Julc.Ledger exposing (PubKeyHash, TxOutRef)
import Scenario exposing (Scenario)


admin : PubKeyHash
admin =
    Scenario.key "admin"


ref : TxOutRef
ref =
    Scenario.outRef "donations" 0


adminSpendsWithoutDatum : Scenario
adminSpendsWithoutDatum =
    Scenario.spendWithoutDatum "donations" ref
        |> Scenario.parameters [ Data.encode admin ]
        |> Scenario.signedBy admin


adminSpendsAnyDatum : Scenario
adminSpendsAnyDatum =
    Scenario.spend "donations" ref (Data.encode "thank you")
        |> Scenario.parameters [ Data.encode admin ]
        |> Scenario.signedBy admin


strangersCannotSpend : Scenario
strangersCannotSpend =
    Scenario.spendWithoutDatum "donations" ref
        |> Scenario.parameters [ Data.encode admin ]
        |> Scenario.signedBy (Scenario.key "stranger")
        |> Scenario.expectFailure
```

```sh
elci test . --match donations
```

```text
PASS DonationsTest.adminSpendsWithoutDatum (cpu 5906941, mem 23432)
PASS DonationsTest.adminSpendsAnyDatum (cpu 5906941, mem 23432)
PASS DonationsTest.strangersCannotSpend (cpu 6285374, mem 25364)
3 passed, 0 failed
```

## How Elci values are encoded

Every boundary type has one fixed Data encoding. You need it whenever off-chain code builds a datum, redeemer or parameter, and whenever you decode data that another contract wrote.

| Elci type | Plutus Data |
| --- | --- |
| `Int` | integer |
| `Bytes`, and hashes such as `PubKeyHash` | byte string |
| `String` | byte string holding the UTF-8 text |
| `Bool` | `False` is constructor 0, `True` is constructor 1, both with no fields |
| `()` | constructor 0 with no fields |
| Record | constructor 0; fields **sorted by name**, not in declaration order |
| Custom type | constructor tag = position in the declaration (first is 0); fields in argument order |
| `Maybe a` | `Nothing` is constructor 0; `Just x` is constructor 1 with one field |
| Tuple | constructor 0; components in order |
| `List a` | list |
| `Dict k v` | map, with its keys in strictly ascending order |
| `Map k v` | map, in the map's order |
| `TxOutRef` | constructor 0 with the transaction id bytes and the index |

Three rules cause most surprises:

- **Record fields are sorted by name.** `Deal` is declared as seller, buyer, price but encoded as buyer, price, seller. Reordering a record's declaration never changes its encoding.
- **Constructor order is the encoding.** Reordering the constructors of a custom type changes their tags, and existing on-chain data no longer decodes as intended. Add new constructors at the end.
- **`Maybe` is not Julc's optional.** Julc's Java `Optional` uses different tags, so the two are not interchangeable. Convert with `Julc.Data.fromOptional` and `toOptional`.

You can see these rules in action by encoding a value and decoding it as a different type with the same layout:

```elm title="tests/EncodingTest.elm"
module EncodingTest exposing (boolsAreConstructors, maybeIsNothingThenJust, recordsAreSortedByFieldName)

import Escrow exposing (Deal)
import Expect exposing (Expectation)
import Julc.Data as Data
import Julc.Ledger exposing (PubKeyHash)
import Scenario


type Switch
    = Off
    | On


type Option
    = None
    | Some Int


seller : PubKeyHash
seller =
    Scenario.key "seller"


buyer : PubKeyHash
buyer =
    Scenario.key "buyer"


{-| `Deal` declares seller, buyer, price; it is encoded as buyer, price, seller.
-}
recordsAreSortedByFieldName : Expectation
recordsAreSortedByFieldName =
    let
        deal : Deal
        deal =
            { seller = seller, buyer = buyer, price = 100 }
    in
    Expect.equal ( buyer, 100, seller ) (Data.decode (Data.encode deal))


{-| `False` is constructor 0 and `True` is constructor 1.
-}
boolsAreConstructors : Expectation
boolsAreConstructors =
    Expect.equal [ Off, On ] (Data.decode (Data.encode [ False, True ]))


{-| `Nothing` is constructor 0; `Just x` is constructor 1 with one field.
-}
maybeIsNothingThenJust : Expectation
maybeIsNothingThenJust =
    Expect.equal [ None, Some 5 ] (Data.decode (Data.encode [ Nothing, Just 5 ]))
```

```sh
elci test . --match encoding
```

```text
PASS EncodingTest.recordsAreSortedByFieldName (cpu 9149529, mem 33661)
PASS EncodingTest.boolsAreConstructors (cpu 7559553, mem 29633)
PASS EncodingTest.maybeIsNothingThenJust (cpu 9322159, mem 36461)
3 passed, 0 failed
```

The build records every layout in `abi.json`, so off-chain code doesn't have to guess. This is the escrow's datum, extracted with `jq`:

```sh
jq '.handlers[0].datum' build/validators/escrow/abi.json
```

```json
{
  "profile": "required",
  "schema": {
    "fields": [
      {
        "name": "buyer",
        "schema": {
          "encoding": "B",
          "kind": "bytes"
        }
      },
      {
        "name": "price",
        "schema": {
          "encoding": "I",
          "kind": "integer"
        }
      },
      {
        "name": "seller",
        "schema": {
          "encoding": "B",
          "kind": "bytes"
        }
      }
    ],
    "identity": "elci.Record:buyer,price,seller [org.julclang.ledger.PubKeyHash, Int, org.julclang.ledger.PubKeyHash]",
    "kind": "record",
    "tag": 0
  },
  "sourceType": "Record:buyer,price,seller [org.julclang.ledger.PubKeyHash, Int, org.julclang.ledger.PubKeyHash]"
}
```

## Converting with `Julc.Data`

`Julc.Data` is a compiler-provided module of explicit conversions. Import it with `import Julc.Data as Data`.

| Function | Type | Behavior |
| --- | --- | --- |
| `Data.encode` | `a -> Data` | The Data encoding of a value |
| `Data.decode` | `Data -> a` | Strict decoding; malformed data fails the script |
| `Data.fromOptional` | `JulcOptional a -> Maybe a` | Re-tags a Julc optional (a ledger field or a library result) as `Maybe` |
| `Data.toOptional` | `Maybe a -> JulcOptional a` | The reverse |

The type `a` must be known where you convert. Usually the context fixes it, as in the tests above; otherwise add a type annotation. Where you use them:

- **In tests**, to build the `Data` a scenario needs: `Data.encode deal`, `Data.encode (Refund 30)`.
- **In contracts**, to read data the handler doesn't receive directly, such as the inline datum of another output (see [Reading the transaction](/contracts/reading-the-transaction/)).
- **In properties**, to check that a type survives a round trip, like `dealsRoundTrip` above.

`decode` never returns a `Maybe` on bad input: a failed decode fails the transaction. In a unit test that decodes a string as an `Int`:

```elm
decodingIsStrict : Bool
decodingIsStrict =
    Data.decode (Data.encode "five") == 5
```

```text
FAIL EncodingTest.decodingIsStrict (cpu 928281, mem 5171)
    evaluation failed: UnIData: not IntData
```

A `Value` converts on its own: `Data.encode ctx.txInfo.mint` costs nothing, and `Data.decode` accepts only a canonical value (see [`Value`](/reference/core-modules/#value)). A type that contains a `Value` can't be decoded yet, a list or tuple of values can't be encoded either, and a ledger record without a checkable layout, such as `TxOut`, can't be decoded. Each is rejected at compile time. For `Data.encode [ Value.lovelace 5 ]`:

```text
ELCI3003: No encoder for List [org.julclang.ledger.Value]: The library provider cannot supply encode List record Value: [JULC0051] Library request encode List record Value is not supported: org.julclang.ledger.Value does not approve ENCODE
```

Convert the values with `Value.toList` first, and back with `Value.fromList`.
