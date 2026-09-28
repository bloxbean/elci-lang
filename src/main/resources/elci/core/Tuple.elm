module Tuple exposing (first, mapBoth, mapFirst, mapSecond, pair, second)

{-| Pairs, as in Elm's `Tuple`. Every module imports it.
-}


{-| Make a pair: `pair 1 "one" == ( 1, "one" )`.
-}
pair : a -> b -> ( a, b )
pair a b =
    ( a, b )


{-| The first component of a pair.
-}
first : ( a, b ) -> a
first ( a, _ ) =
    a


{-| The second component of a pair.
-}
second : ( a, b ) -> b
second ( _, b ) =
    b


{-| Transform the first component of a pair.
-}
mapFirst : (a -> x) -> ( a, b ) -> ( x, b )
mapFirst transform ( a, b ) =
    ( transform a, b )


{-| Transform the second component of a pair.
-}
mapSecond : (b -> y) -> ( a, b ) -> ( a, y )
mapSecond transform ( a, b ) =
    ( a, transform b )


{-| Transform both components of a pair.
-}
mapBoth : (a -> x) -> (b -> y) -> ( a, b ) -> ( x, y )
mapBoth transformFirst transformSecond ( a, b ) =
    ( transformFirst a, transformSecond b )
