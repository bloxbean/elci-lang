---
title: Lists, maps and arrays
description: Work with Elci's three collections, List, Map and Array, write your own list functions with recursion, and choose the right one for on-chain cost.
sidebar:
  order: 5
---

Elci has three collection types. `List` is the everyday sequence, `Map` stores key/value pairs like Elm's
`Dict`, and `Array` gives constant-time indexed reads. This page covers all three, and what they cost on-chain.

## Lists

A list holds any number of values of one type: `[ 1, 2, 3 ]` is a `List Int`. `::` puts an element in front of
a list, `++` joins two lists, and you take lists apart with patterns:

```elm title="src/Main.elm"
module Main exposing (main)


sum : List Int -> Int
sum values =
    case values of
        [] ->
            0

        first :: rest ->
            first + sum rest


main : Int
main =
    let
        amounts =
            0 :: [ 10, 20 ] ++ [ 30 ]
    in
    sum amounts + List.length amounts
```

```text
64
cpu 10418493, mem 50002
```

`amounts` is `[ 0, 10, 20, 30 ]`, so the result is 60 + 4. `[]` matches the empty list, and `first :: rest`
matches a list with a first element and the rest after it. Most list functions are this shape: handle the
empty list, then handle one element and recurse on the rest.

### The List module

`List` is available in every module without an import. It provides these functions:

| Function | Type | Example | Result |
| --- | --- | --- | --- |
| `map` | `(a -> b) -> List a -> List b` | `List.map (\n -> n * 2) [ 3, 1, 4 ]` | `[ 6, 2, 8 ]` |
| `filter` | `(a -> Bool) -> List a -> List a` | `List.filter (\n -> n > 2) [ 3, 1, 4 ]` | `[ 3, 4 ]` |
| `foldl` | `(a -> b -> b) -> b -> List a -> b` | `List.foldl (\n acc -> n :: acc) [] [ 3, 1, 4 ]` | `[ 4, 1, 3 ]` |
| `foldr` | `(a -> b -> b) -> b -> List a -> b` | `List.foldr (\n acc -> n :: acc) [] [ 3, 1, 4 ]` | `[ 3, 1, 4 ]` |
| `any` | `(a -> Bool) -> List a -> Bool` | `List.any (\n -> n == 4) [ 3, 1, 4 ]` | `True` |
| `all` | `(a -> Bool) -> List a -> Bool` | `List.all (\n -> n > 1) [ 3, 1, 4 ]` | `False` |
| `length` | `List a -> Int` | `List.length [ 3, 1, 4 ]` | `3` |
| `reverse` | `List a -> List a` | `List.reverse [ 3, 1, 4 ]` | `[ 4, 1, 3 ]` |
| `append` | `List a -> List a -> List a` | `List.append [ 1 ] [ 2 ]` | `[ 1, 2 ]` |
| `concatMap` | `(a -> List b) -> List a -> List b` | `List.concatMap (\n -> [ n, n ]) [ 1, 2 ]` | `[ 1, 1, 2, 2 ]` |

The folds pass the element first and the accumulator second. `foldl` starts from the front of the list and
`foldr` from the back.

These functions are ordinary Elci code, compiled like yours. Anything else, such as `head`, `sum`,
`member`, `range` or `take`, you write yourself in a few lines:

```elm title="src/Main.elm"
module Main exposing (main)


nth : Int -> List a -> Maybe a
nth index items =
    case items of
        [] ->
            Nothing

        first :: rest ->
            if index == 0 then
                Just first

            else
                nth (index - 1) rest


range : Int -> Int -> List Int
range from to =
    if from > to then
        []

    else
        from :: range (from + 1) to


main : Maybe Int
main =
    nth 90 (range 1 100)
```

```text
Just 91
cpu 157858965, mem 712356
```

The 91st element (index 90) is `Just 91`. Note the budget: building a 100-element list and
walking 90 steps into it took about 158 million CPU units. On-chain lists are linked lists, so reaching
element `n` takes `n` steps.

## Maps

`Map k v` stores values of type `v` under keys of type `k`, like Elm's `Dict`. Write `import Map` to use its
functions. The type name `Map` is always in scope, and `import Map exposing (Map)` works too, as it would
with Elm's `Dict`.

```elm title="src/Main.elm"
module Main exposing (main)

import Map


type alias Balances =
    Map String Int


deposit : String -> Int -> Balances -> Balances
deposit owner amount balances =
    Map.update owner
        (\current ->
            case current of
                Just balance ->
                    Just (balance + amount)

                Nothing ->
                    Just amount
        )
        balances


balanceOf : String -> Balances -> Int
balanceOf owner balances =
    case Map.get owner balances of
        Just balance ->
            balance

        Nothing ->
            0


main : Int
main =
    let
        balances =
            Map.empty
                |> deposit "alice" 100
                |> deposit "bob" 40
                |> deposit "alice" 25
    in
    balanceOf "alice" balances * 1000 + balanceOf "carol" balances + Map.size balances
```

```text
125002
cpu 43209649, mem 141913
```

Alice has 125, Carol has nothing (0), and the map has 2 keys.

| Function | Type |
| --- | --- |
| `empty` | `Map k v` |
| `singleton` | `k -> v -> Map k v` |
| `fromList` | `List ( k, v ) -> Map k v` |
| `get` | `k -> Map k v -> Maybe v` |
| `member` | `k -> Map k v -> Bool` |
| `insert` | `k -> v -> Map k v -> Map k v` (replaces an existing key) |
| `remove` | `k -> Map k v -> Map k v` |
| `update` | `k -> (Maybe v -> Maybe v) -> Map k v -> Map k v` (`Nothing` removes the key) |
| `size`, `isEmpty` | `Map k v -> Int`, `Map k v -> Bool` |
| `keys`, `values` | `Map k v -> List k`, `Map k v -> List v` |
| `toList` | `Map k v -> List ( k, v )` |
| `foldl` | `(k -> v -> b -> b) -> b -> Map k v -> b` |
| `filter` | `(k -> v -> Bool) -> Map k v -> Map k v` |
| `map` | `(k -> a -> b) -> Map k a -> Map k b` |

A `Map` uses the ledger's own map representation: a list of key/value pairs, with keys compared by their
Data encoding. That has consequences:

- **Lookups are O(n).** `get`, `member`, `insert` and `remove` scan the pairs. That's fine for small maps;
  keep large ones off-chain.
- **Maps are not sorted.** `keys`, `values` and `toList` follow the map's own order. Currently `insert` and
  `fromList` put a new key at the front, and `map` and `filter` keep the existing order:

  ```elm title="src/Main.elm"
  module Main exposing (main)

  import Map


  main : List Int
  main =
      Map.fromList [ ( 3, "c" ), ( 1, "a" ), ( 2, "b" ) ]
          |> Map.insert 0 "z"
          |> Map.keys
  ```

  ```text
  [0, 2, 1, 3]
  cpu 30194529, mem 102748
  ```

- **Maps can't be compared with `==`:**

  ```text
  .../src/Main.elm:8:31: ELCI2003: Equality is unsupported for Map [Int, Int] because it contains a function, opaque Data, or an imported type without verified equality
  ```

Keys and values can be any type that has a Data encoding, including your own records and custom types and
ledger types such as `PubKeyHash`.

## Arrays

`Array a` is an immutable sequence with **constant-time** `get` and `length`, like Elm's `Array`. It is a
Plutus V3 array (CIP-138). As with `Map`, import the module for its functions:

```elm title="src/Main.elm"
module Main exposing (main)

import Array


main : Int
main =
    let
        tiers =
            Array.fromList [ 0, 100, 250, 500 ]
    in
    case ( Array.get 2 tiers, Array.get 9 tiers ) of
        ( Just fee, Nothing ) ->
            fee + Array.length tiers

        _ ->
            0
```

```text
254
cpu 6588326, mem 22837
```

`Array.get` returns `Nothing` for an index outside the array. The module also has `empty`, `isEmpty`,
`initialize`, `repeat`, `toList`, `toIndexedList`, `map`, `indexedMap`, `foldl`, `foldr`, `filter` and
`slice`. In `slice start end`, a negative index counts from the end, so `Array.slice 0 -1 tiers` drops the
last element.

Arrays have two important limits:

- **No updates.** There is no `set` or `push`. Build a `List`, then convert it once with `Array.fromList`,
  which is O(n).
- **No Data encoding.** An array can't be a datum, redeemer or parameter, and can't be stored inside a
  record, custom type, tuple, list or map:

  ```elm
  main : Int
  main =
      List.length [ Array.fromList [ 1 ] ]
  ```

  ```text
  .../src/Main.elm:8:5: ELCI2013: Arrays have no Data encoding and cannot be stored in data; keep a List and convert it with Array.fromList
  ```

  Keep a `List` in your data, and convert it where you need many indexed reads.

## Choosing a collection

Everything a script does costs budget, and traversals are where budgets go. Here are nine lookups into the
same 100-element list, done two ways. This version uses an array:

```elm title="src/Main.elm"
module Main exposing (main)

import Array


range : Int -> Int -> List Int
range from to =
    if from > to then
        []

    else
        from :: range (from + 1) to


main : Int
main =
    let
        values =
            Array.fromList (range 1 100)

        at index =
            case Array.get index values of
                Just value ->
                    value

                Nothing ->
                    0
    in
    List.foldl (\index total -> at index + total) 0 [ 10, 20, 30, 40, 50, 60, 70, 80, 90 ]
```

The other version is the same program with `values = range 1 100` and `nth index values` in place of
`Array.get index values`, using the `nth` function from earlier on this page:

| Version | CPU | Memory |
| --- | ---: | ---: |
| `List` with `nth` | 498,539,665 | 2,253,391 |
| `Array` with `Array.get` | 114,358,728 | 482,247 |

Both results are 459, and both build the same list. The array version also converts it once, but it wins
on the lookups: each `nth` walks up to 90 elements, while each `Array.get` takes a constant number of steps.

- Use a **List** for data, and when you process every element once (`map`, `filter`, `foldl`, `any`).
- Use a **Map** for small keyed collections, especially ones that come from ledger data.
- Use an **Array** when you read many elements by index. Convert once, then read many times.

[Costs and performance](/advanced/costs/) goes deeper into budgets.

## Next

[Modules and imports](/learn/modules/) splits your code across files.
