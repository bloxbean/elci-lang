---
title: Coming from Elm
description: What carries over from Elm 0.19 to Elci and what changes, from the program model and missing core functions to Data encoding, Map and strict decoding.
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
- No typeclasses. Like Elm, Elci has no way to declare your own, and it goes further by dropping Elm's
  built-in `number`, `comparable` and `appendable` (see below).
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
| `String` module | Full module | None. Strings support `++` and `==`. Use `Bytes.fromString` and `Bytes.toString` for bytes |
| `number`, `comparable`, `appendable` | Constrained type variables | Not available. `<`, `<=`, `>`, `>=` work on `Int` only |
| `==` on functions | Runtime crash | Compile error, as for maps and opaque `Data` |
| Extensible records `{ r \| x : Int }` | Available | Not available. Records are closed |
| Accessor functions (`.price`) | Available | Not available. Use `\item -> item.price` |
| Operator functions (`(+)`) | Available | Not available. Use `\a b -> a + b` |
| `Result` | Built in | Not built in. Declare your own type if you need one |
| Let-polymorphism | Values and functions | Only for non-expansive bindings (functions, lambdas, plain values) |
| Unresolved type variables | Allowed | Every expression must end up with a concrete type |

Without `appendable`, a generic helper such as `joinTwo : a -> a -> a` that uses `++` still compiles. The
compiler checks each concrete use instead, so `joinTwo "a" "b"` and `joinTwo [ 1 ] [ 2 ]` compile, and
`joinTwo 1 2` doesn't. An expression like `List.length []` is rejected, because nothing fixes the element
type:

```elm
main : Int
main =
    List.length []
```

```text
.../src/Main.elm:6:5: ELCI3001: Cannot specialize List.length at an unconstrained type List t -> Int; add a type annotation so the type is known here
```

## Missing core functions

Elci's core is much smaller than Elm's. Apart from the `List` module, only `not`, `modBy` and `remainderBy`
are available without an import.

| Elm | In Elci |
| --- | --- |
| `identity`, `always`, `max`, `min`, `abs`, `negate`, `clamp`, `compare` | Not built in. Write them, or map Julc's `MathLib` (`max`, `min`, `abs`, `pow` and more). See [Modules and imports](/learn/modules/) |
| `Maybe.withDefault`, `Maybe.map` | No `Maybe` module. Use `case`, or write small helpers |
| `Tuple.first`, `Tuple.second` | No `Tuple` module. Destructure: `let ( a, b ) = pair in …` |
| `String.fromInt`, `Debug.toString` | Not built in. Julc's `ByteStringLib` has byte-string helpers such as `intToDecimalString` and `toHex` |
| `List.head`, `sum`, `member`, `range`, `take`, `drop`, `sort`, `indexedMap`, `filterMap`… | Not in `List`. Write them with recursion (see [Lists, maps and arrays](/learn/collections/)) |
| `Debug.log`, `Debug.todo` | `Debug.trace : String -> a -> a` only, after `import Debug` |
| `Set` | Not available |

`List` provides `map`, `filter`, `foldl`, `foldr`, `any`, `all`, `length`, `reverse`, `append` and
`concatMap`, with Elm's argument order: the fold function takes the element first, then the accumulator.

## Map, Array and Dict

| | Elm `Dict` | Elci `Map` |
| --- | --- | --- |
| Import | `import Dict exposing (Dict)` | `import Map exposing (Map)`, or just `import Map`: the type `Map k v` is always in scope |
| Keys | `comparable` | Any type with a Data encoding, including records and ledger types |
| Structure | Balanced tree, sorted by key | The ledger's association list, not sorted |
| `get`, `insert`, `remove` | O(log n) | O(n) |
| `==` | Supported | Not supported |
| API | Large | `empty`, `singleton`, `fromList`, `get`, `member`, `insert`, `remove`, `update`, `size`, `isEmpty`, `keys`, `values`, `toList`, `foldl`, `filter`, `map` |

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
| `List a` | A Data list |
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
