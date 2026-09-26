---
title: Lists, dictionaries, maps and arrays
description: Work with Elci's collections, List, Dict, Map and Array, write your own list functions with recursion, and choose the right one for on-chain cost.
sidebar:
  order: 5
---

Elci has four collection types. `List` is the everyday sequence, `Dict` stores values under comparable keys
as Elm's `Dict` does, `Map` is the ledger's own key/value map, and `Array` gives constant-time indexed reads.
This page covers all four, and what they cost on-chain.

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
empty list, then handle one element and recurse on the rest. `List.sum` already does this one; writing it
yourself shows the pattern.

### The List module

Every module imports `List`, so its functions need no import. These are the ones you'll use most:

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

`List` has the rest of Elm's list functions too, with the same names, argument orders and edge cases:

| Function | Example | Result |
| --- | --- | --- |
| `singleton`, `repeat` | `List.repeat 3 0` | `[ 0, 0, 0 ]` |
| `range` | `List.range 1 4` | `[ 1, 2, 3, 4 ]` |
| `isEmpty` | `List.isEmpty []` | `True` |
| `head` | `List.head [ 3, 1, 4 ]` | `Just 3` |
| `tail` | `List.tail [ 3, 1, 4 ]` | `Just [ 1, 4 ]` |
| `take` | `List.take 2 [ 3, 1, 4 ]` | `[ 3, 1 ]` |
| `drop` | `List.drop 2 [ 3, 1, 4 ]` | `[ 4 ]` |
| `member` | `List.member 4 [ 3, 1, 4 ]` | `True` |
| `sum`, `product` | `List.sum [ 3, 1, 4 ]` | `8` |
| `maximum`, `minimum` | `List.maximum [ 3, 1, 4 ]` | `Just 4` |
| `indexedMap` | `List.indexedMap (\i n -> i * n) [ 3, 1, 4 ]` | `[ 0, 1, 8 ]` |
| `filterMap` | `List.filterMap List.head [ [ 3 ], [], [ 4 ] ]` | `[ 3, 4 ]` |
| `partition` | `List.partition (\n -> n > 2) [ 3, 1, 4 ]` | `( [ 3, 4 ], [ 1 ] )` |
| `concat` | `List.concat [ [ 1 ], [ 2, 3 ] ]` | `[ 1, 2, 3 ]` |
| `intersperse` | `List.intersperse 0 [ 3, 1, 4 ]` | `[ 3, 0, 1, 0, 4 ]` |
| `map2`, `map3` | `List.map2 (+) [ 3, 1, 4 ] [ 10, 20 ]` | `[ 13, 21 ]` |
| `unzip` | `List.unzip [ ( 1, "a" ), ( 2, "b" ) ]` | `( [ 1, 2 ], [ "a", "b" ] )` |
| `sort` | `List.sort [ 3, 1, 4 ]` | `[ 1, 3, 4 ]` |
| `sortBy` | `List.sortBy negate [ 3, 1, 4 ]` | `[ 4, 3, 1 ]` |
| `sortWith` | `List.sortWith (\a b -> compare b a) [ 3, 1, 4 ]` | `[ 4, 3, 1 ]` |

`map2` stops at the shorter list, as in Elm, so the `4` above is dropped. [Core
modules](/reference/core-modules/#list) has every signature.

These functions are ordinary Elci code, compiled like yours. When one is missing, write it in a few lines.
There is no function to read the element at an index, for example:

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
    nth 90 (List.range 1 100)
```

```text
Just 91
cpu 159618965, mem 723356
```

The 91st element (index 90) is `Just 91`. Note the budget: building a 100-element list and
walking 90 steps into it took about 160 million CPU units. On-chain lists are linked lists, so reaching
element `n` takes `n` steps.

### Sorting

`List.sort` sorts values of a comparable type: numbers, strings, bytes and ledger hashes, and lists and
tuples of them. Records and custom types are not comparable, so sort them by a key with `List.sortBy`, or
with a comparison function that returns `LT`, `EQ` or `GT`:

```elm title="src/Main.elm"
module Main exposing (main)


type alias Bid =
    { bidder : String
    , amount : Int
    }


bids : List Bid
bids =
    [ Bid "carol" 30, Bid "alice" 50, Bid "bob" 30 ]


main : ( List String, List String, List String )
main =
    ( List.sort (List.map (\bid -> bid.bidder) bids)
    , List.map (\bid -> bid.bidder) (List.sortBy (\bid -> bid.amount) bids)
    , List.map (\bid -> bid.bidder) (List.sortWith (\a b -> compare b.amount a.amount) bids)
    )
```

```text
( ["alice", "bob", "carol"], ["carol", "bob", "alice"], ["alice", "carol", "bob"] )
cpu 67631155, mem 300118
```

The sorts are stable: `carol` and `bob` bid the same amount and keep their order. Sorting costs budget, so
sort on-chain only when the result depends on the order.

## Dictionaries

`Dict k v` stores values of type `v` under keys of type `k`, with Elm's `Dict` API. Import it as in Elm,
with `import Dict exposing (Dict)`. Keys must be comparable: `Int`, `String`, `Bytes`, ledger hashes such as
`PubKeyHash`, and lists and tuples of these.

```elm title="src/Main.elm"
module Main exposing (main)

import Dict exposing (Dict)


type alias Balances =
    Dict String Int


deposit : String -> Int -> Balances -> Balances
deposit owner amount balances =
    Dict.update owner
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
    Maybe.withDefault 0 (Dict.get owner balances)


main : ( List ( String, Int ), Int )
main =
    let
        balances =
            Dict.empty
                |> deposit "carol" 70
                |> deposit "alice" 100
                |> deposit "bob" 40
                |> deposit "alice" 25
    in
    ( Dict.toList balances, balanceOf "dave" balances )
```

```text
( [( "alice", 125 ), ( "bob", 40 ), ( "carol", 70 )], 0 )
cpu 37768518, mem 158024
```

Alice has 125, and Dave has no balance, so `balanceOf` gives 0. The bindings come out in key order, not in
the order they went in.

The module has all of Elm's `Dict` functions, with the same names, argument orders and results:

| Group | Functions |
| --- | --- |
| Build | `empty`, `singleton`, `insert`, `update`, `remove` |
| Query | `isEmpty`, `member`, `get`, `size` |
| Lists | `keys`, `values`, `toList`, `fromList` |
| Transform | `map`, `foldl`, `foldr`, `filter`, `partition` |
| Combine | `union`, `intersect`, `diff`, `merge` |

[Core modules](/reference/core-modules/#dict) has every signature. A `Dict` is stored as a Plutus map
whose keys are kept in ascending order. What that means for you:

- **Key order, always.** `keys`, `values`, `toList` and the folds follow ascending key order, as in Elm.
  `fromList` keeps the last value of a repeated key, and `union` prefers the left dictionary's value.
- **`==` works.** Two dictionaries with the same bindings are equal, however they were built:

  ```elm title="src/Main.elm"
  module Main exposing (main)

  import Dict


  main : ( List String, Bool )
  main =
      let
          scores =
              Dict.fromList [ ( "carol", 7 ), ( "alice", 9 ), ( "bob", 4 ), ( "alice", 5 ) ]
      in
      ( Dict.keys scores
      , scores == Dict.fromList [ ( "bob", 4 ), ( "alice", 5 ), ( "carol", 7 ) ]
      )
  ```

  ```text
  ( ["alice", "bob", "carol"], True )
  cpu 73414533, mem 282640
  ```

- **Lookups are O(n).** A `Dict` is a list of pairs, not a tree. `get`, `member`, `insert` and `remove`
  walk from the smallest key and stop at the first key that isn't smaller, at about 1.1 million CPU units
  per key passed. `fromList` sorts, in O(n log n).
- **Datums can hold dictionaries.** A `Dict` encodes as a Plutus map with sorted keys. A datum, redeemer or
  parameter whose keys are out of order or repeated fails to decode, so an attacker can't hide a key where
  lookups stop early. See [datums and redeemers](/contracts/datums-and-redeemers/#dictionaries-in-data).

## Maps

`Map k v` is the ledger's own key/value map: an unsorted list of pairs, with keys compared by their Data
encoding. The transaction's maps are `Map`s, such as `txInfo.withdrawals` (`Map Credential Int`) and
`txInfo.redeemers` (`Map ScriptPurpose Data`). Their keys are not comparable, so they can't be `Dict`s.
Use a `Map` for those, and for keys of your own that are not comparable, such as a custom type. Write
`import Map` to use its functions; the type name `Map` is always in scope.

```elm title="src/Main.elm"
module Main exposing (main)

import Map exposing (Map)


type Asset
    = Ada
    | Token String


holdings : Map Asset Int
holdings =
    Map.fromList [ ( Ada, 2000000 ), ( Token "GOLD", 5 ) ]


main : ( Maybe Int, Maybe Int, List Asset )
main =
    ( Map.get (Token "GOLD") holdings
    , Map.get (Token "SILVER") holdings
    , Map.keys (Map.insert (Token "SILVER") 1 holdings)
    )
```

```text
( Just 5, Nothing, [Token "SILVER", Token "GOLD", Ada] )
cpu 30055770, mem 101862
```

`Asset` is a custom type, so it can't be a `Dict` key, but it can be a `Map` key. The new key
`Token "SILVER"` comes first.

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
| `foldl`, `foldr` | `(k -> v -> b -> b) -> b -> Map k v -> b` |
| `filter` | `(k -> v -> Bool) -> Map k v -> Map k v` |
| `map` | `(k -> a -> b) -> Map k a -> Map k b` |

Because a `Map` is the ledger's representation:

- **Lookups are O(n).** `get`, `member`, `insert` and `remove` scan the pairs from the front, at about 1.1
  million CPU units per key passed. Building a map with `fromList` is O(n²). That's fine for small maps;
  for your own data with comparable keys, a `Dict` builds faster.
- **Maps are not sorted.** `keys`, `values` and `toList` follow the map's own order. `insert` and
  `fromList` put a new key at the front, and `map` and `filter` keep the existing order. When you need key
  order, sort the bindings with `List.sortBy Tuple.first`, or use a `Dict`.

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
  cpu 26382535, mem 110836
  ```

- **Maps can't be compared with `==`:**

  ```text
  .../src/Main.elm:8:23: ELCI2003: The operator == cannot compare Map Int Int values: it has no equality
  ```

Keys and values can be any type that has a Data encoding, including your own records and custom types and
ledger types such as `PubKeyHash`.

## Arrays

`Array a` is an immutable sequence with **constant-time** `get` and `length`, like Elm's `Array`. It is a
Plutus V3 array (CIP-138). As with `Dict` and `Map`, import the module for its functions:

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


main : Int
main =
    let
        values =
            Array.fromList (List.range 1 100)

        at index =
            case Array.get index values of
                Just value ->
                    value

                Nothing ->
                    0
    in
    List.foldl (\index total -> at index + total) 0 [ 10, 20, 30, 40, 50, 60, 70, 80, 90 ]
```

The other version is the same program with `values = List.range 1 100` and `nth index values` in place of
`Array.get index values`, using the `nth` function from earlier on this page:

| Version | CPU | Memory |
| --- | ---: | ---: |
| `List` with `nth` | 500,299,665 | 2,264,391 |
| `Array` with `Array.get` | 116,118,728 | 493,247 |

Both results are 459, and both build the same list. The array version also converts it once, but it wins
on the lookups: each `nth` walks up to 90 elements, while each `Array.get` takes a constant number of steps.

- Use a **List** for data, and when you process every element once (`map`, `filter`, `foldl`, `any`).
- Use a **Dict** for data you build or look up by a comparable key, including datums.
- Use a **Map** for the ledger's maps, such as `txInfo.withdrawals`, and for keys that are not comparable.
- Use an **Array** when you read many elements by index. Convert once, then read many times.

[Costs and performance](/advanced/costs/) goes deeper into budgets.

## Next

[Modules and imports](/learn/modules/) splits your code across files.
