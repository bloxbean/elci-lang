---
title: Elci AI starter pack
description: Everything an AI agent needs to write correct Elci on the first try. Ingest this page before generating Elci code.
sidebar:
  order: 2
---

> **Read this entire page before generating any Elci code.** It states the Elci subset of Elm, the
> project format, the on-chain rules and the mistakes agents make most often. Every rule, signature
> and message here was checked with the Elci CLI (`elci 0.1.0-dev / julc 0.1.0-gb-5a3378b`).

This page is optimized for AI ingestion, not for humans. For the tutorial, see
[your first validator](/start/first-validator/). Sections are numbered so you can cite them.

---

## 1. What Elci is, and what it is not

- **Elci** compiles `.elm` source written in an **Elm 0.19 syntax subset** into **Plutus V3** scripts
  for Cardano. It lowers to typed PIR and uses Julc's backend (validator wrapper, UPLC optimizer,
  evaluator). The only target is `plutus-v3-pv11-uplc-1.1.0`.
- **On-chain only.** Elci code is validator logic. Building transactions is done off-chain with other
  tools (for example Cardano Client Lib, reading `validator.plutus.json`).
- **Not browser Elm.** There is no `Html`, `Browser`, `Cmd`, `Sub`, `port`, JSON decoders or runtime.
- **No Elm packages.** `elm.json` is not used. There is no package manager.
- **Elm's core library, partly.** `Basics`, `List`, `Maybe`, `Result`, `Tuple`, `String` and `Debug` are
  imported by default, as in Elm, with Elm's names and argument orders, but `String` has only five
  functions and `Debug` only `trace`. `Dict` exists with Elm's API (`import Dict exposing (Dict)`); `Set`,
  `Char` and `Float` **do not exist** (section 3.3). A `Value` module (`import Value exposing (Value)`)
  handles the ledger's multi-asset values (section 7). `Tx` and `Interval` answer questions about the
  transaction and its validity range (section 8); prefer them to hand-written list walks and to Julc's
  `ContextsLib` and `IntervalLib`.
- **Strict, not lazy.** Every `let` value and every function argument is evaluated (section 4).
- **Configuration decides deployment.** Handlers are ordinary functions; `elci.toml` says which
  function serves which ledger purpose (section 2).
- **Development preview.** Not production-ready. No CIP-57 blueprints, no language server.

## 2. Project layout and `elci.toml`

`elci init my-project` creates a validator project; `elci init my-project --purpose function` creates a
function project for `elci eval`.

```text
my-project/
  elci.toml            project configuration (strict TOML subset)
  src/Main.elm         source modules; module A.B lives at src/A/B.elm
  tests/MainTest.elm   test modules (validator projects only)
  build/               outputs of elci build (git-ignored)
```

A complete `elci.toml` using every validator setting:

```toml title="elci.toml"
# Settings for the whole project.
[project]
source-directories = ["src", "lib"]
target = "plutus-v3-pv11-uplc-1.1.0"
julc-version = "0.1.0-gb-5a3378b"
library-jars = ["libs/pricing.jar"]

# One script with two purposes and a deployment parameter.
[validators.treasury]
boundary = "julc-strict-v1"
parameters = ["admin"]
spend = "Treasury.spend"
withdraw = "Treasury.withdraw"
spend-datum = "optional"
expected-abi = "abi/treasury.json"

# A second, independent script.
[validators.badge]
boundary = "julc-strict-v1"
parameters = ["admin"]
mint = "Badge.mint"

[java-modules]
"Julc.Math" = "org.julclang.stdlib.lib.MathLib"
"Acme.Pricing" = "acme.Pricing"

[tests]
source-directories = ["spec"]
```

A function project instead has:

```toml title="elci.toml"
[project]
source-directories = ["src"]
target = "plutus-v3-pv11-uplc-1.1.0"
julc-version = "0.1.0-gb-5a3378b"

[entrypoint]
module = "Main"
function = "main"
purpose = "function"
```

### 2.1 Rules

1. `[project]` requires `source-directories`, `target = "plutus-v3-pv11-uplc-1.1.0"` and
   `julc-version = "0.1.0-gb-5a3378b"` (must equal the compiler's pinned version; `elci --version` prints it).
   Optional: `library-jars`; `expected-abi` only in function projects.
2. A project has **either** one or more `[validators.<name>]` tables **or** one `[entrypoint]`, never both.
3. `[validators.<name>]`: name is 1–64 chars of `a-z`, `0-9`, `-`, `_`, starting with a letter. `boundary =
   "julc-strict-v1"` is required. At least one handler key: `spend`, `mint`, `withdraw`, `certify`,
   `vote`, `propose`, each `"Module.function"` naming an **exposed** function. One table = one script = one hash.
4. `parameters = ["a", "b"]`: deployment parameters in application order; names match `[a-z][A-Za-z0-9_]*`.
   Every handler of the table receives all of them as leading arguments, with the same types.
5. `spend-datum = "required"` (default), `"optional"` or `"absent"`; requires a `spend` handler.
6. `expected-abi = "path"`: `check`, `build` and `apply` fail if the ABI (`abi.json`) differs.
7. `[java-modules]`: `"Module.Name" = "fully.qualified.Class"`. You choose the module name. `Julc.TypedMap`
   and `Julc.TypedArray` are reserved, and so are the core module names (`Basics`, `List`, `Maybe`,
   `Result`, `String`, `Tuple`, `Map`, `Dict`, `Array`, `Set`, `Value`, `Interval`, `Tx`, `Elci`) and names
   starting with `Elci.`.
8. `[tests]`: `source-directories`, default `["tests"]`.
9. TOML subset: one `key = value` per line; values are quoted strings without escapes or single-line
   arrays of quoted strings; comments are whole lines starting with `#` (no trailing comments); unknown
   tables and keys are errors.

## 3. The language subset

### 3.1 Allowed

- Modules: `module A.B exposing (f, T, U(..))` or `exposing (..)`; `import M as N exposing (x, T(..))`.
  Imports must be acyclic. Every module has Elm's default imports (section 8). Own declarations shadow
  imports; a name two imports expose is ambiguous where used, except that a name listed in `exposing`
  beats a default import.
- **Every top-level declaration needs a type signature.** Local (`let`) definitions may have one.
- Functions, lambdas `\a b -> ...`, currying, partial application, `|>`, `<|`, `>>`, `<<`. Operators as
  functions: `(+)`, `(::)`, `(<)`, as in `List.foldl (+) 0 xs`. `^` is integer power.
- Rank-1 generics (`a -> a`), specialized per concrete use. Local helpers may be used at several types.
  Elm's constrained variables: a signature variable compared with `<`/`compare` must be named
  `comparable…`, one appended with `++` `appendable…`, both `compappend…`.
- Types nothing determines need no annotation (`[] == []`, `List.length []`, `Data.encode Nothing`),
  except where a value is created: `Data.decode` and generic Java functions (`ELCI3001`).
- Self-recursion and mutually recursive functions (top-level or local).
- Types: `Int` (arbitrary precision), `Bool`, `String`, `()`, `Bytes`, `Data`, `List a`, `Maybe a`,
  `Result error value`, `Order`, `Dict k v` (comparable keys), `Map k v`, `Array a`, `Value`, tuples of 2
  or 3, closed records, custom types (generic, recursive).
- `type alias R = { x : Int }` (also a constructor `R 1`); `type T a = A a | B`.
- Records: `{ x = 1 }`, `r.x`, `{ r | x = 2 }`, destructuring `{ x, y }` in parameters and `let`.
  Accessor functions as in Elm: `.x` is `\r -> r.x`, so `List.map .amount payouts` works. A field of a
  qualified value: `Config.settings.fee`. Write qualified names without spaces.
- `case` with nested patterns: constructors, `[]`, `[ a, b ]`, `a :: rest`, tuples, record fields, `as`,
  `_`, `()`, `True`/`False`, Int and String literals. Exhaustive and no unreachable branches.
- `if c then a else b` (else required), `let ... in`, `++` on `String` and `List`, `::`.
- `<`, `<=`, `>`, `>=`, `compare`, `min`, `max`, `List.sort` on **comparable** types: `Int`, `String`
  (UTF-8 byte order), `Bytes`, ledger hashes (`PubKeyHash`, `ScriptHash`, `PolicyId`, `TokenName`,
  `TxId`, …), and lists and tuples of these.
- Comments `--`, `{- nested {- ok -} -}`, doc comments `{-| -}`.

### 3.2 Rejected by the compiler

| Construct | Result |
| --- | --- |
| `Float`, `1.5`, `/` | `ELCI1001`/`ELCI2001`; use scaled `Int` and `//` |
| `number` in a signature | `ELCI2003`; write `Int` |
| `a > b` or `a ++ b` on a signature variable not named `comparable…`/`appendable…` | `ELCI2003`; rename it |
| Extensible records `{ r \| x : Int }` | `ELCI1002` |
| Refutable patterns in function or lambda arguments (`f (x :: _) = ...`) | `ELCI1002`; use `case` |
| Chained comparisons `a < b < c` | `ELCI1002`; use `a < b && b < c` |
| Tuples of 4 or more | `ELCI1002` |
| `(/)` | `ELCI2001`; use `(//)` |
| Char literals `'a'`, `%`, hex `0xFF`, `1_000` | `ELCI1001` |
| `port module`, `import Html` | `ELCI1002` / `ELCI2101` |
| Polymorphic recursion, recursive values (`a = b + 1`, `b = a + 1`) | `ELCI2002` / `ELCI2008` |
| `==` on functions, `Map`, `Array`, `TxOut`, `TxInInfo`, `TxInfo` (`Data` and `Value` do have `==`) | `ELCI2003` |
| `<`, `compare`, `List.sort` on `Bool`, records, custom types, `Maybe`, `Dict`, `Map`, `Value`, `Data`, `TxOutRef` | `ELCI2003`; use `List.sortBy` with a key; compare values with `Value.geq` |
| `Dict` keys that are not comparable (`Dict.get` on a `Dict Credential Int`; a `Dict Bool Int` in a datum or `Data.decode`) | `ELCI2003`; use a `Map` |
| A Java library generic at a type containing a `Dict` | `ELCI3003`; pass a `Map` or `List`, then `Dict.fromList` |
| A Java export whose result can hold a `Value` (`ValuesLib.add`, `ValuesLib.singleton`, a Java record with a `Value` field, …); `ContextsLib` and `OutputLib` are exempt | `ELCI2105`; use the core `Value` module |
| A Java library generic at a type holding a `Value` (`Lists.filter values f`) | `ELCI3003`; use `List` and the `Value` module |
| `Value` in a datum, redeemer or parameter type | `ELCI2013`; store `List ( PolicyId, TokenName, Int )`, rebuild with `Value.fromList` |
| Module named `Basics`, `List`, `Maybe`, `Result`, `String`, `Tuple`, `Map`, `Dict`, `Array`, `Set`, `Value`, `Interval`, `Tx`, `Elci` or `Elci.*`; alias named after a default import (`Basics`, `List`, `Maybe`, `Result`, `String`, `Tuple`, `Debug`) | `ELCI2104` |
| Constructors named `Just`, `Nothing`, `True`, `False`; types named `Int`, `Bool`, `String`, `Bytes`, `Data`, `List`, `Maybe`, `Dict`, `Map`, `Array`, `Float` | `ELCI2011` |
| `f <\| x \|> g`, `f << g >> h` (opposite associativity mixed) | `ELCI1002`; add parentheses |
| `Tuple a b`, `Unit` as type names | `ELCI2012`; write `( a, b )`, `()` |

### 3.3 Elm idioms that do NOT exist in Elci, and what to write instead

| Elm | Elci |
| --- | --- |
| `String.length s` | `Bytes.length (Bytes.fromString s)` (UTF-8 byte count) |
| `String.toInt`, `split`, `slice`, `toUpper` and other `String.*` | none; keep numbers as `Int` and bytes as `Bytes`. `String` has only `fromInt`, `isEmpty`, `append`, `concat`, `join` |
| `String.fromFloat`, `toFloat`, `round`, `sqrt`, `pi` | none; no `Float`: use scaled `Int` |
| `Set` | `Dict k Bool` or a `List` (`Dict k ()` is rejected: `()` has no Data encoding) |
| `Char`, `'a'` | none |
| `List.map4`, `Maybe.map4`, `Result.map5`, … | nest `map3`, or `case` |
| `Debug.log`, `Debug.toString`, `Debug.todo` | `Debug.trace : String -> a -> a`; `String.fromInt` for numbers |
| `Bytes.lessThan` (removed) | `a < b` |

Everything else in Elm's `Basics`, `List`, `Maybe`, `Result`, `Tuple` exists with Elm's signature
(section 8). This program uses them; it compiles and runs:

```elm title="src/Main.elm"
module Main exposing (main)


type alias Payout =
    { beneficiary : String
    , amount : Int
    }


payouts : List Payout
payouts =
    [ Payout "carol" 30, Payout "alice" 50, Payout "bob" 30 ]


main : ( ( Int, Maybe Int, List String ), ( String, Int, Result String Int ) )
main =
    ( ( List.sum (List.map (\p -> p.amount) payouts)
      , List.maximum (List.map (\p -> p.amount) payouts)
      , List.map (\p -> p.beneficiary) (List.sortBy (\p -> p.amount) payouts)
      )
    , ( String.join ", " (List.map (\p -> p.beneficiary ++ "=" ++ String.fromInt p.amount) payouts)
      , Maybe.withDefault 0 (List.head (List.range 5 9))
      , Result.fromMaybe "no payouts" (Maybe.map (\p -> p.amount) (List.head payouts))
      )
    )
```

```text
$ elci eval
( ( 110, Just 50, ["carol", "bob", "alice"] ), ( "carol=30, alice=50, bob=30", 5, Ok 30 ) )
cpu 65205046, mem 276517
```

## 4. Evaluation rules

1. **Strict.** Every `let` binding is evaluated before the body, even if unused. An unused
   `let bad = 10 // 0` fails the script.
2. **Selective forms only:** `if`, `case`, `&&`, `||` evaluate just the taken branch/operand. Put cheap,
   likely-false checks first **inline**; a check bound in `let` is always paid for.
3. **Order:** function, then arguments left to right; `let` bindings in dependency order, ties in source
   order; record fields, list and tuple elements in source order; record update: base, then new fields.
   Arguments are always evaluated: `Maybe.withDefault expensive m` computes `expensive` even for a
   `Just`, and `(&&) a b` as a function evaluates `b`. Use `case` or the `&&` operator to skip work.
4. **Top-level values** (zero-argument declarations) reachable from any handler run once per script
   execution **before** purpose dispatch, so every purpose pays for them. Make expensive ones functions
   (`limit : () -> Int`).
5. **Negation** follows Elm 0.19: `f -1` passes `-1`; `scale -x 10` passes `-x`; `x - 1` and `x-1`
   subtract; `x -1` is an application of `x`.
6. **Integers:** `//` truncates towards zero (`-7 // 2` is `-3`). `modBy d v` has the divisor's sign
   (`modBy 3 -7` is `2`); `remainderBy d v` has the value's sign (`remainderBy 3 -7` is `-1`). Division by
   zero fails the script.
7. **Failure:** a runtime failure (division by zero, out-of-range byte, failed decode) or a handler result
   of `False` rejects the transaction. There are no exceptions to catch.
8. **Equality** (`==`, `/=`): Int, Bool, String, `()`, Bytes, ledger hashes of the same type, `Value`
   (exact), `Data` (exact structure), lists, tuples, records, `Maybe`, `Dict` (same bindings) and custom
   types of these; imported types with approved equality. Not `Map`, functions, `Array`, `TxOut`,
   `TxInInfo`, `TxInfo`. Both operands are evaluated once, left to right.
9. **Ordering** (`<`, `<=`, `>`, `>=`, `compare`, `min`, `max`, `List.sort`): Int numerically; String by
   UTF-8 bytes (`"Zebra" < "apple"`); Bytes and ledger hashes lexicographically by unsigned byte, a
   proper prefix first (`#ff` > `#0001`); lists lexicographically; tuples by component. Every order agrees
   with `==`.
10. **Integer power:** `2 ^ 10` is `1024`; `^` binds tighter than `*` and groups right; a negative
    exponent fails the script.

## 5. Data encoding and `Julc.Data`

Datums, redeemers and parameters cross the script boundary as Plutus Data and are **decoded strictly**
(tags, arity, field kinds checked; malformed data fails the script, never `Nothing`).

| Elci value | Plutus Data |
| --- | --- |
| `Int` | integer |
| `Bytes`, ledger hashes | byte string |
| `String` | byte string (UTF-8) |
| `Bool` | `False` = constructor 0, `True` = constructor 1, no fields |
| `()` | constructor 0, no fields (as datum/redeemer; `Julc.Data.encode ()` is rejected) |
| record | constructor 0, fields **sorted by field name** (not declaration order) |
| tuple | constructor 0, fields in position order |
| custom type | constructor tag = variant's declaration position from 0, fields in order |
| `Maybe a` | `Nothing` = constructor 0; `Just x` = constructor 1 `[x]` |
| `Result e a` | `Ok x` = constructor 0 `[x]`; `Err e` = constructor 1 `[e]` (the **reverse** of PlutusTx `Either`) |
| `Order` | `LT`, `EQ`, `GT` = constructors 0, 1, 2 |
| `JulcOptional a` (Java `Optional`) | `Some x` = constructor 0 `[x]`; `None` = constructor 1 |
| `List a` / `Map k v` | list / map (in the map's order) |
| `Dict k v` | map with **strictly ascending keys**; unsorted or repeated keys fail to decode (datum, redeemer, parameter, `Data.decode`) |
| `Array a`, functions | **no encoding**; cannot be stored in data (`ELCI2013`) |

```elm
type Action
    = Buy Int
    | Cancel


type alias Payout =
    { beneficiary : Int, amount : Int }


main : List Data
main =
    [ Data.encode (Just 5)
    , Data.encode Cancel
    , Data.encode (Buy 3)
    , Data.encode { beneficiary = 1, amount = 2 }
    , Data.encode ( 1, True )
    , Data.encode [ 1, 2 ]
    , Data.encode (Data.toOptional (Just 5))
    , Data.encode "hi"
    ]
```

```text
$ elci eval
[Constr 1 [5], Constr 1, Constr 0 [3], Constr 0 [2, 1], Constr 0 [1, Constr 1], [1, 2], Constr 0 [5], #6869]
cpu 5878521, mem 25242
```

`Julc.Data` (`import Julc.Data as Data`): `decode : Data -> a`, `encode : a -> Data`,
`fromOptional : JulcOptional a -> Maybe a`, `toOptional : Maybe a -> JulcOptional a`. The decoded type
must be known where `decode` is used; annotate otherwise
(`ELCI3001: Cannot determine the type converted by Julc.Data.decode; add a type annotation`).
`==` on `Data` compares structure exactly, so a datum check needs no decoding:
`Tx.inlineDatum out == Just (Data.encode expected)`. Elci's encodings are deterministic, except that a
`Map`'s encoding follows its insertion order: `Data.encode` of two maps with the same bindings may differ,
so compare their sorted `toList` or use a `Dict`. Tests build datums and redeemers with `Data.encode`.

## 6. Handler signatures

`parameters` come first, in the order listed in `elci.toml`. The last argument is always `ScriptContext`
(`import Julc.Ledger exposing (ScriptContext)`). The result is `Bool`. Handlers must be exposed and have
concrete types.

| Handler key | Required type |
| --- | --- |
| `spend` | `params -> datum -> redeemer -> ScriptContext -> Bool` |
| `spend` + `spend-datum = "optional"` | `params -> Maybe datum -> redeemer -> ScriptContext -> Bool` |
| `spend` + `spend-datum = "absent"` | `params -> redeemer -> ScriptContext -> Bool` |
| `mint`, `withdraw`, `certify`, `vote`, `propose` | `params -> redeemer -> ScriptContext -> Bool` |

- Datum, redeemer and parameter types need a strict Data boundary: your records and custom types, `Int`,
  `Bool`, `String`, `Bytes`, `()`, tuples, lists, `Dict`, `Map`, `Maybe`, and ledger types such as
  `PubKeyHash`, `TxOutRef`, `Credential`, `PolicyId`, `TokenName`. Not: functions, `Array`, `ScriptContext`,
  `TxOut`, `TxInfo`, `Value` (for an amount of tokens, store `List ( PolicyId, TokenName, Int )` and call
  `Value.fromList`).
- Use `()` for an unused redeemer or datum. Only the selected handler's datum and redeemer are decoded.
- A validator always checks the purpose; purposes without a handler are rejected.
- Parameters are decoded but **not validated on-chain**; `elci apply` validates them off-chain. Exception:
  the key order of a `Dict` (in a datum, redeemer or parameter) is checked on every run.

## 7. The script context and ledger types

`import Julc.Ledger exposing (ScriptContext, PubKeyHash)` exposes types. To match or build a sum type's
constructors unqualified, expose them with `(..)`: `Credential(..)`, `ScriptInfo(..)`, `OutputDatum(..)`,
`IntervalBoundType(..)`. `exposing (..)` exposes types only, not constructors (a `PubKeyCredential`
pattern then fails with `ELCI2001`). Fields are read with `.field`.

| Expression | Type |
| --- | --- |
| `ctx.txInfo` | `TxInfo` |
| `ctx.scriptInfo` | `ScriptInfo` |
| `ctx.redeemer` | `Data` (raw; the handler already gets it decoded) |
| `tx.inputs`, `tx.referenceInputs` | `List TxInInfo` |
| `tx.outputs` | `List TxOut` |
| `tx.fee` | `Int` (lovelace) |
| `tx.mint` | `Value` |
| `tx.certificates` | `List TxCert` |
| `tx.withdrawals` | `Map Credential Int` |
| `tx.validRange` | `Interval` (POSIX milliseconds) |
| `tx.signatories` | `List PubKeyHash` |
| `tx.redeemers` | `Map ScriptPurpose Data` |
| `tx.datums` | `Map DatumHash Data` |
| `tx.id` | `TxId` |
| `tx.proposalProcedures` | `List ProposalProcedure` |
| `input.outRef` / `input.resolved` | `TxOutRef` / `TxOut` |
| `out.address`, `out.value`, `out.datum`, `out.referenceScript` | `Address`, `Value`, `OutputDatum`, `JulcOptional ScriptHash` |
| `ref.txId`, `ref.index` | `TxId`, `Int` |
| `addr.credential`, `addr.stakingCredential` | `Credential`, `JulcOptional StakingCredential` |
| `range.from`, `range.to` | `IntervalBound` |
| `bound.boundType`, `bound.isInclusive` | `IntervalBoundType`, `Bool` |
| `pkh.hash`, `txId.hash`, `policy.hash`, `token.hash` (a `TokenName`; it has no `.name`), `scriptHash.hash` | `Bytes` |

Sum types: `Credential = PubKeyCredential PubKeyHash | ScriptCredential ScriptHash`;
`OutputDatum = NoOutputDatum | OutputDatumHash DatumHash | OutputDatumInline Data`;
`IntervalBoundType = NegInf | Finite Int | PosInf`;
`ScriptInfo = MintingScript PolicyId | SpendingScript TxOutRef (JulcOptional Data) | RewardingScript Credential | CertifyingScript Int TxCert | VotingScript Voter | ProposingScript Int ProposalProcedure`.

```elm
keyOf : Credential -> Bytes
keyOf cred =
    case cred of
        PubKeyCredential pkh ->
            pkh.hash

        ScriptCredential sh ->
            sh.hash
```

For datums, use `Tx.inlineDatum out` or `Tx.datumOf out ctx` (section 8) rather than matching
`OutputDatum` yourself.

Rules:

- `PubKeyHash`, `ScriptHash`, `PolicyId`, `TokenName`, `TxId`, `DatumHash` are **distinct from `Bytes`**;
  compare like with like, or use `.hash`. `key == Bytes.fromString "k"` with `key : PubKeyHash` is
  `` ELCI2002: The two sides of == have different types: the left is `PubKeyHash`, but the right is `Bytes` ``,
  with a hint to wrap the bytes with the constructor. `token.name` is
  `ELCI2010: Unknown field 'name' of TokenName`; write `token.hash`.
- Signature check: `Tx.signedBy key ctx`.
- Ledger hashes order like bytes: `List.sort` sorts a `List PubKeyHash`. `TxOutRef` and other ledger
  records are not comparable.
- Construct ledger values qualified: `import Julc.Ledger as Ledger` then `Ledger.PubKeyHash bytes`,
  `Ledger.TxOutRef (Ledger.TxId bytes) 0`.
- `Value` (`out.value`, `tx.mint`) is the core `Value` type: read it with the `Value` module (section 8),
  `Value.lovelaceOf value`, `Value.quantityOf policy tokenName value`. Build asset ids with
  `import Julc.Ledger exposing (PolicyId(..), TokenName(..))`: `PolicyId bytes`, `TokenName (Bytes.fromString "BADGE")`.
  Ada is `PolicyId Bytes.empty` with `TokenName Bytes.empty`; use `Value.lovelace` and `Value.lovelaceOf`.
- Every `Value` is canonical (policies and token names ascending, no zero quantity), so `==` on values is
  exact. `TxOut`, `TxInInfo` and `TxInfo`, which contain a `Value`, have no equality. A `Value` cannot
  cross the boundary yet. In `tx.mint`, burns are negative quantities.
- `JulcOptional` has no equality; convert with `Data.fromOptional`.

## 8. Core modules and test library (exact signatures)

Every source and test module implicitly has, as in Elm:

```elm
import Basics exposing (..)
import List
import Maybe exposing (Maybe(..))
import Result exposing (Result(..))
import String
import Tuple
import Debug
```

`Bytes`, `Dict`, `Map`, `Array`, `Value`, `Interval`, `Tx` and `Julc.Data` need explicit imports. Using one without its import is
`` ELCI2104: Bytes.fromString needs the Bytes module; add `import Bytes` ``. Machine-readable signatures of
every module are in `/ai/catalog.json`.

**Basics** (unqualified everywhere):

```text
negate : Int -> Int
abs : Int -> Int
min : comparable -> comparable -> comparable
max : comparable -> comparable -> comparable
clamp : Int -> Int -> Int -> Int                -- clamp low high x
compare : comparable -> comparable -> Order     -- type Order = LT | EQ | GT
identity : a -> a
always : a -> b -> a
not : Bool -> Bool
xor : Bool -> Bool -> Bool
modBy : Int -> Int -> Int                        -- modBy divisor value; divisor's sign
remainderBy : Int -> Int -> Int                  -- value's sign
(^) : Int -> Int -> Int                          -- negative exponent fails
```

Built-in constructors: `Just`, `Nothing`, `True`, `False`. Every binary operator is also a function:
`(+)`, `(::)`, `(<)`, `(++)`.

**List** (element first, accumulator second in folds; `map2`/`map3` stop at the shortest list; sorts are
stable):

```text
singleton : a -> List a
repeat : Int -> a -> List a                      -- [] when n <= 0
range : Int -> Int -> List Int                   -- range 1 3 == [ 1, 2, 3 ]; range 5 1 == []
map : (a -> b) -> List a -> List b
indexedMap : (Int -> a -> b) -> List a -> List b
filter : (a -> Bool) -> List a -> List a
filterMap : (a -> Maybe b) -> List a -> List b
foldl : (a -> b -> b) -> b -> List a -> b
foldr : (a -> b -> b) -> b -> List a -> b
concat : List (List a) -> List a
concatMap : (a -> List b) -> List a -> List b
intersperse : a -> List a -> List a
map2 : (a -> b -> result) -> List a -> List b -> List result
map3 : (a -> b -> c -> result) -> List a -> List b -> List c -> List result
length : List a -> Int
reverse : List a -> List a
member : a -> List a -> Bool
all : (a -> Bool) -> List a -> Bool
any : (a -> Bool) -> List a -> Bool
maximum : List comparable -> Maybe comparable
minimum : List comparable -> Maybe comparable
sum : List Int -> Int
product : List Int -> Int
append : List a -> List a -> List a
isEmpty : List a -> Bool
head : List a -> Maybe a
tail : List a -> Maybe (List a)
take : Int -> List a -> List a                   -- [] when n <= 0
drop : Int -> List a -> List a                   -- unchanged when n <= 0
partition : (a -> Bool) -> List a -> ( List a, List a )   -- ( passing, failing )
unzip : List ( a, b ) -> ( List a, List b )
sort : List comparable -> List comparable
sortBy : (a -> comparable) -> List a -> List a
sortWith : (a -> a -> Order) -> List a -> List a
```

**Maybe** (`Maybe`, `Just`, `Nothing` built in):

```text
withDefault : a -> Maybe a -> a                  -- evaluates the default even for Just
map : (a -> b) -> Maybe a -> Maybe b
map2 : (a -> b -> value) -> Maybe a -> Maybe b -> Maybe value
map3 : (a -> b -> c -> value) -> Maybe a -> Maybe b -> Maybe c -> Maybe value
andThen : (a -> Maybe b) -> Maybe a -> Maybe b
```

**Result** (`type Result error value = Ok value | Err error`):

```text
map : (a -> value) -> Result x a -> Result x value
map2 : (a -> b -> value) -> Result x a -> Result x b -> Result x value     -- first Err wins
map3 : (a -> b -> c -> value) -> Result x a -> Result x b -> Result x c -> Result x value
andThen : (a -> Result x b) -> Result x a -> Result x b
withDefault : a -> Result x a -> a
toMaybe : Result x a -> Maybe a
fromMaybe : x -> Maybe a -> Result x a
mapError : (x -> y) -> Result x a -> Result y a
```

**Tuple** (pairs only):

```text
pair : a -> b -> ( a, b )
first : ( a, b ) -> a
second : ( a, b ) -> b
mapFirst : (a -> x) -> ( a, b ) -> ( x, b )
mapSecond : (b -> y) -> ( a, b ) -> ( a, y )
mapBoth : (a -> x) -> (b -> y) -> ( a, b ) -> ( x, y )
```

**String** (UTF-8 text; these five only):

```text
fromInt : Int -> String                          -- fromInt -42 == "-42"
isEmpty : String -> Bool
append : String -> String -> String
concat : List String -> String
join : String -> List String -> String           -- join ", " [ "a", "b" ] == "a, b"
```

**Dict** (`import Dict exposing (Dict)`; Elm's API; a Plutus map with keys strictly ascending; keys must be
comparable; `keys`/`toList`/folds in ascending key order; `get`/`member`/`insert`/`remove` O(n), stopping at
the first key not smaller; `fromList` O(n log n), last binding wins; `union` prefers the left; `==` compares
bindings):

```text
empty : Dict k v
singleton : comparable -> v -> Dict comparable v
insert : comparable -> v -> Dict comparable v -> Dict comparable v
update : comparable -> (Maybe v -> Maybe v) -> Dict comparable v -> Dict comparable v
remove : comparable -> Dict comparable v -> Dict comparable v
isEmpty : Dict k v -> Bool
member : comparable -> Dict comparable v -> Bool
get : comparable -> Dict comparable v -> Maybe v
size : Dict k v -> Int
keys : Dict k v -> List k
values : Dict k v -> List v
toList : Dict k v -> List ( k, v )
fromList : List ( comparable, v ) -> Dict comparable v
map : (k -> a -> b) -> Dict k a -> Dict k b
foldl : (k -> v -> b -> b) -> b -> Dict k v -> b          -- smallest key first
foldr : (k -> v -> b -> b) -> b -> Dict k v -> b          -- largest key first
filter : (comparable -> v -> Bool) -> Dict comparable v -> Dict comparable v
partition : (comparable -> v -> Bool) -> Dict comparable v -> ( Dict comparable v, Dict comparable v )
union : Dict comparable v -> Dict comparable v -> Dict comparable v
intersect : Dict comparable v -> Dict comparable v -> Dict comparable v
diff : Dict comparable a -> Dict comparable b -> Dict comparable a
merge : (comparable -> a -> result -> result) -> (comparable -> a -> b -> result -> result) -> (comparable -> b -> result -> result) -> Dict comparable a -> Dict comparable b -> result -> result
```

**Map** (`import Map` or `import Map exposing (Map)`; the ledger's association list, keys compared by Data
encoding, any Data-encodable key; O(n) lookups; `insert` puts the binding first; `fromList` is O(n²);
`filter`/`map` are O(n) and keep order; no `==`. A map decoded from Data may repeat a key: `get` returns
the first binding, `remove`/`insert` drop every binding of it. Use it for ledger maps such as
`txInfo.withdrawals` and for keys that are not comparable; use `Dict` otherwise):

```text
empty : Map k v
singleton : k -> v -> Map k v
get : k -> Map k v -> Maybe v
member : k -> Map k v -> Bool
insert : k -> v -> Map k v -> Map k v
remove : k -> Map k v -> Map k v
update : k -> (Maybe v -> Maybe v) -> Map k v -> Map k v
size : Map k v -> Int
isEmpty : Map k v -> Bool
keys : Map k v -> List k
values : Map k v -> List v
toList : Map k v -> List ( k, v )
fromList : List ( k, v ) -> Map k v
foldl : (k -> v -> b -> b) -> b -> Map k v -> b
foldr : (k -> v -> b -> b) -> b -> Map k v -> b
filter : (k -> v -> Bool) -> Map k v -> Map k v
map : (k -> a -> b) -> Map k a -> Map k b
```

**Array** (`import Array`; O(1) `get`/`length`; no Data encoding; no `set`/`push`):

```text
empty : Array a
fromList : List a -> Array a
toList : Array a -> List a
toIndexedList : Array a -> List ( Int, a )
length : Array a -> Int
isEmpty : Array a -> Bool
get : Int -> Array a -> Maybe a
initialize : Int -> (Int -> a) -> Array a
repeat : Int -> a -> Array a
foldl : (a -> b -> b) -> b -> Array a -> b
foldr : (a -> b -> b) -> b -> Array a -> b
map : (a -> b) -> Array a -> Array b
indexedMap : (Int -> a -> b) -> Array a -> Array b
filter : (a -> Bool) -> Array a -> Array a
slice : Int -> Int -> Array a -> Array a
```

**Value** (`import Value exposing (Value)`; the ledger's multi-asset value, as in `out.value` and
`tx.mint`; `PolicyId` and `TokenName` from `Julc.Ledger`; always canonical, so `==` is exact; quantities may
be negative, as for burns in a mint; `lovelaceOf` and `isZero` read the Data directly, the filters
(`positive`, `negative`, `restrictedTo`, `withoutLovelace`) walk it, the rest convert through the CIP-153
builtins, about 1M CPU for a few assets; a policy id or token name over 32 bytes, or a quantity outside
signed 128 bits, fails the script. `Data.encode` of a `Value` is free and `Data.decode`
accepts only canonical values, but a `Value` cannot be a datum, redeemer or parameter, and a type that
contains one cannot be decoded):

```text
zero : Value
lovelace : Int -> Value
singleton : PolicyId -> TokenName -> Int -> Value
insert : PolicyId -> TokenName -> Int -> Value -> Value      -- sets the quantity; 0 removes the token
fromList : List ( PolicyId, TokenName, Int ) -> Value        -- repeated tokens add up
lovelaceOf : Value -> Int                                    -- ada's token only; 0 when none, as in a mint
quantityOf : PolicyId -> TokenName -> Value -> Int           -- 0 when none
isZero : Value -> Bool
policies : Value -> List PolicyId                            -- ascending; ada's is the empty policy
tokens : PolicyId -> Value -> Dict TokenName Int
toList : Value -> List ( PolicyId, TokenName, Int )          -- ascending
add : Value -> Value -> Value
subtract : Value -> Value -> Value                           -- first minus second
negate : Value -> Value
scale : Int -> Value -> Value
sum : List Value -> Value
geq : Value -> Value -> Bool          -- geq a b: a holds at least as much of every token as b; total, also with negatives
leq : Value -> Value -> Bool
withoutLovelace : Value -> Value
restrictedTo : List PolicyId -> Value -> Value
positive : Value -> Value             -- in a mint: what is minted
negative : Value -> Value             -- in a mint: what is burned, still negative
```

**Interval** (`import Interval`; the ledger's time range, as in `tx.validRange`; POSIX milliseconds; name the
type with `import Interval exposing (Interval)`; build ranges by hand with `Interval`, `IntervalBound`,
`Finite`, `NegInf`, `PosInf` from `Julc.Ledger`; about 3M CPU per check):

```text
isEntirelyAfter : Int -> Interval -> Bool     -- the range has a finite start and every time in it is > t
isEntirelyBefore : Int -> Interval -> Bool    -- the range has a finite end and every time in it is < t
contains : Int -> Interval -> Bool            -- honors inclusive and exclusive bounds
start : Interval -> Maybe Int                 -- Nothing for an infinite start; ignores inclusiveness
end : Interval -> Maybe Int
after : Int -> Interval                       -- [t, +inf)
before : Int -> Interval                      -- (-inf, t]
between : Int -> Int -> Interval              -- [low, high]
everything : Interval
```

"After the deadline": `Interval.isEntirelyAfter deadline tx.validRange` (strict; a range without a finite
start fails, and so does an empty range's `PosInf` start, as in Aiken's stdlib v2). "Before the deadline": `Interval.isEntirelyBefore deadline tx.validRange`. `Scenario.validFrom t`
is an inclusive start, so it passes `isEntirelyAfter d` only for `t > d`; `Scenario.validBefore t` is an
exclusive end, so it passes `isEntirelyBefore d` for `t <= d`. To accept from the deadline itself, check
`isEntirelyAfter (deadline - 1)`.

**Tx** (`import Tx`; questions about the transaction; the `ScriptContext` comes last; walks the context's
lists, about 0.7M CPU per signatory, 2.5M per input and 2.8M per output passed):

```text
signedBy : PubKeyHash -> ScriptContext -> Bool
spends : TxOutRef -> ScriptContext -> Bool                -- inputs only, not reference inputs
findInput : TxOutRef -> ScriptContext -> Maybe TxInInfo
ownRef : ScriptContext -> Maybe TxOutRef                  -- spending only; Nothing for other purposes
ownInput : ScriptContext -> Maybe TxInInfo                -- spending only
ownPolicy : ScriptContext -> Maybe PolicyId               -- minting only
outputsTo : Credential -> ScriptContext -> List TxOut     -- payment credential only; the tx builder picks the staking part
valuePaidTo : Credential -> ScriptContext -> Value        -- total of outputsTo; untagged, open to double satisfaction
continuingOutputs : ScriptContext -> List TxOut           -- spending only: outputs to the spent output's whole address
valueSpent : ScriptContext -> Value                       -- inputs, not reference inputs
valueProduced : ScriptContext -> Value                    -- outputs; fee and mint are separate
mintedOf : PolicyId -> ScriptContext -> Dict TokenName Int  -- burns are negative; empty when nothing minted
inlineDatum : TxOut -> Maybe Data                         -- Nothing for a datum hash or no datum
datumOf : TxOut -> ScriptContext -> Maybe Data            -- inline, or by hash from tx.datums
```

Decode a datum with an annotation: `orderOf out = Maybe.map Data.decode (Tx.inlineDatum out)` with
`orderOf : TxOut -> Maybe Order`; a malformed datum fails the script. Check one without decoding:
`Tx.inlineDatum out == Just (Data.encode expected)`.

**Bytes** (`import Bytes`; builtins; the byte string is the last argument; compare with `==`, `<`,
`compare`):

```text
empty : Bytes
fromString : String -> Bytes
toString : Bytes -> String
length : Bytes -> Int
append : Bytes -> Bytes -> Bytes
slice : Int -> Int -> Bytes -> Bytes          -- start, count (not end)
take : Int -> Bytes -> Bytes
drop : Int -> Bytes -> Bytes
at : Int -> Bytes -> Int                      -- fails out of range
cons : Int -> Bytes -> Bytes
replicate : Int -> Int -> Bytes               -- count, byte
fromIntBigEndian : Int -> Int -> Bytes        -- width (0 = minimal), value
fromIntLittleEndian : Int -> Int -> Bytes
toIntBigEndian : Bytes -> Int
toIntLittleEndian : Bytes -> Int
and : Bytes -> Bytes -> Bytes                 -- and/or/xor truncate to the shorter
or : Bytes -> Bytes -> Bytes
xor : Bytes -> Bytes -> Bytes
complement : Bytes -> Bytes
shift : Int -> Bytes -> Bytes
rotate : Int -> Bytes -> Bytes
testBit : Int -> Bytes -> Bool                -- bit 0 = lowest bit of the last byte
countSetBits : Bytes -> Int
findFirstSetBit : Bytes -> Int                -- -1 when none
blake2b224 : Bytes -> Bytes
blake2b256 : Bytes -> Bytes
sha256 : Bytes -> Bytes
sha3_256 : Bytes -> Bytes
keccak256 : Bytes -> Bytes
ripemd160 : Bytes -> Bytes
```

**Debug** (imported by default): `trace : String -> a -> a`. Both arguments are evaluated first, so the
message is not emitted if the value fails. Traces cost budget and bytes.

**Expect** (tests only; `import Expect exposing (Expectation)`):

```text
pass : Expectation
fail : String -> Expectation
onFail : String -> Expectation -> Expectation
equal : a -> a -> Expectation                 -- expected, actual
notEqual : a -> a -> Expectation
atMost : comparable -> comparable -> Expectation       -- bound, actual: actual <= bound
atLeast : comparable -> comparable -> Expectation      -- actual >= bound
lessThan : comparable -> comparable -> Expectation     -- actual < bound
greaterThan : comparable -> comparable -> Expectation  -- actual > bound
every : List Expectation -> Expectation
```

**Scenario** (tests only; `import Scenario exposing (Scenario)`; the `String` is the validator name from
`elci.toml`):

```text
spend : String -> TxOutRef -> Data -> Scenario        -- spent ref, inline datum
spendWithoutDatum : String -> TxOutRef -> Scenario
mint : String -> Scenario
withdraw : String -> Scenario
certify : String -> Int -> TxCert -> Scenario
vote : String -> Voter -> Scenario
propose : String -> Int -> ProposalProcedure -> Scenario
redeemer : Data -> Scenario -> Scenario               -- default: the Data of ()
parameters : List Data -> Scenario -> Scenario        -- ALL parameters, in declared order
signedBy : PubKeyHash -> Scenario -> Scenario
input : TxOutRef -> Output -> Scenario -> Scenario    -- at the spent ref: replaces it; same datum as spend
referenceInput : TxOutRef -> Output -> Scenario -> Scenario
output : Output -> Scenario -> Scenario
minting : Bytes -> Int -> Scenario -> Scenario        -- token name, quantity, own policy
fee : Int -> Scenario -> Scenario
validFrom : Int -> Scenario -> Scenario               -- inclusive
validBefore : Int -> Scenario -> Scenario             -- exclusive
worth : Int -> Scenario -> Scenario                   -- lovelace of the spent output
expectFailure : Scenario -> Scenario
withinBudget : Int -> Int -> Scenario -> Scenario     -- cpu, memory
toScript : Int -> Output                              -- the validator's own address
toStakedScript : StakingCredential -> Int -> Output   -- own payment credential with a staking part
toAddress : Address -> Int -> Output
toKey : PubKeyHash -> Int -> Output                   -- the key's address, no staking part
withDatum : Data -> Output -> Output                  -- inline datum
withDatumHash : Data -> Output -> Output              -- datum by hash, with the datum as a witness
withToken : Policy -> Bytes -> Int -> Output -> Output
key : String -> PubKeyHash                            -- deterministic test key
outRef : String -> Int -> TxOutRef
unit : Data
```

**Property** (tests only; `import Property exposing (Property)`): `holds : Bool -> Property`,
`expect : Expectation -> Property`, `scenario : Scenario -> Property`.

**Test discovery:** a test is an **exposed, zero-argument, top-level** declaration of type `Bool`,
`Expectation`, `Scenario` or `Property`. An **exposed function returning `Property`** is a property test;
its arguments (any Data-encodable types, and `Value`, generated canonical) are generated, run 100 times by
default, and shrunk on failure.
Unexposed declarations are helpers. Test modules live in `tests/`, may import `src` modules and
`[java-modules]`, and must not share a name with a source module. **Only test modules may import
`Expect`, `Scenario` and `Property`.**

## 9. Java libraries (Julc stdlib)

Java libraries fill gaps in the core library; prefer a core function when one exists (`max`, `abs`,
`^`, `List.member`). Map a class, then import the module. Nothing is mapped by default. Use explicit
exposing lists (`exposing (..)` is rejected for Java modules). Java argument order: the collection or
subject comes **first**.

```toml
[java-modules]
"Julc.Math" = "org.julclang.stdlib.lib.MathLib"
"Julc.Output" = "org.julclang.stdlib.lib.OutputLib"
```

| Function | Type |
| --- | --- |
| `Math.floorDiv`, `floorMod` | `Int -> Int -> Int` (round towards negative infinity; `//` truncates) |
| `Math.expMod` | `Int -> Int -> Int -> Int` |
| `Math.sign` | `Int -> Int` |
| `Output.outputsAt`, `lovelacePaidTo` (`OutputLib`) | `List TxOut -> Address -> List TxOut` / `Int`: whole address, staking part included |
| `Address.isPubKeyAddress`, `isScriptAddress` (`AddressLib`) | `Address -> Bool` |
| `Crypto.verifyEd25519Signature` (`CryptoLib`) | `Bytes -> Bytes -> Bytes -> Bool` (key, message, signature) |
| `ByteString.toHex`, `intToDecimalString` (`ByteStringLib`) | `Bytes -> Bytes`, `Int -> Bytes` |

Use the core `Tx` and `Interval` modules instead of `ContextsLib` and `IntervalLib`. `IntervalLib`'s
`finiteLowerBound` and `finiteUpperBound` return `-1` for an infinite bound, a sentinel that makes "no end"
look like "ends at -1". `Interval` and `Tx` are core module names: a `[java-modules]` key can't be either,
and a Java module should not be aliased as either.

Unsupported signatures (for example `MathLib.divMod`, all of `BlsLib`) are `ELCI2105`, and so is every
export whose result can hold a `Value` (`ValuesLib.add`, `ValuesLib.singleton`), except in `ContextsLib` and
`OutputLib`, which pass along the ledger's own values: use the core `Value` module (section 8), not
`ValuesLib`. Generic Java methods are specialized per use; type arguments must be Data-encodable and must
not contain a `Dict` or a `Value` (`ELCI3003`).

## 10. The CLI loop

Run from the project directory (or pass it as the first argument). Exit status is `0` on success and
`1` on any error, failing test, failed evaluation or when no tests are found.

1. `elci check`: compile every validator (or the entrypoint); prints one line each:
   `validator badge (mint): checked; 299 FLAT bytes; plutus-v3-pv11-uplc-1.1.0`. Fix every error first.
2. `elci test`: run tests; prints `PASS`/`FAIL Module.test (cpu N, mem M)` then
   `N passed, M failed (seed S)`. Options: `--match <substring>` (case-insensitive on `Module.test`),
   `--seed <n>` (reproduce), `--fuzz <runs>` (property runs, default 100).
3. `elci eval` (function projects only): prints the value in Elci syntax, then `cpu N, mem M`, then
   `trace:` lines; `--builtins` lists the last 20 builtin calls; failure prints `evaluation failed: <reason>`.
4. `elci build`: writes `build/validators/<name>/{validator.flat, validator.plutus,
   validator.plutus.json, abi.json}`; `--validator <name>` selects.
5. `elci apply --validator <name> --param name=<json>`: applies parameters, checking them off-chain.
   JSON is cardano-cli detailed schema: `{"int": 5}`, `{"bytes": "hex"}`, `{"list": [...]}`,
   `{"map": [{"k": ..., "v": ...}]}`, `{"constructor": 0, "fields": [...]}`; `@file` reads a file. A `Dict`
   parameter must list its keys strictly ascending (integers numerically, bytes lexicographically).

Error format: the first line is `path:line:col: ELCIxxxx: message`. Then come a blank line, the source line
as `N| code`, a caret line under the reported token or expression, and zero or more `Hint: ...` lines. For a
validator, a blank line and `In .../elci.toml:<line>: [validators.<name>]` follow. The report ends with the
command that explains the code:

```text
src/Main.elm:6:5: ELCI2005: Unknown name 'lenght'

6|     lenght [ 1, 2 ]
       ^^^^^^
Hint: did you mean `List.length`?

For more on ELCI2005, run `elci explain ELCI2005`.
```

Parse the first line, whose form never changes, and apply the hints: they name a close name, a conversion
(`String.fromInt`, `Bytes.fromString`, `.hash`, `Maybe.withDefault`, `Value.lovelaceOf`) or an import to
add. A run reports every error it finds, in source order and separated by blank lines, up to 20, then
`... and N more` and a count such as `3 errors`. Each top-level declaration reports its first error. Type
errors appear only once there are no syntax, import or qualified-name errors, so rerun after each round of
fixes. A long type mismatch adds `It is:` and `It needs:` lines with the first difference marked.
`elci explain ELCI2002` explains a code with an example and its fix. Configuration and command-line errors
start with `elci: ELCI4001:`. For tools, `--report=json` on `check`, `build`, `eval` and `test` prints each error on
stderr as one JSON object on its own line (JSON Lines), with `code`, `path`, `line`, `column`, `message`,
`hints` (an array), `excerpt` and `context` (the validator's `elci.toml` line, or null); prefer it to
parsing text. Results and test output stay text.

## 11. Error codes: cause and fix

| Code | Typical message | Fix |
| --- | --- | --- |
| `ELCI1001` | `Float is unsupported; ...`, `Unsupported character '%'` | scaled `Int`; `modBy` |
| `ELCI1002` | `Function parameters require irrefutable patterns; use case ...`, `Comparison operators cannot be chained`, `Mixing (<\|) and (\|>) needs parentheses ...` | `case`; `&&`; parentheses |
| `ELCI2001` | `Float division is unsupported; use // for integer quotient`, `The constructor Just takes 1 argument, but this pattern gives it 2`, `Unknown constructor Jsut in this pattern` | `//`; fix the pattern |
| `ELCI2002` | `` The 1st argument to `String.fromInt` is `String`, but `String.fromInt` needs `Int` ``, `` The annotation of `identity` says `a` can be any type, but its definition needs `Int` ``, `` The 2nd branch of this case is `String`, but the first branch is `Int` ``, `The operator + works on Int values, not String; use ++ to append` | fix the part the message names; apply the hint |
| `ELCI2003` | `The operator == cannot compare Map Int Int values: it has no equality`, `The operator < needs comparable values, but Bool, in ( Int, Bool ), is not comparable. ...`, `` The operator > compares values of type `a`, but the annotation of `larger` says `a` can be any type. Rename it `comparable` ... ``, `The operator ++ appends String or List values, not Int` | compare lookups or keys; `List.sortBy`; rename to `comparable`/`appendable` |
| `ELCI2004` | `Top-level declarations require a type signature` | add the signature |
| `ELCI2005` | `Unknown name 'lenght'` with ``Hint: did you mean `List.length`?``, `Unknown name 'double'` with ``Hint: write `Helper.double`, or expose `double` in its import``, `Unknown name 'Math.maximum'; check that the imported module exports it` | spelling; the import; section 3.3 |
| `ELCI2006` | `Unreachable pattern`, `Duplicate pattern variable x` | remove the branch; rename |
| `ELCI2007` | `` This case does not cover every possibility; it is missing `Nothing` and `Just (Err _)` `` | add a branch for each listed pattern |
| `ELCI2008` | `Cyclic eager values are unsupported; recursion requires functions` | break the cycle |
| `ELCI2009` | `Entrypoints require concrete types`, `Handler Main.mint requires a concrete type` | no type variables in handlers |
| `ELCI2010` | `Missing field 'price'; available fields: [amount]` | correct field; annotate the record type |
| `ELCI2011` | `Duplicate constructor Buy: the type Action has one too`, `Nothing is a constructor of the built-in Maybe type; choose another name`, `Bytes is a built-in type; choose another name` | rename |
| `ELCI2012` | `The type List takes 1 argument, but it is given 2`, `Type is not declared or imported in this module: Amount`, `Write tuple types as ( a, b ) or ( a, b, c )` | declare/import the type |
| `ELCI2013` | `Functions cannot be serialized as datum or redeemer`, `Arrays have no Data encoding ...`, `A Value cannot be part of a datum, redeemer or parameter yet: ...` | Data-encodable boundary types; `List` in data; `List ( PolicyId, TokenName, Int )` for a value |
| `ELCI2014` | `Handler Main.spend does not have the spend signature datum -> redeemer -> ScriptContext -> Bool that [validators.main] needs (0 parameters); its type is ...` | section 6 |
| `ELCI2101` | `Missing source module: X; add X.elm under a source directory, or map a Java library to it under [java-modules]`, `Expect is part of the test library; only test modules can import it` | add module / mapping; move to `tests/` |
| `ELCI2102` | `max is ambiguous: it could be Basics.max or Utils.max. Write the one you mean, like Basics.max, or stop exposing the other` | qualify, or expose the name explicitly |
| `ELCI2103` | `Handler Main.mint must be exposed by its module`, `Entrypoint Main.main must be exposed by its module` | expose it |
| `ELCI2104` | ``Bytes.fromString needs the Bytes module; add `import Bytes` ``, `Module Helper does not expose 'doubel'`, ``Module Helper is not imported; add `import Helper` ``, `No module Lsit is imported, and none has that name`, `Elci's List module has no find. It has all, any, ...`, `Elci has no Debug.log; use Debug.trace message value, which returns value`, `Map is imported as M; write M.get`, `The module name Tx is taken by Elci's core library; rename this module`, `Import cycle: Main -> Pricing -> Main`, `Java modules cannot be imported with exposing (..); ...` | add the import; section 3.3; rename; break cycles |
| `ELCI2105` | `...MathLib.divMod: Unsupported on-chain source type: Tuple2<BigInteger, BigInteger>`, `...ValuesLib.add: returns a Value, which Java libraries do not keep in the ledger's canonical order; use the core Value module` | use another export; the core `Value` module |
| `ELCI3001` | `Cannot determine the type converted by Julc.Data.decode; add a type annotation` | annotate (`let xs : List Int`) |
| `ELCI3002` | specialization limit (256) | remove polymorphic recursion |
| `ELCI3003` | `No encoder for Int -> Int: functions have no Data encoding`, `Type argument T = Unit of firstOr must have a Plutus Data encoding`, `No strict decoder for org.julclang.ledger.TxOut ...`, `No encoder for List [org.julclang.ledger.Value] ...`, `Type argument a = Dict Int Int of filter contains a Dict, which a Java library cannot keep sorted; ...`, `Type argument a = Value of filter contains a Value, which a Java library does not keep in the ledger's canonical order; ...` | Data-encodable type; convert values with `Value.toList`; no `Dict` or `Value` through Java generics |
| `ELCI4001` | `unknown validator 'x'`, `src/Main.elm is not valid UTF-8 text; save it as UTF-8`, `eval runs an [entrypoint] function; test validators with elci test`, `ABI drift from ...`, `parameter caps has a Dict whose keys are not strictly ascending; write its keys sorted and without repeats`, `Julc version does not match this compiler; expected julc-version = "0.1.0-gb-5a3378b"`, `Unknown configuration key: [project] name`, `Unknown option: --frob`, `my-project/elci.toml not found; ...`, `Source directory not found: ...`, `my-project/elci.toml:6: [validators.main]: Handler Main.spent for spend: module Main does not declare spent` | fix config or command; sort `Dict` keys |
| `ELCI4002`, `ELCI4003` | backend rejected or lacks a capability | compiler issue; report it |
| (none) | `On-chain Java source not found: ...`, `Cannot resolve type: ?`, `Unknown type: T` (a Java library) | fix `[java-modules]` or the library |

A failing scenario that was never run prints `cannot prepare validator 'badge': it takes 1 parameter
[admin], but the scenario gives 0; set them with Scenario.parameters`.

## 12. Anti-patterns agents get wrong

1. **Using Elm APIs Elci lacks** (`Set`, `Debug.log`, `String.length`, `String.toInt`,
   `List.map4`). Use section 3.3. The core functions in section 8 do exist; use them rather than
   writing your own or mapping `Julc.Math`.
2. **Forgetting imports.** `Dict`, `Map`, `Array`, `Value`, `Interval`, `Tx`, `Bytes`, `Julc.Data` must be imported; `Basics`, `List`,
   `Maybe`, `Result`, `String`, `Tuple` and `Debug` are implicit. `Julc.*` Java modules also need a
   `[java-modules]` mapping.
3. **Omitting type signatures** on top-level declarations (`ELCI2004`).
4. **Wrong handler shape.** Parameters first, then datum (spend only), redeemer, `ScriptContext`, returning
   `Bool`. Use `()` for unused datum/redeemer. Expose the handler.
5. **Mixing `Bytes` and `PubKeyHash`.** Keep keys as `PubKeyHash`; use `.hash` only when bytes are needed.
6. **Expensive checks in `let`** or before cheap ones: they always run. Write `cheap && expensive` inline.
7. **Expensive top-level values** in multi-purpose scripts: every purpose pays. Use functions.
8. **Assuming record field order** in Data: records encode fields **sorted by name**.
9. **Assuming `Maybe` = Java `Optional`** tags: convert with `Data.fromOptional` / `Data.toOptional`.
10. **Julc's `IntervalLib` for deadlines**, whose `finiteLowerBound`/`finiteUpperBound` return `-1` for an
    infinite bound. Use `Interval.isEntirelyAfter`/`isEntirelyBefore`, and mind that they are strict.
11. **Storing `Array` in data**, or building big maps with `Map.fromList` (quadratic; a 100-entry map
    exceeds a transaction's memory budget). Keep lists in data, convert with `Array.fromList` once; build
    keyed data with `Dict.fromList` (O(n log n)).
12. **`Data.decode` without a known type** (`ELCI3001`). Annotate the binding.
13. **Missing `Scenario.parameters`**: a scenario must pass every validator parameter, in order.
14. **Importing `Expect`/`Scenario`/`Property` from `src`** (`ELCI2101`). They are for `tests/` only.
15. **`x -1` meaning subtraction.** It is an application; write `x - 1`.
16. **Float, `/`, `%`**: integers only; `//`, `modBy`, `remainderBy`, `^` (non-negative exponents).
17. **`exposing (..)` on Java modules** or relying on Java overloads (rejected).
18. **Trusting parameters on-chain**: they are not validated; validate with `elci apply` before deployment.
19. **Very large integer literals with elm-format**: elm-format 0.8.8 corrupts literals beyond 64 bits.
20. **Plain type variables with `<` or `++`.** Name them `comparable` or `appendable` (`ELCI2003`).
21. **Sorting records with `List.sort`.** Records and custom types are not comparable: use
    `List.sortBy (\r -> r.key)` or `List.sortWith`.
22. **Reading a PlutusTx `Either` as `Result`.** `Ok` is constructor 0 and `Err` 1, the reverse of
    `Left`/`Right`.
23. **Expensive `Maybe.withDefault` defaults.** The default is always evaluated; use `case` instead.
24. **`Map` for your own keyed data.** Use `Dict` when keys are comparable: it is sorted, has `==` and
    builds in O(n log n). Keep `Map` for ledger maps (`txInfo.withdrawals`, `txInfo.redeemers`) and keys
    that are not comparable.
25. **Unsorted `Dict` data.** A `Dict` in a datum, redeemer or parameter must have strictly ascending keys,
    or it fails to decode. Build test data with `Data.encode (Dict.fromList ...)`; off-chain, sort keys in
    Elci's order, not CBOR's canonical order (which puts shorter byte strings first).
26. **Reading values through `ValuesLib`.** Use the core `Value` module; Java exports that return a `Value`
    are rejected (`ELCI2105`). `==` on values is exact, so use `Value.geq` for "pays at least".
    `Value.lovelaceOf tx.mint` is 0, and a burn is a negative quantity in `tx.mint`.
    `import Julc.Ledger exposing (Value(..))` is `ELCI2104`: values come from the `Value` module.
27. **Hand-written context walks.** `List.any (\s -> s == key) tx.signatories`, a `case` on `scriptInfo`
    to find the own input or policy, or a filter over `tx.outputs` by credential: use `Tx.signedBy`,
    `Tx.ownInput`, `Tx.ownPolicy` and `Tx.outputsTo`.
28. **Untagged payment checks.** `Value.lovelaceOf (Tx.valuePaidTo (PubKeyCredential seller) ctx) >= price` lets one output
    pay for two script inputs (double satisfaction). See section 13.
29. **`token.name` on a `TokenName`.** Its bytes are `token.hash`; there is no `.name` field.

## 13. Security checklist for validators

The escrow, marketplace and order-swap example projects apply the items that concern spend validators and
test each attack (`examples/escrow`, `examples/marketplace`, `examples/order-swap`; their sources are in
`/ai/examples.json`).

1. **Tag every payment against double satisfaction.** When two inputs of one script can each require a
   payment to the same party, one output satisfies both checks. Require the paying output to carry the
   spent output's reference as its inline datum:

   ```elm
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

   Never accept an untagged `Tx.valuePaidTo` total. Write a scenario that pays with another input's tag
   (`Scenario.withDatum (Data.encode (Scenario.outRef "listing" 1))`) and expects failure.
2. **One script input per transaction when outputs are matched by address.** An output returned to the
   script carries the script's datum, not a tag, so one returned output could stand for two inputs. Count
   the inputs whose `resolved.address.credential` is the own input's and require exactly one.
3. **Check the whole value that stays.** For an output back to the script, compare its whole value with
   `Value.geq output.value expected`, where `expected` is everything that must stay (for example
   `Value.subtract own.resolved.value taken`), not just one token: otherwise the spender keeps the lovelace
   and the other tokens. To stop padding with junk tokens as well, require
   `Value.withoutLovelace output.value == Value.withoutLovelace expected` and
   `Value.lovelaceOf output.value >= Value.lovelaceOf expected`.
4. **Use `Value.geq` or `>=` for "pays at least", never `==`,** which rejects a payment with extra lovelace or
   tokens. Use `==` when exactness is the rule, as for `Tx.mintedOf policy ctx` in a minting policy.
5. **Check signatures with `Tx.signedBy` for every privileged action** (cancel, refund, update, withdraw),
   and write a scenario where a stranger signs.
6. **Bound numbers taken from datums and parameters.** Parameters are not validated on-chain: check a fee
   in basis points is between 0 and 10000, and amounts are positive (`amount > 0`), before using them.
7. **Deadlines use `Interval`.** `isEntirelyAfter`/`isEntirelyBefore` fail for a transaction without a
   validity range. They are strict; test the boundary with a property over offsets around the deadline.
8. **Payees are matched by payment credential.** `Tx.outputsTo` ignores the staking part: whoever builds
   the transaction chooses the payee's staking part. To protect a payee's staking rights, store their full
   `Address` and compare `output.address`.
9. **Minting policies check the whole mint under their policy:**
   `Dict.toList (Tx.mintedOf policy ctx) == [ ( TokenName name, quantity ) ]` rejects extra token names.

## 14. Canonical examples

All three were built and run with the CLI exactly as shown.

### 14.1 Spend validator with typed datum and redeemer, scenarios and a property

```toml title="elci.toml"
[project]
source-directories = ["src"]
target = "plutus-v3-pv11-uplc-1.1.0"
julc-version = "0.1.0-gb-5a3378b"

[validators.escrow]
boundary = "julc-strict-v1"
spend = "Escrow.spend"
```

```elm title="src/Escrow.elm"
module Escrow exposing (Action(..), Lock, spend)

import Interval
import Julc.Ledger exposing (PubKeyHash, ScriptContext)
import Tx


{-| The datum stored with the locked funds.
-}
type alias Lock =
    { owner : PubKeyHash
    , beneficiary : PubKeyHash
    , deadline : Int
    }


{-| The redeemer: what the spender wants to do.
-}
type Action
    = Claim
    | Refund


spend : Lock -> Action -> ScriptContext -> Bool
spend lock action ctx =
    case action of
        Claim ->
            Tx.signedBy lock.beneficiary ctx
                && Interval.isEntirelyAfter lock.deadline ctx.txInfo.validRange

        Refund ->
            Tx.signedBy lock.owner ctx
```

```elm title="tests/EscrowTest.elm"
module EscrowTest exposing
    ( beneficiaryClaimsAfterTheDeadline
    , claimAtTheDeadlineFails
    , claimsOpenOnlyAfterTheDeadline
    , lockRoundTrips
    , ownerRefunds
    , strangerCannotRefund
    )

import Escrow exposing (Action(..), Lock)
import Expect exposing (Expectation)
import Julc.Data as Data
import Julc.Ledger exposing (PubKeyHash)
import Property exposing (Property)
import Scenario exposing (Scenario)


owner : PubKeyHash
owner =
    Scenario.key "owner"


beneficiary : PubKeyHash
beneficiary =
    Scenario.key "beneficiary"


lock : Lock
lock =
    { owner = owner, beneficiary = beneficiary, deadline = 1000 }


{-| Spend the locked output with a redeemer; helpers are not exposed, so they are not tests.
-}
spendWith : Action -> Scenario
spendWith action =
    Scenario.spend "escrow" (Scenario.outRef "escrow" 0) (Data.encode lock)
        |> Scenario.redeemer (Data.encode action)


beneficiaryClaimsAfterTheDeadline : Scenario
beneficiaryClaimsAfterTheDeadline =
    spendWith Claim
        |> Scenario.validFrom 1001
        |> Scenario.signedBy beneficiary


{-| `validFrom` is inclusive: a transaction valid from 1000 could run at the deadline.
-}
claimAtTheDeadlineFails : Scenario
claimAtTheDeadlineFails =
    spendWith Claim
        |> Scenario.validFrom 1000
        |> Scenario.signedBy beneficiary
        |> Scenario.expectFailure


ownerRefunds : Scenario
ownerRefunds =
    spendWith Refund
        |> Scenario.signedBy owner


strangerCannotRefund : Scenario
strangerCannotRefund =
    spendWith Refund
        |> Scenario.signedBy (Scenario.key "stranger")
        |> Scenario.expectFailure


lockRoundTrips : Expectation
lockRoundTrips =
    Expect.equal lock (Data.decode (Data.encode lock))


{-| A signed claim succeeds exactly when its validity range starts after the deadline.
The generated offset is relative to the deadline, so the boundary is tested early and often.
-}
claimsOpenOnlyAfterTheDeadline : Int -> Property
claimsOpenOnlyAfterTheDeadline offset =
    let
        start =
            lock.deadline + offset

        claim =
            spendWith Claim
                |> Scenario.validFrom start
                |> Scenario.signedBy beneficiary
    in
    if start > lock.deadline then
        Property.scenario claim

    else
        Property.scenario (Scenario.expectFailure claim)
```

```text
$ elci check
validator escrow (spend): checked; 599 FLAT bytes; plutus-v3-pv11-uplc-1.1.0
$ elci test --seed 42
PASS EscrowTest.beneficiaryClaimsAfterTheDeadline (cpu 17930587, mem 67036)
PASS EscrowTest.claimAtTheDeadlineFails (cpu 17914587, mem 66936)
PASS EscrowTest.ownerRefunds (cpu 13130425, mem 52579)
PASS EscrowTest.strangerCannotRefund (cpu 13508858, mem 54511)
PASS EscrowTest.lockRoundTrips (cpu 8484615, mem 30707)
PASS EscrowTest.claimsOpenOnlyAfterTheDeadline (100 runs, max cpu 17930587, mem 67036)
6 passed, 0 failed (seed 42)
```

Changing `Interval.isEntirelyAfter lock.deadline` to `Interval.isEntirelyAfter (lock.deadline - 1)` in
`Escrow.spend` makes the property fail on its first run with the counterexample
`claimsOpenOnlyAfterTheDeadline 0`.

### 14.2 Parameterized minting policy

```toml title="elci.toml"
[project]
source-directories = ["src"]
target = "plutus-v3-pv11-uplc-1.1.0"
julc-version = "0.1.0-gb-5a3378b"

[validators.badge]
boundary = "julc-strict-v1"
parameters = ["admin"]
mint = "Badge.mint"
```

```elm title="src/Badge.elm"
module Badge exposing (badgeName, mint)

import Bytes
import Dict
import Julc.Ledger exposing (PubKeyHash, ScriptContext, TokenName(..))
import Tx


badgeName : Bytes
badgeName =
    Bytes.fromString "BADGE"


{-| The admin (a deployment parameter) may mint or burn BADGE tokens. The redeemer is the
quantity, which must be exactly what the transaction mints under this policy.
-}
mint : PubKeyHash -> Int -> ScriptContext -> Bool
mint admin quantity ctx =
    case Tx.ownPolicy ctx of
        Just policy ->
            (quantity /= 0)
                && Tx.signedBy admin ctx
                && (Dict.toList (Tx.mintedOf policy ctx) == [ ( TokenName badgeName, quantity ) ])

        Nothing ->
            False
```

```elm title="tests/BadgeTest.elm"
module BadgeTest exposing (adminBurns, adminMints, onlyTheDeclaredQuantity, otherTokensAreRejected, strangerCannotMint, zeroIsRejected)

import Badge
import Bytes
import Julc.Data as Data
import Julc.Ledger exposing (PubKeyHash)
import Property exposing (Property)
import Scenario exposing (Scenario)


admin : PubKeyHash
admin =
    Scenario.key "admin"


{-| Every scenario must supply every parameter of the validator.
-}
mintAs : PubKeyHash -> Int -> Int -> Scenario
mintAs signer declared actual =
    Scenario.mint "badge"
        |> Scenario.parameters [ Data.encode admin ]
        |> Scenario.redeemer (Data.encode declared)
        |> Scenario.minting Badge.badgeName actual
        |> Scenario.signedBy signer


adminMints : Scenario
adminMints =
    mintAs admin 5 5


adminBurns : Scenario
adminBurns =
    mintAs admin -2 -2


strangerCannotMint : Scenario
strangerCannotMint =
    mintAs (Scenario.key "stranger") 5 5
        |> Scenario.expectFailure


zeroIsRejected : Scenario
zeroIsRejected =
    mintAs admin 0 0
        |> Scenario.expectFailure


{-| Nothing but BADGE may be minted under the policy.
-}
otherTokensAreRejected : Scenario
otherTokensAreRejected =
    mintAs admin 5 5
        |> Scenario.minting (Bytes.fromString "OTHER") 1
        |> Scenario.expectFailure


{-| The admin's transaction succeeds only when the redeemer equals the minted quantity.
-}
onlyTheDeclaredQuantity : Int -> Int -> Property
onlyTheDeclaredQuantity declared actual =
    let
        transaction =
            mintAs admin declared actual
    in
    if declared == actual && declared /= 0 then
        Property.scenario transaction

    else
        Property.scenario (Scenario.expectFailure transaction)
```

```text
$ elci check
validator badge (mint): checked; 531 FLAT bytes; plutus-v3-pv11-uplc-1.1.0
$ elci test --seed 42
PASS BadgeTest.adminMints (cpu 16338349, mem 56630)
PASS BadgeTest.adminBurns (cpu 16338349, mem 56630)
PASS BadgeTest.strangerCannotMint (cpu 9466389, mem 36712)
PASS BadgeTest.zeroIsRejected (cpu 6425629, mem 25735)
PASS BadgeTest.otherTokensAreRejected (cpu 17971962, mem 62350)
PASS BadgeTest.onlyTheDeclaredQuantity (100 runs, max cpu 16338349, mem 56630)
6 passed, 0 failed (seed 42)
$ elci apply --validator badge --param 'admin={"bytes": "a2c20c77887ace1cd986193e4e75babd8993cfd56995cd5cfce609c2"}'
validator badge: applied 1 parameter; hash 8769d0504d637d83ffdc196eb33ab8146710d182338b0608f970c3cb; wrote .../badge/build/validators/badge/applied.plutus.json
```

### 14.3 Function project for `elci eval`

`elci init pricing --purpose function` creates the `[entrypoint]` configuration from section 2.

```elm title="src/Main.elm"
module Main exposing (main)

import Dict exposing (Dict)


type alias Purchase =
    { item : String
    , quantity : Int
    }


prices : Dict String Int
prices =
    Dict.fromList [ ( "apple", 3 ), ( "pear", 5 ) ]


lineTotal : Purchase -> Maybe Int
lineTotal purchase =
    Maybe.map (\price -> price * purchase.quantity) (Dict.get purchase.item prices)


total : List Purchase -> Int
total purchases =
    List.foldl
        (\purchase sum ->
            case lineTotal purchase of
                Just amount ->
                    sum + amount

                Nothing ->
                    Debug.trace ("no price for " ++ purchase.item) sum
        )
        0
        purchases


main : ( Int, Maybe Int )
main =
    ( total [ { item = "apple", quantity = 2 }, { item = "pear", quantity = 1 }, { item = "kiwi", quantity = 9 } ]
    , lineTotal { item = "kiwi", quantity = 1 }
    )
```

```text
$ elci eval
( 11, Nothing )
cpu 43542823, mem 172473
trace: no price for kiwi
```

## 15. Checklist before generating Elci code

1. Is every top-level declaration annotated, and every module name equal to its file path?
2. Did I avoid every Elm API in section 3.3 and import `Dict`/`Map`/`Array`/`Value`/`Interval`/`Tx`/`Bytes`/`Julc.Data` where used?
3. Does each handler match section 6, with parameters first, and is it exposed and listed in `elci.toml`?
4. Are datum, redeemer and parameter types Data-encodable (no functions, `Array`, `Value`, `TxOut`)?
5. Do I compare `PubKeyHash` with `PubKeyHash` (or `.hash` with `Bytes`), never mixed?
6. Are cheap checks first and inline, and expensive work kept out of `let` and top-level values?
7. Is every `Julc.*` module I import mapped under `[java-modules]`, with explicit exposing only?
8. Do my scenarios pass all parameters, and do they cover success, each failure path and a property?
9. Did I use `Tx` and `Interval` rather than hand-written context walks, `ContextsLib` or `IntervalLib`?
10. Does the validator pass the security checklist (section 13), with a scenario for each attack?
11. Did I run `elci check`, then `elci test`, and fix every error before `elci build`?

More depth: [language reference](/reference/language/), [core modules](/reference/core-modules/),
[test library](/reference/test-library/), [diagnostics](/reference/diagnostics/),
[elci.toml](/reference/configuration/), [CLI](/reference/cli/).
