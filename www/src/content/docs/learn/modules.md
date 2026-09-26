---
title: Modules and imports
description: Split code into modules, control what each module exposes, import Elci modules and Julc Java libraries, and share code between projects.
sidebar:
  order: 6
---

As a project grows, you split it into modules. This page shows how modules map to files, how `exposing`
keeps helpers private, the forms of `import`, and which modules come with Elci. You also see how to use
Julc's Java libraries and how to share Elci code between projects.

## One module per file

Every file starts with a module header. The module name must match the file's path under a source
directory: `Shop.Pricing` lives in `src/Shop/Pricing.elm`.

```elm title="src/Shop/Pricing.elm"
module Shop.Pricing exposing (Item, Tier(..), priceOf)


type Tier
    = Standard
    | Member


type alias Item =
    { name : String
    , price : Int
    }


priceOf : Tier -> Item -> Int
priceOf tier item =
    case tier of
        Standard ->
            item.price

        Member ->
            item.price - memberDiscount item.price


memberDiscount : Int -> Int
memberDiscount price =
    price // 10
```

```elm title="src/Main.elm"
module Main exposing (main)

import Shop.Pricing as Pricing exposing (Tier(..))


main : Int
main =
    Pricing.priceOf Member (Pricing.Item "hat" 500)
        + Pricing.priceOf Standard { name = "scarf", price = 300 }
```

Put both files in a function project (create the `src/Shop` directory) and run it:

```text
750
cpu 4856488, mem 17742
```

A member pays 450 for the hat, and the scarf costs 300.

If the header and the path disagree, the compiler says so:

```text
.../src/Shop/Pricing.elm:1:1: ELCI2104: Module header Pricing does not match its source path, which names module Shop.Pricing

1| module Pricing exposing (Item, Tier(..), priceOf)
          ^^^^^^^
```

## What a module exposes

The `exposing` list in the header is the module's public interface:

| In the header | What other modules can use |
| --- | --- |
| `priceOf` | The function or value `priceOf` |
| `Item` | The type alias `Item`, and for a record alias its constructor `Item` |
| `Tier` | The custom type `Tier`, but not its constructors |
| `Tier(..)` | The custom type `Tier` and all of its constructors |

Everything else is private. `memberDiscount` above isn't exposed, so `Main` can't call it:

```text
.../src/Main.elm:9:11: ELCI2104: Module Pricing does not expose 'memberDiscount'

9|         - Pricing.memberDiscount 500
             ^^^^^^^^^^^^^^^^^^^^^^^
```

Keeping helpers private lets you change them without breaking other modules. In a validator module, expose
the handlers and the datum and redeemer types that tests and off-chain code need.

## Importing

| Import | Then you can write |
| --- | --- |
| `import Shop.Pricing` | `Shop.Pricing.priceOf` |
| `import Shop.Pricing as Pricing` | `Pricing.priceOf` |
| `import Shop.Pricing exposing (priceOf, Tier(..))` | `priceOf`, `Member` and `Standard` unqualified, and `Shop.Pricing.Item` |
| `import Shop.Pricing exposing (..)` | Everything the module exposes, unqualified |

A qualified name only works through an import in the current module. After
`import Shop.Pricing exposing (Tier(..))` without `as Pricing`, the name `Pricing.priceOf` means nothing:

```text
.../src/Main.elm:8:5: ELCI2104: No module Pricing is imported, and none has that name

8|     Pricing.priceOf Member { name = "hat", price = 500 }
       ^^^^^^^^^^^^^^^
```

Write `Shop.Pricing.priceOf`, or import the module `as Pricing`. Without any import of the module,
`Shop.Pricing.priceOf` gives ``Module Shop.Pricing is not imported; add `import Shop.Pricing` ``. A
misspelt qualified name gets a suggestion, such as ``did you mean `List.length`?`` for `Lsit.length`.

Your own declarations take precedence over imported names. If two imports expose different things under
one name, using that name is an error that lists the qualified alternatives; write the one you mean, such
as `Pricing.priceOf`.

Imports can't form a cycle. If `Shop.Pricing` imported `Main` while `Main` imports `Shop.Pricing`, the build
stops with:

```text
.../src/Shop/Pricing.elm:3:1: ELCI2104: Import cycle: Main -> Shop.Pricing -> Main

3| import Main
   ^^^^^^
```

Move the shared code into a third module that both import.

## Modules that come with Elci

As in Elm, every module imports the core modules for you:

| Module | Imported as | Contents |
| --- | --- | --- |
| `Basics` | `import Basics exposing (..)` | The prelude: `min`, `max`, `abs`, `negate`, `clamp`, `compare`, `identity`, `always`, `not`, `xor`, `modBy`, `remainderBy` |
| `List` | `import List` | `map`, `filter`, `foldl`, `head`, `range`, `sum`, `sort` and the rest of Elm's list functions |
| `Maybe` | `import Maybe exposing (Maybe(..))` | `withDefault`, `map`, `map2`, `map3`, `andThen` |
| `Result` | `import Result exposing (Result(..))` | `Ok` and `Err`, with `map`, `andThen`, `withDefault`, `toMaybe` and more |
| `Tuple` | `import Tuple` | `pair`, `first`, `second`, `mapFirst`, `mapSecond`, `mapBoth` |
| `String` | `import String` | `fromInt`, `isEmpty`, `append`, `concat`, `join` |
| `Debug` | `import Debug` | `Debug.trace`, which records a message |

The others you import yourself:

| Module | Import | Contents |
| --- | --- | --- |
| `Dict` | `import Dict exposing (Dict)` | Elm's dictionaries, with comparable keys kept in order |
| `Map` | `import Map` | The ledger's key/value maps, for ledger data and keys that aren't comparable |
| `Array` | `import Array` | Constant-time indexed arrays |
| `Value` | `import Value exposing (Value)` | The ledger's multi-asset values: lovelace and native tokens |
| `Interval` | `import Interval` | Time ranges such as the transaction's validity range: deadline checks |
| `Tx` | `import Tx` | Questions about the transaction: signers, spent outputs, payments, minted tokens, datums |
| `Bytes` | `import Bytes` | Byte-string builtins: building, slicing, integers, bits and hashes |
| `Julc.Data` | `import Julc.Data as Data` | `encode` and `decode` to and from Plutus Data, and `Maybe` adapters |
| `Julc.Ledger` | `import Julc.Ledger exposing (...)` | Ledger types: `ScriptContext`, `PubKeyHash`, `TxOutRef` and more |

[Core modules](/reference/core-modules/) lists every function. Writing a default import yourself, such as
`import List exposing (head)`, is allowed and adds the names you expose. Test modules can also import the
test library, `Expect`, `Scenario` and `Property` (see [Unit tests](/testing/unit-tests/)). Source modules
can't, which keeps test code out of your scripts.

`import Julc.Ledger exposing (..)` exposes the ledger's types but not their constructors. Name the
constructors you need, as in `exposing (Credential(..))`. [Reading the
transaction](/contracts/reading-the-transaction/) covers the ledger types.

## Julc's Java libraries

Julc ships on-chain libraries written in Java, such as `MathLib`, `CryptoLib`, `AddressLib` and
`ByteStringLib`. They fill the gaps in the core modules, such as floor division and signature verification.
To use one, give it a module name in `elci.toml`:

```toml title="elci.toml"
[java-modules]
"Julc.Math" = "org.julclang.stdlib.lib.MathLib"
```

Then import it like any other module:

```elm title="src/Main.elm"
module Main exposing (main)

import Julc.Math as Math exposing (floorDiv)


main : ( Int, Int, Int )
main =
    ( floorDiv -7 2, Math.floorMod -7 2, Math.expMod 3 4 5 )
```

```text
( -4, 1, 1 )
cpu 3991568, mem 16175
```

`floorDiv` rounds towards negative infinity, where `//` truncates towards zero.

The compiler reads the library's Java source and compiles the functions you call into your script. Two
rules differ from Elci modules:

- The module name is whatever you choose in `elci.toml`. `Julc.Math` is a convention, not a built-in name.
  A core module's name, such as `Interval` or `Tx`, can't be used.
- Expose names explicitly. `import Julc.Math exposing (..)` is rejected:

  ```text
  .../src/Main.elm:3:1: ELCI2104: Java modules cannot be imported with exposing (..); list the exported names you use

  3| import Julc.Math exposing (..)
     ^^^^^^
  ```

[Standard library](/advanced/standard-library/) lists what each library provides, and
[Java libraries](/advanced/java-libraries/) covers your own Java code.

## Sharing code between projects

There is no package manager. To reuse Elci code, keep it in its own source directory, for example a `lib`
directory in a Git repository that several projects check out, and list that directory in each project's
`source-directories`:

```toml title="elci.toml"
[project]
source-directories = ["src", "lib"]
target = "plutus-v3-pv11-uplc-1.1.0"
julc-version = "0.1.0-gb-5a3378b"
```

A module in `lib/Finance/Pricing.elm` is then `Finance.Pricing`, imported like any other:

```elm title="lib/Finance/Pricing.elm"
module Finance.Pricing exposing (totalWithFee)


totalWithFee : Int -> List Int -> Int
totalWithFee fee prices =
    subtotal prices + fee


subtotal : List Int -> Int
subtotal prices =
    List.foldl (\price total -> price + total) 0 prices
```

```elm title="src/Main.elm"
module Main exposing (main)

import Finance.Pricing exposing (totalWithFee)
import Shop.Pricing as Pricing exposing (Tier(..))


main : Int
main =
    totalWithFee 5
        [ Pricing.priceOf Member (Pricing.Item "hat" 500)
        , Pricing.priceOf Standard { name = "scarf", price = 300 }
        ]
```

```text
755
cpu 7570256, mem 30966
```

The library is compiled together with each project that uses it. A module name may appear in only one source
directory. The core module names (`Basics`, `List`, `Maybe`, `Result`, `String`, `Tuple`, `Map`, `Dict`,
`Array`, `Value`) are taken, and `Set`, `Elci` and names starting with `Elci.` are reserved for the core
library. The
[elci-library example](https://github.com/bloxbean/elci-lang/tree/main/examples/elci-library) is a complete
project.

:::note
The compiler only compiles modules that your entrypoint or handlers import, directly or indirectly. A module
that nothing imports isn't checked, so import a new module before you rely on `elci check` to report its
errors.
:::

## Next

[Coming from Elm](/learn/coming-from-elm/) summarizes how Elci differs from Elm. If you're new to Elm too,
you can go straight to [Validators and purposes](/contracts/validators/).
