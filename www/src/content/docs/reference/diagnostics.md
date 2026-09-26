---
title: Diagnostics
description: Every ELCI error code with its meaning, plus real messages and fixes for the errors you will meet most often.
sidebar:
  order: 6
---

Elci reports errors with a stable code and a source location. This page lists every code, then shows
the most common errors with a small program that triggers each one, the exact message and the fix.

## Reading a diagnostic

A compile error has the form `path:line:column: code: message`:

```text
src/Main.elm:6:5: ELCI2005: Unknown name 'lenght'
```

- The **path** is the file of the module where the problem is, which may be a module imported by the
  one you compiled. A problem inside a bundled module such as `List` is reported where your code uses it,
  or otherwise as `<bundled>/List.elm`.
- The **code** names the class of error and never changes between releases. The **message** names the
  construct and types involved.
- For a validator, the `elci.toml` table is printed first, on its own line:

```text
elci: my-project/elci.toml:6: [validators.main]
my-project/src/Main.elm:6:1: ELCI2013: Functions cannot be serialized as datum or redeemer
```

Every error makes the command exit with status 1. Codes starting with `ELCI1` come from the lexer and
parser, `ELCI20` from type checking, `ELCI21` from modules and imports, `ELCI3` from code generation and
`ELCI4` from configuration and the backend.

## All codes

| Code | Meaning |
| --- | --- |
| `ELCI1001` | Invalid source character or literal |
| `ELCI1002` | Malformed or unsupported syntax |
| `ELCI2001` | Invalid pattern or expression form |
| `ELCI2002` | Type mismatch or infinite type |
| `ELCI2003` | Unsupported equality or append type |
| `ELCI2004` | Missing required top-level signature |
| `ELCI2005` | Unknown value name |
| `ELCI2006` | Duplicate binder or unreachable pattern |
| `ELCI2007` | Non-exhaustive `case` expression |
| `ELCI2008` | Eager recursive value cycle |
| `ELCI2009` | Non-concrete handler, entrypoint or property |
| `ELCI2010` | Unknown or unconstrained record field |
| `ELCI2011` | Duplicate type or constructor |
| `ELCI2012` | Invalid type, alias, arity or alias cycle |
| `ELCI2013` | Unsupported serialized boundary type, or a value without a Data encoding (a function or an `Array`) stored in data |
| `ELCI2014` | Validator handler signature does not match its purpose, parameters or datum profile |
| `ELCI2101` | Unknown or missing module or imported export; a source module importing the test library |
| `ELCI2102` | Ambiguous or conflicting imported name |
| `ELCI2103` | Entrypoint or handler is not exposed |
| `ELCI2104` | Module path, ownership or cycle error; reference to an unexposed or unimported name |
| `ELCI2105` | Unsupported Java export signature, with the provider's reason |
| `ELCI3001` | The inferred program cannot be lowered, such as a generic function used at a type nothing determines |
| `ELCI3002` | Specialization limit or polymorphic recursion |
| `ELCI3003` | A generic library type argument or a Data conversion needs a Data encoding |
| `ELCI4001` | Invalid project configuration or command-line request |
| `ELCI4002` | Julc's backend rejected the generated code |
| `ELCI4003` | The Julc backend lacks a capability the program needs |

## Syntax errors

### `ELCI1001`: invalid character or literal

```elm
main : Int
main =
    1.5
```

```text
src/Main.elm:6:6: ELCI1001: Float is unsupported; represent fractional amounts using an explicit integer scale
```

**Fix:** use integers with an explicit scale, such as lovelace instead of ada or basis points instead of
percentages. The same code reports tabs, unsupported characters such as `%` or `'` and unknown string
escapes.

### `ELCI1002`: malformed or unsupported syntax

The message says what is wrong. Refutable patterns in function arguments are a common case:

```elm
first : List Int -> Int
first (x :: _) =
    x
```

```text
src/Main.elm:5:8: ELCI1002: Function parameters require irrefutable patterns; use case for refutable matching
```

**Fix:** match with `case`, and handle every shape:

```elm
first : List Int -> Int
first values =
    case values of
        x :: _ ->
            x

        [] ->
            0
```

Other `ELCI1002` messages include `Comparison operators cannot be chained; use && or explicit
parentheses` (for `1 < 2 < 3`), `Extensible records are unsupported; use a closed record` and
`Tuples have two or three elements`.

### `ELCI2001`: invalid expression form

```elm
main : Int
main =
    7 / 2
```

```text
src/Main.elm:6:7: ELCI2001: Float division is unsupported; use // for integer quotient
```

**Fix:** `7 // 2`, together with `modBy` or `remainderBy` when you need the rest.

## Type errors

### `ELCI2002`: type mismatch

```elm
main : Int
main =
    1 + True
```

```text
src/Main.elm:6:7: ELCI2002: The operator + works on Int values, not Bool
```

A signature that claims more than the body delivers is also a mismatch:

```elm
identity : a -> a
identity value =
    value + 1
```

```text
src/Main.elm:4:1: ELCI2002: Type mismatch: expected Int, found a
```

**Fix:** make the signature match the body (`increment : Int -> Int`), or the body match the signature.

Operators name the types they accept, and suggest the right function where there is one:

```text
src/Main.elm:6:9: ELCI2002: The operator < compares Int values, not String
src/Main.elm:8:26: ELCI2002: The operator < compares Int values, not Bytes; use Bytes.lessThan for byte strings
src/Main.elm:6:9: ELCI2002: The operator + works on Int values, not String; use ++ to append
```

Recursion that would need an infinitely growing type, such as a generic function that calls itself with
a tuple of its argument, is reported as `ELCI2002: Infinite type`.

### `ELCI2003`: unsupported equality or append

```elm
double : Int -> Int
double x =
    x * 2


main : Bool
main =
    double == double
```

```text
src/Main.elm:11:12: ELCI2003: Equality is unsupported for (Int -> Int) because it contains a function, opaque Data, or an imported type without verified equality
```

Equality is rejected for functions, `Data`, `Map`, `Array` and imported types without approved
equality. `++` needs a concrete `String` or `List`: `1 ++ 2` gives `ELCI2003: Append requires a concrete
String or List type`.

**Fix:** compare the values you care about, such as a map's lookups or a decoded datum.

### `ELCI2004`: missing signature

```elm
double x =
    x * 2
```

```text
src/Main.elm:4:1: ELCI2004: Top-level declarations require a type signature
```

**Fix:** add `double : Int -> Int` directly above the definition. Local definitions do not need one.

### `ELCI2005`: unknown name

```elm
main : Int
main =
    lenght [ 1, 2 ]
```

```text
src/Main.elm:6:5: ELCI2005: Unknown name 'lenght'
```

**Fix:** correct the spelling, or import the module that defines the name. Elm's `Basics` helpers such
as `max`, `identity` and `negate` do not exist in Elci. A misspelled qualified name gets a hint:

```text
src/Main.elm:8:5: ELCI2005: Unknown name 'Math.maximum'; check that the imported module exports it
```

### `ELCI2006`: duplicate binder or unreachable pattern

```elm
describe : Maybe Int -> Int
describe value =
    case value of
        Just n ->
            n

        Nothing ->
            0

        Just _ ->
            1
```

```text
src/Main.elm:13:9: ELCI2006: Unreachable pattern
```

**Fix:** remove the branch that can never match. A pattern that binds the same name twice, such as
`( x, x )`, gives `ELCI2006: Duplicate pattern variable x`.

### `ELCI2007`: non-exhaustive `case`

```elm
type Action
    = Buy Int
    | Sell Int
    | Cancel


amount : Action -> Int
amount action =
    case action of
        Buy value ->
            value

        Sell value ->
            value
```

```text
src/Main.elm:12:5: ELCI2007: Non-exhaustive case; add the missing constructor or a wildcard branch
```

**Fix:** add a `Cancel ->` branch. Prefer naming every constructor over `_ ->`, so that adding a
constructor later points you to every `case` to update.

### `ELCI2008`: eager value cycle

```elm
main : Int
main =
    let
        a =
            b + 1

        b =
            a + 1
    in
    a
```

```text
src/Main.elm:7:9: ELCI2008: Cyclic eager values are unsupported; recursion requires functions
```

**Fix:** values are computed eagerly, so they cannot depend on each other in a loop. Break the cycle, or
turn the recursion into a function.

### `ELCI2009`: type is not concrete

```elm
main : List a
main =
    []
```

```text
src/Main.elm:4:1: ELCI2009: Entrypoints require concrete types
```

A validator handler gets `Handler Main.mint requires a concrete type`, and a property test with generic
arguments gets a similar message. **Fix:** replace the type variable with a concrete type, such as
`List Int`.

### `ELCI2010`: unknown record field

```elm
main : Int
main =
    let
        order =
            { amount = 5 }
    in
    order.price
```

```text
src/Main.elm:14:10: ELCI2010: Missing field 'price'; available fields: [amount]
```

**Fix:** use one of the listed fields. When Elci cannot tell which record a field access belongs to, add
a signature with the record type.

### `ELCI2011` and `ELCI2012`: invalid type declarations

Two custom types in one module cannot share a constructor name:

```text
src/Main.elm:9:6: ELCI2011: Duplicate constructor Buy
```

A type applied to the wrong number of arguments, and type aliases that refer to each other in a loop,
are `ELCI2012`:

```text
src/Main.elm:4:1: ELCI2012: Type List expects 1 arguments, got 2
src/Main.elm:4:12: ELCI2012: Cyclic type alias: A -> B -> A
```

An unknown type name in a signature is also `ELCI2012`, but it is currently reported without a location:

```text
elci: ELCI2012: Type is not declared or imported in this module: Amount
```

**Fix:** declare or import the type, or correct its spelling.

## Validator errors

### `ELCI2013`: type cannot cross the boundary

A datum, redeemer or parameter must have a strict Data encoding:

```elm
mint : (Int -> Int) -> ScriptContext -> Bool
mint _ _ =
    True
```

```text
elci: my-project/elci.toml:6: [validators.main]
my-project/src/Main.elm:6:1: ELCI2013: Functions cannot be serialized as datum or redeemer
```

Ledger types that contain a `Value`, such as `TxOut`, are rejected the same way:
`org.julclang.ledger.TxOut has no approved strict Data boundary (BOUNDARY); ...`. **Fix:** use your own
record or custom type, or a ledger type with an approved boundary such as `TxOutRef` or `PubKeyHash`.

The same code rejects an `Array` stored anywhere in data (a record, list or tuple field, or a datum,
redeemer or parameter), in any project:

```elm
module Main exposing (main)

import Array exposing (Array)


type alias Book =
    { pages : Array Int }


main : Int
main =
    let
        books =
            [ { pages = Array.fromList [ 1 ] } ]
    in
    List.length books
```

```text
src/Main.elm:10:1: ELCI2013: Arrays have no Data encoding and cannot be stored in data; keep a List and convert it with Array.fromList
```

### `ELCI2014`: handler signature does not match

A spend handler with only a redeemer and context:

```elm
spend : () -> ScriptContext -> Bool
spend _ _ =
    True
```

```text
elci: my-project/elci.toml:6: [validators.main]
my-project/src/Main.elm:6:1: ELCI2014: Handler Main.spend does not have the spend signature datum -> redeemer -> ScriptContext -> Bool that [validators.main] needs (0 parameters); its type is () -> ScriptContext -> Bool (Type mismatch: expected Bool, found ScriptContext -> Bool)
```

The message gives the signature the table needs, how many parameters it declares and the handler's
actual type. The same code reports a missing parameter argument (`does not have the mint signature
owner -> redeemer -> ScriptContext -> Bool that [validators.main] needs (1 parameter)`) and a datum that
should be a `Maybe` (`uses spend-datum = "optional", so its datum must have type Maybe d, not Int`). **Fix:** make the signature match the table in
[`elci.toml`](/reference/configuration/): parameters first, then the datum (for `spend`), the redeemer
and the `ScriptContext`.

### `ELCI2103`: not exposed

```text
elci: my-project/elci.toml:6: [validators.main]
my-project/src/Main.elm:11:1: ELCI2103: Handler Main.mint must be exposed by its module
```

**Fix:** add the handler, or the `[entrypoint]` function, to the module's `exposing` list.

## Module and import errors

### `ELCI2101`, `ELCI2102` and `ELCI2104`

A module that is neither a source file nor a `[java-modules]` mapping, or an unknown ledger type, is
`ELCI2101`, reported at the import:

```elm
import Julc.Nope as Nope
```

```text
src/Main.elm:3:1: ELCI2101: Missing source module: Julc.Nope; add Julc/Nope.elm under a source directory, or map a Java library to it under [java-modules]
```

```elm
import Julc.Ledger exposing (Hash)
```

```text
src/Main.elm:3:1: ELCI2101: Unknown ledger record type Hash
```

The test library (`Expect`, `Scenario`, `Property`) is only for test modules. A source module that imports
it is rejected. `elci test` reports:

```text
src/Main.elm:3:1: ELCI2101: Only test modules can import Expect; it is part of the test library, not available to elci build
```

`elci check` and `elci build` report it when a validator reaches that module:

```text
src/Main.elm:3:1: ELCI2101: Expect is part of the test library; only test modules can import it
```

Imports of existing modules fail with `ELCI2104` when the name is not exposed:

```elm
import Map exposing (lookup)
```

```text
src/Main.elm:3:1: ELCI2104: Module does not expose 'lookup': Map
```

`ELCI2104` also covers a module you use without importing it, `exposing (..)` on a Java module, a file
whose `module` line does not match its path, and import cycles:

```text
src/Main.elm:6:5: ELCI2104: Module Pricing is not imported or does not expose 'fee'
src/Main.elm:3:1: ELCI2104: Java modules cannot be imported with exposing (..); list the exported names you use
src/Pricing.elm:1:1: ELCI2104: Module header Finance.Pricing does not match its source path, which names module Pricing
src/Pricing.elm:3:1: ELCI2104: Import cycle: Main -> Pricing -> Main
```

Declaring a name that is already imported, or importing one name from two modules, is `ELCI2102`:

```text
src/Main.elm:4:1: ELCI2102: Declaration conflicts with imported name not
src/Main.elm:4:1: ELCI2102: Ambiguous name fee
```

**Fix:** import the module, correct the exposed name (`Map.get`, not `lookup`), rename your declaration,
or use qualified names for one of the imports.

### `ELCI2105`: unsupported Java export

```elm
main : Int
main =
    case Math.divMod 7 2 of
        _ ->
            0
```

```text
src/Main.elm:8:10: ELCI2105: org.julclang.stdlib.lib.MathLib.divMod: Unsupported on-chain source type: Tuple2<BigInteger, BigInteger>
```

**Fix:** use another export, such as `Math.floorDiv` and `Math.floorMod`, or Elci operators. See
[Java libraries](/advanced/java-libraries/) for every reason an export can be rejected.

## Code generation errors

### `ELCI3001`: type is not concrete where it is used

```elm
count : List a -> Int
count items =
    List.length items


main : Int
main =
    count []
```

```text
src/Main.elm:11:5: ELCI3001: Cannot specialize count at an unconstrained type List t -> Int; add a type annotation so the type is known here
```

Nothing tells Elci what the empty list contains, so it cannot pick a specialization. **Fix:** give the
value a type:

```elm
main : Int
main =
    let
        noAmounts : List Int
        noAmounts =
            []
    in
    count noAmounts
```

A core function is reported the same way, at your call: `List.length []` gives `ELCI3001: Cannot
specialize List.length at an unconstrained type List t -> Int; ...`. A `Julc.Data.decode` whose result
type is never determined is reported at the enclosing declaration:

```text
src/Main.elm:6:1: ELCI3001: Unresolved source type: t
```

### `ELCI3002`: specialization limit

A program may create at most 256 specializations of its generic functions. This happens with
polymorphic recursion that inference did not already reject. **Fix:** make the recursive function
monomorphic.

### `ELCI3003`: no Data encoding

Generic Java methods and `Julc.Data` conversions need a type with a Data encoding:

```text
src/Main.elm:8:5: ELCI3003: Type argument T = Unit of firstOr must have a Plutus Data encoding (DATA_ENCODABLE); functions, unit and native values do not
src/Main.elm:9:5: ELCI3003: No strict decoder for org.julclang.ledger.Value: The library provider cannot supply decode_strict record Value: [JULC0051] Library request decode_strict record Value is not supported: org.julclang.ledger.Value does not approve DECODE_STRICT
```

**Fix:** use a Data-encodable type. Functions, `()`, `Array`, `Value` and types containing a `Value`
have no encoding.

## Configuration and backend errors

### `ELCI4001`: configuration or command line

```text
elci: ELCI4001: unknown validator 'treasury'; configured: main
elci: ELCI4001: eval runs an [entrypoint] function; test validators with elci test
elci: ELCI4001: parameter owner is not a well-formed org.julclang.ledger.PubKeyHash value
```

The message says what to change. The [CLI](/reference/cli/) and [`elci.toml`](/reference/configuration/)
references list the others. Some configuration errors have no code yet; they are listed below.

### `ELCI4002` and `ELCI4003`: backend

`ELCI4002` means Julc's PIR verifier rejected the code Elci generated. It is reported at the Elci
declaration involved and keeps Julc's own `JULC00xx` code in the message. `ELCI4003` means the Julc
backend in use lacks a capability the program needs, which happens only with a mismatched Julc build.
Both point at a compiler problem rather than a mistake in your code: please report them with the
program that triggers them.

## Errors without a code

A few errors are still reported without a code or location. Each is printed after `elci:`:

| Message | Cause |
| --- | --- |
| `On-chain Java source not found: org.julclang.stdlib.lib.NopeLib` | An imported `[java-modules]` class that does not exist. |
| `Cannot resolve type: ?. Supported types: ...` | A Java library that uses a wildcard type such as `JulcList<?>`. |
| `Unknown type: T. Did you mean: Vote?` | A Java library that declares a parameterized record such as `Box<T>`. |
| `my-project/elci.toml` | The directory has no `elci.toml`. |
| `my-project/libs/pricing.jar` | A `library-jars` file does not exist. |

Configuration mistakes that have no code, such as an unknown key, are listed on the
[`elci.toml`](/reference/configuration/) page.
