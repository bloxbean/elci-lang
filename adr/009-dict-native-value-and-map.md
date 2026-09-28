# ADR-009: `Dict`, a native `Value`, and a faster `Map`

Status: **Accepted**. `Dict` and `Map` (sections 1, 3, 4) are implemented in the first PR; `Value`
(section 2) in the second.
Date: 26 September 2026
Second of three standard-library phases (ADR-008, ADR-009, ADR-010).

## Context

Phase 1 (ADR-008) gave Elci Elm's prelude, `comparable` and the core modules. Three gaps
remain from the beta-readiness review:

- **No dictionary with Elm's semantics.** `Map k v` is the ledger's association list. Its
  keys compare by their Data encoding, its order depends on how it was built, and
  building costs O(n²):
  - `fromList` of 40 entries costs 1.9 billion CPU units, about a fifth of a
    transaction's budget;
  - `map` and `filter` rebuild through `insert`.

  Order dependence also makes datum checks fragile. The same entries built in a
  different order encode differently, so comparing an output datum against an expected
  one can fail for no semantic reason.
- **Multi-asset values are thin.** The ledger `Value` is opaque in Elci. It has no
  equality and no encoding, and it is read through Julc's `ValuesLib`, mapped under
  `[java-modules]`, with raw byte strings for policies and token names. Protocol
  version 11 has native builtins for values (CIP-153), but Elci cannot reach them.
- **`Map` operations are slower than they need to be.** `toList` zips two traversals.
  `map` and `filter` rebuild by repeated insertion. `fromList` deletes before every
  insert.

## Decision

### 1. `Dict comparable v`: Elm's dictionary, as a sorted Plutus map

- **Representation.** A `Dict` is a Plutus map (a list of key/value pairs) whose keys are
  strictly ascending in Elci's `comparable` order (ADR-008). It is a distinct type from
  `Map`, so a `Map` can never pass as a `Dict`. Every `Dict` value has exactly one
  representation, so equality is structural: two dictionaries are equal exactly when
  they have the same bindings. `Dict` supports `==`, which `Map` does not.
- **Module.** `import Dict exposing (Dict)`, with Elm's API, names and argument orders:
  - `empty`, `singleton`, `insert`, `update`, `remove`;
  - `isEmpty`, `member`, `get`, `size`;
  - `keys`, `values`, `toList`, `fromList`;
  - `map`, `foldl`, `foldr`, `filter`, `partition`;
  - `union`, `intersect`, `diff`, `merge`.

  `keys`, `values`, `toList` and the folds follow ascending key order, as in Elm. `union`
  prefers the left dictionary on collisions, as in Elm. `fromList` keeps the last binding
  for a repeated key, as in Elm.
- **Costs**, measured on the Plutus V3 PV11 cost model with `Int` keys:
  - `get`, `member`, `insert`, `remove` and `update` stop at the first key that is not
    smaller, at about 1.1 million CPU units per key passed. That is O(n) in the worst case
    and half a scan on average.
  - `fromList` is a stable merge sort of the input's ascending runs: O(n log n), and O(n)
    for sorted input. For 40 entries it costs about 0.27 billion CPU (sorted) to 0.59 billion
    (shuffled), against 1.13 billion for `Map.fromList`. For 80 entries it is 0.55 to 1.3
    billion, against 4 billion.
  - `map`, `filter`, `partition`, `union`, `intersect`, `diff` and `merge` are single passes
    or merges, O(n + m).
  - `size`, `keys`, `values`, `toList` and the folds are O(n).
- **The Data boundary is checked.** A `Dict` encodes as a Plutus map whose keys ascend in
  the `comparable` order. For byte-string keys (policy ids, token names, hashes) that is
  the ledger's order. It is not CBOR's canonical order, which puts shorter byte strings and
  non-negative integers first, so off-chain code must sort keys the Elci way. Decoding a `Dict`
  requires strictly ascending keys and fails otherwise, and so does every `Dict` nested in
  it. The check applies in four places:
  - `Data.decode`;
  - validator datums, redeemers and deployment parameters, as a guard around the handler,
    because the ledger's strict boundary decodes a `Dict` as an ordinary map;
  - property arguments, which also decode with the check;
  - `elci apply`, which rejects a parameter whose `Dict` keys are unsorted or repeated.

  The check reaches a `Dict` nested in records, tuples, custom types, `Maybe`, `Result`,
  Julc optionals, lists and map values, including recursive types. Any other container of a
  `Dict` is a compile error, never an unchecked path. Checking the order needs comparable
  keys, so a `Dict` with other keys cannot be decoded from Data; use a `Map` there.
- **Only the core library may carry a `Dict` through Java generics.** A Java library generic
  instantiated at a type containing a `Dict` is a compile error. Such a library could build
  the value itself, for example `<T> T cast(PlutusData)`, and skip the check.
  - Without the check, an attacker who controls a datum could supply an unsorted map
    on which `member` answers wrongly (for example, "has this key already voted?").
  - Canonicalizing unsorted input was rejected: a repeated key has no single meaning.
- **Implementation.**
  - The module is written in Elci over a small set of internal intrinsics (section 4)
    that walk the pair list directly and are specialized for the key type.
  - Key comparisons use the ADR-008 ordering lowering (`Int` and byte-string builtins;
    shared helpers for lists and tuples).
  - The boundary check is a generated function per type that contains a `Dict`. It walks
    records, custom types, lists, tuples, `Maybe`, `Result` and `Map` values.
  - Types without a `Dict` pay nothing, so existing scripts are unchanged.
- **Tests with generated arguments.** A property whose argument type contains a `Dict`
  receives dictionaries generated in canonical form. Generated keys are sorted by the same
  order, and shrinks stay canonical.

### 2. `Value`: the ledger's multi-asset value, on CIP-153 builtins

- **One type, stored as the ledger stores it.** `Value` becomes a built-in type and a core
  module, and the ledger's `Value` (`out.value`, `txInfo.mint`) is that type. It stays
  represented as the ledger's Data map, not as a native value. Records, lists, `Maybe` and
  `Map` hold Data, so a native value could not be a field or an element, and converting
  silently at every load and store would hide large costs.
- **Every `Value` is canonical.** Entries are sorted by policy, then token, with no zero
  quantities and no empty token maps.
  - The ledger's values are canonical: V3 builds them in ascending order and rejects
    zeros. The `Value` functions produce canonical values through the builtins
    (`ValueData` output is canonical).
  - `Data.decode` of a `Value` checks the form with `UnValueData`, which rejects
    non-canonical input: unsorted or repeated keys, zero quantities, empty token maps, and
    keys longer than 32 bytes.
  - Equality is therefore exact structural equality (`EqualsData`), and `Data.encode` is
    free.
- **Implementation.** A core-only intrinsic module, as in section 4, emits the CIP-153
  builtins directly. An internal native-value type exists only inside it. Julc needs no
  change: its verifier already types the builtins and accepts the Data-represented value.
- **API**, Elm-style, with the value last:
  - construction: `zero`, `lovelace`, `singleton`, `insert` (sets a quantity; 0 removes it);
  - queries: `lovelaceOf`, `quantityOf`, `isZero`, `policies`, `tokens` (a `Dict`),
    `toList`, and `==`;
  - arithmetic: `add`, `subtract`, `negate`, `scale`, `sum`;
  - comparison: `geq` and `leq`;
  - structure: `withoutLovelace`, `restrictedTo`, `positive`, `negative`.
- **Total comparison.** CIP-153's `valueContains` fails if either side has a negative
  quantity, and a mint that burns has negative quantities. So `geq a b` does not use it: it
  subtracts and checks that no quantity is negative. That is total and correct for any
  values, and a few million CPU units. Arithmetic fails only on 128-bit overflow.
- **Costs** (builtin cost only). Converting a 3-asset value costs about 1.1 million CPU
  (`UnValueData`) and 0.1 million back (`ValueData`). `lovelaceOf` reads the first entry
  directly, since ADA sorts first. Functions that combine many values (`sum`) convert each
  once.
- **Julc's `ValuesLib` results are not canonical** (reversed lists, zero entries). So no
  Java library may hand Elci a `Value`:
  - an export whose result can hold one is unsupported, and using it is an error that
    points to the `Value` module. That covers a direct `Value`, a record or union with a
    `Value` field, and generic exports;
  - a generic may not be instantiated at a type that holds a `Value`.

  The exception is Julc's context accessors (`ContextsLib`, `OutputLib`), which only pass
  along the ledger's own data. Exports that only read a `Value` stay usable.
  The test library's scenario builder now produces canonical values, and property
  arguments of type `Value` are generated in canonical form.
- **Data boundary.**
  - `Data.encode` of a `Value` is free, and `Data.decode` checks the canonical form.
  - A `Value` inside a datum, redeemer or parameter type is not supported yet: the ledger's
    strict boundary has no codec for its map layout, and neither has a record that
    contains it. Store `List ( PolicyId, TokenName, Int )` and rebuild the value with
    `Value.fromList`. Adding such a codec to Julc would lift this limit.
- **The filters avoid conversions.** `positive`, `negative`, `restrictedTo` and
  `withoutLovelace` filter the Data map directly: order is kept and emptied policies are
  dropped, so the result stays canonical.
- **Measured costs**, the snapshot's values. `geq` grows by about 2M CPU per asset: two
  conversions and a walk.
  - `add` of two small values, including building them: 3.5M CPU;
  - `lovelaceOf`: 2.7M, reading the Data directly;
  - `quantityOf`: 2.5M;
  - `geq`: 6.3M;
  - `sum` of five values: 20M.

### 3. `Map`: the ledger's association list, faster

`Map` stays the type of ledger maps, such as `txInfo.redeemers` and
`txInfo.withdrawals`, and of maps whose keys are not comparable. Its semantics are
unchanged: keys are equal when their Data encodings are, and a newly inserted key comes
first.

A map decoded from Data may repeat a key, since the Data boundary does not check `Map` keys.
In that case:
- `get` returns the first binding;
- `remove` and `insert` remove every binding of the key, so after `remove` the key is
  absent;
- `map` and `filter` keep every binding.

Its implementation improves:

- **Cheaper key comparison.** Keys are compared at their type instead of with
  `EqualsData`, which has a fixed cost of about 0.9 million CPU.
  - `Int` and byte-string keys use their own equality builtins.
  - `String` keys compare their UTF-8 bytes.
  - Other key types still use `EqualsData`.

  `get`, `member`, `remove` and `insert` cost about 1.1 million CPU per key passed, down
  from 2.1 million.
- **Fewer passes.** `toList`, `foldl` and the new `foldr` walk the pairs once instead of
  zipping two traversals.
- **`map` and `filter`** build the result in one pass, since their keys are already
  distinct. They become O(n), from O(n²).
- **`fromList`** scans from the end, keeps the last binding of each key and reverses once.
  It gives exactly the order repeated `insert` gives. It stays O(n²), since keys without an
  order cannot be sorted, but is about 1.6 times cheaper. For many entries with
  comparable keys, the documentation points to `Dict.fromList`.

`Map` no longer calls Julc's typed map library for these operations. No example script
changed, and the Map tests and law properties show the results are unchanged.

### 4. Internal association-list intrinsics

A compiler-provided module, `Elci.Assoc`, that only core modules may import, with
functions specialized for the key and value types:

| Function | Meaning |
| --- | --- |
| `toList`, `fromDistinct`, `cons` | pairs to tuples and back, in order, one pass; `fromDistinct` and `cons` trust the caller's keys |
| `find`, `has`, `delete` | `Map`'s operations: key equality by Data encoding, compared at the key's type |
| `get`, `member`, `insert`, `remove` | on ascending keys: stop at the first key not smaller; rebuild up to the position only |
| `ascending` | whether keys are strictly ascending |
| `runs`, `merge`, `dedupe` | the sort: ascending runs, a stable merge, the last of equal adjacent keys |
| `toMap`, `fromMap` | reinterpretations between `Dict` and `Map`, with no runtime cost |

The unchecked functions (`fromDistinct`, `fromMap`) are visible only to core modules, and
the core modules' tests and law properties are what guarantee their preconditions. A
project module that imports `Elci.Assoc` gets an error.

## Invariants and evidence required

- **Byte stability** of every example, except for scripts that use `Map.fromList`,
  `Map.map`, `Map.filter`, `Map.toList` or the folds, whose changes are listed.
- **Tests for every `Dict` function**, against Elm's documented results.
- **Law properties:**
  - `Dict.toList (Dict.fromList xs)` is sorted, has distinct keys, and keeps the last
    binding;
  - `get` after `insert`, and `remove` after `insert`;
  - `union`, `intersect` and `diff` against their definitions through `member` and `get`;
  - `==` agrees with `toList`.
- **Boundary tests**: unsorted keys, duplicate keys and nested dictionaries fail to
  decode, and valid ones round-trip.
- **Budget snapshots** for `Dict` and `Map` against the Phase 1 snapshot.
- **Documentation**: the reference, the learning pages, the starter pack and the catalog.

## Alternatives rejected

- **A balanced tree for `Dict`**, as Elm does. Tree rebalancing costs more on-chain than
  scanning the small maps contracts use. It would also encode as nested constructors
  instead of the Plutus map that off-chain tools expect.
- **Replacing `Map` by `Dict`.** Ledger maps have keys that are not comparable
  (`ScriptPurpose`, `Credential`) and come in the ledger's order; they need `Map`.
- **Leaving `Dict` out of the Data boundary**, as with `Array`. Simpler, but canonical
  sorted maps in datums are one of the main reasons to want `Dict`.
