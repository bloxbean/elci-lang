---
title: Bytes, hashes and bits
description: Build, slice and compare byte strings, convert integers, use bit operations and hashes, verify signatures and print bytes as hex in Elci.
sidebar:
  order: 3
---

Byte strings are everywhere on Cardano: key hashes, script hashes, token names and transaction IDs are
all bytes. This page walks through the `Bytes` module function by function, then covers hashes,
signature checks and the `Julc.ByteString` helpers. Every example is a function project you can run
with `elci eval`.

## The `Bytes` type

`Bytes` is a built-in type, so you can write it in any signature. The functions live in the
compiler-provided `Bytes` module, which you import like any other module:

```elm
import Bytes
```

A few things to know before you start:

- **There is no byte literal.** You create bytes with `Bytes.fromString` (UTF-8), `Bytes.fromIntBigEndian`,
  `Bytes.replicate` or `Bytes.empty`. In a real contract most bytes arrive in a datum, redeemer, parameter
  or the transaction itself.
- **Every function is a direct builtin call.** There is no hidden loop, so each call is cheap and predictable.
- **The byte string comes last.** Functions take the byte string as their final argument, so they read well
  in pipelines: `name |> Bytes.take 4`.
- **`==` compares bytes.** Ledger hashes such as `PubKeyHash` are separate nominal types, even though
  they are byte strings underneath. Use the `.hash` field to get their raw `Bytes`.

## Seeing bytes with `elci eval`

`elci eval` prints a byte string as `#` followed by its hex digits, and the empty byte string as
`#(empty)`. Here is a small function project that checks a token-name prefix:

```elm title="src/Main.elm"
module Main exposing (main)

import Bytes


hasPrefix : Bytes -> Bytes -> Bool
hasPrefix prefix name =
    Bytes.take (Bytes.length prefix) name == prefix


main : Bool
main =
    hasPrefix (Bytes.fromString "GOLD") (Bytes.fromString "GOLD-042")
```

Add `--builtins` to see each builtin call with its arguments and result:

```text
$ elci eval --builtins
True
cpu 714261, mem 3329
builtin: EncodeUtf8("GOLD") → #474f4c44
builtin: EncodeUtf8("GOLD-042") → #474f4c442d303432
builtin: LengthOfByteString(#474f4c44) → 4
builtin: SliceByteString(0, 4, #474f4c442d303432) → #474f4c44
builtin: EqualsByteString(#474f4c44, #474f4c44) → True
```

The builtin log keeps the last 20 calls, which is plenty for small experiments like these.

## Function reference

| Group | Function | Type | Builtin |
| --- | --- | --- | --- |
| Building | `empty` | `Bytes` | constant |
| | `fromString` | `String -> Bytes` | `encodeUtf8` |
| | `toString` | `Bytes -> String` | `decodeUtf8` |
| | `cons` | `Int -> Bytes -> Bytes` | `consByteString` |
| | `replicate` | `Int -> Int -> Bytes` | `replicateByte` |
| | `append` | `Bytes -> Bytes -> Bytes` | `appendByteString` |
| Reading | `length` | `Bytes -> Int` | `lengthOfByteString` |
| | `at` | `Int -> Bytes -> Int` | `indexByteString` |
| | `slice` | `Int -> Int -> Bytes -> Bytes` | `sliceByteString` |
| | `take` | `Int -> Bytes -> Bytes` | `sliceByteString` |
| | `drop` | `Int -> Bytes -> Bytes` | `sliceByteString` |
| Comparing | `lessThan` | `Bytes -> Bytes -> Bool` | `lessThanByteString` |
| | `lessThanOrEqual` | `Bytes -> Bytes -> Bool` | `lessThanEqualsByteString` |
| Integers | `fromIntBigEndian` | `Int -> Int -> Bytes` | `integerToByteString` |
| | `fromIntLittleEndian` | `Int -> Int -> Bytes` | `integerToByteString` |
| | `toIntBigEndian` | `Bytes -> Int` | `byteStringToInteger` |
| | `toIntLittleEndian` | `Bytes -> Int` | `byteStringToInteger` |
| Bits | `and`, `or`, `xor` | `Bytes -> Bytes -> Bytes` | `andByteString`, `orByteString`, `xorByteString` |
| | `complement` | `Bytes -> Bytes` | `complementByteString` |
| | `shift` | `Int -> Bytes -> Bytes` | `shiftByteString` |
| | `rotate` | `Int -> Bytes -> Bytes` | `rotateByteString` |
| | `testBit` | `Int -> Bytes -> Bool` | `readBit` |
| | `countSetBits` | `Bytes -> Int` | `countSetBits` |
| | `findFirstSetBit` | `Bytes -> Int` | `findFirstSetBit` |
| Hashes | `blake2b224`, `blake2b256` | `Bytes -> Bytes` | `blake2b_224`, `blake2b_256` |
| | `sha256`, `sha3_256` | `Bytes -> Bytes` | `sha2_256`, `sha3_256` |
| | `keccak256`, `ripemd160` | `Bytes -> Bytes` | `keccak_256`, `ripemd_160` |

## Building and slicing

```elm title="src/Main.elm"
module Main exposing (main)

import Bytes


abcd : Bytes
abcd =
    Bytes.fromString "abcd"


main : List Bytes
main =
    [ Bytes.fromString "GOLD"
    , Bytes.append (Bytes.fromString "a") (Bytes.fromString "b")
    , Bytes.cons 255 (Bytes.fromString "ab")
    , Bytes.replicate 3 7
    , Bytes.slice 1 2 abcd
    , Bytes.slice 2 10 abcd
    , Bytes.take 2 abcd
    , Bytes.drop 1 abcd
    , Bytes.drop 10 abcd
    ]
```

```text
$ elci eval
[#474f4c44, #6162, #ff6162, #070707, #6263, #6364, #6162, #626364, #(empty)]
cpu 3394517, mem 14178
```

What this shows:

- `append a b` puts `a` first. In a pipeline that reverses the reading order:
  `name |> Bytes.append prefix` produces `prefix` followed by `name`.
- `slice start count bytes` takes a start index and a **length**, not an end index. It clamps to the
  available bytes instead of failing, and so do `take` and `drop`.
- `cons byte bytes` and `replicate count byte` fail for a byte outside 0–255. `replicate` also fails
  above 8,192 bytes.

## Reading and comparing

```elm title="src/Main.elm"
module Main exposing (main)

import Bytes


abcd : Bytes
abcd =
    Bytes.fromString "abcd"


main : ( List Int, List Bool, String )
main =
    ( [ Bytes.length abcd, Bytes.at 1 abcd ]
    , [ Bytes.lessThan (Bytes.fromString "ab") (Bytes.fromString "abc")
      , Bytes.lessThanOrEqual abcd abcd
      , Bytes.take 2 abcd == Bytes.fromString "ab"
      ]
    , Bytes.toString (Bytes.drop 2 abcd)
    )
```

```text
$ elci eval
( [4, 98], [True, True, True], "cd" )
cpu 3401703, mem 14999
```

- `at index bytes` returns a byte as an `Int` from 0 to 255. It **fails** outside the byte string.
- `lessThan` and `lessThanOrEqual` compare lexicographically, so a proper prefix is smaller. The `<`
  operator works only on `Int`; on bytes it is a compile error that points you to `Bytes.lessThan`.
- `toString` decodes UTF-8 and fails on invalid UTF-8.

## Integer conversions

`fromIntBigEndian width n` and `fromIntLittleEndian width n` write a non-negative integer into exactly
`width` bytes. A width of `0` means "as few bytes as needed". The `to...` functions read the bytes back
as a non-negative integer.

```elm title="src/Main.elm"
module Main exposing (main)

import Bytes


main : ( List Bytes, List Int )
main =
    ( [ Bytes.fromIntBigEndian 4 1000
      , Bytes.fromIntLittleEndian 4 1000
      , Bytes.fromIntBigEndian 0 1000
      , Bytes.fromIntBigEndian 0 0
      ]
    , [ Bytes.toIntBigEndian (Bytes.fromIntBigEndian 4 1000)
      , Bytes.toIntLittleEndian (Bytes.fromIntBigEndian 4 1000)
      , Bytes.toIntBigEndian Bytes.empty
      ]
    )
```

```text
$ elci eval
( [#000003e8, #e8030000, #03e8, #(empty)], [1000, 3892510720, 0] )
cpu 13938958, mem 13313
```

Converting fails when the integer is negative (`IntegerToByteString: negative integer`), when it does
not fit in the width (`IntegerToByteString: integer doesn't fit in 1 bytes`) or when the width exceeds
8,192 bytes.

:::tip[A byte-string constant without a literal]
Because `Int` is arbitrary precision, `Bytes.fromIntBigEndian 32 n` turns a decimal constant into a
fixed-width byte string. Keys and hashes usually arrive as data or deployment parameters, so you
rarely need this. If you do, note that elm-format 0.8.8 corrupts integer literals that do not fit in 64
bits, so check large constants after formatting.
:::

## Bit operations

Bits are numbered from the **end** of the byte string: bit 0 is the least significant bit of the last
byte. Here `f0` is the single byte `0xf0`:

```elm title="src/Main.elm"
module Main exposing (main)

import Bytes


f0 : Bytes
f0 =
    Bytes.fromIntBigEndian 1 240


main : ( List Bytes, List Bool, List Int )
main =
    ( [ Bytes.and (Bytes.fromIntBigEndian 2 65535) f0
      , Bytes.or (Bytes.fromIntBigEndian 1 15) f0
      , Bytes.xor (Bytes.fromIntBigEndian 1 255) f0
      , Bytes.complement f0
      , Bytes.shift 1 f0
      , Bytes.shift -1 f0
      , Bytes.rotate 4 (Bytes.fromIntBigEndian 2 4660)
      , Bytes.rotate -4 (Bytes.fromIntBigEndian 2 4660)
      ]
    , [ Bytes.testBit 0 (Bytes.fromIntBigEndian 2 1)
      , Bytes.testBit 8 (Bytes.fromIntBigEndian 2 256)
      , Bytes.testBit 0 f0
      ]
    , [ Bytes.countSetBits f0
      , Bytes.findFirstSetBit f0
      , Bytes.findFirstSetBit (Bytes.replicate 2 0)
      ]
    )
```

```text
$ elci eval
( [#f0, #ff, #0f, #0f, #e0, #78, #2341, #4123], [True, True, False], [4, 4, -1] )
cpu 18446645, mem 28868
```

- `and`, `or` and `xor` **truncate** to the shorter operand: `0xffff` and `0xf0` give one byte.
- `shift n` moves bits towards the front for positive `n` and towards the end for negative `n`. Bits
  shifted out are lost and the length stays the same. `rotate n` wraps them around instead.
- `testBit i` reads bit `i`; `findFirstSetBit` returns the lowest set bit's index, or `-1` when no bit is set.

## Hashes

The six hash functions return fixed-length digests: `blake2b224` gives 28 bytes (the size of key and
script hashes), `ripemd160` gives 20 bytes and the others give 32 bytes.

```elm title="src/Main.elm"
module Main exposing (main)

import Bytes


abc : Bytes
abc =
    Bytes.fromString "abc"


main : List Bytes
main =
    [ Bytes.blake2b224 abc
    , Bytes.blake2b256 abc
    , Bytes.sha256 abc
    , Bytes.sha3_256 abc
    , Bytes.keccak256 abc
    , Bytes.ripemd160 abc
    ]
```

```text
$ elci eval
[#9bd237b02a29e43bdd6738afa5b53ff0eee178d6210b618e4511aec8, #bddd813c634239723171ef3fee98579b94964e3bb1cb3e427262c8c068d52319, #ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad, #3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532, #4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45, #8eb208f7e05d987a9b044a8e98c6b087f15a0bfc]
cpu 8104959, mem 7043
```

Hashing is cheap: `Bytes.blake2b256` over a five-byte input adds roughly 240,000 CPU units. A common
use is checking that a public key belongs to a key hash:

```elm
import Bytes
import Julc.Ledger exposing (PubKeyHash)


ownsKey : PubKeyHash -> Bytes -> Bool
ownsKey owner key =
    Bytes.blake2b224 key == owner.hash
```

## Verifying signatures

Signature checks come from Julc's `CryptoLib`. Map it, then call
`verifyEd25519Signature key message signature`:

```toml title="elci.toml"
[java-modules]
"Julc.Crypto" = "org.julclang.stdlib.lib.CryptoLib"
```

```elm title="src/Oracle.elm"
module Oracle exposing (spend)

import Bytes
import Julc.Crypto as Crypto
import Julc.Ledger exposing (ScriptContext)


type alias Report =
    { price : Int, signature : Bytes }


{-| Accept a price report only when the oracle signed its 8-byte big-endian encoding.
-}
spend : Bytes -> () -> Report -> ScriptContext -> Bool
spend oracleKey _ report _ =
    Crypto.verifyEd25519Signature oracleKey (Bytes.fromIntBigEndian 8 report.price) report.signature
```

Here `oracleKey` is a deployment parameter (see [parameterized validators](/contracts/parameters/)).
The library also has `verifyEcdsaSecp256k1` and `verifySchnorrSecp256k1` with the same
`key -> message -> signature -> Bool` shape. Verifying the RFC 8032 Ed25519 test vector, including
building the key and signature bytes, cost about 57 million CPU units: far more than a hash.

## `Julc.ByteString` helpers

`ByteStringLib` adds composite helpers that the builtins do not cover. Map it first:

```toml title="elci.toml"
[java-modules]
"Julc.ByteString" = "org.julclang.stdlib.lib.ByteStringLib"
```

They are ordinary Java library functions, so they cost more than builtins, and they take the byte string
**first**, in Java argument order:

| Function | Type | Result |
| --- | --- | --- |
| `toHex` | `Bytes -> Bytes` | lowercase hex digits, as UTF-8 bytes |
| `intToDecimalString` | `Int -> Bytes` | decimal digits of a **non-negative** integer |
| `utf8ToInteger` | `Bytes -> Int` | parses ASCII digits; no sign handling |
| `zeros` | `Int -> Bytes` | `n` zero bytes |
| `serialiseData` | `Data -> Bytes` | CBOR encoding of a `Data` value |
| `integerToByteString` | `Bool -> Int -> Int -> Bytes` | big-endian flag, width, integer |
| `byteStringToInteger` | `Bool -> Bytes -> Int` | big-endian flag, bytes |
| `slice` | `Bytes -> Int -> Int -> Bytes` | bytes, start, length |
| `take`, `drop`, `at` | `Bytes -> Int -> ...` | as in `Bytes`, bytes first |

```elm title="src/Main.elm"
module Main exposing (main)

import Bytes
import Julc.ByteString as ByteString
import Julc.Data as Data


main : ( List String, List Bytes, List Int )
main =
    ( [ Bytes.toString (ByteString.toHex (Bytes.fromString "GOLD"))
      , Bytes.toString (ByteString.intToDecimalString 1234)
      ]
    , [ ByteString.zeros 3
      , ByteString.slice (Bytes.fromString "abcd") 1 2
      , ByteString.serialiseData (Data.encode 42)
      , ByteString.serialiseData (Data.encode [ 1, 2 ])
      , ByteString.integerToByteString True 2 258
      ]
    , [ ByteString.utf8ToInteger (Bytes.fromString "42")
      , ByteString.byteStringToInteger False (Bytes.fromIntBigEndian 2 258)
      ]
    )
```

```text
$ elci eval
( ["474f4c44", "1234"], [#000000, #6263, #182a, #9f0102ff, #0102], [42, 513] )
cpu 33110364, mem 94296
```

`toHex` is useful when you need hex as text, for example in a trace message. It is an ordinary recursive
function: `Bytes.toString (ByteString.toHex (Bytes.sha256 (Bytes.fromString "abc")))` cost about 73
million CPU units. Keep it out of a validator's hot path.

:::caution
`intToDecimalString` and `utf8ToInteger` do not validate their input. A negative number or a
non-digit byte produces a meaningless result rather than a failure.
:::

The full list of `ByteStringLib` exports is on the [standard library](/advanced/standard-library/) page.
Prefer the `Bytes` builtins where both exist.

## When byte operations fail

A failing builtin fails the whole script, exactly like a validator returning `False`. There is no
`Maybe` result to check. Guard the input first (for example compare the index with `Bytes.length`)
when a failure would be wrong:

```elm title="src/Main.elm"
module Main exposing (main)

import Bytes


main : Int
main =
    Bytes.at 5 (Bytes.fromString "ab")
```

```text
$ elci eval --builtins
evaluation failed: IndexByteString: index 5 out of bounds for length 2
cpu 206269, mem 1308
builtin: EncodeUtf8("ab") → #6162
builtin: IndexByteString(#6162, 5) → <ERROR: BuiltinException>
```

The command exits with status 1. The other failure messages you may meet are
`ConsByteString: byte value out of range: 256`, `ReplicateByte: length exceeds 8192`,
`DecodeUtf8: invalid UTF-8 byte sequence` and the `IntegerToByteString` errors above.

:::tip[Pin facts with a test]
Once an experiment works, turn it into a test so it stays true, for example
`Expect.equal 4 (Bytes.length abcd)`. See [unit tests](/testing/unit-tests/).
:::

For every signature in one place, see [core modules](/reference/core-modules/).
