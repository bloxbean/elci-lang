module Corpus.Expressions exposing (main)

{-| Operators, operator functions, conditionals, lambdas, tuples, lists, strings and records. -}


type alias Account =
    { owner : String, balance : Int }


main : Bool
main =
    let
        account =
            { owner = "alice", balance = 5 }

        richer =
            { account | balance = account.balance + 1 }

        accounts =
            [ account, Account "bob" 2 ]

        classify n =
            if n < 0 then
                "negative"

            else if n == 0 then
                "zero"

            else
                "positive"
    in
    List.all identity
        [ 1 + 2 * 3 ^ 2 - 8 // 3 == 17
        , 2 ^ 3 ^ 2 == 512
        , (1 :: [ 2 ] ++ [ 3 ]) == [ 1, 2, 3 ]
        , (True || False && False) == True
        , 1 /= 2 && 1 <= 1 && 2 >= 1 && 1 < 2 && 2 > 1
        , ([ 1, 2 ] |> List.map ((+) 1) |> List.sum) == 5
        , (List.sum <| List.map ((*) 2) <| [ 1, 2 ]) == 6
        , ((negate >> abs) 3 == 3) && ((abs << negate) 4 == 4)
        , List.foldl (+) 0 [ 1, 2, 3 ] == 6
        , List.foldr (::) [] [ 1, 2 ] == [ 1, 2 ]
        , (-) 10 3 == 7
        , (//) 7 2 == 3
        , (^) 2 3 == 8
        , (++) "a" "b" == "ab"
        , (|>) 1 negate == -1
        , (<|) negate 1 == -1
        , ((>>) negate abs) 2 == 2
        , ((<<) negate abs) 2 == -2
        , (&&) True True && (||) False True
        , (==) 1 1 && (/=) 1 2 && (<) 1 2 && (>) 2 1 && (<=) 1 1 && (>=) 1 1
        , (\( a, b ) c -> a + b + c) ( 1, 2 ) 3 == 6
        , classify -1 ++ classify 0 ++ classify 1 == "negativezeropositive"
        , Tuple.first ( 1, "one" ) == 1 && ( 1, 2, 3 ) == ( 1, 2, 3 )
        , () == ()
        , [] == List.drop 1 [ 1 ]
        , "tab\tquote\"backslash\\newline\nreturn\r" /= ""
        , richer.balance == 6 && richer.owner == account.owner
        , List.map .balance accounts == [ 5, 2 ]
        , { account | owner = "carol", balance = 0 }.balance == 0
        , (Account "dave" 1).owner == "dave"
        ]
