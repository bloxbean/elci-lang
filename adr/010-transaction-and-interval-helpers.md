# ADR-010: Transaction and interval helpers, and reference contracts

Status: **Accepted**.
Date: 26 September 2026
Third of three standard-library phases (ADR-008, ADR-009, ADR-010).

## Context

With ADR-008 and ADR-009, Elci has Elm's core library, `Dict` and a canonical `Value`.
Reading a transaction still takes hand-written recipes, which the documentation repeats
and every contract copies:
- the signature search;
- finding the spent output from `scriptInfo`;
- the own policy id;
- the value paid to an address;
- the inline datum of an output.

Time checks go through Julc's `IntervalLib`, whose `finiteLowerBound` returns `-1` for an
unbounded range. That sentinel is a known trap: "no end" must not look like "ends at -1".

A beta also needs complete, tested contracts that show how the pieces fit, and that agents
can copy.

## Decision

### 1. `Interval`: the validity range, without sentinels

A core module (`import Interval`). Its `Interval` type is the ledger's, as `Value`'s is.
Times are POSIX milliseconds.

| Function | Meaning |
| --- | --- |
| `isEntirelyAfter t range` | every time in the range is after `t` (strictly) |
| `isEntirelyBefore t range` | every time in the range is before `t` (strictly) |
| `contains t range` | `t` is in the range, honoring inclusive bounds |
| `start range : Maybe Int`, `end range : Maybe Int` | the finite bounds, or `Nothing` when unbounded |
| `after t`, `before t`, `between a b`, `everything` | ranges: from `t` on, up to `t`, both inclusive |

The names follow Aiken's interval module, the closest precedent, and so do the checks. A
check needs a finite bound on its side:

- An unbounded side never satisfies it.
- Neither does an infinite bound on the wrong side, which only an empty range has: a
  `PosInf` start or a `NegInf` end.

So a range that fails closed, such as one decoded from a datum, never passes a time check.
The ledger's validity ranges never have these shapes.

### 2. `Tx`: questions about the transaction

A core module (`import Tx`). Each function takes the `ScriptContext` last, so checks read
`Tx.signedBy owner ctx`.

| Function | Meaning |
| --- | --- |
| `signedBy : PubKeyHash -> ScriptContext -> Bool` | the key signed the transaction |
| `spends : TxOutRef -> ScriptContext -> Bool` | the transaction spends the output |
| `findInput : TxOutRef -> ScriptContext -> Maybe TxInInfo` | a spent output by reference |
| `ownRef : ScriptContext -> Maybe TxOutRef` | when spending, the output being spent |
| `ownInput : ScriptContext -> Maybe TxInInfo` | when spending, that output with its value and datum |
| `ownPolicy : ScriptContext -> Maybe PolicyId` | when minting, the script's policy id |
| `outputsTo : Credential -> ScriptContext -> List TxOut` | the outputs paid to a payment credential |
| `valuePaidTo : Credential -> ScriptContext -> Value` | their total value |
| `continuingOutputs : ScriptContext -> List TxOut` | when spending, the outputs back to the spent output's address |
| `valueSpent`, `valueProduced : ScriptContext -> Value` | the total value of the inputs, and of the outputs |
| `mintedOf : PolicyId -> ScriptContext -> Dict TokenName Int` | the tokens a policy mints (negative quantities burn) |
| `inlineDatum : TxOut -> Maybe Data` | an output's inline datum |
| `datumOf : TxOut -> ScriptContext -> Maybe Data` | an output's datum, inline or by hash from the witnesses |

- **Credentials, not addresses, identify owners.** A payment is usually required to reach a
  key or a script whatever the staking part. `outputsTo` and `valuePaidTo` therefore match
  the payment credential only.
  - The payer chooses the staking part, so a payee's staking rights are not protected.
  - Code that cares about the staking part compares `output.address` directly, against an
    address stored in the datum.
- **`valuePaidTo` alone is open to double satisfaction.** When two inputs of a script can
  each require a payment to the same party, one output can satisfy both. Contracts look
  for a tagged output with `outputsTo`, and the doc comments say so.
- **Datums stay `Data`.** Decoding needs the datum's type: `Tx.inlineDatum out |>
  Maybe.map Data.decode`, with an annotation. Decoding is strict, as everywhere, so a
  malformed datum fails the script rather than looking absent.

The modules are written in Elci over the ledger types, with no new intrinsics. Their
costs are those of the list walks they perform, and the documentation states them.

### 3. Reference contracts

Three complete projects join `examples/`, each with scenarios for every rule and for every
way to break it, and properties over the boundaries:

- **Escrow.** A buyer locks payment for a seller. The seller claims it with the buyer's
  signature, or the buyer reclaims it after a deadline. It uses `Tx.signedBy`,
  `Interval.isEntirelyAfter`, `Tx.outputsTo` and `Tx.inlineDatum`.
- **NFT marketplace.** A seller lists an NFT at a price. Anyone buys it by paying the seller
  and the marketplace fee; the seller may cancel.
  - It uses `Tx.outputsTo`, `Tx.inlineDatum` and exact arithmetic on fees.
  - The fee must be between 0 and 10000 basis points.
  - An operator selling their own NFT is owed the whole price.
- **Order swap.** A maker locks tokens and asks for other tokens in return. A taker fills
  it by paying the maker the asked amount.
  - A partial fill returns everything else the order holds, lovelace and other tokens
    included, to the script under the same order datum.
  - A full fill pays the maker the price and everything the order held besides the
    offered tokens.
  - It uses `Tx.ownInput`, `Tx.continuingOutputs`, `Tx.inlineDatum` and `Value.geq`.
  - One input of the script per transaction keeps a returned output from standing for two
    orders.

All three tag their payments with the spent output's reference, against double
satisfaction. The scenarios show each attack failing:

- two script inputs settled with one payment;
- untagged and wrongly tagged payments;
- a missing signature;
- a changed continuing datum;
- a partial or full fill that keeps part of the order's value.

### 4. Equality on `Data`

Comparing an output's inline datum with an expected encoding,
`Tx.inlineDatum out == Just (Data.encode ref)`, is the standard datum check. So `==` on
`Data` is allowed, and it compares structure (`EqualsData`). Elci's encodings are
deterministic per type, so this is exact for values Elci encodes, with one exception. A
`Map` keeps insertion order and has no `==` of its own, so two encodings of equal maps may
differ.

### 5. Test library

- `Scenario.toKey key lovelace` pays a key's address with no staking part, as most
  payments do.
- `Scenario.withDatumHash datum output` holds a datum by hash. The runner puts the datum's
  hash in the output and the datum among the transaction's witnesses, so `Tx.datumOf` can
  be tested on both kinds of datum.

### 6. Reserved names

`Tx` and `Interval` join the core modules, so a project module can no longer have either
name (ELCI2104), as with every core module (ADR-007).

Each contract ships with a documentation page. The AI starter pack's examples come from the
same sources, as for the existing examples.

## Invariants and evidence required

- **Tests for every function**: the ledger shapes each handles, including unbounded
  ranges, mints without ADA, datum hashes and outputs to scripts.
- **Law properties.**
  - For `Interval`:
    - `contains` and the `isEntirely` checks agree for every finite bound shape;
    - with infinite bounds, a check holds only on a finite bound on its side;
    - `after t` contains exactly the times `>= t`.
  - For `Tx`, generated as scenarios: `valuePaidTo` sums what `outputsTo` returns, and
    `valueProduced` is the sum over the paid credentials.
- **Scenario suites** for the reference contracts: every success path, every failure path
  and a property per boundary.
- **Byte stability** of the existing examples, which are unchanged.

## Alternatives rejected

- **Keeping Julc's `IntervalLib` as the recommended API.** Its `-1` sentinel is exactly the
  kind of trap Elci exists to remove.
- **Address-level matching in `valuePaidTo`.** It is stricter in a way contracts rarely
  want, and it silently fails when a wallet adds a staking part. Matching the payment
  credential is the common need.
- **Typed datum helpers** such as `Tx.inlineDatumAs`. Elci has no way to name a type as an
  argument; an annotated `Data.decode` says the same thing.
