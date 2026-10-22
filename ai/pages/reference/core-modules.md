# Core modules

> Exact signatures and semantics of Elci's core library, imported by default as in Elm, and of the Dict, Map, Array, Value, Interval, Tx, Bytes, Julc.Data and Debug modules.

Source: https://elci-lang.dev/reference/core-modules/

This page lists every function the compiler provides without configuration: the `Basics` prelude, the
`List`, `Maybe`, `Result`, `Tuple` and `String` modules that every module imports, and the `Dict`, `Map`,
`Array`, `Value`, `Interval`, `Tx`, `Bytes`, `Julc.Data` and `Debug` modules. Signatures are exact; the notes
describe behavior, failure cases and cost. Names, argument orders and edge cases follow Elm 0.19's core library; the few differences
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

`Bytes`, `Dict`, `Map`, `Array`, `Value`, `Interval`, `Tx` and `Julc.Data` need an explicit import, as `Dict`
and `Array` do in Elm. Using one without it names the import to add:

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
- Nor can it use the name of any other core module or of a module in the project, since the
  two modules' names would merge. `import Julc.Interval as Interval` gives
  `ELCI2104: The alias Interval names Elci's core module Interval; choose another alias for Julc.Interval`.

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
| `Data` | Plutus Data; `==` compares structure; convert with [`Julc.Data`](#julcdata) |
| `List a` | linked lists; functions in [`List`](#list) |
| `Maybe a` | `Nothing`, `Just a`; functions in [`Maybe`](#maybe) |
| `Result error value` | `Ok value`, `Err error`; functions in [`Result`](#result) |
| `Order` | `LT`, `EQ`, `GT`, the result of `compare` |
| `Dict k v` | dictionaries with comparable keys, kept sorted; functions in [`Dict`](#dict) |
| `Map k v` | the ledger's association maps; functions in [`Map`](#map) |
| `Array a` | constant-time indexed arrays; functions in [`Array`](#array) |
| `Value` | the ledger's multi-asset values: lovelace and native tokens; functions in [`Value`](#value) |
| `JulcOptional a` | Julc's optional, returned by some Java library functions |

Tuples of two or three elements, closed records and your own custom types complete the picture; see the
[language reference](https://elci-lang.dev/reference/language/). `Dict`, `Map` and `Array` are type names you can use without
importing their modules. You may also write them as in Elm, with `import Dict exposing (Dict)` or
qualified as `Dict.Dict`, and likewise for `Map` and `Array`. The compiler's own modules qualify their types
the same way: `Bytes.Bytes` after `import Bytes`, and `Data.Data` after `import Julc.Data as Data`. `Value`
is not an Elm type, so its name needs an import: `import Value exposing (Value)`, or `Value.Value` after
`import Value`. Reading `out.value` needs no import. The same holds for `Interval`: name it after
`import Interval exposing (Interval)`, but read `ctx.txInfo.validRange` without one.

`Maybe`, `Just` and `Nothing` are built into the compiler. A module can't declare constructors named
`Just`, `Nothing`, `True` or `False` (`ELCI2011: Nothing is a constructor of the built-in Maybe type;
choose another name`), or types named `Int`, `Bool`, `String`, `Bytes`, `Data`, `List`, `Maybe`, `Dict`,
`Map`, `Array` or `Float` (`ELCI2011: Bytes is a built-in type; choose another name`).

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

`Bool`, `()`, records, custom types, `Maybe`, `Result`, `Dict`, `Map`, `Array`, `Value`, `Data`, functions
and ledger records such as `TxOutRef` are not comparable. Sort them by a comparable key with `List.sortBy`, or with
your own order and `List.sortWith`. Every order is total and agrees with `==`: `compare x y == EQ` exactly
when `x == y`. The arithmetic operators remain `Int`-only.

The error names the operation and the part of the type that isn't comparable:

```text
src/Main.elm:6:17: ELCI2003: The operator < needs comparable values, but Bool, in ( Int, Bool ), is not comparable. Comparable types are Int, String, Bytes, byte-string ledger types such as PubKeyHash, and lists and tuples of comparable types
```

A generic function that compares its arguments names its type variable `comparable`, as in Elm; see
[constrained type variables](https://elci-lang.dev/reference/language/#constrained-type-variables). The keys of a
[`Dict`](#dict) are comparable too.

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
language. Sorting on-chain is not cheap; see [costs and performance](https://elci-lang.dev/advanced/costs/#sorting-and-comparing).

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

## `Dict`

`import Dict exposing (Dict)`. A `Dict k v` is Elm's dictionary, with Elm's functions, argument orders and
results. It is stored as a Plutus map whose keys are strictly ascending in the
[comparable order](#comparable-types), so every dictionary has exactly one representation. Keys are
comparable types: `Int`, `String`, `Bytes`, ledger hashes such as `PubKeyHash`, and lists and tuples of
these. Values may be any type with a Data encoding.

| Function | Type | Behavior |
| --- | --- | --- |
| `empty` | `Dict k v` | |
| `singleton` | `comparable -> v -> Dict comparable v` | |
| `insert` | `comparable -> v -> Dict comparable v -> Dict comparable v` | replaces an existing binding |
| `update` | `comparable -> (Maybe v -> Maybe v) -> Dict comparable v -> Dict comparable v` | `Nothing` removes the key, `Just v` sets it |
| `remove` | `comparable -> Dict comparable v -> Dict comparable v` | |
| `isEmpty` | `Dict k v -> Bool` | |
| `member` | `comparable -> Dict comparable v -> Bool` | |
| `get` | `comparable -> Dict comparable v -> Maybe v` | `Nothing` when the key is absent |
| `size` | `Dict k v -> Int` | |
| `keys` | `Dict k v -> List k` | in ascending order |
| `values` | `Dict k v -> List v` | in ascending key order |
| `toList` | `Dict k v -> List ( k, v )` | in ascending key order |
| `fromList` | `List ( comparable, v ) -> Dict comparable v` | for a repeated key, the last pair wins |
| `map` | `(k -> a -> b) -> Dict k a -> Dict k b` | |
| `foldl` | `(k -> v -> b -> b) -> b -> Dict k v -> b` | from the smallest key |
| `foldr` | `(k -> v -> b -> b) -> b -> Dict k v -> b` | from the largest key |
| `filter` | `(comparable -> v -> Bool) -> Dict comparable v -> Dict comparable v` | |
| `partition` | `(comparable -> v -> Bool) -> Dict comparable v -> ( Dict comparable v, Dict comparable v )` | `( passing, failing )` |
| `union` | `Dict comparable v -> Dict comparable v -> Dict comparable v` | for a key in both, the left value wins |
| `intersect` | `Dict comparable v -> Dict comparable v -> Dict comparable v` | the left bindings whose keys the right has |
| `diff` | `Dict comparable a -> Dict comparable b -> Dict comparable a` | the left bindings whose keys the right lacks |
| `merge` | `(comparable -> a -> result -> result) -> (comparable -> a -> b -> result -> result) -> (comparable -> b -> result -> result) -> Dict comparable a -> Dict comparable b -> result -> result` | see below |

```elm title="src/Main.elm"
module Main exposing (main)

import Dict exposing (Dict)

stock : Dict String Int
stock =
    Dict.fromList [ ( "pear", 5 ), ( "apple", 3 ), ( "fig", 0 ), ( "apple", 4 ) ]

main : ( List ( String, Int ), ( Maybe Int, Bool ), List String )
main =
    ( Dict.toList (Dict.insert "kiwi" 2 stock)
    , ( Dict.get "apple" stock, Dict.member "plum" stock )
    , Dict.keys (Dict.filter (\_ count -> count > 0) stock)
    )
```

```text
$ elci eval
( [( "apple", 4 ), ( "fig", 0 ), ( "kiwi", 2 ), ( "pear", 5 )], ( Just 4, False ), ["apple", "pear"] )
cpu 77921806, mem 309819
```

The bindings come out in key order, whatever order they went in, and the second `"apple"` replaced the
first. Combining two dictionaries follows Elm too. `merge` folds over both in ascending key order, with one
step for keys only in the left dictionary, one for keys in both and one for keys only in the right:

```elm title="src/Main.elm"
module Main exposing (main)

import Dict exposing (Dict)

left : Dict Int String
left =
    Dict.fromList [ ( 1, "a" ), ( 2, "b" ) ]

right : Dict Int String
right =
    Dict.fromList [ ( 2, "x" ), ( 3, "y" ) ]

main : ( ( List ( Int, String ), List Int, List Int ), ( List String, Bool ) )
main =
    ( ( Dict.toList (Dict.union left right)
      , Dict.keys (Dict.intersect left right)
      , Dict.keys (Dict.diff left right)
      )
    , ( Dict.merge
            (\key _ result -> result ++ [ "only left " ++ String.fromInt key ])
            (\key _ _ result -> result ++ [ "both " ++ String.fromInt key ])
            (\key _ result -> result ++ [ "only right " ++ String.fromInt key ])
            left
            right
            []
      , Dict.insert 2 "b" (Dict.singleton 1 "a") == left
      )
    )
```

```text
$ elci eval
( ( [( 1, "a" ), ( 2, "b" ), ( 3, "y" )], [2], [1] ), ( ["only left 1", "both 2", "only right 3"], True ) )
cpu 116952501, mem 457650
```

**Equality.** `==` compares bindings: two dictionaries are equal when they have the same keys with equal
values, however they were built. The values need equality themselves, so a `Dict Int (Map Int Int)` can't
be compared (`ELCI2003`). Dictionaries are not comparable.

**Cost.** A `Dict` is a list of pairs, not a tree as in Elm:

- `get`, `member`, `insert`, `remove` and `update` walk from the smallest key and stop at the first key
  that isn't smaller than the one they look for. They are O(n), at about 1.1 million CPU units per key
  passed. Small keys are found fastest, and a missing key costs half a scan on average.
- `fromList` is a stable merge sort: O(n log n), and O(n) when the pairs are already in key order. For 50
  entries it cost about 0.28 billion CPU units with sorted input and 0.66 billion with shuffled input,
  against 1.5 billion for `Map.fromList`.
- `size`, `keys`, `values`, `toList`, the folds, `map`, `filter` and `partition` are single passes.
  `union`, `intersect`, `diff` and `merge` walk both dictionaries once: O(n + m).

**In Data.** A `Dict` encodes as a Plutus map with its keys in ascending order. Decoding checks the order:
a map whose keys are not strictly ascending, because they are out of order or repeated, fails to decode.
The check applies to datums, redeemers, parameters, `Julc.Data.decode` and property-test arguments, and to
every `Dict` inside them: in records, tuples, custom types, `Maybe`, `Result`, Julc optionals, lists and
map values. It costs about 1.1 million CPU units per entry. Without it, a datum could place a key after a
larger one, where `member` and `get` stop before reaching it;
[datums and redeemers](https://elci-lang.dev/contracts/datums-and-redeemers/#dictionaries-in-data) shows the attack.
`elci apply` rejects such a parameter:

```text
elci: ELCI4001: parameter caps has a Dict whose keys are not strictly ascending; write its keys sorted and without repeats
```

Two rules keep every decoded `Dict` checked. Checking the order needs comparable keys, so a `Dict` with
other keys can't be decoded from Data:

```text
src/Main.elm:9:5: ELCI2003: A Dict decoded from Data needs comparable keys, to check that they are in order; Bool is not comparable. Use a Map instead
```

And a Java library's generic function can't be used at a type that contains a `Dict`, because the library
could build one without the check:

```text
src/Main.elm:14:18: ELCI3003: Type argument a = Dict Int Int of filter contains a Dict, which a Java library cannot keep sorted; pass a Map or a List and build the Dict with Dict.fromList
```

**`Dict` or `Map`?** Use a `Dict` for data you build or look up by a comparable key, including datums. Use a
[`Map`](#map) for the ledger's own maps, such as `txInfo.withdrawals` and `txInfo.redeemers`, and for keys
that are not comparable.

## `Map`

`import Map`. A `Map k v` is the ledger's map representation: a list of key/value pairs, compared by the
keys' Data encoding. The transaction's maps, such as `txInfo.withdrawals`, `txInfo.redeemers` and
`txInfo.datums`, are `Map`s. Keys and values may be any type with a Data encoding, including records,
custom types and ledger types; keys need not be comparable.

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
| `foldr` | `(k -> v -> b -> b) -> b -> Map k v -> b` | in reverse map order |
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
cpu 36520840, mem 151709
```

Avoid making results depend on map order. To process the bindings in key order, sort them,
`List.sortBy Tuple.first (Map.toList prices)`, or keep them in a [`Dict`](#dict).

**No equality.** `==` on maps is rejected (`ELCI2003`): two maps with the same bindings may hold them in
different orders. For the same reason `Data.encode` of two such maps may differ. Compare the lookups you
care about, or their `toList` after sorting, or use a `Dict`.

**Repeated keys.** A map built in Elci never repeats a key, but a map decoded from Data can, because
decoding doesn't check a `Map`'s keys. In such a map, `get` returns the first binding of the key, `remove`
and `insert` remove every binding of it, and `map` and `filter` keep every binding.

**Cost.** `get`, `member`, `insert`, `remove` and `update` scan the pairs from the front: O(n), at about 1.1
million CPU units per key passed. `size`, `keys`, `values`, `toList`, the folds, `filter` and `map` are
single passes. `fromList` checks each key against the pairs before it, so it is O(n²): building a 50-entry
map cost about 1.5 billion CPU units. With comparable keys, `Dict.fromList` is much cheaper. See
[costs and performance](https://elci-lang.dev/advanced/costs/#maps-and-dictionaries).

A map or dictionary whose key and value types nothing determines can't be compiled, because the
operations are specialized for them. `Map.size Map.empty` on its own fails at the call:

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

## `Value`

`import Value exposing (Value)`. A `Value` is the ledger's multi-asset value: an amount of lovelace and
quantities of native tokens. `out.value` and `ctx.txInfo.mint` are `Value`s, and so is everything this
module builds. A token is named by a policy id and a token name, the ledger types `PolicyId` and
`TokenName`; build them from bytes with `import Julc.Ledger exposing (PolicyId(..), TokenName(..))`, as in
`PolicyId bytes`. Ada is the empty token name under the empty policy, and `lovelace` and `lovelaceOf` cover
it; a token with another name under the empty policy is not lovelace. Functions take the value last, as in
Elm.

| Function | Type | Behavior |
| --- | --- | --- |
| `zero` | `Value` | the empty value |
| `lovelace` | `Int -> Value` | an amount of lovelace |
| `singleton` | `PolicyId -> TokenName -> Int -> Value` | a quantity of one token |
| `insert` | `PolicyId -> TokenName -> Int -> Value -> Value` | sets the quantity of one token, replacing what the value held; `0` removes the token |
| `fromList` | `List ( PolicyId, TokenName, Int ) -> Value` | the listed quantities; a repeated token adds up |
| `lovelaceOf` | `Value -> Int` | the lovelace: the quantity of ada's token; `0` when there is none, as in a minted value |
| `quantityOf` | `PolicyId -> TokenName -> Value -> Int` | the quantity of one token; `0` when there is none |
| `isZero` | `Value -> Bool` | whether the value holds nothing |
| `policies` | `Value -> List PolicyId` | in ascending order; ada's is the empty policy |
| `tokens` | `PolicyId -> Value -> Dict TokenName Int` | the tokens of one policy and their quantities |
| `toList` | `Value -> List ( PolicyId, TokenName, Int )` | every token, in ascending order |
| `add` | `Value -> Value -> Value` | the sum |
| `subtract` | `Value -> Value -> Value` | the first value minus the second |
| `negate` | `Value -> Value` | every quantity negated |
| `scale` | `Int -> Value -> Value` | every quantity multiplied |
| `sum` | `List Value -> Value` | the sum of the list |
| `geq` | `Value -> Value -> Bool` | `geq a b`: `a` holds at least as much of every token as `b` |
| `leq` | `Value -> Value -> Bool` | `leq a b`: `a` holds at most as much of every token as `b` |
| `withoutLovelace` | `Value -> Value` | the value without its lovelace |
| `restrictedTo` | `List PolicyId -> Value -> Value` | only the tokens of the listed policies |
| `positive` | `Value -> Value` | only the positive quantities: in a minted value, what is minted |
| `negative` | `Value -> Value` | only the negative quantities, still negative: in a minted value, what is burned |

```elm title="src/Main.elm"
module Main exposing (main)

import Bytes
import Julc.Ledger exposing (PolicyId(..), TokenName(..))
import Value exposing (Value)

gold : PolicyId
gold =
    PolicyId (Bytes.fromString "gold")

coin : TokenName
coin =
    TokenName (Bytes.fromString "coin")

payment : Value
payment =
    Value.add (Value.lovelace 2000000) (Value.fromList [ ( gold, coin, 3 ), ( gold, coin, 2 ) ])

main : ( Value, ( Int, Int ), ( Bool, Bool ) )
main =
    ( payment
    , ( Value.lovelaceOf payment, Value.quantityOf gold coin payment )
    , ( Value.geq payment (Value.lovelace 1500000)
      , payment == Value.insert gold coin 5 (Value.lovelace 2000000)
      )
    )
```

```text
$ elci eval
( Value.fromList [( #(empty), #(empty), 2000000 ), ( #676f6c64, #636f696e, 5 )], ( 2000000, 5 ), ( True, True ) )
cpu 29654521, mem 85992
```

`elci eval` prints a value as the `Value.fromList` that builds it, one `( policy, token name, quantity )`
per token; `#(empty)` is ada's policy and token name. The two `coin` entries added up to 5.

**Canonical form and equality.** Every `Value` is canonical: policies ascend by their bytes, compared as
unsigned, token names ascend within each policy, and no quantity is zero. The ledger's values are canonical,
the functions above keep them so, and decoding a `Value` from Data rejects any other form. So two values are
equal exactly when they hold the same quantities, whatever order the tokens were added in: `==` works on
values, and on your records, tuples and lists that contain them. Values are not
[comparable](#comparable-types); compare them with `geq` and `leq`.

**Negative quantities.** A value may hold negative quantities. The transaction's `mint` field holds the
tokens it burns as negative quantities. `geq` and `leq` handle them: `geq a b` subtracts `b` from `a` and
checks that no quantity is below zero, so a burn counts as less than none. `positive` and `negative` split
a mint into what it mints and what it burns:

```elm title="src/Main.elm"
module Main exposing (main)

import Bytes
import Dict
import Julc.Ledger exposing (PolicyId(..), TokenName(..))
import Value exposing (Value)

gold : PolicyId
gold =
    PolicyId (Bytes.fromString "gold")

{-| A mint that creates 3 new coins and burns 2 old ones.
-}
minted : Value
minted =
    Value.fromList
        [ ( gold, TokenName (Bytes.fromString "new"), 3 )
        , ( gold, TokenName (Bytes.fromString "old"), -2 )
        ]

main : ( ( Value, Value ), ( Bool, Bool ), List ( TokenName, Int ) )
main =
    ( ( Value.positive minted, Value.negate (Value.negative minted) )
    , ( Value.geq minted Value.zero, Value.geq (Value.positive minted) Value.zero )
    , Dict.toList (Value.tokens gold minted)
    )
```

```text
$ elci eval
( ( Value.fromList [( #676f6c64, #6e6577, 3 )], Value.fromList [( #676f6c64, #6f6c64, 2 )] ), ( False, True ), [( #6e6577, 3 ), ( #6f6c64, -2 )] )
cpu 45778556, mem 170124
```

`Value.negate (Value.negative minted)` gives the burned amounts as positive quantities.

**Failures.** A policy id or token name longer than 32 bytes, and a quantity outside the signed 128-bit
range, fail the script: `evaluation failed: InsertCoin: tokenName too long: 33 bytes (max 32)`.

**In Data.** A `Value` is stored as the ledger stores it, a Plutus map from policy ids to maps from token
names to quantities, so `Julc.Data.encode` of a value costs nothing. `Julc.Data.decode` accepts only the
canonical form. Unsorted or repeated policies or token names fail with
`UnValueData: currencies not strictly ordered or duplicate`, a zero quantity with
`UnValueData: zero quantity`, and a policy without tokens or an id longer than 32 bytes fail too.

Both conversions work on a `Value` itself. A type that contains one can't be decoded yet, and a list or tuple
of values can't be encoded either (`ELCI3003`). A `Value` can't be a datum, redeemer or parameter yet, nor
part of one:

```text
src/Main.elm:7:1: ELCI2013: org.julclang.ledger.Value has no approved strict Data boundary (BOUNDARY); for example records containing the map-encoded Value cannot be checked
```

Store a `List ( PolicyId, TokenName, Int )` instead, and rebuild the value with `Value.fromList`.

**Cost.** `lovelaceOf`, `isZero`, `policies`, `tokens` and `toList` read the value's Data directly, and
`positive`, `negative`, `restrictedTo` and `withoutLovelace` filter it entry by entry, keeping it canonical.
The other functions convert it to the protocol's native value (CIP-153 builtins) and back, which costs about a
million CPU units for a value of a few assets; `sum` converts each value once. These small programs, which
include building their values, cost:

| Program | CPU |
| --- | ---: |
| `Value.add` of `Value.lovelace` and `Value.singleton` | 3.5 million |
| `Value.lovelaceOf (Value.lovelace 5000000)` | 2.7 million |
| `Value.quantityOf` of a `Value.singleton` | 2.5 million |
| `Value.geq` of two `Value.lovelace` values | 6.3 million |
| `Value.sum` of five `Value.lovelace` values | 20 million |

Most functions grow with the number of assets; [costs and performance](https://elci-lang.dev/advanced/costs/#values) has figures
for larger values.

**Java libraries.** A Java library builds values in its own order, as Julc's `ValuesLib` does, so no Java
library may hand Elci a `Value`. An export whose result can hold one, directly or in a field of a record or
union, is rejected:

```text
src/Main.elm:9:5: ELCI2105: org.julclang.stdlib.lib.ValuesLib.add: returns a Value, which Java libraries do not keep in the ledger's canonical order; use the core Value module
```

A generic Java function can't be used at a type that holds a `Value` either:

```text
src/Main.elm:9:5: ELCI3003: Type argument a = Value of filter contains a Value, which a Java library does not keep in the ledger's canonical order; build it with the Value module
```

Julc's `ContextsLib` and `OutputLib` are exempt: they only pass along the ledger's own values, as
`ContextsLib.valuePaid` does. Exports that only read a value, such as `ValuesLib.assetOf`, still work, but
this module covers them. A value has no constructors or patterns of its own:
`import Julc.Ledger exposing (Value(..))` gives
`ELCI2104: Value has no approved constructors or patterns; its values come from the Value module`.

## `Interval`

`import Interval`. An `Interval` is the ledger's time range, such as the transaction's validity range
`ctx.txInfo.validRange`. Times are POSIX milliseconds. A range has a start, `range.from`, and an end,
`range.to`. Each bound is `NegInf`, `Finite time` or `PosInf`, and is inclusive or exclusive.

A script can't read the clock. The ledger accepts a transaction only inside its validity range, so a time
check asks where the whole range lies: `isEntirelyAfter deadline ctx.txInfo.validRange` holds only when
the transaction cannot run at or before the deadline.

| Function | Type | Behavior |
| --- | --- | --- |
| `isEntirelyAfter` | `Int -> Interval -> Bool` | `isEntirelyAfter t range`: the range has a finite start, and every time in it is after `t` |
| `isEntirelyBefore` | `Int -> Interval -> Bool` | `isEntirelyBefore t range`: the range has a finite end, and every time in it is before `t` |
| `contains` | `Int -> Interval -> Bool` | `contains t range`: `t` is in the range |
| `start` | `Interval -> Maybe Int` | the finite start, or `Nothing` |
| `end` | `Interval -> Maybe Int` | the finite end, or `Nothing` |
| `after` | `Int -> Interval` | from `t` on, inclusive |
| `before` | `Int -> Interval` | up to `t`, inclusive |
| `between` | `Int -> Int -> Interval` | `between low high`: from `low` to `high`, both inclusive |
| `everything` | `Interval` | all time |

"After" and "before" are strict: no time in the range may equal `t`. The checks honor each bound's
inclusiveness. A scenario's `Scenario.validFrom t` gives the range an inclusive start, and
`Scenario.validBefore t` an exclusive end, so:

- `isEntirelyAfter deadline` holds for a range valid from `deadline + 1` on, not from `deadline`. To
  accept a transaction valid from the deadline itself, check `isEntirelyAfter (deadline - 1)`.
- `isEntirelyBefore deadline` holds for a range valid before `deadline`: the exclusive end may equal it.

```elm title="src/Main.elm"
module Main exposing (main)

import Interval exposing (Interval)
import Julc.Ledger exposing (Interval(..), IntervalBound(..), IntervalBoundType(..))

{-| A validity range from 1000, inclusive, to 2000, exclusive.
-}
window : Interval
window =
    Interval (IntervalBound (Finite 1000) True) (IntervalBound (Finite 2000) False)

main : ( ( Bool, Bool, Bool ), ( Bool, Bool, Bool ), ( Bool, Bool, Maybe Int ) )
main =
    ( ( Interval.isEntirelyAfter 999 window, Interval.isEntirelyAfter 1000 window, Interval.contains 1000 window )
    , ( Interval.isEntirelyBefore 2000 window, Interval.isEntirelyBefore 1999 window, Interval.contains 2000 window )
    , ( Interval.isEntirelyAfter 0 (Interval.before 5), Interval.isEntirelyBefore 10 (Interval.after 5), Interval.end (Interval.after 5) )
    )
```

```text
$ elci eval
( ( True, False, True ), ( True, False, False ), ( False, False, Nothing ) )
cpu 44424561, mem 167367
```

The window starts at 1000, inclusive: it is entirely after 999 but not after 1000, and it contains 1000.
It ends at 2000, exclusive: it is entirely before 2000 but not before 1999, and it doesn't contain 2000.

**Unbounded ranges.** The two deadline checks never hold on an infinite bound. `isEntirelyAfter` needs a
finite start and `isEntirelyBefore` a finite end, so a transaction that sets no validity range passes
neither check, and `Interval.before 5` is not entirely after any time. A `PosInf` start or a `NegInf` end,
which only an empty range has, fails the check that reads it, as in Aiken's stdlib v2; Aiken v3 lets such
an empty range pass both checks. `start` and `end` return `Nothing` for an infinite bound, never a sentinel
time. They return the bound's time whether or not it is inclusive; read `range.from.isInclusive` when that
matters, or use the checks, which account for it.

The constructors `Interval`, `IntervalBound`, `Finite`, `NegInf` and `PosInf` come from `Julc.Ledger`, as in
the example. The `Interval` type is the ledger's, so `import Julc.Ledger exposing (Interval)` names the
same type.

**Cost.** Each check reads one or two bounds and compares integers. `isEntirelyAfter` and
`isEntirelyBefore` cost about 2.8 million CPU units, `contains` about 5.5 million.

## `Tx`

`import Tx`. Questions about the transaction a script validates. Every function takes the `ScriptContext`
last, so a check reads `Tx.signedBy owner ctx`.

| Function | Type | Behavior |
| --- | --- | --- |
| `signedBy` | `PubKeyHash -> ScriptContext -> Bool` | the key is among the transaction's signatories |
| `spends` | `TxOutRef -> ScriptContext -> Bool` | the transaction spends the output; reference inputs don't count |
| `findInput` | `TxOutRef -> ScriptContext -> Maybe TxInInfo` | the spent output with that reference, with its address, value and datum |
| `ownRef` | `ScriptContext -> Maybe TxOutRef` | when spending, the reference of the output being validated |
| `ownInput` | `ScriptContext -> Maybe TxInInfo` | when spending, the output being validated |
| `ownPolicy` | `ScriptContext -> Maybe PolicyId` | when minting, the script's own policy id |
| `outputsTo` | `Credential -> ScriptContext -> List TxOut` | the outputs whose payment credential is the credential, in order |
| `valuePaidTo` | `Credential -> ScriptContext -> Value` | the total value of those outputs |
| `continuingOutputs` | `ScriptContext -> List TxOut` | when spending, the outputs to the spent output's address |
| `valueSpent` | `ScriptContext -> Value` | the total value of the spent outputs |
| `valueProduced` | `ScriptContext -> Value` | the total value of the outputs |
| `mintedOf` | `PolicyId -> ScriptContext -> Dict TokenName Int` | the tokens the policy mints; a burn is a negative quantity |
| `inlineDatum` | `TxOut -> Maybe Data` | the output's inline datum |
| `datumOf` | `TxOut -> ScriptContext -> Maybe Data` | the output's datum: inline, or by its hash from the datums the transaction supplies |

- **Purposes.** `ownRef`, `ownInput` and `continuingOutputs` describe the output a spend handler
  validates, and `ownPolicy` the policy a mint handler validates. For any other purpose they return
  `Nothing` or `[]`. Match the `Nothing` case and return `False`.
- **Credentials, not addresses.** `outputsTo` and `valuePaidTo` match an output's payment credential
  (`output.address.credential`) and ignore its staking part. Whoever builds the transaction chooses the
  payee's staking part: a payment to the seller's key counts whatever staking credential its address
  carries. When the staking part matters, store the payee's full `Address` and compare `output.address`. `continuingOutputs` compares whole addresses, staking part included.
- **Payments must be tagged.** "Some output pays the seller" is true for two script inputs spent in one
  transaction when one output pays for both. This is the double-satisfaction attack, and an untagged
  `valuePaidTo` check has it. Require each payment to carry the spent output's reference as its inline
  datum, as below, or allow one script input per transaction.
  [Reading the transaction](https://elci-lang.dev/contracts/reading-the-transaction/#one-payment-per-input) explains both
  defences.
- **Datums stay `Data`.** `inlineDatum` returns `Nothing` for an output with a datum hash or no datum.
  `datumOf` also finds a datum by its hash in `txInfo.datums`, and returns `Nothing` when the transaction
  doesn't supply it. Decode a datum with an annotated `Data.decode`, which fails the script on a malformed
  datum, or compare it with an expected encoding: `==` on `Data` compares structure.
- **Values.** `valueSpent` sums the spent outputs, not the reference inputs. `valueProduced` sums the
  outputs; the fee and the minted tokens are separate fields, `txInfo.fee` and `txInfo.mint`. `mintedOf`
  is `Value.tokens policy ctx.txInfo.mint`: an empty `Dict` when the policy mints nothing.

A payment check that pays for this input only:

```elm title="src/Sale.elm"
module Sale exposing (paidFor)

import Julc.Data as Data
import Julc.Ledger exposing (Credential(..), PubKeyHash, ScriptContext)
import Tx
import Value

{-| An output pays the seller at least `price` lovelace and carries this input's
reference as its inline datum.
-}
paidFor : PubKeyHash -> Int -> ScriptContext -> Bool
paidFor seller price ctx =
    case Tx.ownRef ctx of
        Just ref ->
            List.any
                (\output ->
                    (Value.lovelaceOf output.value >= price)
                        && (Tx.inlineDatum output == Just (Data.encode ref))
                )
                (Tx.outputsTo (PubKeyCredential seller) ctx)

        Nothing ->
            False
```

**Cost.** The functions walk the transaction's lists. Measured per element, in CPU units:

| Function | Cost |
| --- | --- |
| `signedBy` | about 0.7 million per signatory before the key |
| `spends`, `findInput`, `ownInput` | about 2.5 million per input before the match; `spends` walks every input when the output isn't spent |
| `outputsTo` | about 2.8 million per output |
| `valuePaidTo` | as `outputsTo`, plus about 2.9 million per matching output to add its value |
| `continuingOutputs` | `ownInput`, then about 3.6 million per output |
| `valueSpent`, `valueProduced` | about 3.3 million per input and 2.8 million per output |
| `ownRef`, `ownPolicy`, `inlineDatum` | a few field reads, whatever the transaction's size |

When a branch needs a result twice, bind it once in a `let` inside that branch.

## `Bytes`

`import Bytes`. Each function is a direct Plutus builtin call, with the byte string last.
[Bytes, hashes and bits](https://elci-lang.dev/advanced/bytes-and-crypto/) explains each group with examples.

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
  `Maybe`, `Result`, `Order`, lists, dictionaries, maps and optionals of these, a [`Value`](#value), and
  imported types whose provider approves decoding or encoding (for example `PubKeyHash` or `TxOutRef`). The
  rest are rejected with `ELCI3003`: `()`, functions, `Array`, and ledger types without a checkable layout,
  such as `TxOut` for `decode`. A type that contains a `Value` can't be decoded yet.
- **Known types for decoding.** `decode` must know the type it creates where it is used. If inference
  cannot tell, annotate the binding (local definitions accept signatures). Otherwise the call fails with
  `ELCI3001: Cannot determine the type converted by Julc.Data.decode; add a type annotation`. `encode`
  and the optional adapters don't need to know it: `Data.encode Nothing` is `Constr 0`.
- **Strict decoding.** `decode` never returns a `Maybe` for malformed input. A value of the wrong shape,
  such as an integer where a record is expected, fails with an error like `UnConstrData: not ConstrData`.
  A `Dict` whose keys are not strictly ascending fails too, and so does a `Value` that is not canonical.
- **Optionals.** Julc's optional tags `Some` as 0 and `None` as 1, the reverse of `Maybe`. The adapters
  swap the tags without decoding the payload.
- **Equality.** `==` and `/=` on `Data` compare structure exactly, with the `equalsData` builtin. Elci's
  encodings are deterministic, so `Data.encode a == Data.encode b` exactly when `a == b` for your records,
  custom types, lists and dictionaries. That makes a datum check one comparison:
  `Tx.inlineDatum output == Just (Data.encode expected)`. A `Map` has no `==`: two maps with the same
  bindings may hold them in different orders, so their encodings may differ too. Compare their `toList`
  after sorting, or use a `Dict`. `Data` is not comparable: `<` and sorting reject it.

```elm title="src/Main.elm"
module Main exposing (main)

import Dict
import Julc.Data as Data
import Map

type alias Payout =
    { beneficiary : Int
    , amount : Int
    }

main : ( Bool, Bool, ( Bool, Bool ) )
main =
    ( Data.encode (Payout 1 2) == Data.encode { amount = 2, beneficiary = 1 }
    , Data.encode (Just 5) == Data.encode 5
    , ( Data.encode (Dict.fromList [ ( 1, 10 ), ( 2, 20 ) ]) == Data.encode (Dict.fromList [ ( 2, 20 ), ( 1, 10 ) ])
      , Data.encode (Map.fromList [ ( 1, 10 ), ( 2, 20 ) ]) == Data.encode (Map.fromList [ ( 2, 20 ), ( 1, 10 ) ])
      )
    )
```

```text
$ elci eval
( True, False, ( True, False ) )
cpu 62954744, mem 240991
```

The record and the record literal with the same fields encode alike; `Just 5` and `5` do not. The two
dictionaries hold the same bindings, so their encodings are equal; the two maps hold them in different
orders, so theirs are not.

The encoding rules are in the [language reference](https://elci-lang.dev/reference/language/#data-encoding).

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
tests. See [debugging and budgets](https://elci-lang.dev/testing/debugging/).

## `Julc.Ledger`

`Julc.Ledger` provides the ledger types (`ScriptContext`, `TxInfo`, `PubKeyHash`, `TxOutRef`,
`Credential` and more) rather than functions. See
[reading the transaction](https://elci-lang.dev/contracts/reading-the-transaction/). Its `Value` is the type of the
[`Value`](#value) module and its `Interval` the type of the [`Interval`](#interval) module, so
`import Julc.Ledger exposing (Value)` names the same type. The [`Tx`](#tx) module answers the common
questions about a `ScriptContext`.

## Differences from Elm

| Elm | Elci | Why |
| --- | --- | --- |
| `String` ordered by UTF-16 code units | ordered by UTF-8 bytes, which is code point order | Plutus strings are UTF-8. The orders differ only for a character outside the Basic Multilingual Plane against one from U+E000 to U+FFFF |
| `2 ^ -1 == 0.5` | fails the script | no `Float` |
| `x // 0 == 0`; `modBy 0 x` crashes | both fail the script | Plutus division by zero |
| `number`, `Float` | `Int` only | no floating point on-chain |
| Bytes are not comparable | `Bytes` and ledger hashes are comparable | they are the natural keys on Cardano |
| two imports exposing one name are ambiguous | a name listed in `exposing (...)` takes precedence over a default import | the intent is explicit |
| `Dict` is a balanced tree: `get` and `insert` are O(log n) | `Dict` is a Plutus map with sorted keys: `get` and `insert` are O(n), `fromList` O(n log n) | a tree would not encode as a Plutus map, and rebalancing it on-chain costs more than scanning the small maps contracts use |
| `Debug.log`, `Debug.todo`, `Debug.toString` | `Debug.trace message value` | traces are the on-chain log |
| `Result` has no fixed encoding | `Ok` is constructor 0, `Err` constructor 1 | values cross the script boundary as Data |

Elm's `Set` and `Char` modules and most of `String` are not available. [Coming from
Elm](https://elci-lang.dev/learn/coming-from-elm/) lists every other difference.
