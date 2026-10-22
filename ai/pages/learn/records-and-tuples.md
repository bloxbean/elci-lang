# Records and tuples

> Group values with records and tuples, update and destructure them, and see how they are encoded as on-chain data.

Source: https://elci-lang.dev/learn/records-and-tuples/

Records and tuples group several values into one. This page shows how to create, read, update and
destructure them, and how Elci encodes them as on-chain data. That encoding matters as soon as a record
becomes a datum or redeemer.

## Records

A record has named fields. `type alias` gives a record type a name:

```elm title="src/Main.elm"
module Main exposing (main)

type alias Listing =
    { price : Int
    , active : Bool
    }

discount : Int -> Listing -> Listing
discount percent listing =
    { listing | price = listing.price - listing.price * percent // 100 }

main : Int
main =
    let
        original =
            { price = 2000, active = True }

        cheaper =
            discount 25 original
    in
    if cheaper.active then
        cheaper.price

    else
        0
```

```text
1500
cpu 5077108, mem 17933
```

- `{ price = 2000, active = True }` creates a record. It has type `Listing` because it has exactly those
  fields.
- `listing.price` reads a field.
- `{ listing | price = ... }` is a **record update**: a copy of `listing` with new values for the named fields.
  You can update several fields at once, separated by commas. The record to update must be a plain name.

Records are values. An update creates a new record and leaves the original unchanged.

## Record constructors

A record alias also defines a constructor function. Its arguments follow the order of the fields in the
declaration:

```elm title="src/Main.elm"
module Main exposing (main)

type alias Listing =
    { price : Int
    , active : Bool
    }

main : Listing
main =
    Listing 2000 True
```

```text
{ active = True, price = 2000 }
cpu 886960, mem 4025
```

`Listing 2000 True` is the record `{ price = 2000, active = True }`. `elci eval` prints record fields in
alphabetical order, which is a hint about how Elci stores them.

## Accessor functions

As in Elm, `.amount` is a function that reads the field `amount` of a record. It is short for
`\payout -> payout.amount`, and it is handy with list functions:

```elm title="src/Main.elm"
module Main exposing (main)

type alias Payout =
    { beneficiary : String
    , amount : Int
    }

main : ( Int, List String )
main =
    let
        payouts =
            [ Payout "carol" 300, Payout "alice" 150, Payout "bob" 250 ]
    in
    ( List.sum (List.map .amount payouts)
    , payouts
        |> List.sortBy .amount
        |> List.map .beneficiary
    )
```

```text
( 700, ["alice", "bob", "carol"] )
cpu 31097157, mem 139215
```

A record that another module exposes is read the same way. `Config.settings.fee` reads the field `fee`
of the value `settings` in module `Config`:

```elm title="src/Config.elm"
module Config exposing (Settings, settings)

type alias Settings =
    { fee : Int
    , owner : String
    }

settings : Settings
settings =
    { fee = 200, owner = "alice" }
```

```elm title="src/Main.elm"
module Main exposing (main)

import Config

main : Int
main =
    Config.settings.fee * 2
```

```text
400
cpu 1310048, mem 4828
```

A qualified name is written without spaces and ends at its first lowercase part: `Config.settings` is the
value, and `.fee` reads its field. In `List.map .amount payouts`, the space before `.amount` makes it an
accessor passed to `List.map`.

## How records are stored on-chain

Cardano stores datums and redeemers as **Plutus Data**: integers, byte strings, lists, maps and constructors
(a tag number with a list of fields). `Julc.Data.encode` turns any value into its Data, so you can see
exactly what goes on-chain:

```elm title="src/Main.elm"
module Main exposing (main)

import Julc.Data as Data

type alias Listing =
    { price : Int
    , active : Bool
    }

main : Data
main =
    Data.encode (Listing 2000 True)
```

```text
Constr 0 [Constr 1, 2000]
cpu 982960, mem 4625
```

- A record is a constructor with tag 0.
- Its fields are sorted **alphabetically by name**, whatever order you declared them in. That is why
  `active` (`True`, itself encoded as constructor 1 with no fields) comes before `price` (`2000`).

This has practical consequences for datums and redeemers:

- Reordering the fields of a record declaration doesn't change its encoding.
- **Renaming a field can change the encoding**, because it can change the sort order. Data already on-chain
  would no longer decode. The build's `abi.json` records the exact layout, so you can detect such changes.

[Datums and redeemers](https://elci-lang.dev/contracts/datums-and-redeemers/) covers the encoding rules for every type.

## Destructuring

Patterns can take a record apart. `{ amount }` binds the field `amount` to a variable of the same name. It
works in function and lambda parameters, in `let`, and in `case`:

```elm title="src/Main.elm"
module Main exposing (main)

type alias Payout =
    { beneficiary : String
    , amount : Int
    }

total : List Payout -> Int
total payouts =
    List.foldl (\{ amount } sum -> amount + sum) 0 payouts

main : Int
main =
    let
        payouts =
            [ Payout "alice" 150, Payout "bob" 250 ]

        { beneficiary } =
            Payout "carol" 10
    in
    if beneficiary == "carol" then
        total payouts

    else
        0
```

```text
400
cpu 6314459, mem 26311
```

In a `case`, a record pattern selects fields and can sit inside other patterns, such as tuples:

```elm title="src/Main.elm"
module Main exposing (main)

type alias Order =
    { kind : String
    , quantity : Int
    , paid : Bool
    }

priority : Order -> Int
priority order =
    case order of
        { paid, quantity } ->
            if paid && quantity > 10 then
                2

            else if paid then
                1

            else
                0

kindCode : Order -> Int
kindCode order =
    case ( order.kind, order.paid ) of
        ( "bulk", True ) ->
            3

        ( "bulk", False ) ->
            2

        ( _, _ ) ->
            1

main : Int
main =
    let
        order =
            { kind = "bulk", quantity = 25, paid = True }
    in
    priority order * 10 + kindCode order
```

```text
23
cpu 7369610, mem 25543
```

## Generic records

A record alias can take type parameters, like a function takes arguments:

```elm title="src/Main.elm"
module Main exposing (main)

type alias Tagged a =
    { label : String
    , value : a
    }

relabel : String -> Tagged a -> Tagged a
relabel label tagged =
    { tagged | label = label }

main : Int
main =
    let
        amount =
            relabel "fee" (Tagged "draft" 170000)

        flag =
            Tagged "enabled" True
    in
    if flag.value && amount.label == "fee" then
        amount.value

    else
        0
```

```text
170000
cpu 5150199, mem 18573
```

`amount` is a `Tagged Int` and `flag` is a `Tagged Bool`. `relabel` works on both.

## Tuples

A tuple groups two or three values without naming them: `( 1, "one" )` has type `( Int, String )`. Tuples are
handy for returning several results from a function:

```elm title="src/Main.elm"
module Main exposing (main)

divMod : Int -> Int -> ( Int, Int )
divMod amount parts =
    ( amount // parts, modBy parts amount )

main : Int
main =
    let
        ( quotient, remainder ) =
            divMod 17 5
    in
    quotient * 10 + remainder
```

```text
32
cpu 3397721, mem 12146
```

The `Tuple` module, which every module imports, works on pairs: `Tuple.first`, `Tuple.second`,
`Tuple.pair`, `Tuple.mapFirst`, `Tuple.mapSecond` and `Tuple.mapBoth`. Tuples of comparable values, such as
numbers and strings, compare component by component, so `List.sort` sorts them:

```elm title="src/Main.elm"
module Main exposing (main)

main : ( List ( Int, String ), List Int )
main =
    let
        scores =
            [ ( 2, "bob" ), ( 1, "carol" ), ( 2, "alice" ) ]
    in
    ( List.sort scores, List.map Tuple.first scores )
```

```text
( [( 1, "carol" ), ( 2, "alice" ), ( 2, "bob" )], [2, 1, 2] )
cpu 28573982, mem 123050
```

A tuple is encoded like a record, as a constructor with tag 0, but its fields stay in position order. For
anything with more than three parts, or whose parts deserve names, use a record. A four-element tuple is
rejected:

```elm
    let
        ( a, b, c, d ) =
            ( 1, 2, 3, 4 )
    in
```

```text
.../src/Main.elm:7:24: ELCI1002: Tuple patterns have two or three elements

7|         ( a, b, c, d ) =
                          ^
```

## Equality

`==` compares records and tuples field by field. Field order in the literal doesn't matter:

```elm title="src/Main.elm"
module Main exposing (main)

main : Bool
main =
    ( 1, "a", True ) == ( 1, "a", True ) && { x = 1, y = 2 } == { y = 2, x = 1 }
```

```text
True
cpu 6141566, mem 15472
```

Records have no order, so `<` and `List.sort` reject them. Sort records by a field with
`List.sortBy (\listing -> listing.price)`; see [sorting](https://elci-lang.dev/learn/collections/#sorting).

## Limits

Records in Elci are **closed**: a record type lists all of its fields.

- Extensible record types such as `{ r | price : Int }` are not supported:

  ```text
  .../src/Main.elm:4:13: ELCI1002: Extensible records are unsupported; use a closed record

  4| total : { r | price : Int } -> Int
                 ^
  ```

- A field access, or an accessor function such as `.price`, needs to know which record type it is
  reading. Use a named alias or a signature when inference can't tell. Reading a field that doesn't exist
  is an error:

  ```text
  .../src/Main.elm:14:12: ELCI2010: Missing field 'cost'; available fields: [price]

  14|     listing.cost
                 ^
  ```

## Next

[Custom types and pattern matching](https://elci-lang.dev/learn/custom-types/) models values that can take different shapes.
