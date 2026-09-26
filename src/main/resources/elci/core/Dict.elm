module Dict exposing (Dict, diff, empty, filter, foldl, foldr, fromList, get, insert, intersect, isEmpty, keys, map, member, merge, partition, remove, singleton, size, toList, union, update, values)

{-| Dictionaries with comparable keys, as in Elm's `Dict`.

A `Dict k v` is a Plutus map whose keys are strictly ascending in the `comparable` order,
so every dictionary has exactly one representation: `==` compares bindings, and it
encodes as the sorted map that off-chain tools write. Decoding a `Dict` from Data
rejects unsorted or repeated keys.

Lookups, `insert` and `remove` stop at the first key that is not smaller: O(n), about half
a `Map` scan on average. `fromList` sorts, O(n log n). The other operations are single
passes.

-}

import Elci.Assoc as Assoc
import List
import Map exposing (Map)


{-| The empty dictionary.
-}
empty : Dict k v
empty =
    Assoc.fromMap Map.empty


{-| A dictionary with one binding.
-}
singleton : comparable -> v -> Dict comparable v
singleton key value =
    Assoc.fromMap (Assoc.cons key value Map.empty)


{-| Bind a key to a value, replacing any existing binding.
-}
insert : comparable -> v -> Dict comparable v -> Dict comparable v
insert key value dict =
    Assoc.fromMap (Assoc.insert key value (Assoc.toMap dict))


{-| Change the binding of a key: `Nothing` removes it.
-}
update : comparable -> (Maybe v -> Maybe v) -> Dict comparable v -> Dict comparable v
update key alter dict =
    case alter (get key dict) of
        Just value ->
            insert key value dict

        Nothing ->
            remove key dict


{-| Remove a key's binding, if it has one.
-}
remove : comparable -> Dict comparable v -> Dict comparable v
remove key dict =
    Assoc.fromMap (Assoc.remove key (Assoc.toMap dict))


{-| Whether the dictionary has no bindings.
-}
isEmpty : Dict k v -> Bool
isEmpty dict =
    Map.isEmpty (Assoc.toMap dict)


{-| Whether a key is bound.
-}
member : comparable -> Dict comparable v -> Bool
member key dict =
    Assoc.member key (Assoc.toMap dict)


{-| The value bound to a key, if any.
-}
get : comparable -> Dict comparable v -> Maybe v
get key dict =
    Assoc.get key (Assoc.toMap dict)


{-| The number of bindings.
-}
size : Dict k v -> Int
size dict =
    Map.size (Assoc.toMap dict)


{-| The keys, in ascending order.
-}
keys : Dict k v -> List k
keys dict =
    Map.keys (Assoc.toMap dict)


{-| The values, in the ascending order of their keys.
-}
values : Dict k v -> List v
values dict =
    Map.values (Assoc.toMap dict)


{-| The bindings, in ascending key order.
-}
toList : Dict k v -> List ( k, v )
toList dict =
    Assoc.toList (Assoc.toMap dict)


{-| A dictionary of the pairs; for a repeated key, the last pair wins. A stable merge sort
of the input's ascending runs: O(n log n), and O(n) for sorted input.
-}
fromList : List ( comparable, v ) -> Dict comparable v
fromList pairs =
    Assoc.fromMap (Assoc.dedupe (mergeRuns (Assoc.runs (Assoc.fromDistinct pairs))))


mergeRuns : List (Map comparable v) -> Map comparable v
mergeRuns runs =
    case runs of
        [] ->
            Map.empty

        run :: [] ->
            run

        _ :: _ ->
            mergeRuns (mergeRunPairs runs)


mergeRunPairs : List (Map comparable v) -> List (Map comparable v)
mergeRunPairs runs =
    case runs of
        left :: right :: rest ->
            Assoc.merge left right :: mergeRunPairs rest

        _ ->
            runs


fromAscending : List ( k, v ) -> Dict k v
fromAscending pairs =
    Assoc.fromMap (Assoc.fromDistinct pairs)


{-| Transform every value.
-}
map : (k -> a -> b) -> Dict k a -> Dict k b
map transform dict =
    fromAscending (List.map (\( key, value ) -> ( key, transform key value )) (toList dict))


{-| Fold over the bindings in ascending key order.
-}
foldl : (k -> v -> b -> b) -> b -> Dict k v -> b
foldl step initial dict =
    List.foldl (\( key, value ) result -> step key value result) initial (toList dict)


{-| Fold over the bindings in descending key order.
-}
foldr : (k -> v -> b -> b) -> b -> Dict k v -> b
foldr step initial dict =
    List.foldr (\( key, value ) result -> step key value result) initial (toList dict)


{-| The bindings that pass the test.
-}
filter : (comparable -> v -> Bool) -> Dict comparable v -> Dict comparable v
filter keep dict =
    fromAscending (List.filter (\( key, value ) -> keep key value) (toList dict))


{-| Split into the bindings that pass the test and those that don't.
-}
partition : (comparable -> v -> Bool) -> Dict comparable v -> ( Dict comparable v, Dict comparable v )
partition keep dict =
    let
        ( passing, failing ) =
            List.partition (\( key, value ) -> keep key value) (toList dict)
    in
    ( fromAscending passing, fromAscending failing )


{-| All bindings of both; for a key in both, the left dictionary's value wins.
-}
union : Dict comparable v -> Dict comparable v -> Dict comparable v
union left right =
    fromAscending (unionPairs (toList left) (toList right))


unionPairs : List ( comparable, v ) -> List ( comparable, v ) -> List ( comparable, v )
unionPairs left right =
    case left of
        [] ->
            right

        ( leftKey, leftValue ) :: moreLeft ->
            case right of
                [] ->
                    left

                ( rightKey, rightValue ) :: moreRight ->
                    if leftKey < rightKey then
                        ( leftKey, leftValue ) :: unionPairs moreLeft right

                    else if rightKey < leftKey then
                        ( rightKey, rightValue ) :: unionPairs left moreRight

                    else
                        ( leftKey, leftValue ) :: unionPairs moreLeft moreRight


{-| The bindings of the left dictionary whose keys the right one also has.
-}
intersect : Dict comparable v -> Dict comparable v -> Dict comparable v
intersect left right =
    fromAscending (intersectPairs (toList left) (keys right))


intersectPairs : List ( comparable, v ) -> List comparable -> List ( comparable, v )
intersectPairs left rightKeys =
    case left of
        [] ->
            []

        ( leftKey, leftValue ) :: moreLeft ->
            case rightKeys of
                [] ->
                    []

                rightKey :: moreRight ->
                    if leftKey < rightKey then
                        intersectPairs moreLeft rightKeys

                    else if rightKey < leftKey then
                        intersectPairs left moreRight

                    else
                        ( leftKey, leftValue ) :: intersectPairs moreLeft moreRight


{-| The bindings of the left dictionary whose keys the right one does not have.
-}
diff : Dict comparable a -> Dict comparable b -> Dict comparable a
diff left right =
    fromAscending (diffPairs (toList left) (keys right))


diffPairs : List ( comparable, a ) -> List comparable -> List ( comparable, a )
diffPairs left rightKeys =
    case left of
        [] ->
            []

        ( leftKey, leftValue ) :: moreLeft ->
            case rightKeys of
                [] ->
                    left

                rightKey :: moreRight ->
                    if leftKey < rightKey then
                        ( leftKey, leftValue ) :: diffPairs moreLeft rightKeys

                    else if rightKey < leftKey then
                        diffPairs left moreRight

                    else
                        diffPairs moreLeft moreRight


{-| Fold over two dictionaries in ascending key order, with one step for keys only on the
left, one for keys in both and one for keys only on the right.
-}
merge :
    (comparable -> a -> result -> result)
    -> (comparable -> a -> b -> result -> result)
    -> (comparable -> b -> result -> result)
    -> Dict comparable a
    -> Dict comparable b
    -> result
    -> result
merge leftStep bothStep rightStep left right initial =
    mergePairs leftStep bothStep rightStep (toList left) (toList right) initial


mergePairs :
    (comparable -> a -> result -> result)
    -> (comparable -> a -> b -> result -> result)
    -> (comparable -> b -> result -> result)
    -> List ( comparable, a )
    -> List ( comparable, b )
    -> result
    -> result
mergePairs leftStep bothStep rightStep left right result =
    case left of
        [] ->
            List.foldl (\( key, value ) acc -> rightStep key value acc) result right

        ( leftKey, leftValue ) :: moreLeft ->
            case right of
                [] ->
                    List.foldl (\( key, value ) acc -> leftStep key value acc) result left

                ( rightKey, rightValue ) :: moreRight ->
                    if leftKey < rightKey then
                        mergePairs leftStep bothStep rightStep moreLeft right (leftStep leftKey leftValue result)

                    else if rightKey < leftKey then
                        mergePairs leftStep bothStep rightStep left moreRight (rightStep rightKey rightValue result)

                    else
                        mergePairs leftStep bothStep rightStep moreLeft moreRight (bothStep leftKey leftValue rightValue result)
