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
| `ELCI2003` | Unsupported equality, ordering or append type; a constrained type variable that is missing or misnamed |
| `ELCI2004` | Missing required top-level signature |
| `ELCI2005` | Unknown value name |
| `ELCI2006` | Duplicate binder or unreachable pattern |
| `ELCI2007` | Non-exhaustive `case` expression |
| `ELCI2008` | Eager recursive value cycle |
| `ELCI2009` | Non-concrete handler, entrypoint or property |
| `ELCI2010` | Unknown or unconstrained record field |
| `ELCI2011` | Duplicate type or constructor, or a built-in constructor or type name (`Just`, `Nothing`, `True`, `False`; `Int`, `Maybe`, `List` and the other built-in types) |
| `ELCI2012` | Invalid type, alias, arity or alias cycle; `Tuple` or `Unit` written as a type name |
| `ELCI2013` | Unsupported serialized boundary type, or a value without a Data encoding (a function or an `Array`) stored in data |
| `ELCI2014` | Validator handler signature does not match its purpose, parameters or datum profile |
| `ELCI2101` | Unknown or missing module or imported export; a source module importing the test library |
| `ELCI2102` | Ambiguous name: two imports expose different things under it |
| `ELCI2103` | Entrypoint or handler is not exposed |
| `ELCI2104` | Module path, ownership or cycle error; a reserved module name or alias; a name a module lacks, an Elm function Elci doesn't have, or a module used without its import |
| `ELCI2105` | Unsupported Java export signature, with the provider's reason |
| `ELCI3001` | The inferred program cannot be lowered, such as `Julc.Data.decode` or a Java library generic at a type nothing determines |
| `ELCI3002` | Specialization limit or polymorphic recursion |
| `ELCI3003` | A generic library type argument or a Data conversion needs a Data encoding |
| `ELCI4001` | Invalid project configuration or command-line request, including a `[java-modules]` key that names a core module |
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
parentheses` (for `1 < 2 < 3`), `Mixing (<|) and (|>) needs parentheses: they associate in opposite
directions, as in Elm` (for `f <| x |> g`), `Extensible records are unsupported; use a closed record` and
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

**Fix:** `7 // 2`, together with `modBy` or `remainderBy` when you need the rest. The function `(/)` is
rejected the same way, with a suggestion to use `(//)`.

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

Arithmetic operators name the types they accept, and suggest the right operator where there is one:

```text
src/Main.elm:6:7: ELCI2002: The operator + works on Int values, not String; use ++ to append
```

Comparisons on a type that has no order are `ELCI2003`, below.

Recursion that would need an infinitely growing type, such as a generic function that calls itself with
a tuple of its argument, is reported as `ELCI2002: Infinite type`.

### `ELCI2003`: unsupported equality, ordering or append

```elm
double : Int -> Int
double x =
    x * 2


main : Bool
main =
    double == double
```

```text
src/Main.elm:11:12: ELCI2003: The operator == cannot compare Int -> Int values: functions have no equality
```

Equality is rejected for functions, `Data`, `Map`, `Array` and imported types without approved
equality. When the offending type is inside another, the message names it:
`The operator == cannot compare List (Int -> Int) values: Int -> Int is part of them, and functions have no
equality`. `++` needs a `String` or a `List`: `1 ++ 2` gives
`ELCI2003: The operator ++ appends String or List values, not Int`.

**Fix:** compare the values you care about, such as a map's lookups or a decoded datum.

Ordering (`<`, `compare`, `max`, `List.sort` and the rest) needs a
[comparable type](/reference/core-modules/#comparable-types). The message names the operation and the part
of the type that isn't comparable:

```elm
main : Bool
main =
    ( 1, True ) < ( 2, False )
```

```text
src/Main.elm:6:17: ELCI2003: The operator < needs comparable values, but Bool, in ( Int, Bool ), is not comparable. Comparable types are Int, String, Bytes, byte-string ledger types such as PubKeyHash, and lists and tuples of comparable types
```

For records and custom types, the message goes on to suggest a key:

```text
src/Main.elm:10:5: ELCI2003: `List.sort` needs comparable values, but { age : Int, name : String } is not comparable. Comparable types are Int, String, Bytes, byte-string ledger types such as PubKeyHash, and lists and tuples of comparable types. Compare a comparable field instead, or sort with List.sortBy or List.sortWith
```

**Fix:** compare a comparable field, or sort with `List.sortBy (\person -> person.age)` or
`List.sortWith`.

A signature whose type variable is compared or appended must say so with its name, as in Elm
([constrained type variables](/reference/language/#constrained-type-variables)):

```elm
larger : a -> a -> a
larger x y =
    if x > y then
        x

    else
        y
```

```text
src/Main.elm:6:10: ELCI2003: The operator > compares values of type `a`, but the annotation of `larger` says `a` can be any type. Rename it `comparable` (as in Elm) to require comparable values
```

**Fix:** write `larger : comparable -> comparable -> comparable`. For `++`, the message suggests
`appendable`. A caller that then passes the wrong type is reported at the call:
``ELCI2003: `twice` appends String or List values, not Int``. Elm's `number` has its own message:

```text
src/Main.elm:4:1: ELCI2003: The annotation of `total` uses `number`, Elm's type variable for Int or Float. Elci has one number type: write Int
```

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

**Fix:** correct the spelling, or import the module that defines the name. A misspelled name from a Java
library module gets a hint:

```text
src/Main.elm:8:5: ELCI2005: Unknown name 'Math.maximum'; check that the imported module exports it
```

A qualified name from a core module that doesn't exist is `ELCI2104`, and lists what the module has; see
[module and import errors](#elci2101-elci2102-and-elci2104).

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

Two custom types in one module cannot share a constructor name. No module may declare the built-in
constructors `Just`, `Nothing`, `True` or `False`, or a type named `Int`, `Bool`, `String`, `Bytes`,
`Data`, `List`, `Maybe`, `Map`, `Array` or `Float`:

```text
src/Main.elm:9:6: ELCI2011: Duplicate constructor Buy
src/Main.elm:4:6: ELCI2011: Nothing is a constructor of the built-in Maybe type; choose another name
src/Main.elm:4:6: ELCI2011: Bytes is a built-in type; choose another name
```

A type applied to the wrong number of arguments, and type aliases that refer to each other in a loop,
are `ELCI2012`:

```text
src/Main.elm:4:1: ELCI2012: Type List expects 1 arguments, got 2
src/Main.elm:4:12: ELCI2012: Cyclic type alias: A -> B -> A
```

Tuple and unit types have their own syntax. `Tuple Int Int` and `Unit` are `ELCI2012`:

```text
src/Main.elm:4:8: ELCI2012: Write tuple types as ( a, b ) or ( a, b, c )
src/Main.elm:4:10: ELCI2012: Write the unit type as ()
```

An unknown type name in a signature is also `ELCI2012`, reported at the declaration:

```text
src/Main.elm:4:1: ELCI2012: Type is not declared or imported in this module: Amount
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

A qualified name that a core module lacks lists what the module has. For Elm functions that Elci doesn't
provide, the message says what to use instead:

```text
src/Main.elm:8:5: ELCI2104: Elci's Map module has no lookup. It has empty, filter, foldl, fromList, get, insert, isEmpty, keys, map, member, remove, singleton, size, toList, update, values
src/Main.elm:6:5: ELCI2104: Elci has no Debug.log; use Debug.trace message value, which returns value
src/Main.elm:6:5: ELCI2104: Elci has no String.length; a String is UTF-8 text; Bytes.length (Bytes.fromString s) is its byte length
src/Main.elm:6:5: ELCI2104: Elci has no Bytes.lessThan; bytes are comparable: write a < b
```

Using a module that isn't imported by default, such as `Bytes`, `Map`, `Array` or `Julc.Data`, without
its import names the import to add:

```text
src/Main.elm:6:5: ELCI2104: Bytes.fromString needs the Bytes module; add `import Bytes`
```

A module imported with an alias is used through the alias:

```text
src/Main.elm:8:5: ELCI2104: Map is imported as M; write M.get
```

`ELCI2104` also covers a source module you use without importing it, `exposing (..)` on a Java module, a
file whose `module` line does not match its path, and import cycles:

```text
src/Main.elm:6:5: ELCI2104: Module Pricing is not imported or does not expose 'fee'
src/Main.elm:3:1: ELCI2104: Java modules cannot be imported with exposing (..); list the exported names you use
src/Pricing.elm:1:1: ELCI2104: Module header Finance.Pricing does not match its source path, which names module Pricing
src/Pricing.elm:3:1: ELCI2104: Import cycle: Main -> Pricing -> Main
```

Core module names are reserved, for modules and for aliases (see
[reserved names](/reference/language/#reserved-names)):

```text
src/String.elm:1:1: ELCI2104: The module name String is taken by Elci's core library; rename this module
src/Dict.elm:1:1: ELCI2104: The module name Dict is reserved for Elci's core library; rename this module
src/Main.elm:3:1: ELCI2104: The alias String names a core module that every module imports; choose another alias for Utils
```

A name that two imports expose, one of them possibly a default import, is `ELCI2102` where you use it:

```text
src/Main.elm:8:5: ELCI2102: max is ambiguous: it could be Basics.max or Utils.max. Write the one you mean, like Basics.max, or stop exposing the other
```

An ambiguous type name is reported at the declaration whose annotation uses it:

```text
src/Main.elm:6:1: ELCI2102: The type Order is ambiguous: it could be Basics.Order or Shop.Order. Write the one you mean, like Basics.Order, or stop exposing the other
```

**Fix:** import the module, correct the name (`Map.get`, not `lookup`), rename your module or alias, or
qualify one of the ambiguous names. Listing the name explicitly, as in `import Utils exposing (max)`, also
resolves a clash with a default import.

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

### `ELCI3001`: type is not known where it is used

A type that nothing in the program determines, such as the element type of `[]`, is fine for most code:
`List.length []` is `0` and `Julc.Data.encode []` is an empty list, as in Elm. Operations that create a
value of the type need to know it. These are `Julc.Data.decode` and generic Java library functions:

```elm
import Julc.Data as Data


main : Int
main =
    List.length (Data.decode (Data.encode [ 1, 2 ]))
```

```text
src/Main.elm:8:18: ELCI3001: Cannot determine the type converted by Julc.Data.decode; add a type annotation
```

`List.length` works on any list, so nothing tells Elci what the decoded list contains, and it cannot pick
a decoder. **Fix:** give the value a type:

```elm
main : Int
main =
    let
        amounts : List Int
        amounts =
            Data.decode (Data.encode [ 1, 2 ])
    in
    List.length amounts
```

A generic Java library function needs its type arguments. When a core module such as `Map` uses one
at a type nothing in your program determines, the error is reported at your call:

```text
src/Main.elm:8:5: ELCI3001: `Map.size` is used here at a type that is not known (Map a b -> Int); add a type annotation, for example to a let binding
```

That one comes from `Map.size Map.empty`; annotate the map (`noPrices : Map String Int`).

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
