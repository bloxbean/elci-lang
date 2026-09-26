---
title: Coming from Elm
description: What carries over from Elm 0.19 to Elci and what changes, from the program model and the core library to Data encoding, Dict, Map and strict decoding.
sidebar:
  order: 7
---

If you know Elm 0.19, you already know most of Elci's syntax. This page lists what carries over and every
difference that matters, so you can skip the parts of the Learn section you don't need.

## What stays the same

- The syntax of Elm 0.19.1: modules and `exposing`, `import … as … exposing`, `type` and `type alias`,
  `case`, `let`, `if`, lambdas, records, tuples of two or three elements, `|>`, `<|`, `>>` and `<<`.
- Immutable values and pure functions. Evaluation is strict, as in Elm: arguments and `let` values are
  evaluated before use, and only the chosen branch of `if` and `case` runs.
- Hindley–Milner type inference, generic functions (`a -> a`), and generic type aliases and custom types.
- Exhaustive `case` checking. Redundant patterns are errors.
- Local type annotations in `let`.
- The core library's default imports: `Basics` with `exposing (..)`, `List`, `Maybe` with `Maybe(..)`,
  `Result` with `Result(..)`, `String`, `Tuple` and `Debug`. Their functions have Elm's names, argument
  orders and edge cases: `max`, `compare`, `identity`, `Maybe.withDefault`, `List.head`, `List.range`,
  `List.sortBy`, `Result.andThen`, `Tuple.first`, `String.fromInt` and more.
- Elm's name rules: your own declarations shadow imports, and two imports exposing one name make it
  ambiguous where you use it.
- No typeclasses, with Elm's constrained type variables `comparable`, `appendable` and `compappend`.
- Operators as functions, such as `List.foldl (+) 0`, and `^` for powers.
- Types that nothing determines, as in `[] == []` or `Result.withDefault 0 (Ok 5)`, need no annotation.
- `//` truncates toward zero, and `modBy` and `remainderBy` follow Elm's sign rules: `modBy` takes the sign
  of the divisor, `remainderBy` the sign of the dividend.
- Negative arguments: `modBy 3 -7` passes `-7`, while `a - 1` and `a-1` subtract.
- elm-format 0.8.8 style, apart from one pitfall described below.

## The program model

Elm programs run in a browser. Elci programs are scripts that the Cardano ledger runs when it validates a
transaction.

| Elm | Elci |
| --- | --- |
| `main : Program flags model msg` | A handler function that returns `Bool`, named in a `[validators.<name>]` table of `elci.toml` |
| `Html`, `Browser`, `Cmd`, `Sub`, `Task`, ports | None. A script has no effects: it reads its inputs and approves or rejects |
| `elm.json` and the package registry | `elci.toml`. No Elm packages; share code as source directories |
| `elm make`, `elm repl`, `elm reactor` | `elci check`, `elci build`, `elci test`, and `elci eval` for a function project |
| Runtime exceptions are rare | Failing is normal: any failure rejects the transaction |

That last row changes how you think about errors. On-chain, failing is often the right answer: a malformed
datum, a missing signature or a division by zero all reject the transaction, and nothing else happens. In
Elm, `n // 0` evaluates to `0`. In Elci, it fails the script, as do `modBy 0` and `remainderBy 0`.

## Types and values

| Topic | Elm | Elci |
| --- | --- | --- |
| Top-level signatures | Optional | **Required** on every top-level declaration (`ELCI2004`) |
| `Int` | JavaScript number | Arbitrary precision, exact |
| `Float`, `/`, `toFloat`, `round` | Available | **Not available.** Use `Int` with an explicit scale, such as lovelace or basis points |
| `Char`, `'a'` | Available | Not available |
| Hex literals (`0xFF`) | Available | Not available |
| String escapes | Including `\u{…}`, and `"""` for multi-line | `\n`, `\r`, `\t`, `\"`, `\\` only |
| `String` module | Full module | `fromInt`, `isEmpty`, `append`, `concat` and `join`. Use `Bytes.fromString` and `Bytes.toString` for bytes |
| `String` order | By UTF-16 code units | By UTF-8 bytes, which is code point order. They differ only for characters outside the Basic Multilingual Plane |
| `comparable`, `appendable`, `compappend` | Constrained type variables | The same. `Bytes` and ledger hashes such as `PubKeyHash` are also comparable |
| `number` | `Int` or `Float` | Not available: write `Int` |
| `2 ^ -1` | `0.5` | Fails the script |
| `==` on functions | Runtime crash | Compile error, as for `Map` and opaque `Data` |
| Extensible records `{ r \| x : Int }` | Available | Not available. Records are closed |
| Accessor functions (`.price`) | Available | Not available. Use `\item -> item.price` |
| Let-polymorphism | Values and functions | Only for non-expansive bindings (functions, lambdas, plain values) |
| Undetermined type variables | Allowed | Allowed, except where a value of the type is created: `Julc.Data.decode` and generic Java library functions need an annotation |

Constrained type variables follow Elm's rule, strictly: a signature variable whose values are compared
must be named `comparable…`, and one whose values are appended `appendable…`. Elci reports the missing
name:

```elm
joinTwo : a -> a -> a
joinTwo left right =
    left ++ right
```

```text
.../src/Main.elm:6:10: ELCI2003: The operator ++ appends values of type `a`, but the annotation of `joinTwo` says `a` can be any type. Rename it `appendable` (as in Elm) to require String or List values
```

With `joinTwo : appendable -> appendable -> appendable`, both `joinTwo "a" "b"` and `joinTwo [ 1 ] [ 2 ]`
compile, and `joinTwo 1 2` doesn't.

## The core library

Elci's core library is Elm's, imported by default in every module:

| Module | What Elci has |
| --- | --- |
| `Basics` | `negate`, `abs`, `min`, `max`, `clamp`, `compare` and `Order`, `identity`, `always`, `not`, `xor`, `modBy`, `remainderBy`, `^` |
| `List` | all of Elm's `List` except `map4` and `map5` |
| `Maybe` | `withDefault`, `map`, `map2`, `map3`, `andThen` |
| `Result` | `Ok`, `Err`, `map`, `map2`, `map3`, `andThen`, `withDefault`, `toMaybe`, `fromMaybe`, `mapError` |
| `Tuple` | all of Elm's `Tuple` |
| `String` | `fromInt`, `isEmpty`, `append`, `concat`, `join` |
| `Debug` | `trace` |

[Core modules](/reference/core-modules/) has every signature. What is still missing:

| Elm | In Elci |
| --- | --- |
| `toFloat`, `round`, `sqrt`, `pi` and the other `Float` functions | Not available: there is no `Float` |
| `map4` and `map5` in `List`, `Maybe` and `Result` | Not available. Nest `map3`, or use `case` |
| `String.length`, `toInt`, `split`, `slice`, `fromFloat`… | Not available. `Bytes.length (Bytes.fromString s)` is the byte length |
| `Char` | Not available |
| `Set` | Not available. Use a `Dict k Bool` or a `List` |
| `Debug.log`, `Debug.todo`, `Debug.toString` | `Debug.trace : String -> a -> a`, and `String.fromInt` for numbers |

A name Elci lacks is an error that says what to use or lists what the module has:

```text
.../src/Main.elm:6:5: ELCI2104: Elci has no Debug.log; use Debug.trace message value, which returns value
.../src/Main.elm:6:5: ELCI2104: Elci's String module has no toInt. It has append, concat, fromInt, isEmpty, join
```

One import rule differs from Elm: a name you list explicitly, as in `import Utils exposing (clamp)`,
takes precedence over the default import of `Basics.clamp` instead of being ambiguous. Modules outside the
default imports, `Bytes`, `Dict`, `Map`, `Array` and `Julc.Data`, need their own imports, as `Dict` and
`Array` do in Elm.

## Dict, Map and Array

`Dict` has all of Elm's `Dict` API, but it is stored differently:

| | Elm `Dict` | Elci `Dict` |
| --- | --- | --- |
| Import | `import Dict exposing (Dict)` | The same; the type `Dict k v` is always in scope |
| Keys | `comparable` | `comparable`, which includes `Bytes` and ledger hashes such as `PubKeyHash` |
| Structure | Balanced tree | A Plutus map with its keys in ascending order |
| `get`, `insert`, `remove` | O(log n) | O(n): they stop at the first key that isn't smaller |
| `fromList` | O(n log n) | O(n log n), and O(n) for sorted input |
| Order of `keys`, `toList` and the folds | Ascending keys | The same |
| `==` | Compares bindings | The same |

In a datum, redeemer or parameter, a `Dict` is a Plutus map with sorted keys, and a map whose keys are out
of order or repeated fails to decode.

Elci also has `Map`, which Elm doesn't. A `Map k v` is the ledger's own map: an unsorted association list
whose keys may be any type with a Data encoding, such as `Credential`. The transaction's maps, such as
`txInfo.withdrawals` and `txInfo.redeemers`, are `Map`s. A map keeps its own order (`insert` puts a key
first), has no `==`, and `Map.fromList` is O(n²). Use `Dict` for keyed data of your own, and `Map` where the
ledger or a key that isn't comparable requires it.

`Array` resembles Elm's `Array`: `get` returns a `Maybe` and `slice` accepts negative indexes. It has no `set`,
`push` or `append`, and an array can't be stored in data (datums, redeemers, records, lists and so on).
Build a `List` and convert it once with `Array.fromList`.

## Your data is Plutus Data

In Elm, you convert values to JSON with hand-written encoders and decoders. In Elci, every datum, redeemer and
parameter is **Plutus Data**, and the encoding is derived from your types:

| Type | Encoding |
| --- | --- |
| Record | Constructor 0, fields **sorted by field name** |
| Custom type | Constructor numbered by declaration order (first is 0), with its values as fields |
| Tuple | Constructor 0, fields in position order |
| `Bool` | `False` is constructor 0, `True` is constructor 1 |
| `Maybe a` | `Nothing` is constructor 0, `Just` is constructor 1 |
| `Result e a` | `Ok` is constructor 0, `Err` is constructor 1: the reverse of PlutusTx's `Either` |
| `List a` | A Data list |
| `Dict k v` | A Data map with its keys in ascending order |
| `Map k v` | A Data map, in the map's order |
| `Int`, `Bytes`, `String` | An integer, a byte string, and UTF-8 bytes |

`Maybe`'s tags are the reverse of those used by Julc's Java `Optional` and the ledger's own optional values,
where the present value is constructor 0. They aren't interchangeable: `Julc.Data.fromOptional` and
`toOptional` convert between them. You can see Elci's tags by encoding a list of `Maybe` values:

```elm title="src/Main.elm"
module Main exposing (main)

import Julc.Data as Data


main : Data
main =
    Data.encode [ Nothing, Just 7 ]
```

```text
[Constr 0, Constr 1 [7]]
cpu 924368, mem 4120
```

Handlers receive their datum and redeemer already decoded. When you hold raw `Data`, such as an inline datum
in a transaction output, convert it explicitly with `Julc.Data`:

```elm title="src/Main.elm"
module Main exposing (main)

import Julc.Data as Data


type alias Payout =
    { beneficiary : String
    , amount : Int
    }


main : Int
main =
    let
        raw =
            Data.encode (Payout "alice" 50)

        decoded : Payout
        decoded =
            Data.decode raw
    in
    decoded.amount
```

```text
50
cpu 4820844, mem 21097
```

Unlike `Json.Decode.decodeValue`, `Data.decode` doesn't return a `Result`. Malformed data fails the script,
which rejects the transaction. Annotate the target type (`decoded : Payout`) when inference can't tell what to
decode. [Datums and redeemers](/contracts/datums-and-redeemers/) covers the rules.

## An elm-format pitfall

elm-format 0.8.8 is Elci's formatter, but it stores integer literals as 64-bit numbers. It silently changes
a literal larger than about 9.2 × 10¹⁸ into a different number. Elci's `Int` has no limit, so compute such
values instead, for example `1000000000 * 1000000000 * 1000000000`.

## Tooling

- There is no Elci language server. The Elm language server doesn't know Elci's modules, so trust
  `elci check` over its diagnostics.
- `elci eval` runs a function project and shows the result and its cost, which is the closest thing to
  `elm repl`. [Values, functions and types](/learn/basics/) shows how to set one up.
- `elci test` replaces elm-test, and adds transaction scenarios and property tests. See
  [Unit tests](/testing/unit-tests/).
