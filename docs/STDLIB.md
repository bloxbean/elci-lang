# Elci standard library guide

Elci bundles a core library written in Elci, following Elm 0.19's core modules
([ADR-008](../adr/008-native-standard-library-foundation.md)), and can import
supported Julc on-chain Java libraries to fill its gaps. It does not provide the
complete Elm core or the browser package ecosystem.

This inventory was first checked on 20 September 2026 using Julc's
source-description boundary at `d6e716e2` and Elci `c7552e9`, with target
`plutus-v3-pv11-uplc-1.1.0`. It was regenerated on 25 September 2026 against Julc
`5a3378ba`. It is a snapshot of the current implementation.

## What “available” means here

**Compile-verified** means an Elci wrapper with the described concrete signature
successfully type-checks, links the actual Java implementation and generates UPLC.
It does not mean every function has been evaluated with representative arguments
through Elci. Existing Elci evaluation fixtures cover Math, Interval and Contexts;
Julc has its own stdlib tests. Ledger decoding, imported-type capabilities and
function preconditions still apply.

The scan found **151 compile-verified exports**, **41 unsupported signatures**
and **12 generic templates** (`TypedMapLib` and `TypedArrayLib`, instantiated at
each use). No library is
blocked at import. `ByteStringLib`, previously blocked by an overload, now
contributes 24 exports. The six programmatic `ListsLib` exports (`map`, `filter`,
`any`, `all`, `find`, `foldl`) are generic schemes outside this Java-source catalogue.

## Bundled Elci library

The core modules live in `src/main/resources/elci/core/` and are compiled with each
program; functions a program does not use are never lowered. As in Elm, every
project and test module implicitly has:

```elm
import Basics exposing (..)
import List
import Maybe exposing (Maybe(..))
import Result exposing (Result(..))
import String
import Tuple
import Debug
```

| Module | Functions |
| --- | --- |
| `Basics` | `negate`, `abs`, `min`, `max`, `clamp`, `compare` (`Order = LT \| EQ \| GT`), `identity`, `always`, `xor`, and the intrinsics `not`, `modBy`, `remainderBy`; the `^` operator |
| `List` | `singleton`, `repeat`, `range`, `map`, `indexedMap`, `filter`, `filterMap`, `foldl`, `foldr`, `concat`, `concatMap`, `intersperse`, `map2`, `map3`, `length`, `reverse`, `member`, `all`, `any`, `maximum`, `minimum`, `sum`, `product`, `append`, `isEmpty`, `head`, `tail`, `take`, `drop`, `partition`, `unzip`, `sort`, `sortBy`, `sortWith` |
| `Maybe` | `withDefault`, `map`, `map2`, `map3`, `andThen` (`Maybe`, `Just`, `Nothing` are built in) |
| `Result` | `type Result error value = Ok value \| Err error`; `map`, `map2`, `map3`, `andThen`, `withDefault`, `toMaybe`, `fromMaybe`, `mapError` |
| `Tuple` | `pair`, `first`, `second`, `mapFirst`, `mapSecond`, `mapBoth` |
| `String` | `fromInt`, `isEmpty`, `append`, `concat`, `join` |

Names, argument orders and edge cases follow Elm. `min`, `max`, `compare`,
`List.sort`, `List.maximum` and `List.minimum` work on comparable types: `Int`,
`String` (UTF-8 byte order), `Bytes`, the ledger's byte-string hashes, and lists
and tuples of these. `Ok` is Data constructor 0 and `Err` constructor 1, the
reverse of PlutusTx's `Either`.

These functions are generic Elci functions, unlike the concrete `List Data`
signatures of many Java `ListsLib` methods. For example:

```elm
List.map (\value -> value + 1) [ 1, 2, 3 ]
```

`Dict` (`import Dict exposing (Dict)`) is Elm's dictionary, with Elm's whole API:
comparable keys, kept strictly ascending in a Plutus map
([ADR-009](../adr/009-dict-native-value-and-map.md)). `Map` (`import Map`) is the
ledger's association map, for ledger data and keys that are not comparable.
`Array` (`import Array`) provides constant-time indexed arrays in the style of Elm's
`Array`. All three are described in the
[language guide](LANGUAGE.md#compiler-provided-modules). They are Elci source over
Julc's `TypedMapLib` and `TypedArrayLib`, which need no `[java-modules]` entry, and
over `Elci.Assoc`, compiler intrinsics for association lists that only core
modules may import.

`Julc.Data` provides explicit `decode`, `encode`, `fromOptional` and `toOptional`
conversions. `Bytes` wraps byte-string builtins (`fromString`, `length`, `append`,
`slice`, hashes); `<` and `compare` order byte strings. `Debug.trace` emits traces
(see the [language guide](LANGUAGE.md#compiler-provided-modules)). Test modules
additionally see the `Expect`, `Scenario` and `Property` test library
([Testing](TESTING.md)). `Julc.Ledger` exposes ledger types and supported fields
rather than an ordinary Java function library.

Still missing compared with Elm's core: `Set` (use a `Dict k Bool`), `Char`, most
of `String`, `map4`/`map5`, and every `Float` function.

## Julc library availability

Each Java class below is under `org.julclang.stdlib.lib`. Module aliases such as
`Julc.Math` are chosen in project configuration; they are not automatically mapped.

| Java library | Compile-verified exports | Current gaps |
| --- | ---: | --- |
| `AddressLib` | 4 | No rejected source exports in this scan |
| `BitwiseLib` | 9 | `writeBits`: `PlutusData.ListData` mapping |
| `BlsLib` | 0 | All 19 exports require unsupported BLS/native-list types |
| `ByteStringLib` | 24 | No rejected source exports; its identical `integerToByteString` overloads are exported once |
| `ContextsLib` | 23 | `listIndex`: `PlutusData.ListData` mapping |
| `CryptoLib` | 9 | No rejected source exports in this scan |
| `IntervalLib` | 9 | No rejected source exports in this scan |
| `ListsLib` | 17 | Programmatic higher-order exports are not in this catalogue; use Elci `List` |
| `MapLib` | 10 | Concrete Data-based signatures; no generic Elci map API |
| `MathLib` | 8 | `divMod`, `quotRem`: Java `Tuple2` mapping |
| `NativeValueLib` | 0 | All 7 exports require unsupported `JulcValue` |
| `OutputLib` | 14 | No rejected source exports in this scan |
| `ValuesLib` | 24 | 11 exports use unmapped Data subtypes or `AssetEntry` |
| `TypedMapLib` | 9 generic | Templates over typed maps; used by the core `Map` module |
| `TypedArrayLib` | 3 generic | `fromList`, `get`, `length` over PV11 arrays; used by the core `Array` module |

“Zero rejected source exports” is scoped to this compilation scan, not a claim
that every runtime case or every Julc intrinsic is supported.

### Import example

```toml
[java-modules]
"Julc.Math" = "org.julclang.stdlib.lib.MathLib"
"Julc.Interval" = "org.julclang.stdlib.lib.IntervalLib"
```

```elm
import Julc.Math as Math
import Julc.Interval as Interval
```

Use `Math.floorDiv -7 2` or `Interval.finiteLowerBound ctx.txInfo.validRange` in an
appropriately typed declaration. Prefer the prelude's `max`, `min`, `abs` and `^`
over their `MathLib` counterparts; they need no mapping. Library dependencies are resolved transitively.
`finiteLowerBound` and `finiteUpperBound` return `-1` for an infinite bound;
account for that sentinel when using these methods.

### Compile-verified methods by library

**AddressLib**: `credentialHash`, `isPubKeyAddress`, `isScriptAddress`, `paymentCredential`.

**BitwiseLib**: `andByteString`, `complementByteString`, `countSetBits`, `findFirstSetBit`, `orByteString`, `readBit`, `rotateByteString`, `shiftByteString`, `xorByteString`.

**ContextsLib**: `findDatum`, `findOwnInput`, `getContinuingOutputs`, `getRedeemer`, `getSpendingDatum`, `getTxInfo`, `ownHash`, `ownInputScriptHash`, `scriptOutputsAt`, `signedBy`, `trace`, `txInfoFee`, `txInfoId`, `txInfoInputs`, `txInfoMint`, `txInfoOutputs`, `txInfoRedeemers`, `txInfoRefInputs`, `txInfoSignatories`, `txInfoValidRange`, `txInfoWithdrawals`, `valuePaid`, `valueSpent`.

**CryptoLib**: `blake2b_224`, `blake2b_256`, `keccak_256`, `ripemd_160`, `sha2_256`, `sha3_256`, `verifyEcdsaSecp256k1`, `verifyEd25519Signature`, `verifySchnorrSecp256k1`.

**IntervalLib**: `after`, `always`, `before`, `between`, `contains`, `finiteLowerBound`, `finiteUpperBound`, `isEmpty`, `never`.

**ListsLib**: `concat`, `contains`, `containsBytes`, `containsInt`, `drop`, `empty`, `hasDuplicateBytes`, `hasDuplicateInts`, `hasDuplicates`, `head`, `isEmpty`, `length`, `nth`, `prepend`, `reverse`, `tail`, `take`.

**MapLib**: `delete`, `empty`, `fromList`, `insert`, `keys`, `lookup`, `member`, `size`, `toList`, `values`.

**MathLib**: `abs`, `expMod`, `floorDiv`, `floorMod`, `max`, `min`, `pow`, `sign`.

**OutputLib**: `countOutputsAt`, `findInputWithToken`, `findOutputWithToken`, `getInlineDatum`, `lovelacePaidTo`, `outputsAt`, `outputsWithToken`, `paidAtLeast`, `resolveDatum`, `txOutAddress`, `txOutDatum`, `txOutValue`, `uniqueOutputAt`, `valueHasToken`.

**ValuesLib**: `_containsPolicy`, `_countTokensWithQty`, `_findTokenName`, `add`, `adjustOuterForAdd`, `assetOf`, `checkPolicyGeq`, `containsPolicy`, `countTokensWithQty`, `eq`, `extraOuterEntries`, `findTokenName`, `flatten`, `flattenStep`, `geq`, `geqMultiAsset`, `isZero`, `leq`, `lovelaceOf`, `negate`, `refBytes`, `singleton`, `subtract`, `uniqueTokenName`.

### Unsupported source signatures

**BitwiseLib**

- `writeBits`: Unsupported on-chain source type: PlutusData.ListData.

**BlsLib**

- `finalVerify`, `mulMlResult`: Unsupported on-chain source type: JulcMlResult.
- `g1Add`, `g1Compress`, `g1Equal`, `g1HashToGroup`, `g1Neg`, `g1ScalarMul`, `g1Uncompress`, `millerLoop`: Unsupported on-chain source type: JulcG1.
- `g1MultiScalarMul`, `g2MultiScalarMul`: Unsupported on-chain source type: JulcScalars.
- `g2Add`, `g2Compress`, `g2Equal`, `g2HashToGroup`, `g2Neg`, `g2ScalarMul`, `g2Uncompress`: Unsupported on-chain source type: JulcG2.

**ContextsLib**

- `listIndex`: Unsupported on-chain source type: PlutusData.ListData.

**MathLib**

- `divMod`, `quotRem`: Unsupported on-chain source type: Tuple2<BigInteger, BigInteger>.

**NativeValueLib**

- `contains`, `fromData`, `insertCoin`, `lookupCoin`, `scale`, `toData`, `union`: Unsupported on-chain source type: JulcValue.

**ValuesLib**

- `_assetOf`, `_singleton`: Unsupported on-chain source type: PlutusData.BytesData.
- `_countInnerTokensWithQty`, `_findInnerTokenName`, `adjustInnerForAdd`, `extraInnerEntries`, `findTokenAmount`, `flattenPolicy`, `isTokenMapZero`, `negateTokenMap`: Unsupported on-chain source type: PlutusData.MapData.
- `flattenTyped`: Unsupported on-chain source type: AssetEntry.


## Representation and usability limits

- Nominal hashes and newtypes stay distinct from raw bytes or integers.
- Imported records and sums use exactly the operations their provider approves:
  constructors, patterns, field access, equality, codecs and named-validator
  boundaries. They are not interchangeable with similarly shaped Elci records.
- Java Optional is distinct from Elci Maybe. Convert a result such as
  `ContextsLib.findOwnInput` with `Julc.Data.fromOptional`.
- `Data` is opaque until converted with `Julc.Data.decode`. Not every Data subtype
  has a mapping. Map/native-value representations are not interchangeable.
- Generic Java methods are specialized per use, and `ListsLib`'s programmatic
  `map`, `filter`, `any`, `all`, `find` and `foldl` are available (see
  [Java libraries](JAVA_LIBRARIES.md#generic-methods-and-programmatic-exports)).
  The only generic bundled Java libraries are `TypedMapLib` and `TypedArrayLib`,
  which back the core `Map` and `Array` modules. Missing capabilities must be
  added generically, rather than by hardcoding library method names in Elci.

See [Java libraries](JAVA_LIBRARIES.md) for custom source JARs and the provider
boundary. The [exact inventory](stdlib-compatibility.tsv) records every described
export's signature or failure reason, including public implementation helpers.
Such helpers are listed for completeness, not as stable recommended application APIs.

## Writing a library in Elci

Yes: create ordinary source modules and expose the public functions/types.
No Java annotation, JAR, compiler plugin or separate library entrypoint is needed.

`lib/Finance/Pricing.elm`:

```elm
module Finance.Pricing exposing (totalWithFee)


totalWithFee : Int -> List Int -> Int
totalWithFee fee prices =
    subtotal prices + fee


subtotal : List Int -> Int
subtotal prices =
    List.foldl (\price total -> price + total) 0 prices
```

`subtotal` is private. In `src/Main.elm`:

```elm
module Main exposing (main)

import Finance.Pricing as Pricing


main : Int
main =
    Pricing.totalWithFee 5 [ 40, 60 ]
```

Include both source roots in the application's configuration:

```toml
[project]
source-directories = ["src", "lib"]
# Also supply the normal target and julc-version keys.
```

The application's entrypoint is still configured normally; a reusable module
needs no entrypoint of its own. Modules may contain generic functions, aliases,
custom types and other imports within the supported Elci language profile.
Use `exposing (TypeName(..))` to export a custom type and its constructors.
Module paths must match names, imports must be acyclic, and duplicate module
ownership is rejected. The core module names (`Basics`, `List`, `Maybe`, `Result`,
`String`, `Tuple`, `Map`, `Dict`, `Array`) and `Set`, `Value`, `Elci` and names
starting with `Elci.` are reserved.

Run the [complete example](../examples/elci-library/README.md):

```sh
build/install/elci/bin/elci eval examples/elci-library
build/native/elci eval examples/elci-library
```

Both commands evaluate to `105`. Share Elci libraries as source directories or
Git checkouts and include their source root in `source-directories`. There is
currently no Elci package registry, dependency/version resolver, Elm package
compatibility or separately compiled Elci library format. Elci library sources
are compiled with the consuming application.

## Reproducing the Java export inventory

From the repository root, with Java 25 and the pinned Julc dependencies:

```sh
./gradlew installDist
java -cp 'build/install/elci/lib/*' scripts/StdlibInventory.java > build/stdlib-inventory.tsv
diff -u docs/stdlib-compatibility.tsv build/stdlib-inventory.tsv
```

The script scans bundled production Java libraries, asks the provider for source
signatures and unsupported-export reasons, then compiles an explicitly typed Elci
wrapper for each available export. It reports failures rather than hiding them.
Review inventory changes when upgrading Julc or changing Elci interoperability.
It is a compilation inventory, not an evaluator test of every method's semantics.
