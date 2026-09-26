module Laws exposing (consistent, flipOrder, isOrdered, main, occurrences, small, transitive)

{-| Helpers for the standard library's law tests (ADR-008).
-}


main : Int
main =
    0


{-| An integer from 0 to 19, so that ranges and exponents stay small.
-}
small : Int -> Int
small n =
    modBy 20 n


flipOrder : Order -> Order
flipOrder order =
    case order of
        LT ->
            GT

        EQ ->
            EQ

        GT ->
            LT


isOrdered : List comparable -> Bool
isOrdered items =
    case items of
        first :: second :: rest ->
            first <= second && isOrdered (second :: rest)

        _ ->
            True


occurrences : a -> List a -> Int
occurrences value items =
    List.length (List.filter (\item -> item == value) items)


{-| `compare` agrees with `==`, `<`, `<=`, `>` and `>=`, and is antisymmetric.
-}
consistent : comparable -> comparable -> Bool
consistent a b =
    let
        order =
            compare a b
    in
    ((order == LT) == (a < b))
        && ((order == EQ) == (a == b))
        && ((order == GT) == (a > b))
        && ((order /= GT) == (a <= b))
        && ((order /= LT) == (a >= b))
        && (compare b a == flipOrder order)


{-| `<=` is transitive.
-}
transitive : comparable -> comparable -> comparable -> Bool
transitive a b c =
    if a <= b && b <= c then
        a <= c

    else
        True
