---
title: Language reference
description: Elci's feature matrix, syntax, imports and reserved names, operators and precedence, equality and ordering, constrained type variables, evaluation order and Plutus Data encoding rules.
sidebar:
  order: 5
---

This page is the compact reference for the Elci language: what is supported, how the syntax looks, how
names resolve, how operators bind, which types compare, in which order things are evaluated and how values
are encoded as Plutus Data. For a
gentle introduction, start with [values, functions and types](/learn/basics/).

Elci uses Elm 0.19 syntax for a functional core, with its own on-chain semantics. It is a development
preview: the profile below is implemented and tested, and everything outside it is rejected with an error.
The [grammar](/reference/grammar/) defines the syntax exactly.

## Feature matrix

| Feature | Support |
| --- | --- |
| Modules and imports | Source modules, `as` aliases, explicit `exposing`, `exposing (..)` for source modules, `Type(..)` for constructors, and Elm's default imports. Import cycles are rejected. |
| Functions | Top-level and local functions, lambdas with several arguments, curried and partial application, operators as functions such as `(+)`. |
| Type signatures | Required on every top-level declaration; allowed on local ones. |
| Generics | Rank-1 type variables, specialized to each concrete use. Elm's constrained variables `comparable`, `appendable` and `compappend`. |
| Local polymorphism | Simple local functions and values can be used at several types; calls, conditionals and matches stay monomorphic. |
| Recursion | Self-recursion and mutually recursive functions, top-level or local. Polymorphic recursion and recursive values are rejected. |
| Basic types | `Int` (arbitrary precision), `Bool`, `String`, `()`, `Bytes`, `Data`. |
| Core library | The `Basics` prelude and the `List`, `Maybe`, `Result`, `Tuple` and `String` modules, imported by default. See [core modules](/reference/core-modules/). |
| Lists | `List a`, literals, `::`, `++` and list patterns. |
| Tuples | Pairs and triples, with patterns and destructuring. |
| Records | Closed records, generic aliases with constructors, field access, accessor functions such as `.name`, update, destructuring. |
| Custom types | Generic and recursive unions; `Maybe` is built in, and `Result` and `Order` come from the core library. |
| Patterns | Nested constructors, lists, cons, tuples, record fields, `as`, `_`, unit, and `Bool`, `Int` and `String` literals. Exhaustiveness and reachability are checked. |
| Equality and order | Structural `==` and `/=` for equality-capable types, and `<`, `compare` and sorting for comparable types (below). |
| Dictionaries, maps and arrays | `Dict k v` (Elm's `Dict`, stored as a sorted Plutus map), `Map k v` (the ledger's association map) and `Array a` (constant-time `get`). |
| Values | `Value`, the ledger's multi-asset value, with the core `Value` module: arithmetic, comparison and exact `==` on the CIP-153 builtins. |
| Bytes | The `Bytes` module: every byte-string builtin. |
| Ledger access | Typed views of the script context, nominal hash types, approved constructors, patterns and equality. The core `Tx` and `Interval` modules answer common questions about the transaction and its validity range. |
| Data conversions | `Julc.Data.decode` and `encode`, optional adapters, and structural `==` on `Data`. |
| Java libraries | Julc's on-chain Java libraries and your own library JARs, including generic methods. |
| Validators | `[validators.<name>]` scripts with spend, mint, withdraw, certify, vote and propose handlers, parameters and datum profiles. |
| Tests | `elci test` with unit tests, transaction scenarios and property tests. |

**Not supported:** `Float`, `/` and `number`, typeclasses and user-defined constraints, extensible
records and row polymorphism, higher-rank types, polymorphic recursion, ports and effects, Elm's `Set` and
`Char` modules and most of `String`, character literals, hexadecimal literals, `%`, tuples of four or
more, and Elm packages.

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
| Accessor function | `.x`, which is `\r -> r.x`: `List.map .x points` |
| Field of a qualified value | `Config.settings.x` |
| Tuple | `( 1, True )`, `( 1, 2, 3 )` |
| List | `[ 1, 2 ]`, `1 :: rest`, `xs ++ ys` |
| Operator as a function | `(+)`, `(::)`, `(<)`: `List.foldl (+) 0 xs` |
| Unit | `()` as a value, type and pattern |

Function and lambda parameters, and local destructuring, accept only **irrefutable** patterns: names,
`_`, `()`, tuples and record fields. Anything that can fail to match belongs in `case`.

### Layout

Indentation is significant, as in Elm. Top-level declarations start in column 1. The declarations of a
`let` align with each other, and so do the branches of a `case`. A signature must directly precede its
definition. Tabs are rejected. The grammar gives the [exact layout rules](/reference/grammar/#layout).

### Lexical rules

- **Names.** Values and functions start with a lowercase letter; types, constructors and module segments
  start with an uppercase letter. Letters, digits and `_` may follow.
- **Reserved words.** `if`, `then`, `else`, `case`, `of`, `let`, `in`, `module`, `import`, `exposing`,
  `as`, `type` and `port`. `alias` is only special after `type`. Don't use them as names: the parser
  doesn't yet reject every such use, as the grammar's [parser details](/reference/grammar/#parser-details-that-differ-from-elm)
  describe.
- **Integers.** Decimal digits only, arbitrarily large. `1.5` is rejected (`Float is unsupported`), and so
  are `0xFF` and `1_000`. elm-format 0.8.8 corrupts literals that do not fit in 64 bits, so check large
  constants after formatting.
- **Strings.** Double-quoted, on one line. The escapes are `\n`, `\r`, `\t`, `\"` and `\\`. There are no
  triple-quoted strings, Unicode escapes or character literals.
- **Comments.** `--` to the end of the line, and `{- ... -}` blocks, which nest. `{-| ... -}` doc comments
  are ordinary block comments.

## Imports and names

Every module has Elm's default imports, which you don't write:

```elm
import Basics exposing (..)
import List
import Maybe exposing (Maybe(..))
import Result exposing (Result(..))
import String
import Tuple
import Debug
```

`Bytes`, `Dict`, `Map`, `Array`, `Value`, `Interval`, `Tx`, `Julc.Data` and Java library modules are
imported explicitly.
Names resolve as in Elm, with one addition:

- A module's own declarations shadow every import.
- Two imports that expose different things under one name make it ambiguous where it is used
  (`ELCI2102`). The message lists the qualified alternatives.
- *Addition:* a name listed explicitly, as in `import Utils exposing (clamp)`, takes precedence over a
  default import.
- Qualified names such as `Basics.max` and `Result.Ok` always work. A qualified name is written without
  spaces and ends at its first lowercase part, so `Config.settings.fee` is the field `fee` of the value
  `Config.settings`.
- A qualified name that doesn't resolve is `ELCI2104`, with one of three messages: the module doesn't
  expose the name, the module isn't imported (with the import to add), or no imported module has that
  name. See [diagnostics](/reference/diagnostics/#elci2101-elci2102-and-elci2104).
- An import can't be aliased to the name of another module, core or in the project:
  `import Utils as String` and `import Julc.Interval as Interval` are `ELCI2104`.

[Core modules](/reference/core-modules/#default-imports) has the details and the messages.

### Reserved names

- **Module names.** `Basics`, `List`, `Maybe`, `Result`, `String`, `Tuple`, `Map`, `Dict`, `Array`,
  `Value`, `Interval` and `Tx` are core modules, and `Set` and `Elci` are reserved for the core library. Neither a project module
  nor a `[java-modules]` key can use these names, or a name that starts with `Elci.`:

  ```text
  src/String.elm:1:1: ELCI2104: The module name String is taken by Elci's core library; rename this module
  src/Value.elm:1:1: ELCI2104: The module name Value is taken by Elci's core library; rename this module
  src/Tx.elm:1:1: ELCI2104: The module name Tx is taken by Elci's core library; rename this module
  src/Set.elm:1:1: ELCI2104: The module name Set is reserved for Elci's core library; rename this module
  src/Elci/Tools.elm:1:1: ELCI2104: The module name Elci.Tools is reserved for Elci's core library; rename this module
  elci: ELCI4001: [java-modules] may not map Value, a module name of Elci's core library
  ```

  `Bytes`, `Debug` and `Julc.Data` belong to the compiler as well.
- **Constructors and types.** A module can't declare constructors named `Just`, `Nothing`, `True` or
  `False`, or types named `Int`, `Bool`, `String`, `Bytes`, `Data`, `List`, `Maybe`, `Dict`, `Map`, `Array`
  or `Float`:

  ```text
  src/Main.elm:4:6: ELCI2011: Nothing is a constructor of the built-in Maybe type; choose another name
  src/Main.elm:4:6: ELCI2011: Bytes is a built-in type; choose another name
  ```

  Other prelude names, such as `Ok`, `Order` or `max`, can be declared; your declaration shadows the
  prelude's in your module.
- **Type names.** Tuples are written `( a, b )` and unit `()`. `Tuple Int Int` gives
  `ELCI2012: Write tuple types as ( a, b ) or ( a, b, c )`, and `Unit` gives
  `ELCI2012: Write the unit type as ()`.

## Operators

From the tightest binding to the loosest:

| Precedence | Operators | Associativity | Meaning |
| ---: | --- | --- | --- |
| highest | `record.field` | | field access, written without spaces |
| 10 | `f x` | left | function application |
| 9 | `>>`, `<<` | `>>` right, `<<` left | function composition |
| 8 | `^` | right | integer power |
| 7 | `*`, `//` | left | multiplication, integer division truncating towards zero |
| 6 | `+`, `-` | left | addition, subtraction |
| 5 | `::`, `++` | right | list cons; append lists or strings |
| 4 | `==`, `/=`, `<`, `>`, `<=`, `>=` | none | comparisons; chaining is an error |
| 3 | `&&` | right | and, short-circuit |
| 2 | `\|\|` | right | or, short-circuit |
| 0 | `\|>`, `<\|` | `\|>` left, `<\|` right | pipelines |

Operators of one precedence that group in opposite directions can't be mixed without parentheses, as in
Elm: `f <| x |> g` is `ELCI1002: Mixing (<|) and (|>) needs parentheses: they associate in opposite
directions, as in Elm`, and the same holds for `>>` with `<<`.

- `+`, `-`, `*`, `//` and `^` work on `Int`. `2 ^ 10` is `1024`, `2 * 3 ^ 2` is `18`, and a negative
  exponent fails the script.
- `<`, `>`, `<=` and `>=` compare two values of the same [comparable type](#ordering).
- `==` and `/=` work on equality-capable types (below).
- `/` is rejected; use `//` for the integer quotient, and `modBy` or `remainderBy` for the rest. `%`
  does not exist.
- `a < b < c` is rejected: write `a < b && b < c`.

### Operators as functions

Every binary operator can be used as a function by writing it in parentheses, as in Elm: `(+)`, `(*)`,
`(::)`, `(++)`, `(<)`, `(^)`, `(|>)`. `(op)` is the function `\a b -> a op b`, so `(-) 10` subtracts
from 10. `(/)` is rejected, like `/`. As functions, `(&&)` and `(||)` evaluate both arguments, like any
other call.

```elm title="src/Main.elm"
module Main exposing (main)


main : ( Int, List Int, List Bool )
main =
    ( List.foldl (+) 0 [ 1, 2, 3 ]
    , List.foldr (::) [] [ 4, 5 ] ++ List.map ((-) 10) [ 1, 2 ]
    , List.map2 (<) [ 1, 5 ] [ 2, 2 ]
    )
```

```text
$ elci eval
( 6, [4, 5, 9, 8], [True, False] )
cpu 16223362, mem 77496
```

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

`==` and `/=` work on `Int`, `Bool`, `String`, `()`, `Bytes`, ledger hashes of the same type, `Value`,
`Data`, and lists, tuples, records, `Maybe`, `Dict` and custom types whose contents are all
equality-capable. Two dictionaries are equal when they have the same bindings, and two values when they
hold the same quantities: every `Value` is kept in one canonical form, so `==` compares their encodings.
`Data` compares structure exactly, so `Tx.inlineDatum output == Just (Data.encode expected)` checks a datum
without decoding it. Imported types use the equality their provider approves (Java `equals`). Both operands
are evaluated once, left to right.

Rejected with `ELCI2003`: functions, `Map`, `Array`, imported types without approved equality, and the
ledger records that contain a `Value` (`TxOut`, `TxInInfo`, `TxInfo`). Two maps with the same bindings may
hold them in different orders, so `Data.encode` of the two may differ: compare their sorted `toList`, or use
a `Dict`. The message names the part that
has no equality:

```text
src/Main.elm:6:21: ELCI2003: The operator == cannot compare List (Int -> Int) values: Int -> Int is part of them, and functions have no equality
```

## Ordering

`<`, `<=`, `>`, `>=`, `compare`, `min`, `max` and the sorting functions take two values of the same
**comparable** type:

- `Int`, ordered numerically;
- `String`, ordered by its UTF-8 bytes, which is Unicode code point order;
- `Bytes` and the ledger's byte-string types (`PubKeyHash`, `ScriptHash`, `PolicyId`, `TokenName`,
  `TxId` and the other hashes), ordered lexicographically by unsigned byte, a proper prefix first;
- lists of a comparable type, lexicographically, and tuples of comparable types, component by component.

`Bool`, `()`, records, custom types, `Maybe`, `Dict`, `Map`, `Array`, `Value`, `Data`, functions and ledger
records such as `TxOutRef` are not comparable (`ELCI2003`). Every order is total and agrees with `==`. Comparing `Int`
values uses the integer builtins; byte strings use `lessThanByteString` and `lessThanEqualsByteString`;
`String` encodes both sides to UTF-8 and compares the bytes; lists and tuples use a comparison function
generated once per type.

## Constrained type variables

As in Elm, a type variable in a signature is unconstrained unless its name says otherwise:

| Name | Values must support | Example |
| --- | --- | --- |
| `comparable`, or a name starting with it (`comparable2`, `comparableKey`) | `<`, `compare` and sorting | `max : comparable -> comparable -> comparable` |
| `appendable…` | `++` (strings and lists) | `twice : appendable -> appendable` |
| `compappend…` | both | |

The name is also a requirement on callers: each use is checked at its concrete type.

```elm title="src/Main.elm"
module Main exposing (main)


largest : comparable -> List comparable -> comparable
largest first rest =
    List.foldl max first rest


twice : appendable -> appendable
twice value =
    value ++ value


main : ( String, List Int )
main =
    ( twice (largest "fig" [ "apple", "pear" ]), twice [ largest 3 [ 9, 4 ] ] )
```

```text
$ elci eval
( "pearpear", [9, 9] )
cpu 8891872, mem 43250
```

Comparing or appending values of a plain variable is an error that suggests the rename:

```text
src/Main.elm:6:10: ELCI2003: The operator > compares values of type `a`, but the annotation of `larger` says `a` can be any type. Rename it `comparable` (as in Elm) to require comparable values
```

A requirement that no type can meet, such as `x < y` on values of type `Maybe comparable`, is reported at
the definition. A caller that passes the wrong type is reported at the call, and Elm's `number` gets its
own message, because Elci has one number type:

```text
src/Main.elm:11:5: ELCI2003: `twice` appends String or List values, not Int
src/Main.elm:4:1: ELCI2003: The annotation of `total` uses `number`, Elm's type variable for Int or Float. Elci has one number type: write Int
```

Equality needs no special name, as in Elm: `List.member : a -> List a -> Bool` uses `==`, and each use is
checked at its type. Type and alias parameters are never constrained, and a definition without a
signature, such as a local helper, infers its requirements.

## Ambiguous types

A type variable that nothing in a program determines, such as the element type of `[] == []` or the error
type of `Ok 5`, is *ambiguous*. As in Elm, such programs compile without annotations: no value of that
type can exist, so any representation serves. `List.length []` is `0`,
`Result.withDefault 0 (Ok 5)` is `5`, and `Julc.Data.encode Nothing` is `Constr 0`.

Creating a value of the type still needs to know it. `Julc.Data.decode` and generic Java library
functions report `ELCI3001` and ask for an annotation:

```elm
import Julc.Data as Data


main : Int
main =
    List.length (Data.decode (Data.encode [ 1, 2 ]))
```

```text
src/Main.elm:8:18: ELCI3001: Cannot determine the type converted by Julc.Data.decode; add a type annotation
```

Annotating the decoded value, as in a local `amounts : List Int`, fixes it.

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
| `Result e a` | `Ok x` is constructor 0 and `Err e` constructor 1, each with one field |
| `Order` | `LT`, `EQ` and `GT` are constructors 0, 1 and 2, no fields |
| `JulcOptional a` | `Some x` is constructor 0 with one field; `None` is constructor 1 |
| `List a` | list |
| `Dict k v` | map with its keys in strictly ascending order |
| `Map k v` | map, in the map's order |
| `Value` | map from policy ids to maps from token names to quantities, both in ascending byte order, without zero quantities |
| imported record or sum | as declared in Java: constructor in declaration order, fields in declaration order |

Functions and arrays have no encoding. A `Value` can't be a datum, redeemer or parameter yet, and a type
that contains one can't be decoded. You can see the encoding of any value with `Julc.Data.encode` and
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
script. A `Dict`, at any depth, must have strictly ascending keys: a map whose keys are out of order or
repeated fails to decode. A `Value` must be canonical: its policies and token names strictly ascending,
and no quantity zero. Each build records the encoding of its boundary types in `abi.json`; see
[datums and redeemers](/contracts/datums-and-redeemers/).

## Compiler limits

- A program may create at most 256 specializations of generic functions.
- Tuples have two or three elements.
- The only target is `plutus-v3-pv11-uplc-1.1.0`.

For the error codes these rules produce, see [diagnostics](/reference/diagnostics/).
