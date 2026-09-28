---
title: Diagnostics
description: How to read an Elci error, the JSON report for tools, and every ELCI error code with real messages and fixes for the errors you will meet most often.
sidebar:
  order: 6
---

Elci reports each error with a stable code and a source location, shows the source line it is about and
suggests a fix. This page explains how to read a diagnostic and how tools can read one. It then lists
every code, and shows the most common errors with a small program that triggers each one, the exact
output and the fix.

## Reading a diagnostic

A compile error starts with one line, `path:line:column: code: message`. A blank line follows, then the
source line with a caret under the reported token, then any hints:

```text
src/Main.elm:6:5: ELCI2005: Unknown name 'lenght'

6|     lenght [ 1, 2 ]
       ^^^^^^
Hint: did you mean `List.length`?
```

- The **first line** has the same form for every error, so editors and scripts can parse it. The
  **path** is the file of the module where the problem is, which may be a module imported by the one you
  compiled. A problem inside a bundled module such as `List` is reported where your code uses it, or
  otherwise as `<bundled>/List.elm`.
- The **code** names the class of error and never changes between releases. The **message** says what is
  wrong in terms of your code: which argument, branch, list element, operator side, annotation or field
  has the wrong type, or which patterns a `case` doesn't cover.
- The **excerpt** shows the line with its number. The caret underlines the reported token, such as a
  qualified name or a string literal with its quotes. For an error at the end of the file, it points just
  past the last code. An error in a bundled module has no excerpt.
- Each **hint** suggests a likely fix: a close name, a conversion such as `String.fromInt`, or an import
  to add. An error has zero or more hints.

Types in messages are written as in source, in backticks: `Result a Int`, `Order`. When two types of one
name meet in a message, both are qualified, as in `Main.Value` and `Julc.Ledger.Value`.

For a validator, the `elci.toml` table follows the diagnostic, on its own line:

```text
my-project/src/Main.elm:6:1: ELCI2013: Functions cannot be serialized as datum or redeemer

6| mint : (Int -> Int) -> ScriptContext -> Bool
   ^^^^

In my-project/elci.toml:6: [validators.main]
```

The compiler stops at the first error: fix it and run the command again. Every error makes the command
exit with status 1. Codes starting with `ELCI1` come from the lexer and parser, `ELCI20` from type
checking, `ELCI21` from modules and imports, `ELCI3` from code generation and `ELCI4` from configuration
and the backend.

Some examples below show only the first line of a diagnostic, without its excerpt and hints.

## JSON reports for tools

`elci check`, `elci build`, `elci eval` and `elci test` take `--report=json`. An error is then printed on
standard error as one JSON object, on one line, as Elm's `--report=json` does:

```text
$ elci check --report=json
{"code":"ELCI2005","path":"src/Main.elm","line":6,"column":5,"message":"Unknown name 'lenght'","hints":["did you mean `List.length`?"],"excerpt":"6|     lenght [ 1, 2 ]\n       ^^^^^^","context":null}
```

| Field | Type | Contents |
| --- | --- | --- |
| `code` | string or null | The `ELCI` code; null for a failure that has no code |
| `path` | string or null | The file, as on the first line of the text report |
| `line`, `column` | number or null | The position, counting from 1 |
| `message` | string | The message, without the path and code |
| `hints` | array of strings | The hints, without `Hint:`; empty when there are none |
| `excerpt` | string or null | The source line and the caret line, separated by `\n`; null when there is no source to show |
| `context` | string or null | For a failure in a named validator, its `elci.toml` line and table; otherwise null |

A failure in a validator names its table in `context`:

```text
{"code":"ELCI2013","path":"my-project/src/Main.elm","line":6,"column":1,"message":"Functions cannot be serialized as datum or redeemer","hints":[],"excerpt":"6| mint : (Int -> Int) -> ScriptContext -> Bool\n   ^^^^","context":"my-project/elci.toml:6: [validators.main]"}
```

A failure without a source location keeps the same fields, with null location fields:

```text
{"code":"ELCI4001","path":null,"line":null,"column":null,"message":"unknown validator 'treasury'; configured: main","hints":[],"excerpt":null,"context":null}
```

The exit status is the same with or without the option. Results stay text on standard output: the lines of
`elci check` and `elci build`, the value and budget of `elci eval` or its `evaluation failed:` line, and
test results, including failing tests. The other commands reject the option with `ELCI4001`.

## All codes

| Code | Meaning |
| --- | --- |
| `ELCI1001` | Invalid source character or literal |
| `ELCI1002` | Malformed or unsupported syntax |
| `ELCI2001` | Invalid pattern or expression form |
| `ELCI2002` | Type mismatch, or a type that would have to contain itself |
| `ELCI2003` | Unsupported equality, ordering or append type; a constrained type variable that is missing or misnamed; a `Dict` decoded from Data whose keys aren't comparable |
| `ELCI2004` | Missing required top-level signature |
| `ELCI2005` | Unknown value name |
| `ELCI2006` | Duplicate binder or unreachable pattern |
| `ELCI2007` | Non-exhaustive `case` expression |
| `ELCI2008` | Eager recursive value cycle |
| `ELCI2009` | Non-concrete handler, entrypoint or property |
| `ELCI2010` | Unknown or unconstrained record field |
| `ELCI2011` | Duplicate type or constructor, or a built-in constructor or type name (`Just`, `Nothing`, `True`, `False`; `Int`, `Maybe`, `List` and the other built-in types) |
| `ELCI2012` | Invalid type, alias, arity or alias cycle; `Tuple` or `Unit` written as a type name |
| `ELCI2013` | Unsupported serialized boundary type, such as a `Value` in a datum, or a value without a Data encoding (a function or an `Array`) stored in data |
| `ELCI2014` | Validator handler signature does not match its purpose, parameters or datum profile |
| `ELCI2101` | Unknown or missing module or imported export; a source module importing the test library |
| `ELCI2102` | Ambiguous name: two imports expose different things under it |
| `ELCI2103` | Entrypoint or handler is not exposed |
| `ELCI2104` | Module path, ownership or cycle error; a reserved module name or alias; a name a module lacks, an Elm function Elci doesn't have, or a module used without its import |
| `ELCI2105` | Unsupported Java export signature, with the provider's reason; a Java export whose result can hold a `Value` |
| `ELCI3001` | The inferred program cannot be lowered, such as `Julc.Data.decode` or a Java library generic at a type nothing determines |
| `ELCI3002` | Specialization limit or polymorphic recursion |
| `ELCI3003` | A generic library type argument or a Data conversion needs a Data encoding; a Java library generic used at a type containing a `Dict` or a `Value` |
| `ELCI4001` | Invalid project configuration or command-line request, including a `[java-modules]` key that names a core module and an `elci apply` parameter that doesn't fit its type |
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

6|     1.5
        ^
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

5| first (x :: _) =
          ^
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

### `ELCI2001`: invalid expression or pattern

```elm
main : Int
main =
    7 / 2
```

```text
src/Main.elm:6:7: ELCI2001: Float division is unsupported; use // for integer quotient

6|     7 / 2
         ^
```

**Fix:** `7 // 2`, together with `modBy` or `remainderBy` when you need the rest. The function `(/)` is
rejected the same way, with a suggestion to use `(//)`.

`ELCI2001` also covers patterns that can't match their value's shape. A constructor pattern must give the
constructor all of its arguments:

```elm
main : Int
main =
    case Just 1 of
        Just x y ->
            x

        Nothing ->
            0
```

```text
src/Main.elm:7:9: ELCI2001: The constructor Just takes 1 argument, but this pattern gives it 2

7|         Just x y ->
           ^^^^
```

A misspelt constructor in a pattern gets a suggestion:

```text
src/Main.elm:7:9: ELCI2001: Unknown constructor Jsut in this pattern

7|         Jsut n ->
           ^^^^
Hint: did you mean `Just`?
```

A record pattern such as `{ index }` can't take apart a ledger record. Read its fields instead:

```text
src/Main.elm:10:9: ELCI2001: TxOutRef is a library record, which a pattern cannot take apart; read its fields with `.index` instead
```

## Type errors

### `ELCI2002`: type mismatch

```elm
main : String
main =
    String.fromInt "42"
```

```text
src/Main.elm:6:20: ELCI2002: The 1st argument to `String.fromInt` is `String`, but `String.fromInt` needs `Int`

6|     String.fromInt "42"
                      ^^^^
```

The message names the part of the code whose type differs, and the type it needs:

| Where | Example message |
| --- | --- |
| An argument | `` The 1st argument to `String.fromInt` is `String`, but `String.fromInt` needs `Int` `` |
| A value piped in with `\|>` | `` The 1st argument to `double`, the value piped in, is `String`, but `double` needs `Int` `` |
| Too many arguments | `` `max` takes 2 arguments, but it is given 3 `` |
| A value applied like a function | `` `fee` is `Int`, not a function, so it cannot take an argument `` |
| The condition of an `if` | `` The condition of this if is `Int`, but it must be `Bool` `` |
| The branches of an `if` | `` The branches of this if have different types: the then branch is `Int`, but the else branch is `String` `` |
| A branch of a `case` | `` The 2nd branch of this case is `String`, but the first branch is `Int` `` |
| A pattern | `` This pattern matches `Maybe a`, but the value it is matched against is `Int` `` |
| A list element | `` The 3rd element of this list is `String`, but the elements before it are `Int` `` |
| An operator | `` The left side of :: is `String`, but the list on the right holds `Int` ``, `` The two sides of ++ have different types: the left is `String`, but the right is `Int` `` |
| A composition | `` The left side of >> returns `String`, but the right side takes `Int` `` |
| An annotation | `` The annotation of `add` says it returns `Int`, but its body is `String` ``, `` The annotation of `add` gives it 1 argument, but its definition has 2 parameters `` |
| A record field | `` The field `owner` is `String`, but it is used here as `Int` `` |

A too-many-arguments error also hints that a missing comma or parentheses can make values look like extra
arguments.

A signature that claims more than the body delivers is also a mismatch:

```elm
identity : a -> a
identity value =
    value + 1
```

```text
src/Main.elm:4:1: ELCI2002: The annotation of `identity` says `a` can be any type, but its definition needs `Int`

4| identity : a -> a
   ^^^^^^^^
Hint: write `Int` in the annotation, or make the definition work for any type
```

**Fix:** make the signature match the body (`increment : Int -> Int`), or the body match the signature. An
annotation with two type variables that the definition needs to be the same says
`` The annotation of `pick` says `a` and `b` can be different types, but its definition needs them to be the same ``.
The annotations of mutually recursive functions are all read before any body is checked, so a mistake is
reported in the body that makes it.

Arithmetic operators name the types they accept, and suggest the right operator where there is one:

```text
src/Main.elm:6:7: ELCI2002: The operator + works on Int values, not String; use ++ to append
```

Comparisons on a type that has no order are `ELCI2003`, below.

**Hints.** A mismatch between types that are often confused carries a hint with the conversion:

```elm
main : Int
main =
    List.head [ 1, 2 ] + 1
```

```text
src/Main.elm:6:24: ELCI2002: The operator + works on Int values, not Maybe Int

6|     List.head [ 1, 2 ] + 1
                          ^
Hint: this is a Maybe: handle `Nothing` with a case, or use `Maybe.withDefault`
```

The hints cover `Int` and `String` (`String.fromInt`), `String` and `Bytes` (`Bytes.fromString`,
`Bytes.toString`), `Bytes` and a ledger hash such as `PubKeyHash` (the constructor, or the `.hash` field),
a `Maybe` or `Result` and a plain value (a `case`, `Maybe.withDefault`, `Result.withDefault` or `Just`),
`Value` and `Int` (`Value.lovelaceOf`, `Value.lovelace`), `Data` and a typed value (`Data.decode`,
`Data.encode`), and a function where a value is needed (a missing argument). An error shows at most two
hints, and only those that fit the types. A pattern of the wrong type gets no hint.

A type that would have to contain itself, as when a generic function calls itself with a tuple of its
argument, is reported as
`` ELCI2002: This needs a type that contains itself: `a` would have to be `( a, a )` ``.

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

11|     double == double
               ^^
```

Equality is rejected for functions, `Map`, `Array` and imported types without approved equality, such as
`TxOut`. A `Dict` has equality when its values do, and a `Value` and `Data` always have it. When the
offending type is inside another, the message names it:
`The operator == cannot compare List (Int -> Int) values: Int -> Int is part of them, and functions have no
equality`. `++` needs a `String` or a `List`: `1 ++ 2` gives
`ELCI2003: The operator ++ appends String or List values, not Int`.

**Fix:** compare the values you care about, such as a map's lookups or a decoded datum, or keep keyed data
in a `Dict`, which has equality.

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

6|     ( 1, True ) < ( 2, False )
                   ^
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

6|     if x > y then
            ^
```

**Fix:** write `larger : comparable -> comparable -> comparable`. For `++`, the message suggests
`appendable`. A caller that then passes the wrong type is reported at the call:
``ELCI2003: `twice` appends String or List values, not Int``. Elm's `number` has its own message:

```text
src/Main.elm:4:1: ELCI2003: The annotation of `total` uses `number`, Elm's type variable for Int or Float. Elci has one number type: write Int
```

Decoding a `Dict` checks that its keys are in order, so a `Dict` in a datum, a redeemer, a parameter or a
`Julc.Data.decode` result needs comparable keys:

```text
src/Main.elm:9:5: ELCI2003: A Dict decoded from Data needs comparable keys, to check that they are in order; Bool is not comparable. Use a Map instead
```

**Fix:** use a `Map`, whose keys may be any type with a Data encoding.

### `ELCI2004`: missing signature

```elm
double x =
    x * 2
```

```text
src/Main.elm:4:1: ELCI2004: Top-level declarations require a type signature

4| double x =
   ^^^^^^
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

6|     lenght [ 1, 2 ]
       ^^^^^^
Hint: did you mean `List.length`?
```

**Fix:** correct the spelling, or import the module that defines the name. The hint lists up to three
close names. It looks first at the names the module can write unqualified, local ones included, and then
at qualified names. A name is close when it differs only in case, by one edit for a name of three to five
characters, or by two edits for a longer name. Swapping two neighbouring letters counts as one edit.

A name that an imported module exposes, but that its import doesn't list, gets a hint that says how to
reach it. Here the module `Helper` exposes `double`:

```elm title="src/Main.elm"
module Main exposing (main)

import Helper


main : Int
main =
    double 4
```

```text
src/Main.elm:8:5: ELCI2005: Unknown name 'double'

8|     double 4
       ^^^^^^
Hint: write `Helper.double`, or expose `double` in its import
```

A name that a Java library module doesn't export is reported with a reminder to check its exports:

```text
src/Main.elm:8:5: ELCI2005: Unknown name 'Math.maximum'; check that the imported module exports it
```

A qualified name whose module is missing, not imported or lacks the name is `ELCI2104`; see
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

13|         Just _ ->
            ^^^^
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
src/Main.elm:12:5: ELCI2007: This case does not cover every possibility; it is missing `Cancel`

12|     case action of
        ^^^^
Hint: add a branch for each missing pattern
```

**Fix:** add a `Cancel ->` branch. Prefer naming every constructor over `_ ->`, so that adding a
constructor later points you to every `case` to update.

The message lists the missing patterns as you would write them, including nested ones:

```elm
describe : Maybe (Result String Int) -> String
describe value =
    case value of
        Just (Ok n) ->
            String.fromInt n
```

```text
src/Main.elm:6:5: ELCI2007: This case does not cover every possibility; it is missing `Nothing` and `Just (Err _)`

6|     case value of
       ^^^^
Hint: add a branch for each missing pattern
```

The list is complete: a branch for each pattern in it makes the `case` exhaustive. It shows up to three
patterns, then how many more there are, as in
`` it is missing `Square _`, `Triangle _ _ _`, `Line` and 1 more ``. Lists and literals are covered too:
a `case` on a list with branches for `[]` and `[ _ ]` is missing `_ :: _ :: _`, and one on an `Int` or a
`String` without a `_` branch is missing `_`.

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

7|         a =
           ^
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

4| main : List a
   ^^^^
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
src/Main.elm:10:10: ELCI2010: Missing field 'price'; available fields: [amount]

10|     order.price
             ^
```

**Fix:** use one of the listed fields. A field name close to one of them, such as `order.amont`, also
gets the hint ``did you mean `amount`?``. When Elci cannot tell which record a field access belongs to,
add a signature with the record type.

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
src/Main.elm:4:1: ELCI2012: The type List takes 1 argument, but it is given 2
src/Main.elm:4:12: ELCI2012: Cyclic type alias: A -> B -> A
```

Tuple and unit types have their own syntax. `Tuple Int Int` and `Unit` are `ELCI2012`:

```text
src/Main.elm:4:8: ELCI2012: Write tuple types as ( a, b ) or ( a, b, c )
src/Main.elm:4:10: ELCI2012: Write the unit type as ()
```

An unknown type name in a signature is also `ELCI2012`, reported at the declaration. A close type name is
suggested:

```elm
main : Strin
main =
    "x"
```

```text
src/Main.elm:4:1: ELCI2012: Type is not declared or imported in this module: Strin

4| main : Strin
   ^^^^
Hint: did you mean `String`?
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
my-project/src/Main.elm:6:1: ELCI2013: Functions cannot be serialized as datum or redeemer

6| mint : (Int -> Int) -> ScriptContext -> Bool
   ^^^^

In my-project/elci.toml:6: [validators.main]
```

A `Value`, and ledger types that contain one such as `TxOut`, are rejected the same way:

```text
my-project/src/Main.elm:7:1: ELCI2013: org.julclang.ledger.Value has no approved strict Data boundary (BOUNDARY); for example records containing the map-encoded Value cannot be checked

In my-project/elci.toml:6: [validators.main]
```

**Fix:** use your own record or custom type, or a ledger type with an approved boundary such as `TxOutRef`
or `PubKeyHash`. For an amount of tokens, store a `List ( PolicyId, TokenName, Int )` and rebuild the value
with `Value.fromList`.

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

10| main : Int
    ^^^^
```

### `ELCI2014`: handler signature does not match

A spend handler with only a redeemer and context:

```elm
spend : () -> ScriptContext -> Bool
spend _ _ =
    True
```

```text
my-project/src/Main.elm:6:1: ELCI2014: Handler Main.spend does not have the spend signature datum -> redeemer -> ScriptContext -> Bool that [validators.main] needs (0 parameters); its type is () -> ScriptContext -> Bool; it has `Bool` where the configuration needs `ScriptContext -> Bool`

6| spend : () -> ScriptContext -> Bool
   ^^^^^

In my-project/elci.toml:6: [validators.main]
```

The message gives the signature the table needs, how many parameters it declares and the handler's
actual type. The part in parentheses names the first pair of types that differ. The same code reports a missing parameter argument (`does not have the mint signature
owner -> redeemer -> ScriptContext -> Bool that [validators.main] needs (1 parameter)`) and a datum that
should be a `Maybe` (`uses spend-datum = "optional", so its datum must have type Maybe d, not Int`). **Fix:** make the signature match the table in
[`elci.toml`](/reference/configuration/): parameters first, then the datum (for `spend`), the redeemer
and the `ScriptContext`.

### `ELCI2103`: not exposed

```text
my-project/src/Main.elm:11:1: ELCI2103: Handler Main.mint must be exposed by its module

In my-project/elci.toml:6: [validators.main]
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

3| import Julc.Nope as Nope
   ^^^^^^
```

```elm
import Julc.Ledger exposing (Hash)
```

```text
src/Main.elm:3:1: ELCI2101: Unknown ledger record type Hash

3| import Julc.Ledger exposing (Hash)
   ^^^^^^
```

A misspelt module, ledger type or exposed name in an import gets a suggestion:

```text
src/Main.elm:3:1: ELCI2101: Unknown ledger type Credentail

3| import Julc.Ledger exposing (Credentail(..))
   ^^^^^^
Hint: did you mean `Credential`?
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
src/Main.elm:3:1: ELCI2104: Map does not expose 'lookup'

3| import Map exposing (lookup)
   ^^^^^^
```

A qualified name gives one of three messages, depending on the module. The hint suggests close names:

| Situation | Message |
| --- | --- |
| The module is imported, but doesn't expose the name | `Module Helper does not expose 'doubel'` |
| A project or Java module exists, but this module doesn't import it | ``Module Helper is not imported; add `import Helper` `` |
| No imported module has that name | `No module Lsit is imported, and none has that name` |

```elm
main : Int
main =
    Lsit.length [ 1, 2 ]
```

```text
src/Main.elm:6:5: ELCI2104: No module Lsit is imported, and none has that name

6|     Lsit.length [ 1, 2 ]
       ^^^^^^^^^^^
Hint: did you mean `List.length`?
```

A qualified name must use the name the module is imported under. After `import Shop.Pricing` without
`as Pricing`, `Pricing.priceOf` gets the third message; write `Shop.Pricing.priceOf`.

A qualified name that a core module lacks lists what the module has. For Elm functions that Elci doesn't
provide, the message says what to use instead:

```text
src/Main.elm:8:5: ELCI2104: Elci's Map module has no lookup. It has empty, filter, foldl, foldr, fromList, get, insert, isEmpty, keys, map, member, remove, singleton, size, toList, update, values
src/Main.elm:6:5: ELCI2104: Elci has no Debug.log; use Debug.trace message value, which returns value
src/Main.elm:6:5: ELCI2104: Elci has no String.length; a String is UTF-8 text; Bytes.length (Bytes.fromString s) is its byte length
src/Main.elm:6:5: ELCI2104: Elci has no Bytes.lessThan; bytes are comparable: write a < b
```

Using a core module that isn't imported by default, such as `Bytes`, `Dict`, `Map`, `Array` or
`Julc.Data`, without its import names the import to add:

```text
src/Main.elm:6:5: ELCI2104: Bytes.fromString needs the Bytes module; add `import Bytes`
```

A module imported with an alias is used through the alias:

```text
src/Main.elm:8:5: ELCI2104: Map is imported as M; write M.get
src/Main.elm:8:5: ELCI2104: Shop.Pricing is imported as P; write P.priceOf
```

`ELCI2104` also covers `exposing (..)` on a Java module, a file whose `module` line does not match its
path, and import cycles:

```text
src/Main.elm:3:1: ELCI2104: Java modules cannot be imported with exposing (..); list the exported names you use
src/Pricing.elm:1:1: ELCI2104: Module header Finance.Pricing does not match its source path, which names module Pricing
src/Pricing.elm:3:1: ELCI2104: Import cycle: Main -> Pricing -> Main
```

Core module names are reserved, for modules and for aliases (see
[reserved names](/reference/language/#reserved-names)):

```text
src/String.elm:1:1: ELCI2104: The module name String is taken by Elci's core library; rename this module
src/Tx.elm:1:1: ELCI2104: The module name Tx is taken by Elci's core library; rename this module
src/Set.elm:1:1: ELCI2104: The module name Set is reserved for Elci's core library; rename this module
src/Main.elm:3:1: ELCI2104: The alias String names a core module that every module imports; choose another alias for Utils
```

Modules whose names start with `Elci.` belong to the core library too, and the ones it uses internally
can't be imported: `ELCI2104: Elci.Assoc is internal to Elci's core library; use Map or Dict`, and
`ELCI2104: Elci.Value is internal to Elci's core library; use Value`. A `Value` has no constructors to
expose: `import Julc.Ledger exposing (Value(..))` gives
`ELCI2104: Value has no approved constructors or patterns; its values come from the Value module`.

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

8|     case Math.divMod 7 2 of
            ^^^^^^^^^^^
```

**Fix:** use another export, such as `Math.floorDiv` and `Math.floorMod`, or Elci operators.

An export whose result can hold a ledger `Value`, directly or in a field of a record or union, is rejected
too. Java libraries such as `ValuesLib` build values in their own order, and Elci relies on every value
being canonical. Julc's `ContextsLib` and `OutputLib` are exempt, because they only pass along the ledger's
own values:

```text
src/Main.elm:9:5: ELCI2105: org.julclang.stdlib.lib.ValuesLib.add: returns a Value, which Java libraries do not keep in the ledger's canonical order; use the core Value module
```

**Fix:** use the core [`Value`](/reference/core-modules/#value) module, here `Value.add`. See
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

8|     List.length (Data.decode (Data.encode [ 1, 2 ]))
                    ^^^^^^^^^^^
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
src/Main.elm:12:13: ELCI3003: No strict decoder for org.julclang.ledger.TxOut: The library provider cannot supply decode_strict record TxOut: [JULC0051] Library request decode_strict record TxOut is not supported: org.julclang.ledger.TxOut does not approve DECODE_STRICT
```

**Fix:** use a Data-encodable type. Functions, `()` and `Array` have no encoding, and `TxOut` can't be
decoded. A `Value` converts on its own, but a type that contains one can't be decoded yet, and a list or
tuple of values can't be encoded: convert them with `Value.toList` and back with `Value.fromList`.

A Java library's generic function can't be used at a type that contains a `Dict`. The library could build
the dictionary itself and skip the check that its keys are in order:

```text
src/Main.elm:14:18: ELCI3003: Type argument a = Dict Int Int of filter contains a Dict, which a Java library cannot keep sorted; pass a Map or a List and build the Dict with Dict.fromList
```

**Fix:** pass the library a `Map` or a `List`, and build the `Dict` with `Dict.fromList`. A type that holds
a `Value` is rejected the same way:

```text
src/Main.elm:9:5: ELCI3003: Type argument a = Value of filter contains a Value, which a Java library does not keep in the ledger's canonical order; build it with the Value module
```

Use the core `List` and [`Value`](/reference/core-modules/#value) modules instead.

## Configuration and backend errors

### `ELCI4001`: configuration or command line

```text
elci: ELCI4001: unknown validator 'treasury'; configured: main
elci: ELCI4001: eval runs an [entrypoint] function; test validators with elci test
elci: ELCI4001: parameter owner is not a well-formed org.julclang.ledger.PubKeyHash value
elci: ELCI4001: parameter caps has a Dict whose keys are not strictly ascending; write its keys sorted and without repeats
elci: ELCI4001: src/Main.elm is not valid UTF-8 text; save it as UTF-8
```

The message says what to change. A source file must be UTF-8 text. A `Dict` parameter must list its keys in ascending order, each once; see
[parameterized validators](/contracts/parameters/#what-apply-checks). The [CLI](/reference/cli/) and
[`elci.toml`](/reference/configuration/) references list the others. Some configuration errors have no code yet; they are listed below.

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
