# ADR-008: A native standard library: prelude, `comparable` and Elm's core modules

Status: **Accepted**.
Date: 26 September 2026
First of three standard-library phases (ADR-008, ADR-009, ADR-010) towards a beta
release. ADR-011 covers diagnostics.

## Context

Elci inherited its library from two places: a small bundled core (`List`, `Map`,
`Array`) and Julc's Java libraries, imported under `[java-modules]`. A beta-readiness
review found three problems:

- **Missing basics.** Elm developers and AI agents reach first for `min`, `max`,
  `abs`, `negate`, `Maybe.withDefault`, `List.head`, `List.sum` and `List.range`.
  None of these exist. The Julc equivalents have Java names and argument orders, and
  they must be mapped in `elci.toml` first.
- **Ordering is `Int`-only.** `<` and friends accept only `Int`. There is no
  `compare`, so there can be no generic `min`, `max`, `sort` or sorted dictionary.
  Byte strings, which is what every Cardano key and hash is, cannot be ordered at all.
- **No `Result`, `Order` or `Tuple` helpers.** Idiomatic Elm validation code
  depends on them.

The goal for Elci is Elm's promise: correct programs are easy to write and hard to
get wrong. The standard library is where most of that promise is kept or lost.

## Decision

### 1. The standard library is Elci source, bundled and versioned with the compiler

Standard modules are written in Elci under `src/main/resources/elci/core/`, like
`List` today. They are compiled by the same frontend, tested with `elci test` and
formatted by elm-format. Compiler intrinsics are used only for single Plutus builtins
(as `Bytes` does) and for operations the language cannot express, such as ordering
(below). Julc's Java libraries stay available under `Julc.*` and fill gaps; a standard
module replaces one only when it is clearly better in cost, types or ergonomics.

Names, argument orders and semantics follow Elm 0.19's core library wherever on-chain
semantics allow. Every deviation is listed in section 8.

The library grows in three phases:

| Phase | ADR | Scope |
| --- | --- | --- |
| 1 | this ADR | Prelude, `comparable`, `Maybe`, `Result`, `Tuple`, `String`, `List` additions |
| 2 | ADR-009 | `Dict`, a native `Value`, a faster `Map` |
| 3 | ADR-010 | Transaction and interval helpers, reference contracts |

### 2. Default imports and name precedence, as in Elm

Every project and test module implicitly imports, as in Elm 0.19:

```elm
import Basics exposing (..)
import List
import Maybe exposing (Maybe(..))
import Result exposing (Result(..))
import String
import Tuple
import Debug
```

- **Precedence.** Elm's rules, with one addition:
  - A module's own declarations shadow every import.
  - Two imports that expose different things under one name make the name
    ambiguous. The error is reported only where the name is used, and it lists the
    qualified alternatives. This includes a default import against an explicit
    `exposing (..)`: `import Utils exposing (..)` whose `clamp` has a different
    argument order cannot silently replace `Basics.clamp`.
  - *Addition:* a name listed explicitly (`import Utils exposing (clamp)`) takes
    precedence over a default import. The intent is explicit, so it is not ambiguous.
- **Qualified names always work.** `Basics.max`, `Maybe.withDefault` and `Result.Ok`
  resolve even when a module shadows the unqualified name. Writing `import Maybe` or
  `import Debug` explicitly is allowed and changes nothing.
- **Aliases.** An import may not use a default module's name as its alias
  (`import Utils as String`), because that name is always imported.
- **Core modules import explicitly.** As in `elm/core`, core modules get no default
  imports. Their dependencies form a DAG: `Basics`, `Maybe`, `Result` and `Tuple`
  import nothing; `List` imports `Basics`; `String` imports `Basics`, `Bytes` and `List`;
  `Map` and `Array` import `List`.
- **Reserved names.** `Basics`, `List`, `Maybe`, `Result`, `String`, `Tuple`, `Map`
  and `Array` are core modules. `Dict`, `Set` and `Value` are reserved for later
  phases. A project module may not use these names, and neither may a
  `[java-modules]` key.
- **Built-in names.** `Maybe`, `Just` and `Nothing` stay built in, with one type
  identity that `Julc.Data`'s optional adapters and `spend-datum = "optional"` depend
  on. A module may not declare constructors named `Just`, `Nothing`, `True` or `False`,
  or types named `Int`, `Bool`, `String`, `Bytes`, `Data`, `List`, `Maybe`, `Map`,
  `Array` or `Float`: the core modules refer to these. `Elci` is reserved as a module
  name.
  `not`, `modBy` and `remainderBy` are compiler intrinsics exposed through `Basics`,
  so they can be shadowed and qualified like any prelude function.
- **Explicit imports stay explicit** for `Bytes`, `Map`, `Array`, `Julc.Data`, and
  (Phase 2) `Dict` and `Value`, as for Elm's `Dict` and `Array`.

### 3. `comparable`

A new compiler-known requirement, checked like equality: at every concrete use, and
carried through generic functions.

- **Comparable types:**
  - `Int` and `String`;
  - `Bytes`, and the ledger's byte-string types (`PubKeyHash`, `ScriptHash`,
    `PolicyId`, `TokenName`, `TxId`, …). Other imported types represented as byte
    strings are not comparable: the order of their bytes need not mean anything;
  - lists and tuples of comparable types.

  `Bool`, `()`, records, custom types, `Maybe`, `Map`, `Array`, `Data` and functions
  are not comparable, as in Elm. Bytes and hashes are an addition for Cardano, where
  they are the natural keys. Ordering records and constructor-tagged ledger types
  such as `TxOutRef` is ADR-009's question.
- **Operators.** `<`, `<=`, `>` and `>=` take two operands of the same comparable
  type. The arithmetic operators remain `Int`-only.
- **Order:**
  - `Int` numerically;
  - byte strings lexicographically by unsigned byte, a proper prefix first (Plutus
    `lessThanByteString`);
  - `String` by its UTF-8 bytes, which is Unicode code point order;
  - lists lexicographically, with a proper prefix first;
  - tuples lexicographically by component.

  Every order is total and agrees with `==`: `compare x y == EQ` exactly when
  `x == y`.
- **Constrained type variables follow Elm.** A variable whose values are compared
  must be named `comparable` or start with it (`comparable2`, `comparableKey`). One
  whose values are appended with `++` is named `appendable…`, and `compappend…` allows
  both. Naming a variable that way also imposes the requirement on callers. Using `<`
  or `++` on a value whose annotated type is `a` is an error that suggests the rename,
  as in Elm. Elm's `number` is reported with a targeted error: Elci has one number
  type, `Int`. Equality stays unnamed, as in Elm. Type and alias parameters are never
  constrained, and an unannotated definition infers its requirements.
- **Requirements are checked where they arise and where they are used.**
  - The members of a recursive group share type variables, so a requirement from one
    member's body applies to every member's type and naming rule.
  - A requirement no instantiation can meet, such as `x < y` on `Maybe comparable`,
    is reported at the definition, as in Elm, not first at a use.
- **Lowering.**
  - `Int` keeps its existing builtins, so script bytes are unchanged.
  - Byte strings use `lessThanByteString` and `lessThanEqualsByteString`.
  - `String` encodes both sides to UTF-8, then compares the bytes. Keys compared many
    times are cheaper as `Bytes`.
  - Lists and tuples use comparison definitions generated once per concrete type,
    like other specializations, so comparing two `List Int` values twice in a script
    emits one helper.

### 4. Ambiguous type variables

A type variable that nothing in a program determines, like the error type of `Ok 5`
or the element type of `[] == []`, is *ambiguous*. Elm accepts such programs, and so
does Elci: no value of an ambiguous type can exist, so lowering represents it as
`Data`, and equality, ordering and `Data.encode` accept it.

- **Decided once, by inference.** After type checking, every variable that no
  declaration's scheme quantifies is ambiguous. Lowering never defaults any other
  variable: one it cannot resolve is a compiler error, not a guess.
- **Only creating values needs the type.** `Data.decode` and Java library generics
  still require a known type and report `ELCI3001` with a request to annotate.

### 5. The prelude: `Basics`

Exposed unqualified in every module:

| Function | Type | Notes |
| --- | --- | --- |
| `negate` | `Int -> Int` | |
| `abs` | `Int -> Int` | |
| `min`, `max` | `comparable -> comparable -> comparable` | Elm's definitions: `min x y = if x < y then x else y` |
| `clamp` | `Int -> Int -> Int -> Int` | `clamp low high x`, as in Elm |
| `compare` | `comparable -> comparable -> Order` | |
| `Order` | `LT \| EQ \| GT` | constructors 0, 1, 2 |
| `identity` | `a -> a` | |
| `always` | `a -> b -> a` | |
| `xor` | `Bool -> Bool -> Bool` | |
| `not`, `modBy`, `remainderBy` | | compiler intrinsics, as before |
| `(^)` | `Int -> Int -> Int` | right-associative, binds tighter than `*` (Elm's precedence 8); a negative exponent fails evaluation |

Every binary operator can be used as a function by parenthesizing it, as in Elm:
`List.foldl (+) 0`, `List.map2 (<)`, `List.foldr (::) []`. `(op)` is the function
`\a b -> a op b`. `<<` is left-associative and `>>` right-associative, as in Elm, and
operators of one precedence that associate in opposite directions (`|>` and `<|`,
`<<` and `>>`) cannot be mixed without parentheses.

### 6. Core modules

| Module | Additions |
| --- | --- |
| `Maybe` | `withDefault`, `map`, `map2`, `map3`, `andThen` |
| `Result` | `type Result error value = Ok value \| Err error`; `map`, `map2`, `map3`, `andThen`, `withDefault`, `toMaybe`, `fromMaybe`, `mapError` |
| `Tuple` | `pair`, `first`, `second`, `mapFirst`, `mapSecond`, `mapBoth` |
| `String` | `fromInt`, `isEmpty`, `append`, `concat`, `join` |
| `List` | `singleton`, `repeat`, `range`, `isEmpty`, `head`, `tail`, `take`, `drop`, `member`, `sum`, `product`, `maximum`, `minimum`, `indexedMap`, `filterMap`, `partition`, `concat`, `intersperse`, `map2`, `map3`, `unzip`, `sort`, `sortBy`, `sortWith` |

- **Signatures and edge cases** match Elm:
  - `range 5 1 == []` and `range 3 3 == [ 3 ]`;
  - `take -1 xs == []`, `drop -1 xs == xs` and `repeat -1 x == []`;
  - `head [] == Nothing`, and `maximum` and `minimum` return `Nothing` for `[]`;
  - `partition` returns `( passing, failing )`, both in input order;
  - `Result.map2` and `map3` return the first `Err`;
  - `fromMaybe : x -> Maybe a -> Result x a`;
  - `String.fromInt` writes the decimal form, with `-` for negative numbers.
- **Truncation.** `List.map2`, `List.map3` and zipping after `List.unzip` stop at the
  shortest list, as in Elm. On-chain, check lengths first when the extras matter:
  pairing outputs with expected payouts silently ignores extra outputs.
- **Sorting.** `List.sort`, `sortBy` and `sortWith` are a stable bottom-up merge sort,
  O(n log n). `sort` compares with `<` directly; `sortBy` computes the key at each
  comparison, as Elm does; `sortWith` calls the comparison function on the
  left-hand element first.
- **Strict evaluation**, as in Elm: arguments are evaluated before a call, so
  `Maybe.withDefault default m` always evaluates `default`. On-chain that costs budget,
  and a failing default fails the script. Keep expensive or failing defaults behind a
  `case`.
- **Data encoding** follows declaration order: `Ok` is constructor 0, `Err`
  constructor 1; `LT`, `EQ`, `GT` are 0, 1, 2. This is the reverse of PlutusTx's
  `Either` (`Left` 0, `Right` 1). A datum written as `Either` does not read as
  `Result`: decoding would swap success and failure.

`Bytes.lessThan` and `Bytes.lessThanOrEqual` are removed: `<` and `<=` order bytes.
The test library's `Expect.lessThan`, `atMost`, `atLeast` and `greaterThan` take
`comparable` values, as in elm-test.

### 7. Diagnostics

Each new error names the use and the type, and says what to do:

- a comparison, sort or `++` on a type that doesn't support it names the operator or
  function, the offending part of the type (`Bool`, in `( Int, Bool )`), the types that
  do work, and, for records and custom types, `List.sortBy` and `List.sortWith`;
- a signature that compares or appends values of an unconstrained variable suggests
  renaming it `comparable` or `appendable`; `number` gets its own message;
- an ambiguous name lists the qualified alternatives;
- a reserved module name, alias or built-in constructor name is rejected at its
  declaration;
- a qualified name that a core module lacks lists what the module has; for `Debug.log`,
  `Debug.todo`, `Debug.toString`, `String.length` and `String.fromFloat` the message
  says what to use instead; a core or intrinsic module that isn't imported is named
  with the import to add.

The full Elm-style rendering of all diagnostics is ADR-011.

### 8. Deviations from Elm

| Elm | Elci | Why |
| --- | --- | --- |
| `String` order by UTF-16 code units | by UTF-8 bytes (code points) | Plutus strings are UTF-8. They differ only for a character outside the Basic Multilingual Plane against one from U+E000 to U+FFFF |
| `2 ^ -1 == 0.5` | fails evaluation | no `Float` |
| `x // 0 == 0`, `modBy 0 x` crashes | both fail evaluation | Plutus division by zero |
| `number`, `Float` | `Int` only | no floating point on-chain |
| Bytes and hashes are not comparable | they are | the natural keys on Cardano |
| two imports exposing one name are ambiguous | a named exposure beats a default import | the intent is explicit |
| `Debug.log`, `Debug.todo`, `Debug.toString` | `Debug.trace message value` | traces are the on-chain log |
| `Result` has no fixed encoding | `Ok` 0, `Err` 1 | Data boundary; opposite of `Either` |

## Invariants and evidence required

- **Byte stability.** Existing programs compile to identical script bytes: `Int`
  comparisons are unchanged, and prelude functions are specialized only when used.
- **Tests for every function:** its Elm semantics and edge cases.
- **Budget and size snapshots** for representative calls, compared exactly in
  `src/test/resources/stdlib-budgets.csv`; a deliberate change updates the snapshot.
- **Property tests of the laws**, written in Elci and run by `elci test`:
  - `compare` agrees with `==`, `<`, `<=`, `>` and `>=`, and is antisymmetric, for
    `Int`, `String`, `Bytes`, lists and tuples; `<=` is transitive;
  - `String` order equals the order of `Bytes.fromString`;
  - `List.sort` returns an ordered permutation and is idempotent; `sortBy` is stable
    and agrees with `sortWith`;
  - `min`, `max`, `clamp`, `maximum`, `minimum`, `take`/`drop`, `range`, `(^)`,
    `partition`, `unzip`, `intersperse`, `String.fromInt`, and `map` over `Maybe` and
    `Result` obey their definitions.
- **Diagnostics** for each new error, located at the use.
- **Documentation:** the site's reference, the AI starter pack and the generated
  catalog change in the same PR.

## Alternatives rejected

- **Julc's Java libraries as the standard library.** They have Java names and argument
  orders, need mapping in `elci.toml`, and compare by Data equality. Elm developers and
  agents would keep reaching for names that don't exist.
- **An intrinsic (Java-implemented) prelude.** It would be opaque to users and harder
  to maintain than Elci source that users can read.
- **An `Int`-only `min` and `max`.** A trap: it would be the first thing to change
  later, and it rules out sorting and dictionaries.
- **Typeclasses.** They are outside Elm, and Elm's constrained variable families are
  enough here.
- **Leaving `comparable` unnamed in signatures**, as Elci does for equality. Following
  Elm's rule gives better error messages and documents the requirement in the
  signature.
- **Default imports that silently yield to any explicit import.** Simpler, but an
  `exposing (..)` could replace a prelude function with a different one without a
  word. Elm reports the ambiguity, and so does Elci.
- **Requiring annotations for ambiguous type variables.** Safe but noisy: `[] == []`
  and `Result.withDefault 0 (Ok 5)` would need annotations that Elm doesn't ask for.

## Consequences

Elm code that uses the core library largely reads and behaves the same in Elci.
Generic code can order keys and hashes, which Phase 2's `Dict` needs. Every
compilation parses and type-checks the prelude modules. The cost is small, and
unused functions are never lowered. Twelve module names, four constructor names
and ten type names are reserved. A module whose explicit `exposing (..)` provides a name that the
prelude also provides, such as `max`, must qualify that name where it uses it.
