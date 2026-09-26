---
title: Values, functions and types
description: Elci's basic types, operators, functions with type signatures, lambdas, currying and generic functions, with examples you can run.
sidebar:
  order: 1
---

This page covers the building blocks of Elci: basic values and their types, operators, functions and
their signatures, lambdas, partial application and generic functions. Every example is a complete module
that you can run.

## Set up a playground

A function project runs one value with `elci eval`, which makes it the quickest way to try things out:

```sh
elci init playground --purpose function
cd playground
elci eval .
```

```text
42
cpu 16100, mem 200
```

The project's `elci.toml` ends with an `[entrypoint]` table, which points `elci eval` at the value `main` in
module `Main`:

```toml title="elci.toml"
[entrypoint]
module = "Main"
function = "main"
purpose = "function"
```

and `src/Main.elm` is:

```elm title="src/Main.elm"
module Main exposing (main)


main : Int
main =
    42
```

To try an example from these pages, replace `src/Main.elm` with it and run `elci eval .` again. Keep the
`module Main exposing (main)` line and a `main` value, because that's what `elci.toml` names.

`elci eval` compiles `main` to a Plutus script, runs it on Julc's virtual machine and prints:

- the value, written in Elci syntax;
- the execution budget the script used, in CPU and memory units. The ledger charges fees for these units;
- a `trace:` line for each `Debug.trace` message, if there are any.

Add `--builtins` to also list every Plutus builtin function that the script called.

## Basic types

| Type | Examples | Notes |
| --- | --- | --- |
| `Int` | `42`, `-7`, `1000000` | Whole numbers of any size. There is no `Float`. |
| `Bool` | `True`, `False` | |
| `String` | `"GOLD"`, `"line\n"` | Text. Join with `++`, compare with `==` or `<`. The `String` module has `String.fromInt` and more. |
| `Bytes` | `Bytes.fromString "GOLD"` | Raw byte strings, from `import Bytes` |
| `()` | `()` | Unit: a type with exactly one value, used for "nothing to say" |

Strings support the escapes `\n`, `\r`, `\t`, `\"` and `\\`. There is no `Char` type.

Values and functions are both defined with `=`, each with a type signature above it. A function lists its
parameters before the `=`:

```elm title="src/Main.elm"
module Main exposing (main)


lovelacePerAda : Int
lovelacePerAda =
    1000000


toLovelace : Int -> Int
toLovelace ada =
    ada * lovelacePerAda


main : Int
main =
    toLovelace 25 + 500000
```

```sh
elci eval . --builtins
```

```text
25500000
cpu 480261, mem 1904
builtin: MultiplyInteger(25, 1000000) → 25000000
builtin: AddInteger(25000000, 500000) → 25500000
```

The builtin lines show the two operations the script performed. A lovelace is the smallest unit of ada,
and on-chain amounts are always whole lovelace, which is one reason Elci doesn't need `Float`.

## Numbers

`Int` has arbitrary precision: `+`, `-` and `*` are exact and never overflow.

```elm title="src/Main.elm"
module Main exposing (main)


main : Int
main =
    1000000000 * 1000000000 * 1000000000
```

```text
1000000000000000000000000000
cpu 16100, mem 200
```

There are three ways to divide:

| Operation | Result |
| --- | --- |
| `a // b` | Integer division, truncating toward zero: `7 // 2` is `3` and `-7 // 2` is `-3` |
| `modBy d n` | Remainder with the sign of the divisor `d`: `modBy 3 -7` is `2` |
| `remainderBy d n` | Remainder with the sign of the dividend `n`: `remainderBy 3 -7` is `-1` |

```elm title="src/Main.elm"
module Main exposing (main)


main : ( Int, Int, Int )
main =
    ( -7 // 2, modBy 3 -7, remainderBy 3 -7 )
```

```text
( -3, 2, -1 )
cpu 1377500, mem 4659
```

`^` raises an integer to a power, and the prelude, which every module imports, provides the other everyday
functions: `negate`, `abs`, `min`, `max` and `clamp`.

```elm title="src/Main.elm"
module Main exposing (main)


main : List Int
main =
    [ 2 ^ 10, negate 5, abs -4, min 3 7, max 3 7, clamp 0 100 250 ]
```

```text
[1024, -5, 4, 3, 7, 100]
cpu 9197162, mem 37659
```

`clamp low high x` keeps `x` between the bounds. [Core modules](/reference/core-modules/#basics) lists the
whole prelude.

Dividing by zero (with `//`, `modBy` or `remainderBy`) doesn't return a value: it fails the script, and a
failing validator rejects the transaction. `/` and fractional literals such as `1.5` are rejected by the
compiler:

```elm
main : Int
main =
    7 / 2
```

```text
.../src/Main.elm:6:7: ELCI2001: Float division is unsupported; use // for integer quotient
```

To work with fractions, scale your integers. For example, store a percentage in basis points (1% is 100).

## Booleans and comparisons

| Operator | Meaning |
| --- | --- |
| `==`, `/=` | Equal, not equal. Works on numbers, strings, bytes, lists, tuples, records, custom types and dictionaries, but not on functions or `Map`s |
| `<`, `<=`, `>`, `>=` | Ordering, for comparable types: numbers, strings, bytes, and lists and tuples of them |
| `&&`, `\|\|` | And, or. The right side is evaluated only when needed |
| `not` | Negation: `not True` is `False` |

```elm title="src/Main.elm"
module Main exposing (main)


isValidFee : Int -> Bool
isValidFee fee =
    fee >= 155381 && fee <= 2000000


main : Bool
main =
    isValidFee 200000 && not (isValidFee 5) && "ada" < "lovelace"
```

```text
True
cpu 1051432, mem 5116
```

Strings order by their UTF-8 bytes, so `"Zebra" < "apple"`: capital letters come first. `compare a b`
returns `LT`, `EQ` or `GT`. Booleans, records and custom types have no order, and `True < False` is a
compile error.

Comparisons can't be chained. Write `low < x && x < high` instead of `low < x < high`:

```elm
main : Bool
main =
    1 < 2 < 3
```

```text
.../src/Main.elm:6:11: ELCI1002: Comparison operators cannot be chained; use && or explicit parentheses
```

## Functions and signatures

A call lists its arguments after the function name, separated by spaces: `toLovelace 25`,
`isValidFee 200000`. Parentheses group expressions, not arguments: `not (isValidFee 5)`.

**Every top-level declaration needs a type signature.** In a signature, `->` separates the parameter types
from the result type. `Int -> Int -> Int` takes two integers and returns one. Leaving a signature out is an
error:

```elm
double x =
    x * 2
```

```text
.../src/Main.elm:4:1: ELCI2004: Top-level declarations require a type signature
```

Inside a function, types are inferred, so local values need no annotations (you may still add them). When
types don't fit together, the compiler tells you where:

```elm
main : Int
main =
    1 + "one"
```

```text
.../src/Main.elm:6:7: ELCI2002: The operator + works on Int values, not String; use ++ to append
```

## Lambdas, currying and partial application

`\x -> expression` is an anonymous function, or lambda. `\n total -> n + total` takes two arguments.

Every function takes its arguments one at a time. `add : Int -> Int -> Int` is a function that takes an
`Int` and returns a function `Int -> Int`, so you can supply only the first argument:

```elm title="src/Main.elm"
module Main exposing (main)


add : Int -> Int -> Int
add x y =
    x + y


addFive : Int -> Int
addFive =
    add 5


main : Int
main =
    List.foldl add 0 (List.map addFive [ 1, 2, 3 ])
```

```text
21
cpu 6795728, mem 31808
```

`addFive` is `add` with its first argument already supplied. `List.map addFive` adds 5 to each element
(giving `[ 6, 7, 8 ]`), and `List.foldl add 0` sums them.

Operators are functions too. Written in parentheses, `(+)` is the function `\a b -> a + b`, so
`List.foldl (+) 0` also sums a list, and `(*) 2` doubles a number.

Elci is strict: a partial application evaluates the arguments you give it straight away. `add (10 // 0)`
fails at that point, not later when the second argument arrives.

## Generic functions

A lowercase name in a signature, such as `a`, is a type variable: the function works for any type in that
position.

```elm title="src/Main.elm"
module Main exposing (main)


twice : (a -> a) -> a -> a
twice transform value =
    transform (transform value)


main : Int
main =
    if twice (\word -> word ++ "!") "go" == "go!!" then
        twice (\n -> n * 10) 3

    else
        0
```

```text
300
cpu 1189600, mem 6013
```

`twice` is used with strings and with integers. The compiler specializes a generic function for each
concrete type it's used with.

A signature can't promise more than the code delivers. This one claims to work for any `a`, but `x + 0` only
works for `Int`:

```elm
identity : a -> a
identity x =
    x + 0
```

```text
.../src/Main.elm:4:1: ELCI2002: Type mismatch: expected Int, found a
```

To compare values of a type variable with `<`, or to join them with `++`, name the variable
`comparable` or `appendable`, as in Elm: `largest : comparable -> comparable -> comparable` works for
numbers, strings and bytes. The [language reference](/reference/language/#constrained-type-variables)
covers these names.

## Bytes and unit

Byte strings are everywhere on Cardano: key hashes, token names and script hashes. The `Bytes` module wraps
the Plutus byte-string builtins:

```elm title="src/Main.elm"
module Main exposing (main)

import Bytes


tokenName : Bytes
tokenName =
    Bytes.fromString "GOLD"


main : Int
main =
    Bytes.length tokenName + Bytes.length (Bytes.sha256 tokenName)
```

```sh
elci eval . --builtins
```

```text
36
cpu 786669, mem 2032
builtin: EncodeUtf8("GOLD") → #474f4c44
builtin: LengthOfByteString(#474f4c44) → 4
builtin: Sha2_256(#474f4c44) → #fb5b8ae0fd37258b...
builtin: LengthOfByteString(#fb5b8ae0fd37258b...) → 32
builtin: AddInteger(4, 32) → 36
```

"GOLD" is the 4 bytes `#474f4c44`, and its SHA-256 hash is 32 bytes. Byte strings are written in hex after
a `#`. [Bytes, hashes and bits](/advanced/bytes-and-crypto/) covers the whole module.

The unit value `()` carries no information. You met it in [Your first validator](/start/first-validator/)
as a datum and redeemer that the validator ignores.

## Comments

```elm title="src/Main.elm"
module Main exposing (main)


answer : Int
answer =
    -- A line comment.
    42



{- A block comment,
   which can span lines.
-}


{-| A documentation comment, directly above a declaration.
-}
main : Int
main =
    answer
```

## Next

[Conditionals, let and pipelines](/learn/control-flow/) shows how to make decisions, name intermediate
values and chain function calls.
