# Costs and performance

> Measure execution budgets and script size, understand how strictness and top-level values affect cost, choose between lists, arrays, dictionaries and maps, and know what values cost.

Source: https://elci-lang.dev/advanced/costs/

Every validator run is metered, and every script byte is paid for. This page shows how to measure both
with the Elci CLI, explains the language rules that most affect cost, and backs each tip with a
measurement you can reproduce.

## What the ledger charges

A script run consumes two budgets:

- **CPU steps**: how much computation the script does.
- **Memory units**: how much data the script allocates.

The transaction declares the budget of each script it runs and pays fees for it. The ledger also caps the
total per transaction. At the time of writing, mainnet allows 14 million memory units and 10 billion CPU
steps per transaction, and a transaction of at most 16,384 bytes. Check the current protocol parameters
before you rely on these numbers. In practice, memory usually runs out first.

**Script size** matters too: a script is carried in the transaction that runs it, or in a reference
input, and fees grow with bytes.

All budgets on this page come from Julc's evaluator for Elci's target, `plutus-v3-pv11-uplc-1.1.0`.

## Measuring execution

`elci eval` runs a function project's `[entrypoint]` and prints its value, then the budget it consumed:

```text
$ elci eval
42
cpu 16100, mem 200
```

Add `--builtins` to list the builtin calls behind the number (the last 20 of them).

`elci test` prints the budget of every test. For a transaction scenario that is the cost of the
deployed script, run with a full ledger context. For a property it is the largest cost over all runs:

```text
$ elci test --seed 42
PASS VestingTest.claimsAfterTheDeadline (cpu 16763911, mem 61637)
PASS VestingTest.claimsInAClosedRange (cpu 16763911, mem 61637)
PASS VestingTest.claimsAtTheDeadlineFail (cpu 13950292, mem 52228)
PASS VestingTest.earlyClaimsFail (cpu 13950292, mem 52228)
PASS VestingTest.unsignedClaimsFail (cpu 16458714, mem 60004)
PASS VestingTest.claimsSucceedExactlyAfterTheDeadline (100 runs, max cpu 16763911, mem 61637)
6 passed, 0 failed (seed 42)
```

To turn a budget into a requirement, add `Scenario.withinBudget cpu memory` to a scenario. See
[debugging and budgets](https://elci-lang.dev/testing/debugging/).

As a baseline, a mint handler that ignores everything and returns `True` compiles to 114 bytes and costs
2,712,472 CPU and 11,155 memory in a scenario. That is roughly the fixed cost every script pays before
your handler's own logic runs: the wrapper that checks the purpose and decodes the redeemer.

## Measuring script size

`elci check` and `elci build` print each validator's size in FLAT bytes, the serialized script:

```text
$ cd treasury && elci build
validator badge (mint): checked; 198 FLAT bytes; plutus-v3-pv11-uplc-1.1.0
validator treasury (spend, withdraw): checked; 540 FLAT bytes; plutus-v3-pv11-uplc-1.1.0
```

The same number is `sizeBytes` in `build/validators/<name>/validator.plutus.json`. For reference, the
example projects build to:

| Example | Handlers | FLAT bytes |
| --- | --- | ---: |
| `threshold` | spend | 149 |
| `signed` | spend | 241 |
| `vesting` | spend | 533 |
| `treasury` (`badge`) | mint | 198 |
| `treasury` (`treasury`) | spend, withdraw | 540 |

What adds bytes, measured on small mint handlers:

| Change | FLAT bytes |
| --- | ---: |
| Handler using `List.any` at `Int` | 267 |
| Same, calling `List.any` twice at `Int` | 280 |
| Same, plus `List.any` at `Bool` and `List.map` from `Int` to `Bool` | 404 |
| A trivial handler | 114 |
| The same handler in a script that also has a `withdraw` handler | 189 |

A generic function is compiled once for each concrete type it is used at. Calling it again at the same
type reuses that copy; using it at a new type adds a new copy. Library functions are linked once per
script. Each `Debug.trace` message is stored in the script: a 19-character message added 26 bytes.

## Strict evaluation

Elci is strict. Every `let` value is evaluated when the `let` is entered, even if the body never uses
it. Only `if`, `case`, `&&` and `||` skip the branches they do not take.

```elm title="src/Main.elm"
module Main exposing (main)

main : Int
main =
    let
        unused =
            10 // 0
    in
    7
```

```text
$ elci eval
evaluation failed: QuotientInteger: division by zero
cpu 276441, mem 1001
```

The same rule has a cost side. Naming a check in a `let` evaluates it even when an earlier check
already decided the result:

```elm
main : Bool
main =
    let
        manyItems =
            List.length (range 1 100) > 50
    in
    amount > 1000 && manyItems
```

| Form | CPU |
| --- | ---: |
| `amount > 1000 && List.length (range 1 100) > 50` | 560,100 |
| `List.length (range 1 100) > 50 && amount > 1000` | 165,862,550 |
| `let manyItems = List.length (range 1 100) > 50 in amount > 1000 && manyItems` | 165,910,550 |

Here `amount` is `5`, so the first check fails. Written inline and first, it saves the whole traversal.
Put cheap, likely-to-fail checks first, and keep expensive checks inline or behind a function call rather
than in a `let`.

## Top-level values run at script start

A top-level value (a definition with no arguments) that a handler uses is computed once per script run,
**before** the ledger purpose is dispatched. In a script with several handlers, every purpose pays for it.
This validator has a spend handler that needs an expensive minimum fee and a mint handler that does not:

```elm title="src/Shop.elm"
minimumFee : Int
minimumFee =
    List.foldl (\value total -> value + total) 0 (range 1 50)

spend : () -> () -> ScriptContext -> Bool
spend _ _ ctx =
    ctx.txInfo.fee >= minimumFee

mint : () -> ScriptContext -> Bool
mint _ _ =
    True
```

```text
$ elci test
PASS ShopTest.minting (cpu 81348535, mem 374470)
PASS ShopTest.spending (cpu 84875573, mem 386950)
2 passed, 0 failed
```

The mint handler pays about 78 million CPU for a value it never reads. Make the value a function, so it is only
computed where it is called:

```elm
minimumFee : () -> Int
minimumFee _ =
    List.foldl (\value total -> value + total) 0 (range 1 50)

spend : () -> () -> ScriptContext -> Bool
spend _ _ ctx =
    ctx.txInfo.fee >= minimumFee ()
```

```text
$ elci test
PASS ShopTest.minting (cpu 3288472, mem 14755)
PASS ShopTest.spending (cpu 84923573, mem 387250)
2 passed, 0 failed
```

A top-level constant such as `limit = 1275` costs almost nothing, so this matters only for values that
compute something. Zero-argument library values, such as `Map.empty`, are also evaluated at script start.

## Purpose dispatch

A validator always checks the ledger purpose before it calls a handler, even when it has only one, so a
purpose without a handler is rejected. The check costs roughly 1.1 million CPU and 35 bytes per script,
and it is part of the 2.7 million CPU baseline above. Adding a second handler cost 75 bytes and 48,000
CPU in the measurements above. Only the selected handler's datum and redeemer are decoded.

## Lists and arrays

A `List` is a linked list: reaching element `n` walks `n` cells, and `List.length` walks them all. An
`Array` (from `import Array`) is a Plutus V3 array with constant-time `get` and `length`, built once from a
list. This program reads nine elements of a 100-element list by walking it:

```elm title="src/Main.elm"
module Main exposing (main)

range : Int -> Int -> List Int
range from to =
    if from > to then
        []

    else
        from :: range (from + 1) to

nth : Int -> List Int -> Int
nth index items =
    case items of
        [] ->
            0

        first :: rest ->
            if index == 0 then
                first

            else
                nth (index - 1) rest

main : Int
main =
    let
        values =
            range 1 100
    in
    List.foldl (\index total -> total + nth index values) 0 [ 10, 20, 30, 40, 50, 60, 70, 80, 90 ]
```

The array version converts once and reads in constant time:

```elm
at : Int -> Array Int -> Int
at index values =
    case Array.get index values of
        Just value ->
            value

        Nothing ->
            0

main : Int
main =
    let
        values =
            Array.fromList (range 1 100)
    in
    List.foldl (\index total -> total + at index values) 0 [ 10, 20, 30, 40, 50, 60, 70, 80, 90 ]
```

| Program (100 elements) | CPU | Memory |
| --- | ---: | ---: |
| Build the list with `range 1 100` | 78,436,213 | 350,865 |
| Build it and take `List.length` | 165,658,713 | 779,865 |
| Build it and read 9 elements by walking | 486,410,560 | 2,208,802 |
| Build it, `Array.fromList`, read 9 elements with `Array.get` | 114,646,728 | 484,047 |

After building the list, nine walking reads cost about 408 million CPU; converting to an array and
reading cost about 36 million. The list version's 2.2 million memory units are already about a sixth of
a transaction's memory limit.

Use a `List` when you traverse once (`List.any`, `List.foldl`, `List.filter`) and an `Array` when you
read many positions. An array has no Data encoding, so datums, redeemers and parameters stay lists; call
`Array.fromList` inside the handler.

## Sorting and comparing

`List.sort`, `List.sortBy` and `List.sortWith` are merge sorts written in Elci, O(n log n). Each
comparison is a function call, so sorting is one of the more expensive things a script can do. Each row
includes building its input:

| Program (20 elements) | CPU | Memory |
| --- | ---: | ---: |
| `List.range 1 20` | 16,577,300 | 75,673 |
| `List.sort (List.range 1 20)` | 133,140,808 | 644,693 |
| `List.map String.fromInt (List.range 1 20)` | 61,921,144 | 261,394 |
| `List.sort` of those strings | 213,536,764 | 959,078 |
| The same 20 values as `Bytes` | 62,881,144 | 267,394 |
| `List.sort` of those byte strings | 190,046,812 | 894,614 |

Sorting cost about 117 million CPU for 20 integers, 152 million for 20 strings and 127 million for 20 byte
strings. Checking that a list is already sorted is a single pass. When a transaction can supply sorted
data, have the validator check the order instead of sorting:

```elm title="src/Main.elm"
module Main exposing (main)

isSorted : List comparable -> Bool
isSorted items =
    case items of
        first :: second :: rest ->
            first <= second && isSorted (second :: rest)

        _ ->
            True

main : Bool
main =
    isSorted (List.range 1 20)
```

```text
True
cpu 33372778, mem 162456
```

That is about 17 million CPU for the check, against 117 million for the sort.

The cost of one comparison depends on the type. `Int` and `Bytes` use one builtin call. `String` first
encodes both sides to UTF-8, then compares the bytes. In a fold that compares the same two values 100
times, `Bytes` values cost about 10 million CPU more than `Int` values, and `String` values about
24 million more. Keys that are compared many times are cheaper as `Bytes`. Lists and tuples compare
element by element: `compare [ 1, 2, 3 ] [ 1, 2, 4 ]` cost about 6 million CPU, against 0.5 million for
`compare 3 7`.

## Maps and dictionaries

A `Map k v` is the ledger's map representation: a list of key/value pairs, compared by their Data
encoding, in no particular order. A `Dict k v` is the same list of pairs with its keys kept in ascending
order. Neither hashes its keys or balances a tree, so their costs are those of a list. The figures below use
`Int` keys and count the operation alone: the cost of a program that runs it, minus the cost of the same
program without it.

- **Lookups scan.** `Map.get` walks from the front until it finds the key. `Dict.get` walks from the
  smallest key and stops at the first key that isn't smaller, so a missing key costs half a scan on
  average. Both cost about 1.1 million CPU per entry passed: in a 50-entry map or dictionary, the first key
  cost about 2 million CPU and the last about 55 million. `member`, `insert` and `remove` walk the same way,
  and `insert` and `remove` also rebuild the pairs in front of the position.
- **`size` scans too.** It cost about 9 million CPU at 10 entries and 44 million at 50.
- **Building.** `Map.fromList` checks each key against the pairs before it, so it is O(n²). `Dict.fromList`
  sorts in O(n log n), and takes a single pass when the keys are already in order:

  | Building from a list of pairs | 10 entries | 50 entries | 100 entries |
  | --- | ---: | ---: | ---: |
  | `Map.fromList` | 82 million | 1.47 billion | 5.58 billion |
  | `Dict.fromList`, keys in order | 57 million | 0.28 billion | 0.55 billion |
  | `Dict.fromList`, keys shuffled | 111 million | 0.66 billion | 1.44 billion |

  The pairs were `( i, i )` for `i` from 1 to n; the shuffled keys were `modBy 101 (i * 17)` for 100
  entries, with 53 and 11 for the smaller sizes. The 100-entry `Map.fromList` also used 21 million memory
  units, more than a whole transaction may use.
- **Whole-collection operations are single passes.** `map`, `filter`, the folds and `toList`, and a
  dictionary's `union`, `intersect`, `diff` and `merge`, are O(n). Their cost per entry depends on the
  function you pass.
- **Decoding a `Dict` checks its keys.** When a datum, redeemer, parameter or `Julc.Data.decode` result
  contains a `Dict`, the script checks that its keys ascend, at about 1.1 million CPU per entry. A `Map` is
  decoded without this check.

Read maps that arrive in data, such as datum fields or the transaction's withdrawals and redeemers,
rather than building large ones on-chain. Build keyed data of your own with `Dict.fromList`, with its keys
in order when you can. Keys you look up often are cheapest near the front: the newest binding in a `Map`,
the smallest keys in a `Dict`.

## Values

A [`Value`](https://elci-lang.dev/reference/core-modules/#value) is stored as the ledger's Data map. `lovelaceOf` and `isZero`
read the start of that map, so their cost is fixed. `==` compares two maps directly, and `positive`,
`negative`, `restrictedTo` and `withoutLovelace` filter one entry by entry. The other functions convert the
whole value to the protocol's native value (the CIP-153 builtins) and back. Every cost except the fixed ones
grows with the number of assets. The figures below count the operation alone, on values with lovelace and
one token under each of the other policies:

| Operation | 3 assets | 10 assets | 30 assets |
| --- | ---: | ---: | ---: |
| `Value.isZero v` | 0.3 million | 0.3 million | 0.3 million |
| `Value.lovelaceOf v` | 2.0 million | 2.0 million | 2.0 million |
| `Value.quantityOf policy name v` | 3.5 million | 6.2 million | 13.9 million |
| `v == w` | 2.8 million | 7.0 million | 19.0 million |
| `Value.add v w` | 3.9 million | 12.1 million | 35.3 million |
| `Value.geq v w` | 5.4 million | 15.2 million | 43.3 million |
| `Value.positive v` | 12.7 million | 40.6 million | 120.4 million |

`geq` subtracts, then checks every quantity of the difference, so it costs more again when the difference
holds many tokens. `Value.sum` converts each value once: summing five small values cost about 20 million
CPU, against 22 million for folding them with `Value.add`. Read ada with `lovelaceOf`, and prefer
`quantityOf` or `==` to building a filtered value.

## Traces

`Debug.trace` costs budget and bytes even when the script succeeds. One trace of a 19-character message
added about 140,000 CPU and 26 bytes. Remove traces from validators you deploy, or keep them only on
failure paths.

## Practical tips

- Measure before and after a change: `elci test` for validators, `elci eval` for helper functions.
- Order `&&` chains from cheap to expensive, and avoid naming expensive checks in a `let` you might not need.
- Keep purpose-specific work inside its handler. Turn expensive top-level values into functions.
- Traverse lists once. Over 100 elements, `List.filter` followed by `List.foldl` cost about 127 million CPU;
  one `List.foldl` with an `if` inside cost about 96 million.
- Convert to an `Array` once when you read many positions; never rebuild an array to change it.
- Avoid sorting on-chain. When the transaction can provide sorted data, check the order in one pass.
- Build keyed data with `Dict.fromList` rather than `Map.fromList`, which is quadratic. Keep maps and
  dictionaries small: every lookup scans.
- Read ada with `Value.lovelaceOf`, whose cost is fixed. Other `Value` operations grow with the number of
  assets in the value.
- Use each generic helper at as few types as you can, and drop `Debug.trace` from deployed scripts.
- Library functions are compiled Java code, not builtins: `Julc.ByteString.toHex` over 32 bytes cost
  about 73 million CPU. Check the cost of a library call before using it in a loop.

For how strictness and specialization come from the compiler's design, see
[how Elci compiles](https://elci-lang.dev/advanced/how-it-works/).
