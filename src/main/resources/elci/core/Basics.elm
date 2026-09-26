module Basics exposing (Order(..), abs, always, clamp, compare, identity, max, min, negate, xor)

{-| The prelude, as in Elm's `Basics`: every module imports it with `exposing (..)`.

`not`, `modBy` and `remainderBy` are built into the compiler and also belong to the
prelude. Integers are unbounded, so there is no overflow.

-}


{-| The result of `compare`.
-}
type Order
    = LT
    | EQ
    | GT


{-| Negate an integer: `negate 42 == -42`.
-}
negate : Int -> Int
negate n =
    0 - n


{-| The absolute value of an integer: `abs -4 == 4`.
-}
abs : Int -> Int
abs n =
    if n < 0 then
        0 - n

    else
        n


{-| The smaller of two comparable values: `min 3 7 == 3`.
-}
min : comparable -> comparable -> comparable
min x y =
    if x < y then
        x

    else
        y


{-| The larger of two comparable values: `max 3 7 == 7`.
-}
max : comparable -> comparable -> comparable
max x y =
    if x > y then
        x

    else
        y


{-| Keep a number between two bounds: `clamp 0 100 x` is `0` when `x < 0`, `100` when
`x > 100`, and `x` otherwise.
-}
clamp : Int -> Int -> Int -> Int
clamp low high number =
    if number < low then
        low

    else if number > high then
        high

    else
        number


{-| Compare two comparable values: `compare 3 7 == LT`.
-}
compare : comparable -> comparable -> Order
compare x y =
    if x < y then
        LT

    else if x == y then
        EQ

    else
        GT


{-| Return the argument unchanged.
-}
identity : a -> a
identity x =
    x


{-| A function that ignores its second argument: `List.map (always 0) [ 1, 2 ] == [ 0, 0 ]`.
-}
always : a -> b -> a
always a _ =
    a


{-| Exclusive or: `True` when exactly one argument is `True`.
-}
xor : Bool -> Bool -> Bool
xor a b =
    a /= b
