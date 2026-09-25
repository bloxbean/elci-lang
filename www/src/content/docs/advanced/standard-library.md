---
title: Standard library
description: What ships with Elci — the core modules and Julc's verified Java libraries — how to import them, and the most useful functions of each.
sidebar:
  order: 2
---

Elci's standard library has two layers: a few modules built into the compiler, and Julc's on-chain Java
libraries, which you map in `elci.toml`. This page shows what each layer offers, how to import it and
which functions you will reach for most. It is not the full Elm core library: there is no `String`,
`Maybe` or `Dict` module, and Elm packages cannot be installed.

## Built into the compiler

These need no configuration:

| Module | Import | What it gives you |
| --- | --- | --- |
| `List` | always available | `map`, `filter`, `foldl`, `foldr`, `all`, `any`, `length`, `reverse`, `append`, `concatMap` |
| `Map` | `import Map` | typed association maps in the style of Elm's `Dict` |
| `Array` | `import Array` | arrays with constant-time `get` and `length` |
| `Bytes` | `import Bytes` | byte-string builtins: slicing, integers, bits, hashes |
| `Julc.Data` | `import Julc.Data as Data` | explicit `decode`, `encode` and optional/`Maybe` adapters |
| `Debug` | `import Debug` | `trace`, which emits a Plutus trace |
| `Julc.Ledger` | `import Julc.Ledger exposing (...)` | ledger types such as `ScriptContext`, `PubKeyHash` and `TxOutRef` |

Three functions are available everywhere without an import: `not`, `modBy` and `remainderBy`. The types
`Maybe` (with `Just` and `Nothing`), `Int`, `Bool`, `String`, `Bytes`, `Data`, `List`, `Map` and `Array` are
built in.

`List`, `Map` and `Array` are written in Elci itself, and compiled with your code. `Map` and `Array` sit on
top of Julc's typed map and array libraries. Every signature is on the
[core modules](/reference/core-modules/) reference page, and [lists, maps and arrays](/learn/collections/)
teaches them. Bytes get their own page: [bytes, hashes and bits](/advanced/bytes-and-crypto/).

## Julc's Java libraries

Julc ships its on-chain standard library as Java source, and the Elci compiler bundles it. To use a
library, map a module name to its class, then import that module. Here is every bundled library, with
the module names used on this site:

```toml title="elci.toml"
[java-modules]
"Julc.Address" = "org.julclang.stdlib.lib.AddressLib"
"Julc.Bitwise" = "org.julclang.stdlib.lib.BitwiseLib"
"Julc.ByteString" = "org.julclang.stdlib.lib.ByteStringLib"
"Julc.Contexts" = "org.julclang.stdlib.lib.ContextsLib"
"Julc.Crypto" = "org.julclang.stdlib.lib.CryptoLib"
"Julc.Interval" = "org.julclang.stdlib.lib.IntervalLib"
"Julc.Lists" = "org.julclang.stdlib.lib.ListsLib"
"Julc.MapLib" = "org.julclang.stdlib.lib.MapLib"
"Julc.Math" = "org.julclang.stdlib.lib.MathLib"
"Julc.Output" = "org.julclang.stdlib.lib.OutputLib"
"Julc.Values" = "org.julclang.stdlib.lib.ValuesLib"
```

Map only what you import; an unused mapping is never loaded. The module names are yours to choose. How
mapping, types and rejections work in general is covered in [Java libraries](/advanced/java-libraries/).

### What "verified" means

The compiler repository keeps an inventory of every public export. An export is **compile-verified**
when an Elci wrapper with its concrete signature type-checks, links the real Java implementation and
produces UPLC. That proves the signature maps correctly; it does not prove each function was evaluated
with representative inputs through Elci. Julc tests the libraries' behavior separately.

The current scan found **151 compile-verified exports**, **41 unsupported signatures** and **12 generic
templates**:

| Library | Verified exports | Gaps |
| --- | ---: | --- |
| `AddressLib` | 4 | none |
| `BitwiseLib` | 9 | `writeBits` (uses `PlutusData.ListData`) |
| `BlsLib` | 0 | all 19 exports use BLS point types |
| `ByteStringLib` | 24 | none |
| `ContextsLib` | 23 | `listIndex` (uses `PlutusData.ListData`) |
| `CryptoLib` | 9 | none |
| `IntervalLib` | 9 | none |
| `ListsLib` | 17 | plus 6 generic programmatic exports |
| `MapLib` | 10 | concrete `Data`-based signatures only |
| `MathLib` | 8 | `divMod`, `quotRem` (return a Java `Tuple2`) |
| `NativeValueLib` | 0 | all 7 exports use `JulcValue` |
| `OutputLib` | 14 | none |
| `ValuesLib` | 24 | 11 exports use unmapped Data subtypes or `AssetEntry` |
| `TypedMapLib` | 9 generic | used by the core `Map` module |
| `TypedArrayLib` | 3 generic | used by the core `Array` module |

Using an unsupported export is a compile error (`ELCI2105`) that names the Java type involved. Some
verified exports are public implementation helpers (their names often start with `_` or end in `Step`).
They are listed for completeness; do not build on them.

## An example

This listing validator uses five libraries. It checks that the seller has a key address and was paid,
that the transaction's validity range includes the deadline, that nothing is minted and that the script
is spending its own input:

```elm title="src/Market.elm"
module Market exposing (spend)

import Julc.Address as Address
import Julc.Contexts as Contexts
import Julc.Data as Data
import Julc.Interval as Interval
import Julc.Ledger exposing (Address, ScriptContext)
import Julc.Math as Math
import Julc.Output as Output
import Julc.Values as Values


type alias Listing =
    { seller : Address, price : Int, deadline : Int }


spendsOwnInput : ScriptContext -> Bool
spendsOwnInput ctx =
    case Data.fromOptional (Contexts.findOwnInput ctx) of
        Just _ ->
            True

        Nothing ->
            False


spend : Listing -> () -> ScriptContext -> Bool
spend listing _ ctx =
    Address.isPubKeyAddress listing.seller
        && (Output.lovelacePaidTo ctx.txInfo.outputs listing.seller >= Math.max listing.price 0)
        && Interval.contains ctx.txInfo.validRange listing.deadline
        && (Values.lovelaceOf ctx.txInfo.mint == 0)
        && spendsOwnInput ctx
```

```text
$ elci check
validator market (spend): checked; 1460 FLAT bytes; plutus-v3-pv11-uplc-1.1.0
```

Things to notice:

- **Java argument order.** Library functions take the collection or subject first:
  `Output.lovelacePaidTo outputs address`, `Interval.contains range time`.
- **Optional results.** `findOwnInput` returns a Julc optional. Convert it with `Data.fromOptional`
  before you match on `Just` and `Nothing`.
- **Size.** Library code is linked into your script: this validator is 1,460 bytes. Check the size
  after adding a library.

## Most useful functions

The tables give each export's Elci type. `Ledger.X` means the type `X` from `Julc.Ledger`.

### `MathLib`

| Function | Type | Notes |
| --- | --- | --- |
| `max`, `min` | `Int -> Int -> Int` | |
| `abs` | `Int -> Int` | |
| `sign` | `Int -> Int` | `-1`, `0` or `1` |
| `pow` | `Int -> Int -> Int` | `Math.pow 2 10` is `1024` |
| `expMod` | `Int -> Int -> Int -> Int` | modular exponentiation: `Math.expMod 3 4 5` is `1` |
| `floorDiv`, `floorMod` | `Int -> Int -> Int` | round towards negative infinity: `Math.floorDiv -7 2` is `-4` and `Math.floorMod -7 2` is `1` |

Elci's own `//` truncates towards zero, and `modBy` and `remainderBy` are always available.

### `IntervalLib`

| Function | Type | Notes |
| --- | --- | --- |
| `contains` | `Ledger.Interval -> Int -> Bool` | bounds are inclusive |
| `after`, `before` | `Int -> Ledger.Interval` | from a time onwards; up to a time |
| `between` | `Int -> Int -> Ledger.Interval` | both ends inclusive |
| `always`, `never` | `Ledger.Interval` | |
| `isEmpty` | `Ledger.Interval -> Bool` | |
| `finiteLowerBound`, `finiteUpperBound` | `Ledger.Interval -> Int` | return `-1` for an infinite bound |

The `-1` sentinel is easy to miss: `Interval.finiteUpperBound (Interval.after 100)` is `-1`. Handle it
before comparing times.

### `ContextsLib`

| Function | Type |
| --- | --- |
| `signedBy` | `Ledger.TxInfo -> Bytes -> Bool` |
| `findOwnInput` | `Ledger.ScriptContext -> JulcOptional Ledger.TxInInfo` |
| `getContinuingOutputs` | `Ledger.ScriptContext -> List Ledger.TxOut` |
| `ownHash` | `Ledger.ScriptContext -> Bytes` |
| `scriptOutputsAt` | `Ledger.TxInfo -> Bytes -> List Ledger.TxOut` |
| `valuePaid` | `Ledger.TxInfo -> Ledger.Address -> List Ledger.Value` |
| `valueSpent` | `Ledger.TxInfo -> List Ledger.Value` |
| `findDatum` | `Ledger.TxInfo -> Data -> JulcOptional Data` |
| `getSpendingDatum` | `Ledger.ScriptContext -> JulcOptional Data` |
| `getRedeemer` | `Ledger.ScriptContext -> Data` |

`signedBy` takes raw bytes, so pass a key hash's `.hash`: `Contexts.signedBy ctx.txInfo owner.hash`. The
`txInfo...` accessors (`txInfoFee`, `txInfoInputs` and so on) duplicate fields you can read directly,
such as `ctx.txInfo.fee`.

### `OutputLib`

| Function | Type |
| --- | --- |
| `lovelacePaidTo` | `List Ledger.TxOut -> Ledger.Address -> Int` |
| `paidAtLeast` | `List Ledger.TxOut -> Ledger.Address -> Int -> Bool` |
| `outputsAt` | `List Ledger.TxOut -> Ledger.Address -> List Ledger.TxOut` |
| `countOutputsAt` | `List Ledger.TxOut -> Ledger.Address -> Int` |
| `uniqueOutputAt` | `List Ledger.TxOut -> Ledger.Address -> Ledger.TxOut` |
| `outputsWithToken` | `List Ledger.TxOut -> Bytes -> Bytes -> List Ledger.TxOut` |
| `findOutputWithToken` | `List Ledger.TxOut -> Bytes -> Bytes -> Bytes -> Ledger.TxOut` |
| `findInputWithToken` | `List Ledger.TxInInfo -> Bytes -> Bytes -> Bytes -> Ledger.TxInInfo` |
| `valueHasToken` | `Ledger.Value -> Bytes -> Bytes -> Bool` |
| `getInlineDatum` | `Ledger.TxOut -> Data` |
| `txOutAddress`, `txOutValue`, `txOutDatum` | `Ledger.TxOut -> ...` |

### `ValuesLib`

A ledger `Value` is opaque in Elci: you cannot build, match or compare it directly, so these functions
are how you work with it.

| Function | Type |
| --- | --- |
| `lovelaceOf` | `Ledger.Value -> Int` |
| `assetOf` | `Ledger.Value -> Bytes -> Bytes -> Int` (policy, token name) |
| `geq`, `leq`, `eq` | `Ledger.Value -> Ledger.Value -> Bool` |
| `isZero` | `Ledger.Value -> Bool` |
| `add`, `subtract` | `Ledger.Value -> Ledger.Value -> Ledger.Value` |
| `negate` | `Ledger.Value -> Ledger.Value` |
| `singleton` | `Bytes -> Bytes -> Int -> Ledger.Value` |
| `containsPolicy` | `Ledger.Value -> Bytes -> Bool` |
| `countTokensWithQty` | `Ledger.Value -> Bytes -> Int -> Int` |
| `findTokenName` | `Ledger.Value -> Bytes -> Int -> Bytes` |
| `uniqueTokenName` | `Ledger.TxOutRef -> Bytes` |

### `AddressLib` and `CryptoLib`

| Function | Type |
| --- | --- |
| `Address.isPubKeyAddress`, `Address.isScriptAddress` | `Ledger.Address -> Bool` |
| `Address.paymentCredential` | `Ledger.Address -> Ledger.Credential` |
| `Address.credentialHash` | `Ledger.Address -> Bytes` |
| `Crypto.verifyEd25519Signature` | `Bytes -> Bytes -> Bytes -> Bool` (key, message, signature) |
| `Crypto.verifyEcdsaSecp256k1`, `Crypto.verifySchnorrSecp256k1` | `Bytes -> Bytes -> Bytes -> Bool` |
| `Crypto.sha2_256`, `sha3_256`, `blake2b_224`, `blake2b_256`, `keccak_256`, `ripemd_160` | `Bytes -> Bytes` |

The hashes are also in the built-in `Bytes` module; the signature checks are only here.

### `ByteStringLib` and `BitwiseLib`

| Function | Type | Notes |
| --- | --- | --- |
| `ByteString.toHex` | `Bytes -> Bytes` | lowercase hex digits as UTF-8 bytes |
| `ByteString.intToDecimalString` | `Int -> Bytes` | non-negative integers only |
| `ByteString.utf8ToInteger` | `Bytes -> Int` | ASCII digits only, no sign |
| `ByteString.serialiseData` | `Data -> Bytes` | CBOR encoding |
| `ByteString.zeros` | `Int -> Bytes` | |
| `Bitwise.andByteString`, `orByteString`, `xorByteString` | `Bool -> Bytes -> Bytes -> Bytes` | `True` pads the shorter operand at the end |
| `Bitwise.readBit` | `Bytes -> Int -> Bool` | |

The rest of both libraries mirrors builtins that the `Bytes` module already provides with the byte string
last. [Bytes, hashes and bits](/advanced/bytes-and-crypto/) covers both.

### `ListsLib` and `MapLib`

Most `ListsLib` and `MapLib` exports work on `List Data` and `Map Data Data`, so you must encode every
element with `Julc.Data.encode` first. Examples are `ListsLib.containsInt : List Data -> Int -> Bool`,
`ListsLib.hasDuplicateInts : List Data -> Bool` and `MapLib.lookup : Map Data Data -> Data -> JulcOptional Data`.
Prefer the typed core `List` and `Map` modules. `ListsLib`'s generic `map`, `filter`, `any`, `all`, `find`
and `foldl` are covered in [Java libraries](/advanced/java-libraries/).

## What is missing

- **BLS12-381** (`BlsLib`) and **native values** (`NativeValueLib`) have no usable exports yet.
- **`Value` arithmetic** is limited to what `ValuesLib` provides; you cannot inspect a `Value` directly.
- **Elm's core modules** are not available: no `String`, `Maybe`, `Dict`, `Set`, `Tuple` or `Basics`
  helpers such as `max` or `identity`. Write small helpers yourself or use the Java libraries above.

## Your own libraries

You can write reusable libraries in Elci: ordinary modules under any source directory, listed in
`source-directories`. There is no package registry yet; share them as source. See
[modules and imports](/learn/modules/). To reuse Java code, package it as a
[library JAR](/advanced/java-libraries/).
