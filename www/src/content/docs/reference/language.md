---
title: Language reference
description: Elci's feature matrix, syntax summary, lexical rules, operators and precedence, evaluation order and Plutus Data encoding rules.
sidebar:
  order: 5
---

This page is the compact reference for the Elci language: what is supported, how the syntax looks, how
operators bind, in which order things are evaluated and how values are encoded as Plutus Data. For a
gentle introduction, start with [values, functions and types](/learn/basics/).

Elci uses Elm 0.19 syntax for a functional core, with its own on-chain semantics. It is a development
preview: the profile below is implemented and tested, and everything outside it is rejected with an error.

## Feature matrix

| Feature | Support |
| --- | --- |
| Modules and imports | Source modules, `as` aliases, explicit `exposing`, `exposing (..)` for source modules, `Type(..)` for constructors. Import cycles are rejected. |
| Functions | Top-level and local functions, lambdas with several arguments, curried and partial application. |
| Type signatures | Required on every top-level declaration; allowed on local ones. |
| Generics | Rank-1 type variables, specialized to each concrete use. |
| Local polymorphism | Simple local functions and values can be used at several types; calls, conditionals and matches stay monomorphic. |
| Recursion | Self-recursion and mutually recursive functions, top-level or local. Polymorphic recursion and recursive values are rejected. |
| Basic types | `Int` (arbitrary precision), `Bool`, `String`, `()`, `Bytes`, `Data`. |
| Lists | `List a`, literals, `::`, `++`, list patterns and the core `List` module. |
| Tuples | Pairs and triples, with patterns and destructuring. |
| Records | Closed records, generic aliases with constructors, field access, update, destructuring. |
| Custom types | Generic and recursive unions; `Maybe` is built in. |
| Patterns | Nested constructors, lists, cons, tuples, record fields, `as`, `_`, unit, and `Bool`, `Int` and `String` literals. Exhaustiveness and reachability are checked. |
| Equality | Structural `==` and `/=` for equality-capable types (below). |
| Maps and arrays | `Map k v` (like Elm's `Dict`) and `Array a` (constant-time `get`). |
| Bytes | The `Bytes` module: every byte-string builtin. |
| Ledger access | Typed views of the script context, nominal hash types, approved constructors, patterns and equality. |
| Data conversions | `Julc.Data.decode` and `encode`, optional adapters. |
| Java libraries | Julc's on-chain Java libraries and your own library JARs, including generic methods. |
| Validators | `[validators.<name>]` scripts with spend, mint, withdraw, certify, vote and propose handlers, parameters and datum profiles. |
| Tests | `elci test` with unit tests, transaction scenarios and property tests. |

**Not supported:** `Float` and `/`, typeclasses and user-defined constraints, extensible records and
row polymorphism, higher-rank types, polymorphic recursion, ports and effects, `String`/`Maybe`/`Dict`
modules from Elm core, record accessor functions such as `.name`, operator sections such as `(+)`,
character literals, hexadecimal literals, tuples of four or more, and Elm packages.

## Syntax summary

This module uses most of the syntax:

```elm title="src/Main.elm"
module Main exposing (main)

{-| A tour of the syntax. Block comments {- can {- nest -} -}.
-}


type Shape
    = Circle Int
    | Rect Int Int


type alias Point =
    { x : Int, y : Int }


type alias Pair a =
    ( a, a )


area : Shape -> Int
area shape =
    case shape of
        Circle radius ->
            3 * radius * radius

        Rect width height ->
            width * height


describe : List Int -> String
describe values =
    case values of
        [] ->
            "empty"

        [ _ ] ->
            "one"

        0 :: _ ->
            "starts with zero"

        first :: _ ->
            if first < 0 then
                "starts negative"

            else
                "many"


norm : Point -> Int
norm { x, y } =
    x * x + y * y


swap : Pair a -> Pair a
swap ( first, second ) =
    ( second, first )


main : Int
main =
    let
        origin =
            Point 0 0

        moved =
            { origin | x = 3, y = 4 }

        ( a, b ) =
            swap ( 1, 2 )

        total : Int
        total =
            [ Circle 1, Rect 2 3 ]
                |> List.map area
                |> List.foldl (\value sum -> value + sum) 0
    in
    if describe [ 0, 1 ] == "starts with zero" && not (a == b) then
        norm moved + total

    else
        -1
```

| Construct | Syntax |
| --- | --- |
| Module header | `module A.B exposing (f, T, U(..))` or `exposing (..)` |
| Import | `import A.B as B exposing (f, T(..))` |
| Signature and definition | `f : Int -> Int` then `f x = x + 1` |
| Lambda | `\x y -> x + y`, `\( a, b ) -> a + b` |
| Local definitions | `let` ... `in`, with optional signatures and tuple/record destructuring |
| Conditional | `if c then a else b` (the `else` is required) |
| Match | `case e of` followed by aligned `pattern ->` branches |
| Custom type | `type T a = A a \| B` |
| Type alias | `type alias R = { x : Int }`; a record alias is also a constructor `R 1` |
| Record | `{ x = 1, y = 2 }`, `r.x`, `{ r \| x = 3 }` |
| Tuple | `( 1, True )`, `( 1, 2, 3 )` |
| List | `[ 1, 2 ]`, `1 :: rest`, `xs ++ ys` |
| Unit | `()` as a value, type and pattern |

Function and lambda parameters, and local destructuring, accept only **irrefutable** patterns: names,
`_`, `()`, tuples and record fields. Anything that can fail to match belongs in `case`.

### Layout

Indentation is significant, as in Elm. Top-level declarations start in column 1. The declarations of a
`let` align with each other, and so do the branches of a `case`. A signature must directly precede its
definition. Tabs are rejected.

### Lexical rules

- **Names.** Values and functions start with a lowercase letter or `_`; types, constructors and module
  segments start with an uppercase letter.
- **Reserved words.** `if`, `then`, `else`, `case`, `of`, `let`, `in`, `module`, `import`, `exposing`,
  `as`, `type` and `port`. `alias` is only special after `type`.
- **Integers.** Decimal digits only, arbitrarily large. `1.5` is rejected (`Float is unsupported`), and so
  are `0xFF` and `1_000`. elm-format 0.8.8 corrupts literals that do not fit in 64 bits, so check large
  constants after formatting.
- **Strings.** Double-quoted, on one line. The escapes are `\n`, `\r`, `\t`, `\"` and `\\`. There are no
  triple-quoted strings, Unicode escapes or character literals.
- **Comments.** `--` to the end of the line, and `{- ... -}` blocks, which nest. `{-| ... -}` doc comments
  are ordinary block comments.

## Operators

From the tightest binding to the loosest:

| Precedence | Operators | Associativity | Meaning |
| ---: | --- | --- | --- |
| highest | `record.field` | | field access, written without spaces |
| 10 | `f x` | left | function application |
| 9 | `>>`, `<<` | `>>` left, `<<` right | function composition |
| 7 | `*`, `//` | left | multiplication, integer division truncating towards zero |
| 6 | `+`, `-` | left | addition, subtraction |
| 5 | `::`, `++` | right | list cons; append lists or strings |
| 4 | `==`, `/=`, `<`, `>`, `<=`, `>=` | none | comparisons; chaining is an error |
| 3 | `&&` | right | and, short-circuit |
| 2 | `\|\|` | right | or, short-circuit |
| 0 | `\|>`, `<\|` | `\|>` left, `<\|` right | pipelines |

- `<`, `>`, `<=` and `>=` compare `Int` only. Compare bytes with `Bytes.lessThan`.
- `==` and `/=` work on equality-capable types (below).
- `/` is rejected; use `//` for the integer quotient, and `modBy` or `remainderBy` for the rest.
  `^` and `%` do not exist.
- `a < b < c` is rejected: write `a < b && b < c`.

### Unary minus

Negation follows Elm 0.19. A `-` written directly before its operand, after a space or an opening
bracket, negates it: `f -1` passes `-1` to `f`, and `scale -x 10` passes `-x`. With a space after it, or
with no space before it, `-` subtracts: `x - 1` and `x-1` are both subtractions. A leading `-` negates the
whole function application that follows, so `-(scale x 2)` and `-f x` are negations of the result.

```elm title="src/Main.elm"
module Main exposing (main)


scale : Int -> Int -> Int
scale value factor =
    value * factor


main : List Int
main =
    let
        x =
            4
    in
    [ scale -x 10
    , modBy 3 -7
    , x - 1
    , -x * 2
    , -(scale x 2)
    , 2 * -3
    ]
```

```text
$ elci eval
[-40, 2, 3, -8, -8, -6]
cpu 2719341, mem 9131
```

This is also how elm-format writes negative arguments, so formatted code keeps its meaning. As in Elm,
`x -1` is an application of `x` to `-1`, not a subtraction.

## Equality

`==` and `/=` work on `Int`, `Bool`, `String`, `()`, `Bytes`, ledger hashes of the same type, and lists,
tuples, records, `Maybe` and custom types whose contents are all equality-capable. Imported types use
the equality their provider approves (Java `equals`). Both operands are evaluated once, left to right.

Rejected with `ELCI2003`: functions, `Data`, `Map`, `Array`, the ledger `Value` and types that contain one,
and imported types without approved equality.

## Evaluation order

Elci is strict. These rules are part of the language, because they decide which failures and costs
happen:

1. **Application.** The function, then its arguments from left to right. A partial application
   evaluates the arguments it has immediately.
2. **`let`.** Every binding is evaluated, even if unused, before the body. Bindings run in dependency
   order; independent ones run in source order.
3. **Literals and constructors.** List elements, tuple components, record fields and constructor
   arguments are evaluated from left to right, in source order. Record order does not follow the
   field names.
4. **Record update.** The base record first, then the new field values in source order.
5. **Selective forms.** `if` evaluates the condition and one branch. `case` evaluates the scrutinee once
   and one branch. `&&` and `||` evaluate the right operand only when needed.
6. **Composition.** `f >> g` evaluates `f` and `g` when the composition is built.
7. **Validators.** Top-level values reachable from any handler are evaluated once per script run,
   before the purpose is dispatched.

This program traces every step:

```elm title="src/Main.elm"
module Main exposing (main)

import Debug


type alias Order =
    { price : Int, amount : Int }


add : Int -> Int -> Int
add left right =
    left + right


main : Int
main =
    let
        total =
            Debug.trace "total" (subtotal + 1)

        subtotal =
            Debug.trace "subtotal" 10

        unrelated =
            Debug.trace "unrelated" 0

        order =
            { price = Debug.trace "price" 5, amount = Debug.trace "amount" 2 }

        sum =
            add (Debug.trace "left" 1) (Debug.trace "right" 2)

        same =
            Debug.trace "eq-left" 1 == Debug.trace "eq-right" 1
    in
    if same then
        total + order.price + sum

    else
        0
```

```text
$ elci eval
19
cpu 3661600, mem 13949
trace: subtotal
trace: total
trace: unrelated
trace: price
trace: amount
trace: left
trace: right
trace: eq-left
trace: eq-right
```

`subtotal` runs before `total` because `total` needs it, and `unrelated` runs although nothing uses it.

## Data encoding

Values that cross the script boundary (datums, redeemers, parameters) or pass through `Julc.Data.encode`
use a fixed encoding:

| Elci value | Plutus Data |
| --- | --- |
| `Int` | integer |
| `Bytes`, ledger hashes | byte string |
| `String` | byte string with the UTF-8 encoding |
| `Bool` | constructor 0 (`False`) or 1 (`True`), no fields |
| `()` | constructor 0, no fields (as a datum or redeemer; `Julc.Data.encode ()` is rejected) |
| record | constructor 0, fields **sorted by field name** |
| tuple | constructor 0, fields in position order |
| custom type | constructor tag = the variant's position in the declaration, from 0; fields in order |
| `Maybe a` | `Nothing` is constructor 0; `Just x` is constructor 1 with one field |
| `JulcOptional a` | `Some x` is constructor 0 with one field; `None` is constructor 1 |
| `List a` | list |
| `Map k v` | map |
| imported record or sum | as declared in Java: constructor in declaration order, fields in declaration order |

Functions and arrays have no encoding. You can see the encoding of any value with `Julc.Data.encode` and
`elci eval`, which prints Data as `Constr tag [fields]`, lists, `{key: value}` maps, integers and `#bytes`:

```elm title="src/Main.elm"
module Main exposing (main)

import Julc.Data as Data


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

The record `{ beneficiary = 1, amount = 2 }` encodes as `Constr 0 [2, 1]`, because `amount` sorts before
`beneficiary`.

Decoding is strict: tags, field counts and nested field kinds are checked, and a mismatch fails the
script. Each build records the encoding of its boundary types in `abi.json`; see
[datums and redeemers](/contracts/datums-and-redeemers/).

## Compiler limits

- A program may create at most 256 specializations of generic functions.
- Tuples have two or three elements.
- The only target is `plutus-v3-pv11-uplc-1.1.0`.

For the error codes these rules produce, see [diagnostics](/reference/diagnostics/).
