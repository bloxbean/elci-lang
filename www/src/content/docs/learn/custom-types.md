---
title: Custom types and pattern matching
description: Define custom types, match on them exhaustively with case, use Maybe and Result, nest patterns, and see how constructors are encoded on-chain.
sidebar:
  order: 4
---

A custom type lists the shapes a value can take. Custom types and exhaustive pattern matching are the
most useful part of Elci for contracts. A redeemer is usually a custom type with one constructor per
action, and the compiler checks that your validator handles every one.

## Defining a custom type

Each alternative is a **constructor**, optionally carrying values:

```elm title="src/Main.elm"
module Main exposing (main)


type Action
    = Deposit Int
    | Withdraw Int
    | Close


apply : Action -> Int -> Int
apply action balance =
    case action of
        Deposit amount ->
            balance + amount

        Withdraw amount ->
            balance - amount

        Close ->
            0


main : Int
main =
    [ Deposit 100, Withdraw 30, Deposit 5 ]
        |> List.foldl apply 0
```

```text
75
cpu 7551636, mem 30690
```

`Deposit 100` builds a value of type `Action`. In `case`, the pattern `Deposit amount` matches that
constructor and binds its value to `amount`.

## Every case must be complete

If you add a constructor, every `case` on that type must handle it. Here `Close` is missing:

```elm
apply : Action -> Int -> Int
apply action balance =
    case action of
        Deposit amount ->
            balance + amount

        Withdraw amount ->
            balance - amount
```

```text
.../src/Main.elm:12:5: ELCI2007: Non-exhaustive case; add the missing constructor or a wildcard branch
```

A branch that can never match is also an error. `Just 0` below is unreachable, because `Just _` already
matches every `Just`:

```elm
isZero : Maybe Int -> Bool
isZero value =
    case value of
        Just _ ->
            False

        Just 0 ->
            True

        Nothing ->
            False
```

```text
.../src/Main.elm:10:9: ELCI2006: Unreachable pattern
```

:::tip
Prefer listing constructors to a `_` wildcard in validators. With explicit branches, adding a new redeemer
action makes the compiler point at every place that must decide what to do with it.
:::

## How constructors are encoded

Constructors are numbered in declaration order, starting at 0: `Deposit` is 0, `Withdraw` is 1 and
`Close` is 2. On-chain, a value is a Plutus Data constructor with that tag and its values as fields.
`Julc.Data.encode` shows the encoding:

```elm title="src/Main.elm"
module Main exposing (main)

import Julc.Data as Data


type Action
    = Deposit Int
    | Withdraw Int
    | Close


main : List Data
main =
    [ Data.encode (Deposit 100), Data.encode (Withdraw 30), Data.encode Close ]
```

```text
[Constr 0 [100], Constr 1 [30], Constr 2]
cpu 1623933, mem 7648
```

:::caution
Reordering, inserting or removing constructors changes their tags, and with them the encoding of every
datum and redeemer of that type. Add new constructors at the end, and pin the encoding with `expected-abi`
(see [Build and deploy](/contracts/build-and-deploy/)).
:::

`Bool` is encoded the same way: `False` is constructor 0 and `True` is constructor 1.

## Maybe

`Maybe a` is built in. It is either `Just value` or `Nothing`, and it's how Elci represents "there may be no
answer". The `Maybe` module, which every module imports, has Elm's helpers: `withDefault`, `map`, `map2`,
`map3` and `andThen`.

```elm title="src/Main.elm"
module Main exposing (main)


findPrice : String -> List ( String, Int ) -> Maybe Int
findPrice name prices =
    case prices of
        [] ->
            Nothing

        ( candidate, price ) :: rest ->
            if candidate == name then
                Just price

            else
                findPrice name rest


main : Int
main =
    let
        prices =
            [ ( "apple", 3 ), ( "pear", 5 ) ]
    in
    Maybe.withDefault 0 (findPrice "pear" prices) + Maybe.withDefault 0 (findPrice "plum" prices)
```

```text
5
cpu 11103777, mem 42693
```

`Nothing` is constructor 0 and `Just` is constructor 1. Julc's Java `Optional` uses different tags, so the
two aren't interchangeable. `Julc.Data.fromOptional` and `toOptional` convert between them when a Java
library returns an optional value.

## Result

`Result error value` is either `Ok value` or `Err error`, as in Elm. Use it for a computation that can
fail with a reason. The `Result` module, imported into every module, has `map`, `andThen`, `withDefault`,
`toMaybe`, `mapError` and more:

```elm title="src/Main.elm"
module Main exposing (main)


type alias Payout =
    { amount : Int
    , fee : Int
    }


validate : Payout -> Result String Payout
validate payout =
    if payout.amount <= 0 then
        Err "amount must be positive"

    else if payout.fee > payout.amount then
        Err "fee exceeds amount"

    else
        Ok payout


main : List (Result String Int)
main =
    [ Payout 100 2, Payout 0 1, Payout 5 9 ]
        |> List.map (\payout -> Result.map (\valid -> valid.amount - valid.fee) (validate payout))
```

```text
[Ok 98, Err "amount must be positive", Err "fee exceeds amount"]
cpu 15781289, mem 59252
```

On-chain, a validator returns `Bool`, so a `Result` usually ends in a `case` that returns `False` for an
`Err`. As Data, `Ok` is constructor 0 and `Err` is constructor 1, the reverse of PlutusTx's `Either`.

## Nested patterns

Patterns can nest constructors, tuples, records and lists. A list pattern such as `[ x ]` matches a list of
exactly that length, and `first :: rest` matches any non-empty list:

```elm title="src/Main.elm"
module Main exposing (main)


type Shape
    = Circle Int
    | Rectangle Int Int


describe : List Shape -> Int
describe shapes =
    case shapes of
        [] ->
            0

        [ Circle 0 ] ->
            1

        [ Rectangle width height ] ->
            if width == height then
                2

            else
                3

        (Circle _) :: rest ->
            10 + describe rest

        (Rectangle _ _) :: rest ->
            20 + describe rest


main : Int
main =
    describe [ Circle 5, Rectangle 2 3, Rectangle 4 4 ]
```

```text
32
cpu 12364548, mem 46519
```

`Circle 5` doesn't match `[ Circle 0 ]` (the list has three elements), so the `(Circle _) :: rest` branch adds
10. The same happens for `Rectangle 2 3`, which adds 20. The last element, `Rectangle 4 4`, is alone in its
list and square, which adds 2.

## Constructors as functions, and `as`

A constructor is also a function: `Deposit` has type `Int -> Action`, so you can pass it to `List.map`. An
`as` pattern names the whole value that a pattern matched:

```elm title="src/Main.elm"
module Main exposing (main)


type Action
    = Deposit Int
    | Withdraw Int
    | Close


total : List Action -> Int
total actions =
    case actions of
        [] ->
            0

        ((Deposit amount) as first) :: rest ->
            if first == Deposit 0 then
                total rest

            else
                amount + total rest

        _ :: rest ->
            total rest


main : Int
main =
    total (List.map Deposit [ 0, 10, 20 ] ++ [ Close, Withdraw 5 ])
```

```text
30
cpu 19075676, mem 69864
```

Custom types support `==` when all their fields do.

## Recursive and generic types

A custom type can refer to itself, and can take type parameters:

```elm title="src/Main.elm"
module Main exposing (main)


type Tree a
    = Leaf a
    | Branch (Tree a) (Tree a)


sum : Tree Int -> Int
sum tree =
    case tree of
        Leaf value ->
            value

        Branch left right ->
            sum left + sum right


depth : Tree a -> Int
depth tree =
    case tree of
        Leaf _ ->
            1

        Branch left right ->
            1 + max (depth left) (depth right)


max : Int -> Int -> Int
max a b =
    if a > b then
        a

    else
        b


main : Int
main =
    let
        tree =
            Branch (Leaf 3) (Branch (Leaf 4) (Leaf 5))
    in
    sum tree * 10 + depth tree
```

```text
123
cpu 17632608, mem 64972
```

The sum is 12 and the depth is 3. Elci has no built-in `max`, so this module defines one. Recursion is how
you walk any recursive structure, including lists. Every step costs budget, so keep on-chain data shallow.

## Next

[Lists, maps and arrays](/learn/collections/) covers Elci's collection types and what they cost.
