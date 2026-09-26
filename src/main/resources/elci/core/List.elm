module List exposing (all, any, append, concat, concatMap, drop, filter, filterMap, foldl, foldr, head, indexedMap, intersperse, isEmpty, length, map, map2, map3, maximum, member, minimum, partition, product, range, repeat, reverse, singleton, sort, sortBy, sortWith, sum, tail, take, unzip)

{-| Lists, as in Elm's `List`. Every module imports it.

The functions follow Elm's names, argument orders and results, including the edge cases:
`range 5 1 == []`, `take -1 xs == []` and `head [] == Nothing`. The sorts are stable merge
sorts, O(n log n).

-}

import Basics exposing (Order(..), max, min)


map : (a -> b) -> List a -> List b
map transform items =
    case items of
        [] ->
            []

        first :: rest ->
            transform first :: map transform rest


filter : (a -> Bool) -> List a -> List a
filter keep items =
    case items of
        [] ->
            []

        first :: rest ->
            if keep first then
                first :: filter keep rest

            else
                filter keep rest


foldl : (a -> b -> b) -> b -> List a -> b
foldl step initial items =
    case items of
        [] ->
            initial

        first :: rest ->
            foldl step (step first initial) rest


foldr : (a -> b -> b) -> b -> List a -> b
foldr step initial items =
    case items of
        [] ->
            initial

        first :: rest ->
            step first (foldr step initial rest)


all : (a -> Bool) -> List a -> Bool
all predicate items =
    case items of
        [] ->
            True

        first :: rest ->
            predicate first && all predicate rest


any : (a -> Bool) -> List a -> Bool
any predicate items =
    case items of
        [] ->
            False

        first :: rest ->
            predicate first || any predicate rest


length : List a -> Int
length items =
    foldl (\_ count -> count + 1) 0 items


reverse : List a -> List a
reverse items =
    foldl (\item result -> item :: result) [] items


append : List a -> List a -> List a
append left right =
    case left of
        [] ->
            right

        first :: rest ->
            first :: append rest right


concatMap : (a -> List b) -> List a -> List b
concatMap transform items =
    case items of
        [] ->
            []

        first :: rest ->
            append (transform first) (concatMap transform rest)


{-| A list of one element.
-}
singleton : a -> List a
singleton value =
    [ value ]


{-| A list of `n` copies of a value; empty when `n <= 0`.
-}
repeat : Int -> a -> List a
repeat n value =
    repeatOnto [] n value


repeatOnto : List a -> Int -> a -> List a
repeatOnto result n value =
    if n <= 0 then
        result

    else
        repeatOnto (value :: result) (n - 1) value


{-| The integers from `low` to `high`, inclusive: `range 1 4 == [ 1, 2, 3, 4 ]`; empty when
`low > high`.
-}
range : Int -> Int -> List Int
range low high =
    rangeOnto low high []


rangeOnto : Int -> Int -> List Int -> List Int
rangeOnto low high result =
    if low <= high then
        rangeOnto low (high - 1) (high :: result)

    else
        result


{-| Whether a list is empty.
-}
isEmpty : List a -> Bool
isEmpty items =
    case items of
        [] ->
            True

        _ :: _ ->
            False


{-| The first element, or `Nothing` for an empty list.
-}
head : List a -> Maybe a
head items =
    case items of
        [] ->
            Nothing

        first :: _ ->
            Just first


{-| Everything but the first element, or `Nothing` for an empty list.
-}
tail : List a -> Maybe (List a)
tail items =
    case items of
        [] ->
            Nothing

        _ :: rest ->
            Just rest


{-| The first `n` elements; empty when `n <= 0`.
-}
take : Int -> List a -> List a
take n items =
    if n <= 0 then
        []

    else
        case items of
            [] ->
                []

            first :: rest ->
                first :: take (n - 1) rest


{-| The list without its first `n` elements; unchanged when `n <= 0`.
-}
drop : Int -> List a -> List a
drop n items =
    if n <= 0 then
        items

    else
        case items of
            [] ->
                []

            _ :: rest ->
                drop (n - 1) rest


{-| Whether a value is an element of the list.
-}
member : a -> List a -> Bool
member value items =
    case items of
        [] ->
            False

        first :: rest ->
            first == value || member value rest


{-| The sum of a list of integers; `0` for an empty list.
-}
sum : List Int -> Int
sum items =
    foldl (\item total -> item + total) 0 items


{-| The product of a list of integers; `1` for an empty list.
-}
product : List Int -> Int
product items =
    foldl (\item total -> item * total) 1 items


{-| The largest element, or `Nothing` for an empty list.
-}
maximum : List comparable -> Maybe comparable
maximum items =
    case items of
        [] ->
            Nothing

        first :: rest ->
            Just (foldl max first rest)


{-| The smallest element, or `Nothing` for an empty list.
-}
minimum : List comparable -> Maybe comparable
minimum items =
    case items of
        [] ->
            Nothing

        first :: rest ->
            Just (foldl min first rest)


{-| Map with the index of each element, counting from 0.
-}
indexedMap : (Int -> a -> b) -> List a -> List b
indexedMap transform items =
    indexedMapFrom 0 transform items


indexedMapFrom : Int -> (Int -> a -> b) -> List a -> List b
indexedMapFrom index transform items =
    case items of
        [] ->
            []

        first :: rest ->
            transform index first :: indexedMapFrom (index + 1) transform rest


{-| Map, keeping only the `Just` results.
-}
filterMap : (a -> Maybe b) -> List a -> List b
filterMap transform items =
    case items of
        [] ->
            []

        first :: rest ->
            case transform first of
                Just value ->
                    value :: filterMap transform rest

                Nothing ->
                    filterMap transform rest


{-| Split a list into the elements that satisfy a predicate and those that don't, keeping
their order.
-}
partition : (a -> Bool) -> List a -> ( List a, List a )
partition predicate items =
    foldr
        (\item ( yes, no ) ->
            if predicate item then
                ( item :: yes, no )

            else
                ( yes, item :: no )
        )
        ( [], [] )
        items


{-| Concatenate a list of lists.
-}
concat : List (List a) -> List a
concat lists =
    foldr append [] lists


{-| Put a separator between the elements: `intersperse 0 [ 1, 2, 3 ] == [ 1, 0, 2, 0, 3 ]`.
-}
intersperse : a -> List a -> List a
intersperse separator items =
    case items of
        [] ->
            []

        first :: rest ->
            first :: prependEach separator rest


prependEach : a -> List a -> List a
prependEach separator items =
    case items of
        [] ->
            []

        first :: rest ->
            separator :: first :: prependEach separator rest


{-| Combine two lists element by element; the longer list's extra elements are dropped.
-}
map2 : (a -> b -> result) -> List a -> List b -> List result
map2 transform firsts seconds =
    case firsts of
        [] ->
            []

        a :: moreA ->
            case seconds of
                [] ->
                    []

                b :: moreB ->
                    transform a b :: map2 transform moreA moreB


{-| Combine three lists element by element, up to the shortest.
-}
map3 : (a -> b -> c -> result) -> List a -> List b -> List c -> List result
map3 transform firsts seconds thirds =
    case firsts of
        [] ->
            []

        a :: moreA ->
            case seconds of
                [] ->
                    []

                b :: moreB ->
                    case thirds of
                        [] ->
                            []

                        c :: moreC ->
                            transform a b c :: map3 transform moreA moreB moreC


{-| Split a list of pairs into a pair of lists.
-}
unzip : List ( a, b ) -> ( List a, List b )
unzip pairs =
    ( map (\( a, _ ) -> a) pairs, map (\( _, b ) -> b) pairs )


{-| Sort from smallest to largest. The sort is stable: equal elements keep their order.
-}
sort : List comparable -> List comparable
sort items =
    mergeAll (map singleton items)


mergeAll : List (List comparable) -> List comparable
mergeAll runs =
    case runs of
        [] ->
            []

        run :: [] ->
            run

        _ :: _ ->
            mergeAll (mergePairs runs)


mergePairs : List (List comparable) -> List (List comparable)
mergePairs runs =
    case runs of
        left :: right :: rest ->
            merge left right :: mergePairs rest

        _ ->
            runs


merge : List comparable -> List comparable -> List comparable
merge left right =
    case left of
        [] ->
            right

        l :: moreLeft ->
            case right of
                [] ->
                    left

                r :: moreRight ->
                    if r < l then
                        r :: merge left moreRight

                    else
                        l :: merge moreLeft right


{-| Sort by a derived comparable key, stably: `sortBy (\person -> person.age) people`.
-}
sortBy : (a -> comparable) -> List a -> List a
sortBy toKey items =
    mergeAllBy toKey (map singleton items)


mergeAllBy : (a -> comparable) -> List (List a) -> List a
mergeAllBy toKey runs =
    case runs of
        [] ->
            []

        run :: [] ->
            run

        _ :: _ ->
            mergeAllBy toKey (mergePairsBy toKey runs)


mergePairsBy : (a -> comparable) -> List (List a) -> List (List a)
mergePairsBy toKey runs =
    case runs of
        left :: right :: rest ->
            mergeBy toKey left right :: mergePairsBy toKey rest

        _ ->
            runs


mergeBy : (a -> comparable) -> List a -> List a -> List a
mergeBy toKey left right =
    case left of
        [] ->
            right

        l :: moreLeft ->
            case right of
                [] ->
                    left

                r :: moreRight ->
                    if toKey r < toKey l then
                        r :: mergeBy toKey left moreRight

                    else
                        l :: mergeBy toKey moreLeft right


{-| Sort with a comparison function, stably: `sortWith (\a b -> compare b a)` sorts from
largest to smallest.
-}
sortWith : (a -> a -> Order) -> List a -> List a
sortWith order items =
    mergeAllWith order (map singleton items)


mergeAllWith : (a -> a -> Order) -> List (List a) -> List a
mergeAllWith order runs =
    case runs of
        [] ->
            []

        run :: [] ->
            run

        _ :: _ ->
            mergeAllWith order (mergePairsWith order runs)


mergePairsWith : (a -> a -> Order) -> List (List a) -> List (List a)
mergePairsWith order runs =
    case runs of
        left :: right :: rest ->
            mergeWith order left right :: mergePairsWith order rest

        _ ->
            runs


mergeWith : (a -> a -> Order) -> List a -> List a -> List a
mergeWith order left right =
    case left of
        [] ->
            right

        l :: moreLeft ->
            case right of
                [] ->
                    left

                r :: moreRight ->
                    case order l r of
                        GT ->
                            r :: mergeWith order left moreRight

                        _ ->
                            l :: mergeWith order moreLeft right
