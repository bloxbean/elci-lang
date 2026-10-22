---
title: Agent instructions
description: A compact ruleset for AI coding agents working in an Elci project. Save it as AGENTS.md, CLAUDE.md or a Cursor rule.
sidebar:
  order: 3
---

Rules for AI coding agents working in an Elci project. Save this page as `AGENTS.md`, `CLAUDE.md` or
`.cursor/rules/elci.mdc` at the project root. For depth, read `/ai/starter-pack.md` and `/llms.txt` on
the Elci documentation site before writing code.

## What Elci is

Elci compiles an Elm 0.19 syntax subset (`.elm` files) to Plutus V3 validators for Cardano. It is not
browser Elm: there is no `Html`, `Cmd`, `port`, `elm.json`, Elm package, `Float`, `Char` or `Set`. Elm's
core modules `Basics`, `List`, `Maybe`, `Result`, `Tuple`, `String` and `Dict` exist with Elm's names and
argument orders, but `String` has only a few functions. A `Value` module handles the ledger's multi-asset
values, and the `Tx` and `Interval` modules answer questions about the transaction and its validity range.
Evaluation is strict.

## Must-follow rules

1. Give every top-level declaration a type signature. Module `A.B` lives at `src/A/B.elm`.
2. Elm's default imports apply: `Basics exposing (..)`, `List`, `Maybe exposing (Maybe(..))`,
   `Result exposing (Result(..))`, `String`, `Tuple`, `Debug`. Import `Dict exposing (Dict)`, `Map`,
   `Array`, `Value exposing (Value)`, `Interval`, `Tx`, `Bytes` and `Julc.Data as Data` when you use them.
   `Julc.Ledger` provides ledger types; expose constructors with `Type(..)`. Never name a module or alias
   `Basics`, `List`, `Maybe`, `Result`, `String`, `Tuple`, `Map`, `Dict`, `Array`, `Set`, `Value`, `Interval`,
   `Tx` or `Elci`, a constructor `Just`, `Nothing`, `True` or
   `False`, or a type `Int`, `Bool`, `String`, `Bytes`, `Data`, `List`, `Maybe`, `Dict`, `Map`, `Array` or
   `Float`.
3. Use only the core functions that exist (see `/ai/catalog.json`). Prelude: `min`, `max`, `abs`,
   `negate`, `clamp`, `compare` (`LT`/`EQ`/`GT`), `identity`, `always`, `not`, `xor`, `modBy`,
   `remainderBy`, `^`. `Maybe`: `withDefault`, `map`, `map2`, `map3`, `andThen`. `Result`: `map`, `map2`,
   `map3`, `andThen`, `withDefault`, `toMaybe`, `fromMaybe`, `mapError`. `String`: only `fromInt`,
   `isEmpty`, `append`, `concat`, `join`. `List` has all of Elm's except `map4`/`map5`, and `Dict` all of
   Elm's. Replacements: `Set` → `Dict k Bool`; `Debug.log` → `Debug.trace`; `String.length s` →
   `Bytes.length (Bytes.fromString s)`.
4. No `Float`, `/`, `%`, `number`, char literals or extensible records. Use `Int`, `//`, `modBy` and
   `remainderBy`. Operators work as functions: `(+)`, `(::)`. Accessor functions work as in Elm:
   `List.map .amount payouts`. Write qualified names without spaces. Tuples have 2 or 3 elements.
5. `<`, `>`, `<=`, `>=`, `compare`, `max` and `List.sort` work on comparable types: `Int`, `String`,
   `Bytes`, ledger hashes such as `PubKeyHash`, and lists and tuples of these. Sort records with
   `List.sortBy`. A signature variable used with `<` must be named `comparable`, with `++` `appendable`.
   `==` does not work on functions, `Map`, `Array` or `TxOut`; it works on `Dict`, `Value` and `Data`
   (exact structure: `Tx.inlineDatum out == Just (Data.encode expected)`).
   Arguments are strict: `Maybe.withDefault` evaluates its default.
6. Negation is Elm's: `f -1` passes `-1`; write `x - 1` to subtract.
7. Configure validators in `elci.toml`, never in source: `[validators.<name>]` with
   `boundary = "julc-strict-v1"`, handler keys `spend`/`mint`/`withdraw`/`certify`/`vote`/`propose` =
   `"Module.function"`, optional `parameters = [...]` and `spend-datum = "required" | "optional" | "absent"`.
   Keep `target = "plutus-v3-pv11-uplc-1.1.0"` and the `julc-version` the compiler prints.
8. Handler types: parameters first, then the datum (spend only; `Maybe datum` when optional), the
   redeemer, and `ScriptContext`, returning `Bool`. Expose every handler. Use `()` for unused values.
9. Datum, redeemer and parameter types must be Data-encodable: records, custom types, `Int`, `Bool`,
   `String`, `Bytes`, `()`, tuples, lists, `Dict`, `Map`, `Maybe`, `Result` and ledger types such as
   `PubKeyHash` and `TxOutRef`. Never functions, `Array`, `Value`, `TxOut` or `ScriptContext`; for an
   amount of tokens store `List ( PolicyId, TokenName, Int )` and call `Value.fromList`.
10. Records encode with fields sorted by name; custom types by declaration order; `Maybe` is
    `Nothing` 0, `Just` 1; `Result` is `Ok` 0, `Err` 1 (the reverse of PlutusTx `Either`). Julc
    optionals differ: convert with `Data.fromOptional`/`Data.toOptional`. A `Dict` is a Plutus map with
    strictly ascending keys: unsorted or repeated keys fail to decode, so build test data with
    `Data.encode (Dict.fromList ...)` and give `elci apply` sorted keys.
11. Use `Dict` for data you build or look up by a comparable key, including datums. Use `Map` for ledger
    maps (`txInfo.withdrawals`, `txInfo.redeemers`) and keys that are not comparable. Both look up in
    O(n); `Map.fromList` is O(n²), `Dict.fromList` O(n log n).
12. Keep `PubKeyHash` and `Bytes` apart; use `.hash` when raw bytes are needed, including `token.hash` for
    a `TokenName` (it has no `.name`). Read the transaction with the core `Tx` module:
    `Tx.signedBy key ctx`, `Tx.ownRef`, `Tx.ownInput`, `Tx.ownPolicy`, `Tx.outputsTo`,
    `Tx.continuingOutputs`, `Tx.mintedOf`, `Tx.inlineDatum`, `Tx.datumOf`. Don't hand-write these walks.
13. Every `let` value is evaluated even if unused. Put cheap checks first, inline, in `&&` chains.
    Keep expensive work out of top-level values; they run for every purpose of the script.
14. Check deadlines with the core `Interval` module: `Interval.isEntirelyAfter deadline ctx.txInfo.validRange`
    and `Interval.isEntirelyBefore deadline ctx.txInfo.validRange`. Both are strict and need a finite bound,
    so a transaction without a validity range fails them. `Scenario.validFrom` is inclusive and
    `Scenario.validBefore` exclusive. Don't use Julc's `IntervalLib`, whose bounds use a `-1` sentinel.
15. Read and build ledger values (`out.value`, `ctx.txInfo.mint`) with the core `Value` module:
    `Value.lovelaceOf v`, `Value.quantityOf policy name v`, `Value.geq paid required`, `==` (exact). Make
    asset ids with `PolicyId bytes` and `TokenName bytes` from `Julc.Ledger exposing (PolicyId(..),
    TokenName(..))`. A mint holds burns as negative quantities; split it with `Value.positive` and
    `Value.negative`. Never read values through `Julc.Values` (`ValuesLib`): Java exports that return a
    `Value` are rejected (`ELCI2105`), except the ledger accessors of `ContextsLib` and `OutputLib`.
16. Java libraries need a `[java-modules]` mapping, explicit `exposing` lists, and take the subject
    first (`Output.outputsAt outputs address`). Never pass a type containing a `Dict` or a `Value` to a
    generic Java function (`ELCI3003`).
17. Annotate a `Data.decode` result when inference cannot see its type (`ELCI3001`).
18. Tests live in `tests/`. Only test modules may import `Expect`, `Scenario` and `Property`. A test is
    an exposed zero-argument value of type `Bool`, `Expectation`, `Scenario` or `Property`; an exposed
    function returning `Property` is a property test. Keep helpers unexposed.
19. Every scenario passes all validator parameters with `Scenario.parameters [ Data.encode x, ... ]`.
    Cover the success path, every failure path (`Scenario.expectFailure`) and at least one property
    that generates offsets around each boundary. Pay a key with `Scenario.toKey key lovelace`; attach an
    inline datum with `Scenario.withDatum` or a datum by hash with `Scenario.withDatumHash`.
20. Do not weaken tests or add `expectFailure` to make a failing test pass. Fix the validator.

## Security rules for validators

The `escrow`, `marketplace` and `order-swap` example projects follow the rules for spend validators and test each attack.

1. Tag every payment a spend validator requires: the paying output carries the spent output's reference,
   `Tx.inlineDatum output == Just (Data.encode ref)` with `ref` from `Tx.ownRef ctx`. An untagged
   `Tx.valuePaidTo` check lets one output pay for two script inputs (double satisfaction).
2. When outputs back to the script are matched by address (`Tx.continuingOutputs`), allow one input of
   the script per transaction.
3. Check the whole value that stays at the script with `Value.geq`, not one token, so the spender can't keep
   the lovelace or other tokens. To stop padding with junk tokens too, compare `Value.withoutLovelace` of
   both values with `==` and their lovelace with `>=`.
4. Use `Value.geq` or `>=` for "pays at least"; `==` on values is exact.
5. Require `Tx.signedBy` for every privileged action, and test it with a stranger's signature.
6. Bound numbers from datums and parameters before using them: a fee in basis points between 0 and 10000,
   quantities above 0. Parameters are not validated on-chain.
7. `Tx.outputsTo` matches the payment credential; whoever builds the transaction chooses the payee's
   staking part. Store and compare the full `output.address` when the staking part matters.
8. A minting policy checks the whole mint under its policy:
   `Dict.toList (Tx.mintedOf policy ctx) == [ ( TokenName name, quantity ) ]`.

## Workflow

Run these from the project directory after every change and fix every error before moving on:

```sh
elci check                  # compile; prints FLAT size per validator
elci test --seed 42         # run tests; reproduce with the printed seed
elci test --match <name>    # rerun one test; --fuzz 1000 for deeper property runs
elci build                  # write build/validators/<name>/ artifacts
```

Use `elci eval` only in a function project (`[entrypoint]`) to evaluate helper logic. Apply
parameters with `elci apply --validator <name> --param name='{"bytes": "..."}'`, which validates them
off-chain. Every command exits with status 0 on success and 1 on any error or failing test.

## Reading diagnostics

A diagnostic's first line is `path:line:col: ELCIxxxx: message`, and its form never changes. Below it come
the source line with a caret under the reported token, and zero or more `Hint:` lines:

```text
src/Main.elm:8:5: ELCI2005: Unknown name 'double'

8|     double 4
       ^^^^^^
Hint: write `Helper.double`, or expose `double` in its import
```

- Fix the part the message names: the argument, branch, list element, operator side, annotation or field
  whose type differs, or each pattern a `case` is missing.
- Apply the hint when it fits: a close name (`did you mean`), a conversion such as `String.fromInt`,
  `Bytes.fromString`, `.hash` or `Maybe.withDefault`, or an import to add.
- A run reports every error it finds, in source order, up to 20, with a count such as `3 errors`. Each
  top-level declaration reports its first error, and type errors appear only once syntax, import and
  qualified-name errors are fixed. Fix what is reported, then rerun `elci check`.
- `elci explain <code>` explains a code, such as `elci explain ELCI2002`, with a failing example and its
  fix. The last line of a failed run names the codes to look up.
- To parse errors, add `--report=json` to `check`, `build`, `eval` or `test`. Each error is then one JSON
  object on its own line on stderr (JSON Lines), with `code`, `path`, `line`, `column`, `message`, `hints`,
  `excerpt` and `context`.

Look the code up in the starter pack's error table: `ELCI2002` type mismatch, `ELCI2003` equality,
ordering or append on a type without it, `ELCI2004` missing signature, `ELCI2005` unknown name, `ELCI2007`
non-exhaustive case, `ELCI2013` type cannot cross the data boundary, `ELCI2014` handler signature does not
match `elci.toml`, `ELCI2102` ambiguous name, `ELCI2101`/`ELCI2104` import problems or a function Elci
lacks, `ELCI2105` a Java export Elci can't use, such as one that returns a `Value`.

## Done means

- `elci check` and `elci test` both exit with status 0.
- Every rule of the validator has a passing scenario, and every way to break it has an
  `expectFailure` scenario or a property, including each attack in the security rules that applies.
- elm-format 0.8.8 `--validate` passes. elm-format corrupts integer literals beyond 64 bits, so
  re-check any large constant after formatting.
