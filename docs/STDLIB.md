# Elci standard library guide

Elci has a small bundled library written in Elci and can import supported Julc
on-chain Java libraries. It does not provide the complete Elm core/browser
package ecosystem.

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

`Map` (`import Map`) provides typed association maps in the style of Elm's `Dict`.
`Array` (`import Array`) provides constant-time indexed arrays in the style of Elm's
`Array`. Both are described in the
[language guide](LANGUAGE.md#compiler-provided-modules). They are Elci source over
Julc's `TypedMapLib` and `TypedArrayLib`, which need no `[java-modules]` entry.

`List` is available as a qualified module without configuration:

| Functions | Purpose |
| --- | --- |
| `map`, `filter`, `concatMap` | Transform or select list elements |
| `foldl`, `foldr` | Accumulate values; callbacks receive element then accumulator |
| `all`, `any` | Predicate checks |
| `length`, `reverse`, `append` | Basic list operations |

These functions are generic Elci functions, unlike the concrete `List Data`
signatures of many Java `ListsLib` methods. For example:

```elm
List.map (\value -> value + 1) [ 1, 2, 3 ]
```

`not` and `remainderBy` are compiler-provided functions. `Julc.Data` provides
explicit `decode`, `encode`, `fromOptional` and `toOptional` conversions. `Bytes`
wraps byte-string builtins (`fromString`, `length`, `append`, `slice`, hashes).
`Debug.trace` emits traces (see the
[language guide](LANGUAGE.md#compiler-provided-modules)). Test modules
additionally see the `Expect` and `Scenario` test library ([Testing](TESTING.md)). `Maybe` with `Just` and
`Nothing` is a built-in type; a complete Elm-style `Maybe` module is not bundled.
`Julc.Ledger` exposes ledger types and supported fields rather than an ordinary
Java function library.

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

Use `Math.max 5 9` or `Interval.finiteLowerBound ctx.txInfo.validRange` in an
appropriately typed declaration. Library dependencies are resolved transitively.
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
  No bundled stdlib method is generic today. Missing capabilities must be added
  generically, rather than by hardcoding library method names in Elci.

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
ownership is rejected. `List` is reserved for the bundled core module.

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
