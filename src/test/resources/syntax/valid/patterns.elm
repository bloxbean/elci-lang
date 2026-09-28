module Corpus.Patterns exposing (main)

{-| Every pattern form. -}


type Token
    = Word String
    | Number Int
    | Pair Token Token
    | Marker


classify : List Token -> Int
classify tokens =
    case tokens of
        [] ->
            0

        [ Word "stop" ] ->
            1

        (Number -1) :: _ ->
            2

        (Number 0) :: (Pair (Word w) Marker) :: _ ->
            3

        [ Pair
            (Number n)
            _
          ] ->
            n

        ((Word _) :: rest) as all ->
            List.length all + List.length rest

        -- `as` names the pattern just before it: `tail` is `rest`, not the whole list.
        first :: second :: rest as tail ->
            List.length tail + 100

        _ :: _ ->
            -2


check : Bool -> () -> { a : Int } -> Maybe.Maybe String -> Int
check flag () { a } maybe =
    case ( flag, maybe, a ) of
        ( True, Maybe.Just "x", _ ) ->
            a

        ( False, Just _, 5 ) ->
            -a

        ( _, Nothing, _ ) ->
            0

        ( _, Just _, _ ) ->
            1


main : Bool
main =
    List.all identity
        [ classify [] == 0
        , classify [ Word "stop" ] == 1
        , classify [ Number -1 ] == 2
        , classify [ Number 0, Pair (Word "w") Marker ] == 3
        , classify [ Pair (Number 7) Marker ] == 7
        , classify [ Word "a", Marker ] == 3
        , classify [ Marker, Marker, Marker ] == 101
        , classify [ Marker ] == -2
        , check True () { a = 5 } (Just "x") == 5
        , check False () { a = 5 } (Just "y") == -5
        , check False () { a = 6 } (Just "y") == 1
        ]
