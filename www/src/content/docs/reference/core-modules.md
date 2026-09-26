---
title: Core modules
description: Exact signatures and semantics of Elci's core library, imported by default as in Elm, and of the Map, Array, Bytes, Julc.Data and Debug modules.
sidebar:
  order: 3
---

This page lists every function the compiler provides without configuration: the `Basics` prelude, the
`List`, `Maybe`, `Result`, `Tuple` and `String` modules that every module imports, and the `Map`, `Array`,
`Bytes`, `Julc.Data` and `Debug` modules. Signatures are exact; the notes describe behavior, failure
cases and cost. Names, argument orders and edge cases follow Elm 0.19's core library; the few differences
are listed [at the end](#differences-from-elm).

## Default imports

As in Elm, every source and test module starts with these imports, which you don't write:

```elm
import Basics exposing (..)
import List
import Maybe exposing (Maybe(..))
import Result exposing (Result(..))
import String
import Tuple
import Debug
```

So `max`, `compare`, `Just` and `Ok` work unqualified, and `List.map`, `String.fromInt` and `Debug.trace`
work qualified. Writing one of these imports yourself is allowed; an `exposing` list then adds names, as
in `import List exposing (head)`.

`Bytes`, `Map`, `Array` and `Julc.Data` need an explicit import, as Elm's `Dict` and `Array` do. Using
one without it names the import to add:

```text
src/Main.elm:6:5: ELCI2104: Bytes.fromString needs the Bytes module; add `import Bytes`
```

**Name precedence** follows Elm, with one addition:

- A module's own declarations shadow every import. If your module defines `max`, it uses its own;
  `Basics.max` still reaches the prelude's.
- Two imports that expose different things under one name make that name ambiguous. The error is
  reported where you use the name, and lists the qualified alternatives. A default import counts, so an
  `import Utils exposing (..)` whose `max` differs from `Basics.max` gives:

  ```text
  src/Main.elm:8:5: ELCI2102: max is ambiguous: it could be Basics.max or Utils.max. Write the one you mean, like Basics.max, or stop exposing the other
  ```

- *Addition:* a name you list explicitly, as in `import Utils exposing (max)`, takes precedence over a
  default import.
- Qualified names always work: `Basics.max`, `Maybe.withDefault`, `Result.Ok`.
- An import can't use a default module's name as its alias. `import Utils as String` gives
  `ELCI2104: The alias String names a core module that every module imports; choose another alias for Utils`.

The core modules are compiled with your code. A function you don't use is never compiled into the
script, so the default imports cost nothing.

### Built-in types

| Type | Values |
| --- | --- |
| `Int` | arbitrary-precision integers |
| `Bool` | `True`, `False` |
| `String` | UTF-8 text; `++` appends; functions in [`String`](#string) |
| `()` | the unit value `()` |
| `Bytes` | byte strings; functions in [`Bytes`](#bytes) |
| `Data` | opaque Plutus Data; convert with [`Julc.Data`](#julcdata) |
| `List a` | linked lists; functions in [`List`](#list) |
| `Maybe a` | `Nothing`, `Just a`; functions in [`Maybe`](#maybe) |
| `Result error value` | `Ok value`, `Err error`; functions in [`Result`](#result) |
| `Order` | `LT`, `EQ`, `GT`, the result of `compare` |
| `Map k v` | association maps; functions in [`Map`](#map) |
| `Array a` | constant-time indexed arrays; functions in [`Array`](#array) |
| `JulcOptional a` | Julc's optional, returned by some Java library functions |

Tuples of two or three elements, closed records and your own custom types complete the picture; see the
[language reference](/reference/language/). `Map` and `Array` are type names you can use without
importing their modules. As with Elm's `Dict`, you may also write `import Map exposing (Map)`,
`import Array exposing (Array)` or `Map.Map`.

`Maybe`, `Just` and `Nothing` are built into the compiler. A module can't declare constructors named
`Just`, `Nothing`, `True` or `False` (`ELCI2011: Nothing is a constructor of the built-in Maybe type;
choose another name`), or types named `Int`, `Bool`, `String`, `Bytes`, `Data`, `List`, `Maybe`, `Map`,
`Array` or `Float` (`ELCI2011: Bytes is a built-in type; choose another name`).

## Comparable types

`<`, `<=`, `>`, `>=`, `compare`, `min`, `max`, `List.sort`, `List.sortBy`, `List.maximum` and
`List.minimum` work on **comparable** types. Both operands have the same type.

| Type | Order |
| --- | --- |
| `Int` | numeric |
| `String` | by its UTF-8 bytes, which is Unicode code point order: `"Zebra" < "apple"` |
| `Bytes` | lexicographic by unsigned byte; a proper prefix comes first |
| ledger byte strings: `PubKeyHash`, `ScriptHash`, `PolicyId`, `TokenName`, `TxId`, `DatumHash` and the other hashes | as `Bytes` |
| lists of a comparable type | lexicographic; a proper prefix comes first |
| tuples of comparable types | component by component |

`Bool`, `()`, records, custom types, `Maybe`, `Result`, `Map`, `Array`, `Data`, functions and ledger
records such as `TxOutRef` are not comparable. Sort them by a comparable key with `List.sortBy`, or with
your own order and `List.sortWith`. Every order is total and agrees with `==`: `compare x y == EQ` exactly
when `x == y`. The arithmetic operators remain `Int`-only.

The error names the operation and the part of the type that isn't comparable:

```text
src/Main.elm:6:17: ELCI2003: The operator < needs comparable values, but Bool, in ( Int, Bool ), is not comparable. Comparable types are Int, String, Bytes, byte-string ledger types such as PubKeyHash, and lists and tuples of comparable types
```

A generic function that compares its arguments names its type variable `comparable`, as in Elm; see
[constrained type variables](/reference/language/#constrained-type-variables).

## `Basics`

The prelude. Every module imports it with `exposing (..)`, so these names need no qualifier.

| Function | Type | Behavior |
| --- | --- | --- |
| `negate` | `Int -> Int` | `negate 42` is `-42` |
| `abs` | `Int -> Int` | `abs -4` is `4` |
| `min` | `comparable -> comparable -> comparable` | the smaller: `min 3 7` is `3` |
| `max` | `comparable -> comparable -> comparable` | the larger: `max "pear" "apple"` is `"pear"` |
| `clamp` | `Int -> Int -> Int -> Int` | `clamp low high x`: `clamp 0 100 150` is `100` |
| `compare` | `comparable -> comparable -> Order` | `compare 3 7` is `LT` |
| `identity` | `a -> a` | returns its argument |
| `always` | `a -> b -> a` | `always x` ignores its argument: `List.map (always 0) [ 1, 2 ]` is `[ 0, 0 ]` |
| `xor` | `Bool -> Bool -> Bool` | `True` when exactly one argument is `True` |
| `not` | `Bool -> Bool` | Boolean negation |
| `modBy` | `Int -> Int -> Int` | `modBy divisor value`: the result has the divisor's sign |
| `remainderBy` | `Int -> Int -> Int` | `remainderBy divisor value`: the result has the value's sign |

`type Order = LT | EQ | GT` is also part of `Basics`.

The operator `^` raises an `Int` to a power: `2 ^ 10` is `1024`. It binds tighter than `*` and groups to
the right, so `2 * 3 ^ 2` is `18` and `2 ^ 3 ^ 2` is `512`. A negative exponent fails the script, because
there is no `Float` for `2 ^ -1`.

| Expression | Result |
| --- | ---: |
| `modBy 3 7` | `1` |
| `modBy 3 -7` | `2` |
| `modBy -3 7` | `-2` |
| `remainderBy 3 7` | `1` |
| `remainderBy 3 -7` | `-1` |
| `remainderBy -3 7` | `1` |
| `-7 // 2` | `-3` |

A zero divisor fails the script. `//` truncates towards zero. There is no `/`, `Float`, `toFloat`,
`round` or other floating-point function.

```elm title="src/Main.elm"
module Main exposing (main)


main : ( List Int, ( String, String ), List Order )
main =
    ( [ negate 42, abs -4, min 3 7, clamp 0 100 150, 2 ^ 10, always 1 2 ]
    , ( max "pear" "apple", min "Zebra" "apple" )
    , [ compare 3 7, compare [ 1, 2 ] [ 1, 2 ], compare ( 2, "a" ) ( 1, "z" ) ]
    )
```

```text
$ elci eval
( [-42, 4, 3, 100, 1024, 1], ( "pear", "Zebra" ), [LT, EQ, GT] )
cpu 23660515, mem 86721
```

## `List`

Every module imports `List`; call its functions as `List.map` and so on, or expose them with
`import List exposing (foldl)`. The module is ordinary Elci source, compiled with your code.

| Function | Type | Behavior |
| --- | --- | --- |
| `singleton` | `a -> List a` | a list of one element |
| `repeat` | `Int -> a -> List a` | `n` copies; empty when `n <= 0` |
| `range` | `Int -> Int -> List Int` | `range 1 4` is `[ 1, 2, 3, 4 ]`; empty when the first is larger |
| `map` | `(a -> b) -> List a -> List b` | |
| `indexedMap` | `(Int -> a -> b) -> List a -> List b` | the index counts from 0 |
| `filter` | `(a -> Bool) -> List a -> List a` | |
| `filterMap` | `(a -> Maybe b) -> List a -> List b` | keeps the `Just` results |
| `foldl` | `(a -> b -> b) -> b -> List a -> b` | from the first element |
| `foldr` | `(a -> b -> b) -> b -> List a -> b` | from the last element |
| `concat` | `List (List a) -> List a` | |
| `concatMap` | `(a -> List b) -> List a -> List b` | |
| `intersperse` | `a -> List a -> List a` | `intersperse 0 [ 1, 2, 3 ]` is `[ 1, 0, 2, 0, 3 ]` |
| `map2` | `(a -> b -> result) -> List a -> List b -> List result` | stops at the shorter list |
| `map3` | `(a -> b -> c -> result) -> List a -> List b -> List c -> List result` | stops at the shortest list |
| `length` | `List a -> Int` | walks the whole list |
| `reverse` | `List a -> List a` | |
| `member` | `a -> List a -> Bool` | uses `==` |
| `all` | `(a -> Bool) -> List a -> Bool` | `True` for `[]` |
| `any` | `(a -> Bool) -> List a -> Bool` | `False` for `[]` |
| `maximum` | `List comparable -> Maybe comparable` | `Nothing` for `[]` |
| `minimum` | `List comparable -> Maybe comparable` | `Nothing` for `[]` |
| `sum` | `List Int -> Int` | `0` for `[]` |
| `product` | `List Int -> Int` | `1` for `[]` |
| `append` | `List a -> List a -> List a` | `append a b` is `a ++ b` |
| `isEmpty` | `List a -> Bool` | |
| `head` | `List a -> Maybe a` | `Nothing` for `[]` |
| `tail` | `List a -> Maybe (List a)` | `Nothing` for `[]` |
| `take` | `Int -> List a -> List a` | the first `n`; empty when `n <= 0` |
| `drop` | `Int -> List a -> List a` | all but the first `n`; the whole list when `n <= 0` |
| `partition` | `(a -> Bool) -> List a -> ( List a, List a )` | `( passing, failing )`, both in input order |
| `unzip` | `List ( a, b ) -> ( List a, List b )` | |
| `sort` | `List comparable -> List comparable` | smallest first |
| `sortBy` | `(a -> comparable) -> List a -> List a` | by a key |
| `sortWith` | `(a -> a -> Order) -> List a -> List a` | by a comparison function |

Semantics:

- `foldl` combines from the first element and `foldr` from the last. Both callbacks receive the
  **element first** and the accumulator second: `List.foldl (\v acc -> acc * 10 + v) 0 [ 1, 2, 3 ]` is
  `123`, and the same with `foldr` is `321`.
- `all` stops at the first `False` and `any` at the first `True`.
- `map2` and `map3` drop the extra elements of longer lists, as in Elm. On-chain, check lengths first when
  extras matter: pairing outputs with expected payouts would silently ignore an extra output.
- The sorts are stable merge sorts, O(n log n): equal elements keep their order. `sortBy` computes the
  key at each comparison, so keep keys cheap.
- Every other function is a traversal, O(n) in the list's length.

```elm title="src/Main.elm"
module Main exposing (main)


main : ( List Int, Maybe Int, ( List Int, List Int ) )
main =
    let
        amounts =
            [ 30, 5, 12, 5, 40 ]
    in
    ( List.sort amounts
    , List.maximum amounts
    , List.partition (\amount -> amount >= 10) amounts
    )
```

```text
$ elci eval
( [5, 5, 12, 30, 40], Just 40, ( [30, 12, 40], [5, 5] ) )
cpu 48378577, mem 228938
```

Records and custom types are not comparable. Sort them by a key, or with a comparison:

```elm title="src/Main.elm"
module Main exposing (main)


type alias Bid =
    { bidder : String
    , amount : Int
    }


bids : List Bid
bids =
    [ Bid "carol" 30, Bid "alice" 50, Bid "bob" 30 ]


main : ( List String, List String, String )
main =
    ( List.map (\bid -> bid.bidder) (List.sortBy (\bid -> bid.amount) bids)
    , List.map (\bid -> bid.bidder) (List.sortWith (\a b -> compare b.amount a.amount) bids)
    , String.join ", " (List.map (\bid -> String.fromInt bid.amount) bids)
    )
```

```text
$ elci eval
( ["carol", "bob", "alice"], ["alice", "carol", "bob"], "30, 50, 30" )
cpu 60322206, mem 262554
```

The constructors `[]` and `::`, list literals `[ 1, 2 ]`, `++` and list patterns are part of the
language. Sorting on-chain is not cheap; see [costs and performance](/advanced/costs/#sorting-and-comparing).

## `Maybe`

`Maybe a` is built in. Every module imports the `Maybe` module with `exposing (Maybe(..))`, so `Just` and
`Nothing` need no qualifier.

| Function | Type | Behavior |
| --- | --- | --- |
| `withDefault` | `a -> Maybe a -> a` | the value inside a `Just`, or the default |
| `map` | `(a -> b) -> Maybe a -> Maybe b` | transforms the value inside a `Just` |
| `map2` | `(a -> b -> value) -> Maybe a -> Maybe b -> Maybe value` | `Just` when both are present |
| `map3` | `(a -> b -> c -> value) -> Maybe a -> Maybe b -> Maybe c -> Maybe value` | `Just` when all are present |
| `andThen` | `(a -> Maybe b) -> Maybe a -> Maybe b` | chains computations that may produce nothing |

Arguments are evaluated before a call, as in Elm, so `Maybe.withDefault default m` always evaluates
`default`, even when `m` is a `Just`. On-chain that costs budget, and a failing default fails the script.
Keep an expensive or failing default in a `case` branch.

`Nothing` is constructor 0 and `Just` is constructor 1 in Plutus Data.

## `Result`

`type Result error value = Ok value | Err error`. Every module imports the `Result` module with
`exposing (Result(..))`.

| Function | Type | Behavior |
| --- | --- | --- |
| `map` | `(a -> value) -> Result x a -> Result x value` | transforms an `Ok` value |
| `map2` | `(a -> b -> value) -> Result x a -> Result x b -> Result x value` | the first `Err` wins |
| `map3` | `(a -> b -> c -> value) -> Result x a -> Result x b -> Result x c -> Result x value` | the first `Err` wins |
| `andThen` | `(a -> Result x b) -> Result x a -> Result x b` | chains computations that may fail |
| `withDefault` | `a -> Result x a -> a` | the `Ok` value, or the default |
| `toMaybe` | `Result x a -> Maybe a` | `Just` the `Ok` value, or `Nothing` |
| `fromMaybe` | `x -> Maybe a -> Result x a` | `Ok` with the value of a `Just`, or `Err` with the given error |
| `mapError` | `(x -> y) -> Result x a -> Result y a` | transforms an `Err` error |

In Plutus Data, `Ok` is constructor 0 and `Err` is constructor 1, following the declaration order. This
is the reverse of PlutusTx's `Either` (`Left` 0, `Right` 1): a datum written as an `Either` does not read
as a `Result`, because decoding would swap success and failure.

```elm title="src/Main.elm"
module Main exposing (main)


positive : Int -> Result String Int
positive amount =
    if amount > 0 then
        Ok amount

    else
        Err "not positive"


main : ( ( Int, Maybe Int, Maybe Int ), ( Result String Int, Result String Int, Int ) )
main =
    ( ( Maybe.withDefault 0 (List.head [])
      , Maybe.map (\n -> n * 2) (List.head [ 4, 5 ])
      , Maybe.andThen List.head (List.tail [ 1, 2, 3 ])
      )
    , ( Result.map2 (+) (positive 5) (positive 7)
      , Result.map2 (+) (positive -1) (positive 0)
      , Result.withDefault 0 (Result.andThen positive (Ok 3))
      )
    )
```

```text
$ elci eval
( ( 0, Just 8, Just 2 ), ( Ok 12, Err "not positive", 3 ) )
cpu 19297460, mem 75445
```

## `Tuple`

Every module imports `Tuple`. The functions work on pairs; take a triple apart with a pattern such as
`( a, b, c )`.

| Function | Type | Behavior |
| --- | --- | --- |
| `pair` | `a -> b -> ( a, b )` | `pair 1 "one"` is `( 1, "one" )` |
| `first` | `( a, b ) -> a` | |
| `second` | `( a, b ) -> b` | |
| `mapFirst` | `(a -> x) -> ( a, b ) -> ( x, b )` | |
| `mapSecond` | `(b -> y) -> ( a, b ) -> ( a, y )` | |
| `mapBoth` | `(a -> x) -> (b -> y) -> ( a, b ) -> ( x, y )` | |

## `String`

Every module imports `String`. A `String` is Plutus text: UTF-8, compared by its bytes.

| Function | Type | Behavior |
| --- | --- | --- |
| `fromInt` | `Int -> String` | the decimal form: `fromInt -42` is `"-42"` |
| `isEmpty` | `String -> Bool` | |
| `append` | `String -> String -> String` | the same as `++` |
| `concat` | `List String -> String` | `concat [ "ab", "cd" ]` is `"abcd"` |
| `join` | `String -> List String -> String` | `join ", " [ "a", "b" ]` is `"a, b"` |

There is no `length`, `toInt`, `split`, `slice` or `fromFloat`. `Bytes.fromString` and `Bytes.toString`
convert to and from the UTF-8 bytes, and `Bytes.length (Bytes.fromString s)` is the byte length. Asking
for a missing function lists what the module has:

```text
src/Main.elm:6:5: ELCI2104: Elci's String module has no toInt. It has append, concat, fromInt, isEmpty, join
```

## `Map`

`import Map`. A `Map k v` works like Elm's `Dict`, but it is the ledger's representation: a list of
key/value pairs, compared by the keys' Data encoding. Keys and values may be any type with a Data
encoding, including records and ledger types; keys need not be comparable.

| Function | Type | Behavior |
| --- | --- | --- |
| `empty` | `Map k v` | |
| `singleton` | `k -> v -> Map k v` | |
| `get` | `k -> Map k v -> Maybe v` | `Nothing` when the key is absent |
| `member` | `k -> Map k v -> Bool` | |
| `insert` | `k -> v -> Map k v -> Map k v` | replaces an existing binding |
| `remove` | `k -> Map k v -> Map k v` | |
| `update` | `k -> (Maybe v -> Maybe v) -> Map k v -> Map k v` | `Nothing` removes the key, `Just v` sets it |
| `size` | `Map k v -> Int` | |
| `isEmpty` | `Map k v -> Bool` | |
| `keys` | `Map k v -> List k` | in map order |
| `values` | `Map k v -> List v` | in map order |
| `toList` | `Map k v -> List ( k, v )` | in map order |
| `fromList` | `List ( k, v ) -> Map k v` | inserts the pairs in list order |
| `foldl` | `(k -> v -> b -> b) -> b -> Map k v -> b` | in map order |
| `filter` | `(k -> v -> Bool) -> Map k v -> Map k v` | keeps map order |
| `map` | `(k -> a -> b) -> Map k a -> Map k b` | keeps map order |

**Map order.** `insert` puts the binding at the front, and a replaced key moves to the front. So
`Map.keys (Map.fromList [ ( 1, 10 ), ( 2, 20 ), ( 3, 30 ) ])` is `[ 3, 2, 1 ]`. `filter` and `map` keep
the order of the map they are given:

```elm title="src/Main.elm"
module Main exposing (main)

import Map exposing (Map)


prices : Map String Int
prices =
    Map.fromList [ ( "apple", 3 ), ( "pear", 5 ) ]


main : ( Map String Int, Map String Int, Maybe Int )
main =
    ( Map.insert "fig" 7 prices
    , Map.filter (\_ price -> price > 3) (Map.insert "fig" 7 prices)
    , Map.get "pear" prices
    )
```

```text
$ elci eval
( Map.fromList [( "fig", 7 ), ( "pear", 5 ), ( "apple", 3 )], Map.fromList [( "fig", 7 ), ( "pear", 5 )], Just 5 )
cpu 58597935, mem 218057
```

Avoid making results depend on map order. To process the bindings in key order, sort them:
`List.sortBy Tuple.first (Map.toList prices)`.

**No equality.** `==` on maps is rejected (`ELCI2003`). Compare the lookups you care about.

**Cost.** `get`, `member`, `insert`, `remove`, `update` and `size` scan the pairs: O(n). `fromList`,
`filter` and `map` insert every pair, so they are O(n²). Building a 50-entry map with `fromList` cost
about 2.76 billion CPU units. See [costs and performance](/advanced/costs/).

A map whose key and value types nothing determines can't be compiled, because the map operations
are specialized for them. `Map.size Map.empty` on its own fails at the call:

```text
src/Main.elm:8:5: ELCI3001: `Map.size` is used here at a type that is not known (Map a b -> Int); add a type annotation, for example to a let binding
```

Give the map a type, for example with a local definition `noPrices : Map String Int`.

## `Array`

`import Array`. An `Array a` works like Elm's `Array`. It is a Plutus V3 array: built once from a list,
then read in constant time.

| Function | Type | Behavior |
| --- | --- | --- |
| `empty` | `Array a` | |
| `fromList` | `List a -> Array a` | O(n) |
| `toList` | `Array a -> List a` | O(n) |
| `toIndexedList` | `Array a -> List ( Int, a )` | pairs of index and element |
| `length` | `Array a -> Int` | O(1) |
| `isEmpty` | `Array a -> Bool` | O(1) |
| `get` | `Int -> Array a -> Maybe a` | O(1); `Nothing` for an index below 0 or at least `length` |
| `initialize` | `Int -> (Int -> a) -> Array a` | `initialize 4 (\i -> i * i)` is `[ 0, 1, 4, 9 ]` |
| `repeat` | `Int -> a -> Array a` | `repeat 3 7` is `[ 7, 7, 7 ]` |
| `foldl` | `(a -> b -> b) -> b -> Array a -> b` | from the first element |
| `foldr` | `(a -> b -> b) -> b -> Array a -> b` | from the last element |
| `map` | `(a -> b) -> Array a -> Array b` | O(n) |
| `indexedMap` | `(Int -> a -> b) -> Array a -> Array b` | O(n) |
| `filter` | `(a -> Bool) -> Array a -> Array a` | O(n) |
| `slice` | `Int -> Int -> Array a -> Array a` | from `start` up to, not including, `end` |

`slice` counts a negative index from the end and clamps to the array. For `[ 10, 20, 30, 40, 50 ]`,
`slice 1 3` is `[ 20, 30 ]`, `slice 0 -1` is `[ 10, 20, 30, 40 ]` and `slice -2 10` is `[ 40, 50 ]`.

Arrays are immutable and there is no `set` or `push`. Build a list, then convert it once.

**No Data encoding.** An array cannot be a datum, redeemer, parameter or property-test argument, nor a
field of a record, custom type, tuple, list or map. Storing one there fails with `ELCI2013: Arrays have no
Data encoding and cannot be stored in data; keep a List and convert it with Array.fromList`, and
`Julc.Data.encode` on an array is rejected with `ELCI3003`. Keep lists in your data and call
`Array.fromList` in the handler. An `[entrypoint]` may return an array on its own; `elci eval` prints it
as `Array.fromList [40, 50]`.

## `Bytes`

`import Bytes`. Each function is a direct Plutus builtin call, with the byte string last.
[Bytes, hashes and bits](/advanced/bytes-and-crypto/) explains each group with examples.

| Function | Type | Behavior |
| --- | --- | --- |
| `empty` | `Bytes` | |
| `fromString` | `String -> Bytes` | UTF-8 encoding |
| `toString` | `Bytes -> String` | UTF-8 decoding; fails on invalid UTF-8 |
| `length` | `Bytes -> Int` | |
| `append` | `Bytes -> Bytes -> Bytes` | `append a b` is `a` followed by `b` |
| `slice` | `Int -> Int -> Bytes -> Bytes` | `slice start count bytes`; clamps |
| `take` | `Int -> Bytes -> Bytes` | clamps |
| `drop` | `Int -> Bytes -> Bytes` | clamps |
| `at` | `Int -> Bytes -> Int` | the byte at an index, 0–255; fails outside the string |
| `cons` | `Int -> Bytes -> Bytes` | prepends a byte; fails outside 0–255 |
| `replicate` | `Int -> Int -> Bytes` | `replicate count byte`; at most 8,192 bytes |
| `fromIntBigEndian` | `Int -> Int -> Bytes` | `fromIntBigEndian width n`; width `0` is minimal; fails for negative `n` or if it does not fit |
| `fromIntLittleEndian` | `Int -> Int -> Bytes` | as above, little-endian |
| `toIntBigEndian` | `Bytes -> Int` | unsigned |
| `toIntLittleEndian` | `Bytes -> Int` | unsigned |
| `and` | `Bytes -> Bytes -> Bytes` | truncates to the shorter operand |
| `or` | `Bytes -> Bytes -> Bytes` | truncates to the shorter operand |
| `xor` | `Bytes -> Bytes -> Bytes` | truncates to the shorter operand |
| `complement` | `Bytes -> Bytes` | flips every bit |
| `shift` | `Int -> Bytes -> Bytes` | positive shifts towards the front, negative towards the end; same length |
| `rotate` | `Int -> Bytes -> Bytes` | like `shift`, wrapping bits around |
| `testBit` | `Int -> Bytes -> Bool` | bit 0 is the lowest bit of the last byte |
| `countSetBits` | `Bytes -> Int` | |
| `findFirstSetBit` | `Bytes -> Int` | lowest set bit, or `-1` if none |
| `blake2b224` | `Bytes -> Bytes` | 28-byte digest |
| `blake2b256` | `Bytes -> Bytes` | 32-byte digest |
| `sha256` | `Bytes -> Bytes` | 32-byte digest |
| `sha3_256` | `Bytes -> Bytes` | 32-byte digest |
| `keccak256` | `Bytes -> Bytes` | 32-byte digest |
| `ripemd160` | `Bytes -> Bytes` | 20-byte digest |

`==` and `/=` compare byte strings. `<`, `<=`, `>`, `>=` and `compare` order them lexicographically, a
proper prefix first, with the Plutus builtins `lessThanByteString` and `lessThanEqualsByteString`. There
is no `Bytes.lessThan`: `ELCI2104: Elci has no Bytes.lessThan; bytes are comparable: write a < b`. Ledger
hashes such as `PubKeyHash` are distinct types that compare and order like bytes; read their bytes with
`.hash`.

## `Julc.Data`

`import Julc.Data as Data`. Explicit conversions between Elci values and Plutus Data.

| Function | Type | Behavior |
| --- | --- | --- |
| `decode` | `Data -> a` | Julc's strict decoder for `a`. Malformed Data fails the script. |
| `encode` | `a -> Data` | The Data encoding of `a`. |
| `fromOptional` | `JulcOptional a -> Maybe a` | Re-tags a Julc optional as a `Maybe`. |
| `toOptional` | `Maybe a -> JulcOptional a` | The reverse. |

```elm
import Julc.Data as Data
import Julc.Ledger exposing (OutputDatum(..))


inlineAmount : OutputDatum -> Maybe Int
inlineAmount datum =
    case datum of
        OutputDatumInline raw ->
            Just (Data.decode raw)

        _ ->
            Nothing
```

- **Supported types.** `Int`, `Bool`, `String`, `Bytes`, `Data`, your records and custom types, tuples,
  `Maybe`, `Result`, `Order`, lists, maps and optionals of these, and imported types whose provider
  approves decoding or encoding (for example `PubKeyHash` or `TxOutRef`). The rest are rejected with
  `ELCI3003`: `()`, functions, `Array`, and ledger types without a checkable layout such as `Value` or
  `TxOut`.
- **Known types for decoding.** `decode` must know the type it creates where it is used. If inference
  cannot tell, annotate the binding (local definitions accept signatures). Otherwise the call fails with
  `ELCI3001: Cannot determine the type converted by Julc.Data.decode; add a type annotation`. `encode`
  and the optional adapters don't need to know it: `Data.encode Nothing` is `Constr 0`.
- **Strict decoding.** `decode` never returns a `Maybe` for malformed input. A value of the wrong shape,
  such as an integer where a record is expected, fails with an error like `UnConstrData: not ConstrData`.
- **Optionals.** Julc's optional tags `Some` as 0 and `None` as 1, the reverse of `Maybe`. The adapters
  swap the tags without decoding the payload.
- **Opaque.** `Data` has no `==` and no order; decode it first.

The encoding rules are in the [language reference](/reference/language/#data-encoding).

## `Debug`

Every module imports `Debug`.

| Function | Type | Behavior |
| --- | --- | --- |
| `trace` | `String -> a -> a` | Emits the message as a Plutus trace and returns the second argument. |

There is no `Debug.log`, `Debug.todo` or `Debug.toString`; the error says what to use instead, such as
`ELCI2104: Elci has no Debug.log; use Debug.trace message value, which returns value`.

Both arguments are evaluated first, because Elci is strict. The message is emitted only after the value
has been computed, so `Debug.trace "about to divide" (10 // 0)` fails without tracing. To trace before
something that may fail, trace in a `let`:

```elm
main : Int
main =
    let
        _ =
            Debug.trace "about to divide" ()
    in
    10 // 0
```

```text
$ elci eval
evaluation failed: QuotientInteger: division by zero
cpu 431939, mem 1633
trace: about to divide
```

Traces cost CPU and script bytes in deployed validators. `elci test` prints the traces of failing
tests. See [debugging and budgets](/testing/debugging/).

## `Julc.Ledger`

`Julc.Ledger` provides the ledger types (`ScriptContext`, `TxInfo`, `PubKeyHash`, `TxOutRef`,
`Credential` and more) rather than functions. See
[reading the transaction](/contracts/reading-the-transaction/).

## Differences from Elm

| Elm | Elci | Why |
| --- | --- | --- |
| `String` ordered by UTF-16 code units | ordered by UTF-8 bytes, which is code point order | Plutus strings are UTF-8. The orders differ only for a character outside the Basic Multilingual Plane against one from U+E000 to U+FFFF |
| `2 ^ -1 == 0.5` | fails the script | no `Float` |
| `x // 0 == 0`; `modBy 0 x` crashes | both fail the script | Plutus division by zero |
| `number`, `Float` | `Int` only | no floating point on-chain |
| Bytes are not comparable | `Bytes` and ledger hashes are comparable | they are the natural keys on Cardano |
| two imports exposing one name are ambiguous | a name listed in `exposing (...)` takes precedence over a default import | the intent is explicit |
| `Debug.log`, `Debug.todo`, `Debug.toString` | `Debug.trace message value` | traces are the on-chain log |
| `Result` has no fixed encoding | `Ok` is constructor 0, `Err` constructor 1 | values cross the script boundary as Data |

Elm's `Dict`, `Set` and `Char` modules and most of `String` are not available; `Map` covers keyed data
today. [Coming from Elm](/learn/coming-from-elm/) lists every other difference.
