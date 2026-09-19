module Main exposing (main)


type Tree
    = Leaf Int
    | Branch Tree Tree


total : Tree -> Int
total tree =
    case tree of
        Leaf amount ->
            amount

        Branch left right ->
            total left + total right


main : Int
main =
    [ Branch (Leaf 3) (Leaf 4), Leaf 5 ]
        |> List.map total
        |> List.foldl (\amount sum -> amount + sum) 0
