module Corpus.Negation exposing (main)

{-| Unary minus and the adjacency rules for `-`. -}


type alias Box =
    { size : Int }


scale : Int -> Int -> Int
scale factor value =
    factor * value


main : Bool
main =
    let
        x =
            4

        box =
            { size = 2 }
    in
    List.all identity
        [ scale -x 10 == -40
        , modBy 3 -7 == 2
        , x - 1 == 3
        , x-1 == 3
        , x- 1 == 3
        , -x * 2 == -8
        , -x ^ 2 == 16
        , -(scale x 2) == -8
        , -scale x 2 == -8
        , 2 * -3 == -6
        , scale -box.size 3 == -6
        , - 1 == -1
        , [ -1, -x ] == [ -1, -4 ]
        , scale 2 (-x) == -8
        ]
