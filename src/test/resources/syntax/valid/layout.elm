module Corpus.Layout exposing (main)

{-| Layout: blocks, floors, brackets and comments. {- Comments {- nest -}. -}
-}


{-| A doc comment before a declaration.
-}
choose : Int -> Int
choose n =
    case n of 0 -> 10
              _ -> 20


bracketed : List Int
bracketed =
    [ 1
, 2
    ]


total : Int -> Int
total n =
    let a = n in a + 1


main : Bool
main =
    let
        x =
            1
    -- a comment left of the block does not end it
        y =
            case x of
                1 ->
                    case x + 1 of
                        2 ->
                            20

                        _ ->
                            0

                _ ->
                    0
        {- a block comment -}
        z =
            (case x of
                1 -> 3
                _ -> 0
            )
                + { value = case x of 1 -> 4
                                      _ -> 0, other = 0 }.value
    in
    x
-- a comment in column 1 inside an expression
        + y
        + z
        + choose 0
        + total 1
        + List.sum bracketed
        + (if case x of 1 -> True
                        _ -> False then 1 else 0)
        == 1 + 20 + 7 + 10 + 2 + 3 + 1
