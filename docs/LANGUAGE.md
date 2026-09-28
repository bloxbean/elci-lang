# Elci language guide

## Implemented profile

Elci uses an implemented functional-core subset of Elm syntax and its own
on-chain semantics. The core is complete for the profile below; full Elm, the
generic Java interoperability ADR, and the wider ledger marketplace surface are
separate milestones. [GRAMMAR.md](GRAMMAR.md) defines the syntax exactly.

| Feature | Current support |
| --- | --- |
| Modules, imports, aliases, exposing | Source modules and configured Java modules; Elm's default imports and name precedence; import cycles rejected |
| Functions, lambdas, partial application | Strict, curried application and immediate argument capture |
| Top-level generics | Rank-1 inference, signature checking, concrete specialization |
| Local declarations | Dependency SCCs, conservative value-restricted rank-1 polymorphism, strict initialization |
| Recursion | Self-recursion and finite function-only mutual groups; polymorphic recursion rejected |
| Basic types | Arbitrary-precision Int, Bool, String, unit |
| Core library | Elm's `Basics` prelude and `List`, `Maybe`, `Result`, `Tuple`, `String` modules, written in Elci ([ADR-008](../adr/008-native-standard-library-foundation.md)) |
| Lists | Homogeneous Data-backed lists, list patterns, generic core functions |
| Tuples | Pairs/triples, nested patterns and parameter destructuring; tag-zero encoding |
| Records | Closed structural records, generic aliases and constructors, destructuring, field access, Elm accessor functions (`.field`) and updates |
| Custom types | Generic and regular recursive unions; declaration-order tags |
| Patterns | Nested constructors/lists/tuples/records, aliases, unit and Bool/Int/String literals |
| Pattern coverage | Nested exhaustiveness and usefulness analysis; duplicate/unreachable branches rejected |
| Equality and ordering | Structural equality for eligible primitives, lists, tuples, records, unions, `Dict` and `Value`; `comparable` ordering for `Int`, `String`, bytes, ledger hashes, lists and tuples |
| Ledger access | Typed record views, transaction fields, nominal hash types, approved constructors/patterns/equality of ledger sums and records |
| Data conversions | `Julc.Data.decode`/`encode` strict codecs and Julc optional ↔ `Maybe` adapters |
| Dictionaries and maps | Elm's `Dict k v`, stored as a Plutus map with ascending keys ([ADR-009](../adr/009-dict-native-value-and-map.md)), and the ledger's `Map k v` |
| Values | The ledger's multi-asset `Value` as a core type and module, always canonical, on the CIP-153 value builtins ([ADR-009](../adr/009-dict-native-value-and-map.md)) |
| Bytes | Complete byte-string builtins in `Bytes` |
| Arrays | `Array a` with O(1) `get` and `length` (PV11 arrays, Elm `Array`-style) |
| Java libraries | Concrete exports, generic Java templates specialized per use, and Julc's programmatic list exports |
| Validators | Named `[validators.<name>]` groups with spend, mint, withdraw, certify, vote and propose handlers, deployment parameters and required/optional/absent spending datums; `[entrypoint]` functions for `eval` |
| CLI | Check/build/function evaluation; `--validator` selection; `apply` parameters; `test`; pinned target/version; ABI comparison |
| Testing | `elci test`: `Bool`/`Expect` unit tests, transaction scenarios on the deployed scripts and property tests with shrinking ([Testing](TESTING.md)) |
| Native image | GraalVM executable with bundled source and reflection resources |

Intentionally outside this profile: row polymorphism and extensible records,
polymorphic recursion, higher-rank and unrestricted let-polymorphism, Float and
`number`, ports, browser effects and user-defined typeclasses. Complete ledger-wide contract
adapters and a language server remain future integration work. Java wildcard exposing is currently rejected; use
explicit exported names.

## Functions and types

```elm
identity : a -> a
identity value =
    value


apply : (a -> b) -> a -> b
apply transform value =
    transform value
```

Every top-level definition needs a signature. Lowercase type variables are
universally quantified at eligible top-level functions. A signature cannot claim
`a -> a` for an implementation that only operates on integers. Generic helpers
are specialized for their concrete uses. Entrypoints cannot retain type variables.

Application is curried and strict. `f expressionA expressionB` evaluates the
function, then `expressionA`, then `expressionB`. A partial application evaluates
and captures its supplied argument immediately. It does not defer a failure until
the missing argument arrives.

Lambdas use `\argument -> expression`. Multiple arguments are supported. A
function can call itself or any function declared later. Finite mutually
recursive function groups may contain different arities and return types. Eager
value cycles are errors, and recursive calls must remain monomorphic.

Eligible local non-expansive bindings are rank-1 polymorphic. Function
declarations, lambdas and immutable values can therefore be used at more than one
type. Calls, conditionals, matches and record updates remain monomorphic so
specialization cannot duplicate a strict computation. Compiler-known equality
requirements follow a generic helper and are checked at each concrete use.
Ordering and append requirements follow Elm's naming rule: a signature variable
compared with `<` must be named `comparable…`, one appended with `++`
`appendable…`, and `compappend…` allows both; `number` is rejected. Type variables
that nothing determines, as in `[] == []`, are accepted as in Elm; only
`Julc.Data.decode` and generic Java exports require the type to be known.

## Arithmetic and control flow

`Int` is arbitrary precision. `+`, `-`, and `*` are exact; `//` truncates toward
zero; `^` is integer power (right-associative, precedence 8), and a negative
exponent fails evaluation. `remainderBy divisor value` uses the dividend's sign and `modBy divisor value`
the divisor's, as in Elm. Division, remainder and modulus by zero fail evaluation. `/` and Float literals are rejected.

`if`, `&&`, `||`, and `case` evaluate only the selected branch. Local values are
strict even when unused: a failing or diverging unused binding cannot be removed.
Forward local references are evaluated in dependency order; source order breaks
independent ties.

`==` and `/=` support Int, Bool, String, unit, raw bytes, matching nominal
byte-backed ledger hashes, lists, tuples, closed records and regular recursive
unions whose reachable fields are themselves equality-capable. Both operands are
evaluated once from left to right. Two `Dict` values are equal when they have
the same bindings, and two `Value`s when they hold the same quantities: every
`Value` is canonical, so `==` compares encodings with `EqualsData`. `==` on
`Data` compares structure with `EqualsData`
([ADR-010](../adr/010-transaction-and-interval-helpers.md)): the standard datum
check is `Tx.inlineDatum out == Just (Data.encode expected)`. Elci's encodings are
deterministic per type, except that a `Map` encodes in insertion order: two maps
with the same bindings may encode differently, so compare their sorted `toList` or
use a `Dict`. Functions,
`Map` and imported values without verified equality, such as `TxOut`, are
rejected at compile time. `<`, `<=`, `>`, `>=` and
`compare` order comparable values: `Int` numerically, `String` by UTF-8 bytes,
`Bytes` and byte-string ledger hashes lexicographically (`lessThanByteString`),
lists lexicographically and tuples by component. Comparisons cannot be chained
without parentheses or Boolean operators.

`|>` and `<|` apply functions; `>>` and `<<` compose them. Composition captures
its function operands strictly. `>>` groups to the right and `<<` to the left, as in
Elm, and operators of one precedence that group in opposite directions cannot be
mixed without parentheses. `++` appends lists or strings; `::` prepends a list
element. Every binary operator can be used as a function: `(+)`, `(::)`, `(<)`;
`(/)` is rejected.

As in Elm 0.19, a `-` directly before its operand negates it when it follows
whitespace or an opening bracket. `f -1` therefore passes `-1` to `f`, and
`scale -x 10` passes `-x`. `a - 1` and `a-1` subtract. This matches elm-format,
which writes `f (-1)` as `f -1`.

## Lists and recursion

`List` is imported by default. It has Elm's list functions except `map4` and
`map5`: `map`, `filter`, `foldl`, `head`, `range`, `sum`, `member`, `sort`,
`sortBy` and the rest ([standard library](STDLIB.md#bundled-elci-library)).

```elm
sumPositive : List Int -> Int
sumPositive values =
    values
        |> List.filter (\value -> value > 0)
        |> List.foldl (\value total -> value + total) 0
```

These core helpers are Elci source, compiled through the same frontend. Their
implementation is visible in `src/main/resources/elci/core/List.elm`.

```elm
sum : List Int -> Int
sum values =
    case values of
        [] ->
            0

        first :: rest ->
            first + sum rest
```

Lists use Julc's native `List<Data>` representation internally, with explicit
encoding/decoding of each element. Serialized lists use `ListData`. The compiler
does not pretend native integer lists and Data lists are interchangeable.

## Records and tuples

```elm
type alias Listing owner =
    { price : Int, active : Bool, owner : owner }


increase : Listing owner -> Listing owner
increase listing =
    { listing | price = listing.price + 1 }
```

Aliases reuse structural record types and may have type parameters. A record alias
also introduces a constructor whose curried arguments follow declaration order.
Source field order does not determine wire order: serialized records use tag zero
and fields sorted lexicographically by name. Constructor and field expressions
still evaluate in source order. Updates evaluate the base once, then replacements
in source order.

A record field access needs enough context to determine a closed record type.
As in Elm, `.amount` is the accessor function `\r -> r.amount`, as in
`List.map .amount payouts`; like a field access, it needs a closed record type,
because row-polymorphic accessors are not inferred. A field of a qualified value
reads as in Elm: `Config.settings.amount`. A qualified name is written without
spaces and ends at its first lowercase segment. Generic fields containing
functions or unit are not yet supported in serialized containers.

Pairs and triples use `( first, second )` and `( first, second, third )`. Tuple and
record patterns may destructure function/lambda parameters and local bindings
when every component is irrefutable. Their encoding is tag zero with fields in
positional order, as recorded in the ABI; this is an Elci tuple codec, not a
promise of representation equivalence with arbitrary Java tuple types.

## Custom types and matching

```elm
type Tree
    = Leaf Int
    | Branch Tree Tree
```

Constructors are curried functions. The first constructor has tag zero, the
second tag one, and fields preserve argument order. Recursive references retain a
stable nominal identity. `Maybe a`, `Nothing`, and `Just` are built in, with
`Nothing` tag zero and `Just` tag one. This differs from Julc Java Optional's tags;
they are not silently interchangeable. The core `Result` type has `Ok` tag zero and
`Err` tag one, the reverse of PlutusTx's `Either`. Modules cannot declare the
constructors `Just`, `Nothing`, `True` or `False`, or types named after the
built-in types (`Int`, `Bool`, `String`, `Bytes`, `Data`, `List`, `Maybe`, `Dict`,
`Map`, `Array`, `Float`).

Cases must be exhaustive. Patterns can nest constructors, exact-length lists,
cons, tuples, closed-record field selections, aliases, unit, and Bool, Int or
String literals. Refutable forms belong in `case`; function parameters and local
destructuring accept only irrefutable tuple/record forms. Scrutinees and local
destructuring right-hand sides evaluate once. Duplicate binders and unreachable
branches are errors.

## Modules and Java imports

A source module `Market.Pricing` belongs at `src/Market/Pricing.elm` under a
configured source directory. Imports can use `as` and explicit `exposing`. Only
exposed source names are importable, whether exposed unqualified or referenced
through a qualifier such as `Pricing.fee`. A qualified name must belong to a module
that the current module imports. Module cycles and duplicate ownership are errors.

Every module has Elm's default imports: `Basics exposing (..)`, `List`,
`Maybe exposing (Maybe(..))`, `Result exposing (Result(..))`, `String`, `Tuple`
and `Debug`. A module's own declarations shadow imports. Two imports that expose different
things under one name make it ambiguous where it is used (`ELCI2102`), except that
a name listed explicitly in `exposing` takes precedence over a default import. An
import cannot be aliased to a default module's name. The core module names
(`Basics`, `List`, `Maybe`, `Result`, `String`, `Tuple`, `Map`, `Dict`, `Array`,
`Value`, `Interval`, `Tx`) and the reserved names `Set` and `Elci` cannot name a project module or a
`[java-modules]` key, and neither can a name that starts with `Elci.`. The core
library's internal `Elci.Assoc` and `Elci.Value` modules cannot be imported by
project modules.

Configure a Java library mapping in `elci.toml`, then write normal Elm imports:

```elm
import Julc.Math as Math exposing (floorDiv)
```

`Math.floorDiv` and `floorDiv` resolve to the configured class's public on-chain
export.
The provider parses and compiles its real Java source into PIR. It does not run
that Java function on the JVM to fake an on-chain result. Ambiguous Java overloads
are rejected. Generic Java methods are imported as generic functions and compiled
for each concrete use. Unmapped result or parameter types are never exported as
erased Data. See [Java libraries](JAVA_LIBRARIES.md#generic-methods-and-programmatic-exports).

## Ledger context and boundaries

```elm
import Julc.Ledger exposing (PubKeyHash, ScriptContext)


validate : PubKeyHash -> Int -> ScriptContext -> Bool
validate owner minimumFee ctx =
    ctx.txInfo.fee >= minimumFee
        && List.any (\signer -> signer == owner) ctx.txInfo.signatories
```

The imported ledger model retains nominal type identity. `PubKeyHash` and raw
bytes do not unify even though their runtime representations are byte strings.
The `.hash` accessor explicitly exposes the underlying bytes where available.
Ledger hash lengths follow Julc's existing model; no new length invariant is
invented by Elci.

### Imported types

What Elci may do with an imported type (ledger or Java) is decided by the
operations its provider approves, never by its shape.

- **Constructors and patterns.** A sum's constructors are available qualified,
  such as `Ledger.PubKeyCredential`, or unqualified after `exposing (Credential(..))`.
  They can build values (CONSTRUCT) and appear in exhaustive `case` patterns
  (MATCH). A record or newtype constructor is named after the type:
  `Ledger.TxOutRef txId 0`, `Ledger.ScriptHash key.hash`, or `Pricing.Quote 5 True`
  for Java records, whose fields are in declaration order.
  `import Julc.Ledger exposing (..)` exposes types only; expose constructors
  explicitly.
- **Fields.** Approved record fields (PROJECT) are read with `.field`.
- **Equality.** `==` uses the provider's approved equality (EQUALS), with Java
  `equals` semantics.
- **Boundaries.** Validators accept imported datum, redeemer and parameter types
  that approve BOUNDARY, such as `TxOutRef`, `Credential` or `PubKeyHash`.

```elm
import Julc.Ledger as Ledger exposing (Credential(..), ScriptContext, TxOutRef)


{-| A one-shot minting policy parameterized by the output it must spend. -}
mint : TxOutRef -> Credential -> ScriptContext -> Bool
mint seed credential ctx =
    List.any (\input -> input.outRef == seed) ctx.txInfo.inputs
        && (case credential of
                PubKeyCredential _ ->
                    True

                ScriptCredential _ ->
                    False
           )
```

The ledger's `Value` is the core `Value` type, with its own module and equality
(below). Types that contain it (`TxOut`, `TxInfo`, `TxInInfo`, `ScriptContext`)
keep field access and matching, but have no equality, strict decoding or boundary
use. No Java library may hand Elci a `Value`, because Java libraries such as
`ValuesLib` do not keep values canonical: an export whose result can hold one,
directly or in a record or union field, is `ELCI2105`, and a generic
instantiated at a type that holds one is `ELCI3003`. Julc's `ContextsLib` and
`OutputLib` are exempt, since they only pass along the ledger's own values.
Complex ledger-wide contract patterns are not yet a complete supported surface.

### Compiler-provided modules

The default imports (`Basics`, `List`, `Maybe`, `Result`, `String`, `Tuple` and
`Debug`) are listed in the [standard library guide](STDLIB.md#bundled-elci-library).
These modules are imported explicitly:

| Module | Functions |
| --- | --- |
| `Dict` | `empty`, `singleton`, `insert`, `update`, `remove`, `isEmpty`, `member`, `get`, `size`, `keys`, `values`, `toList`, `fromList`, `map`, `foldl`, `foldr`, `filter`, `partition`, `union`, `intersect`, `diff`, `merge` |
| `Map` | `empty`, `singleton`, `get`, `member`, `insert`, `remove`, `update`, `size`, `isEmpty`, `keys`, `values`, `toList`, `fromList`, `foldl`, `foldr`, `filter`, `map` |
| `Array` | `empty`, `fromList`, `toList`, `toIndexedList`, `length`, `isEmpty`, `get`, `initialize`, `repeat`, `map`, `indexedMap`, `foldl`, `foldr`, `filter`, `slice` |
| `Value` | `zero`, `lovelace`, `singleton`, `insert`, `fromList`, `lovelaceOf`, `quantityOf`, `isZero`, `policies`, `tokens`, `toList`, `add`, `subtract`, `negate`, `scale`, `sum`, `geq`, `leq`, `withoutLovelace`, `restrictedTo`, `positive`, `negative` |
| `Interval` | `isEntirelyAfter`, `isEntirelyBefore`, `contains`, `start`, `end`, `after`, `before`, `between`, `everything` |
| `Tx` | `signedBy`, `spends`, `findInput`, `ownRef`, `ownInput`, `ownPolicy`, `outputsTo`, `valuePaidTo`, `continuingOutputs`, `valueSpent`, `valueProduced`, `mintedOf`, `inlineDatum`, `datumOf` |
| `Julc.Data` | `decode`, `encode`, `fromOptional`, `toOptional` (below) |
| `Bytes` | see the table below |

The types `Dict`, `Map` and `Array` are always in scope. As in Elm, you may still
write `import Dict exposing (Dict)` or `Dict.Dict`, and likewise for `Map` and
`Array`. `Bytes.Bytes` and `Data.Data` (after `import Julc.Data as Data`) name the
compiler's own types the same way. `Value` is named through its module:
`import Value exposing (Value)`, `Value.Value`, or `Julc.Ledger exposing (Value)`,
and `Interval` likewise.

**`Dict k v`** is Elm's dictionary, with Elm's names, argument orders and
results. It is a Plutus map whose keys are strictly ascending in the `comparable`
order, so each dictionary has one representation and `==` compares bindings.
`keys`, `values`, `toList` and the folds follow ascending key order; `fromList`
keeps the last binding of a repeated key; `union` prefers the left dictionary.

- **Costs:** `get`, `member`, `insert`, `remove` and `update` stop at the first
  key that is not smaller, at about 1.1M CPU per key passed. `fromList` is a
  stable merge sort of the input's ascending runs: O(n log n), and O(n) for sorted
  input. The other operations are single passes or merges.
- **Data:** a `Dict` encodes as a Plutus map with ascending keys. Decoding one,
  at any depth, requires strictly ascending keys and fails otherwise: in
  `Julc.Data.decode`, in validator datums, redeemers and parameters (checked
  around the handler on every run), and in property-test arguments, which are
  generated sorted. `elci apply` rejects a parameter whose `Dict` keys are
  unsorted or repeated. Without the check, a datum with an out-of-order key could
  make `member` answer wrongly. The check reaches a `Dict` inside records,
  tuples, unions, `Maybe`, `Result`, Julc optionals, lists and map values. It
  needs comparable keys, so a decoded `Dict` with other keys is `ELCI2003`, and a
  Java library generic instantiated at a type containing a `Dict` is `ELCI3003`,
  since the library could build one without the check.
- **Implementation:** the module is Elci source over `Elci.Assoc`, compiler
  intrinsics that walk the pair list and compare keys at their type.

**`Map k v`** is the ledger's association list: `Map.get key map : Maybe v`, and
`Map.insert key value map` replaces an existing binding and puts it first. Keys
are compared by their Data encoding, so they may be any Data-encodable type,
including Elci records and unions and ledger types such as `Credential`; `keys`,
`values` and `toList` follow map order. The ledger's own maps
(`txInfo.withdrawals`, `txInfo.redeemers`) are `Map`s. Lookups, `insert` and
`remove` are O(n), at about 1.1M CPU per key passed; `map`, `filter`, `toList`
and the folds are single passes; `fromList` is O(n²). Maps built in Elci never
repeat a key; a map decoded from Data may, and then `get` returns the first
binding, `remove` and `insert` remove every binding of the key, and `map` and
`filter` keep every binding. The module uses Julc's
`TypedMapLib` templates for `empty`, `size`, `isEmpty`, `keys` and `values`, and
`Elci.Assoc` for the rest, where `Int`, byte-string and `String` keys compare
with their own builtins instead of `EqualsData`.

**`Array a`** works like Elm's `Array`: `Array.get index array : Maybe a` is
`Nothing` outside the array, and `slice` counts negative indexes from the end. An
array is a Plutus V3 array (CIP-138). `fromList` builds it in O(n), and `get` and
`length` are O(1).

- **Operations:** the whole-array operations (`map`, `filter`, `foldl` and the
  others) are O(n). There is no `set` or `push`: build a list and convert it once.
- **Data:** an array has no Data encoding. It cannot be a datum, redeemer,
  parameter or property argument, nor a field of a record, union, tuple, list or
  map. Keep a `List` in data and call `Array.fromList` where many indexed reads
  follow.
- **Implementation:** the module is Elci source over Julc's `TypedArrayLib`, so it
  shares the `JulcArray` lowering that Java validators use.

**`Value`** is the ledger's multi-asset value, the type of `TxOut.value` and
`TxInfo.mint`. It stays represented as the ledger's Data map, and every value is
canonical: policies, then token names, ascend by unsigned bytes, and no quantity
is zero. The ledger's values are canonical, the module's functions produce
canonical values through the builtins, and decoding checks the form with
`UnValueData`, so `==` is `EqualsData` and `Julc.Data.encode` is free. Token ids
are `PolicyId` and `TokenName` from `Julc.Ledger`; ada is the empty token name
under the empty policy, and `lovelaceOf` counts only that token.

- **Operations:** `lovelaceOf` and `isZero` read the start of the map; `policies`,
  `tokens` and `toList` walk it; `positive`, `negative`, `restrictedTo` and
  `withoutLovelace` filter it, keeping it canonical; the others convert to the
  native value (`UnValueData`, about 1.1M CPU for three assets) and back
  (`ValueData`). `geq a b`
  subtracts and checks that no quantity is negative, so it is total with the
  negative quantities of a mint that burns; `positive` and `negative` split such a
  mint. Arithmetic fails only on 128-bit overflow, and ids longer than 32 bytes
  fail.
- **Data:** a `Value` itself encodes for free and decodes only in canonical form:
  `UnValueData` rejects unsorted or repeated keys, zero quantities, empty token
  maps and keys longer than 32 bytes.
  It cannot be a datum, redeemer or parameter yet: the strict boundary has no codec
  for its layout. Store `List ( PolicyId, TokenName, Int )` and rebuild with
  `Value.fromList`. A type that contains a `Value` cannot be decoded.
- **Tests:** scenario outputs, inputs and mints are built canonical, and property
  arguments of type `Value` are generated canonical.
- **Patterns:** a `Value` has no constructors or patterns; `import Julc.Ledger
  exposing (Value(..))` is `ELCI2104`.
- **Implementation:** the module is Elci source over `Elci.Value`, core-only
  intrinsics that emit the CIP-153 builtins and walk the Data map; the native
  value type never escapes them.

**`Interval`** reads the ledger's `Interval`, the type of `txInfo.validRange`
([ADR-010](../adr/010-transaction-and-interval-helpers.md)). Times are POSIX
milliseconds. `isEntirelyAfter t range` holds when the range has a finite start
and every time in it is after `t`; `isEntirelyBefore t range` when it has a finite
end and every time in it is before `t`. Both are strict and honor inclusive and
exclusive bounds; an infinite bound on their side never satisfies them. A
`PosInf` start or `NegInf` end, which only an empty range has, fails the check
that reads it, as in Aiken's stdlib v2; Aiken v3 lets such an empty range pass
both checks. `contains t range` tests one time. `start` and
`end` return the finite bounds as `Maybe Int`, with no sentinel. `after`, `before`,
`between` and `everything` build inclusive ranges. Each check costs about 3M CPU.

**`Tx`** answers questions about a `ScriptContext`, which it takes last:
`signedBy`, `spends`, `findInput`, `ownRef` and `ownInput` (spending only),
`ownPolicy` (minting only), `outputsTo` and `valuePaidTo` (matching the payment
credential; whoever builds the transaction chooses the payee's staking part),
`continuingOutputs` (outputs to
the spent output's whole address), `valueSpent`, `valueProduced`, `mintedOf`
(`Dict TokenName Int`, burns negative), `inlineDatum` and `datumOf` (inline, or by
hash from `txInfo.datums`). Datums stay `Data`: decode with an annotated
`Data.decode` or compare with `==`. The functions walk the context's lists, about
0.7M CPU per signatory, 2.5M per input and 2.8M per output passed. An untagged
payment check is open to double satisfaction; the reference contracts under
`examples/` tag payments with the spent output's reference.

**`Bytes`** functions are single Plutus builtins, with the byte string last so they
compose in pipelines:

| Group | Functions |
| --- | --- |
| Building | `empty`, `fromString` (UTF-8), `toString`, `cons byte`, `replicate count byte`, `append` |
| Reading | `length`, `at index`, `slice start length`, `take n`, `drop n` |
| Comparing | the operators `==`, `<`, `<=`, `>`, `>=` and the prelude's `compare`, `min`, `max` |
| Integers | `fromIntBigEndian width n`, `fromIntLittleEndian width n` (width `0` is minimal), `toIntBigEndian`, `toIntLittleEndian` |
| Bits | `and`, `or`, `xor` (truncating to the shorter operand), `complement`, `shift n`, `rotate n`, `testBit i`, `countSetBits`, `findFirstSetBit` |
| Hashes | `blake2b224`, `blake2b256`, `sha256`, `sha3_256`, `keccak256`, `ripemd160` |

Token names can be written as `Bytes.fromString "GOLD"`. `Bytes.toString` fails on
invalid UTF-8. Composite helpers such as `toHex`, `intToDecimalString` and
`utf8ToInteger` come from Julc's `ByteStringLib`: map
`"Julc.ByteString" = "org.julclang.stdlib.lib.ByteStringLib"` and
`import Julc.ByteString as ByteString`. `Debug.trace : String -> a -> a`, imported
by default, emits a Plutus trace and returns its second argument; it costs budget
like any trace.

### Explicit Data conversions

`Julc.Data` is a compiler-provided module of explicit conversions:

| Function | Type | Behavior |
| --- | --- | --- |
| `decode` | `Data -> a` | Julc's strict decoder for `a`; malformed Data fails the script |
| `encode` | `a -> Data` | The Data encoding of `a` |
| `fromOptional` | `JulcOptional a -> Maybe a` | Re-tags a Julc optional (such as a library result) as `Maybe` |
| `toOptional` | `Maybe a -> JulcOptional a` | The reverse adapter |

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

The converted type must be concrete where the conversion is used: annotate it
when inference cannot tell. `decode` and `encode` work for primitives, Elci
records and unions, lists, dictionaries, maps and optionals of these, a `Value`,
and imported types approving DECODE_STRICT or ENCODE. Types without a checkable
layout, such as `TxOut`, and types containing a `Value` cannot be decoded
(`ELCI3003`). Decoding never becomes a
`Maybe` on malformed input; a failed decode fails the transaction.

Strict boundary checks validate nested datum/redeemer kinds, constructor tags,
arity and fields, even when the handler ignores those values. The ledger context
uses Julc's existing ledger-supplied-context convention; it is not a new universal
decoder that certifies arbitrary off-chain context data.

## Validators and handler roles

Configuration, not source syntax, decides how a function is deployed. A validator
is a `[validators.<name>]` table. Each table is **one script** with one hash. Its
handler keys name exposed functions as `Module.function`. The ledger purpose selects
exactly one handler; purposes without a handler fail.

| Handler key | Required source type |
| --- | --- |
| `spend` | `parameters -> datum -> redeemer -> ScriptContext -> Bool` |
| `spend` with `spend-datum = "optional"` | `parameters -> Maybe datum -> redeemer -> ScriptContext -> Bool` |
| `spend` with `spend-datum = "absent"` | `parameters -> redeemer -> ScriptContext -> Bool` |
| `mint`, `withdraw`, `certify`, `vote`, `propose` | `parameters -> redeemer -> ScriptContext -> Bool` |

`parameters = ["owner", "limit"]` declares deployment parameters in application
order. Every handler of the group receives all of them as leading arguments, and
their types must agree across handlers. Parameter, datum and redeemer types must
have a strict Data boundary: the same rules as datums and redeemers below. Each
parameter is decoded once when the script is applied. As with Julc Java `@Param`
fields, parameters are decoded but not validated on-chain, except that the key
order of a `Dict` inside them is checked on every run.

A spending datum is `required` by default: a missing datum fails. With `optional`,
the handler receives `Just datum` or `Nothing`. The present datum is still strictly
checked. Elci converts Julc's optional value to Elci's `Maybe`, whose tags differ,
before calling the handler. With `absent`, the datum is never inspected.

Only the selected handler's datum and redeemer are decoded, so handlers may use
different redeemer types. Top-level values reachable from any handler are
program-level definitions. They are evaluated once per script execution, before
dispatch. A failing top-level value therefore fails every purpose; keep
purpose-specific work inside the handler. Library imports are linked the same way:
functions once per script, and zero-argument library values, such as `Map.empty`,
evaluated once at script start like top-level values.

Adding, removing or changing a handler changes the group's script hash. Validators
always check the purpose before calling a handler, even with one handler, so a
purpose without a handler is rejected. The check costs roughly 1.1M CPU and 35
bytes per script.

An `[entrypoint]` with `purpose = "function"` is not a validator. It names one
concrete function or value for `elci eval`. `[entrypoint]` spending and minting
validators were removed in the development preview
([ADR-007](../adr/007-named-validators-only.md)).

## Costs and limits

Recursion expresses unbounded computations, but ledger execution is budgeted.
List functions are ordinary traversals; nested scans can be quadratic. Use the
VM and inspect CPU/memory budgets. `BudgetTest` writes representative traversal
measurements to `build/reports/elci/budgets.csv`.

Compilation has finite specialization and recursive-type expansion guards.
Unsupported growth is a compile error, not implicit type erasure. Compiler size
and resource limits will need further hardening before accepting untrusted source
as a hosted service.
