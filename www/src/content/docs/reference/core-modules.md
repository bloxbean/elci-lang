---
title: Core modules
description: Exact signatures and semantics of Elci's built-in functions and the List, Map, Array, Bytes, Julc.Data and Debug modules.
sidebar:
  order: 3
---

This page lists every function the compiler provides without configuration: the always-available
functions and types, and the `List`, `Map`, `Array`, `Bytes`, `Julc.Data` and `Debug` modules. Signatures
are exact; the notes describe behavior, failure cases and cost.

## Always available

These need no import.

### Types

| Type | Values |
| --- | --- |
| `Int` | arbitrary-precision integers |
| `Bool` | `True`, `False` |
| `String` | UTF-8 text; `++` appends |
| `()` | the unit value `()` |
| `Bytes` | byte strings; functions in [`Bytes`](#bytes) |
| `Data` | opaque Plutus Data; convert with [`Julc.Data`](#julcdata) |
| `List a` | linked lists; functions in [`List`](#list) |
| `Maybe a` | `Nothing`, `Just a` |
| `Map k v` | association maps; functions in [`Map`](#map) |
| `Array a` | constant-time indexed arrays; functions in [`Array`](#array) |
| `JulcOptional a` | Julc's optional, returned by some Java library functions |

Tuples of two or three elements, closed records and your own custom types complete the picture; see the
[language reference](/reference/language/). `Map` and `Array` are type names you can use without
importing their modules. As with Elm's `Dict`, you may also write `import Map exposing (Map)`,
`import Array exposing (Array)` or `Map.Map`.

### Functions

| Function | Type | Behavior |
| --- | --- | --- |
| `not` | `Bool -> Bool` | Boolean negation. |
| `modBy` | `Int -> Int -> Int` | `modBy divisor value`: the result has the divisor's sign, as in Elm. |
| `remainderBy` | `Int -> Int -> Int` | `remainderBy divisor value`: the result has the value's sign, as in Elm. |

| Expression | Result |
| --- | ---: |
| `modBy 3 7` | `1` |
| `modBy 3 -7` | `2` |
| `modBy -3 7` | `-2` |
| `remainderBy 3 7` | `1` |
| `remainderBy 3 -7` | `-1` |
| `remainderBy -3 7` | `1` |
| `-7 // 2` | `-3` |

A zero divisor fails the script. `//` truncates towards zero. There is no `/`, `max`, `min`, `abs`,
`identity` or other Elm `Basics` function; `Julc.Math` provides `max`, `min` and `abs` (see
[standard library](/advanced/standard-library/)).

## `List`

`List` is imported into every module; call its functions as `List.map` and so on, or expose them with
`import List exposing (foldl)`. The module is ordinary Elci source, compiled with your code.

| Function | Type |
| --- | --- |
| `map` | `(a -> b) -> List a -> List b` |
| `filter` | `(a -> Bool) -> List a -> List a` |
| `foldl` | `(a -> b -> b) -> b -> List a -> b` |
| `foldr` | `(a -> b -> b) -> b -> List a -> b` |
| `all` | `(a -> Bool) -> List a -> Bool` |
| `any` | `(a -> Bool) -> List a -> Bool` |
| `length` | `List a -> Int` |
| `reverse` | `List a -> List a` |
| `append` | `List a -> List a -> List a` |
| `concatMap` | `(a -> List b) -> List a -> List b` |

Semantics:

- `foldl` combines from the first element and `foldr` from the last. Both callbacks receive the
  **element first** and the accumulator second: `List.foldl (\v acc -> acc * 10 + v) 0 [ 1, 2, 3 ]` is
  `123`, and the same with `foldr` is `321`.
- `all` stops at the first `False` and `any` at the first `True`. On an empty list, `all` is `True` and
  `any` is `False`.
- `append a b` is `a ++ b`. `concatMap f xs` appends the lists `f` returns, in order.
- Every function is a traversal, O(n) in the list's length. `length` walks the whole list.

The constructors `[]` and `::`, list literals `[ 1, 2 ]`, `++` and list patterns are part of the
language. There is no `head`, `sum`, `range` or `sort`: write a short recursive function, or use a
pattern match.

## `Map`

`import Map`. A `Map k v` works like Elm's `Dict`, but it is the ledger's representation: a list of
key/value pairs, compared by the keys' Data encoding. Keys and values may be any type with a Data
encoding, including records and ledger types.

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

Avoid making results depend on map order.

**No equality.** `==` on maps is rejected (`ELCI2003`). Compare the lookups you care about.

**Cost.** `get`, `member`, `insert`, `remove`, `update` and `size` scan the pairs: O(n). `fromList`,
`filter` and `map` insert every pair, so they are O(n²). Building a 50-entry map with `fromList` cost
about 2.76 billion CPU units. See [costs and performance](/advanced/costs/).

A generic value such as `Map.empty` needs a concrete type where it is used. `Map.size Map.empty` on its
own fails with `ELCI3001: Cannot specialize Map.size at an unconstrained type Map t t -> Int; add a type
annotation so the type is known here`.

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
| `lessThan` | `Bytes -> Bytes -> Bool` | lexicographic `a < b` |
| `lessThanOrEqual` | `Bytes -> Bytes -> Bool` | lexicographic `a <= b` |
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

`==` and `/=` compare byte strings. Ledger hashes such as `PubKeyHash` are distinct types; read their
bytes with `.hash`.

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
  `Maybe`, lists, maps and optionals of these, and imported types whose provider approves decoding or
  encoding (for example `PubKeyHash` or `TxOutRef`). The rest are rejected with `ELCI3003`: `()`,
  functions, `Array`, and ledger types without a checkable layout such as `Value` or `TxOut`.
- **Concrete types.** The type being converted must be known where the conversion is used. If inference
  cannot tell, annotate the binding (local definitions accept signatures). An unresolved type fails with
  `ELCI3001: Unresolved source type: t`, reported at the enclosing declaration.
- **Strict decoding.** `decode` never returns a `Maybe` for malformed input. A value of the wrong shape,
  such as an integer where a record is expected, fails with an error like `UnConstrData: not ConstrData`.
- **Optionals.** Julc's optional tags `Some` as 0 and `None` as 1, the reverse of `Maybe`. The adapters
  swap the tags without decoding the payload.
- **Opaque.** `Data` has no `==`; decode it first.

The encoding rules are in the [language reference](/reference/language/).

## `Debug`

`import Debug`.

| Function | Type | Behavior |
| --- | --- | --- |
| `trace` | `String -> a -> a` | Emits the message as a Plutus trace and returns the second argument. |

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
