module Corpus.Declarations exposing (Shape(..), main)

{-| Type aliases, custom types, signatures, parameters and let declarations. -}


type alias Point =
    { x : Int, y : Int }


type alias Pair a =
    ( a, a )


type alias Transform a =
    a -> a


type Shape a
    = Dot
    | Circle Int
    | Rect Point ( Int, Int )
    | Labelled (Maybe a) (List (Maybe.Maybe a))


area : Shape a -> Int
area shape =
    case shape of
        Dot ->
            0

        Circle radius ->
            3 * radius * radius

        Rect _ ( width, height ) ->
            width * height

        Labelled _ labels ->
            List.length labels


apply :
    Transform Int
    -> Int
    -> Int
apply transform value =
    transform value


sumPair : Pair Int -> { x : Int } -> () -> Int
sumPair ( a, b ) { x } () =
    a + b + x


first : ( Int, Int ) -> Int
first (( a, _ ) as whole) =
    a + Tuple.second whole


main : Bool
main =
    let
        ( low, high ) =
            ( 1, 2 )

        { x, y } =
            Point 3 4

        _ =
            area Dot

        double : Int -> Int
        double n =
            n * 2

        nested =
            let
                inner =
                    5
            in
            inner
    in
    area (Rect (Point 0 0) ( low, high ))
        + area (Circle 1)
        + area (Labelled (Just "a") [ Nothing ])
        + apply double x
        + sumPair ( y, nested ) { x = 1 } ()
        + first ( 1, 2 )
        == 2 + 3 + 1 + 6 + 10 + 3
