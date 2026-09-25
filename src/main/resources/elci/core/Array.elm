module Array exposing (empty, filter, foldl, foldr, fromList, get, indexedMap, initialize, isEmpty, length, map, repeat, slice, toIndexedList, toList)

{-| Arrays with constant-time indexing, in the style of Elm's `Array`.

An `Array a` is a Plutus V3 array (CIP-138): `fromList` builds it in O(n), then
`get` and `length` are O(1). The builtins come from Julc's typed array library,
specialized for the element type. Arrays are immutable, and there is no `set` or
`push`: building a list and converting it once is cheaper than rebuilding an array.

An array has no Data encoding. It cannot be a datum, a redeemer, a parameter or a
field of a record, union, tuple, list or map. Keep lists in data and convert them
with `fromList` when many indexed reads follow.

-}

import Julc.TypedArray as Typed


empty : Array a
empty =
    Typed.fromList []


fromList : List a -> Array a
fromList items =
    Typed.fromList items


length : Array a -> Int
length values =
    Typed.length values


isEmpty : Array a -> Bool
isEmpty values =
    length values == 0


{-| The element at a 0-based index, or `Nothing` outside the array.
-}
get : Int -> Array a -> Maybe a
get index values =
    if index >= 0 && index < length values then
        Just (Typed.get values index)

    else
        Nothing


{-| `initialize n f` is `[ f 0, f 1, ..., f (n - 1) ]` as an array.
-}
initialize : Int -> (Int -> a) -> Array a
initialize count element =
    fromList (build element 0 count)


build : (Int -> a) -> Int -> Int -> List a
build element index count =
    if index >= count then
        []

    else
        element index :: build element (index + 1) count


repeat : Int -> a -> Array a
repeat count value =
    initialize count (\_ -> value)


toList : Array a -> List a
toList values =
    foldr (\value rest -> value :: rest) [] values


toIndexedList : Array a -> List ( Int, a )
toIndexedList values =
    indexedFrom values 0


indexedFrom : Array a -> Int -> List ( Int, a )
indexedFrom values index =
    if index >= length values then
        []

    else
        ( index, Typed.get values index ) :: indexedFrom values (index + 1)


{-| Reduce from the first element.
-}
foldl : (a -> b -> b) -> b -> Array a -> b
foldl step initial values =
    foldlFrom step values 0 initial


foldlFrom : (a -> b -> b) -> Array a -> Int -> b -> b
foldlFrom step values index accumulated =
    if index >= length values then
        accumulated

    else
        foldlFrom step values (index + 1) (step (Typed.get values index) accumulated)


{-| Reduce from the last element.
-}
foldr : (a -> b -> b) -> b -> Array a -> b
foldr step initial values =
    foldrFrom step values (length values - 1) initial


foldrFrom : (a -> b -> b) -> Array a -> Int -> b -> b
foldrFrom step values index accumulated =
    if index < 0 then
        accumulated

    else
        foldrFrom step values (index - 1) (step (Typed.get values index) accumulated)


map : (a -> b) -> Array a -> Array b
map transform values =
    fromList (foldr (\value rest -> transform value :: rest) [] values)


indexedMap : (Int -> a -> b) -> Array a -> Array b
indexedMap transform values =
    fromList (List.map (\( index, value ) -> transform index value) (toIndexedList values))


filter : (a -> Bool) -> Array a -> Array a
filter keep values =
    fromList
        (foldr
            (\value rest ->
                if keep value then
                    value :: rest

                else
                    rest
            )
            []
            values
        )


{-| The elements from `start` up to, but not including, `end`. A negative index counts
from the end, as in Elm: `slice 0 -1` drops the last element.
-}
slice : Int -> Int -> Array a -> Array a
slice start end values =
    let
        from =
            bound start (length values)

        to =
            bound end (length values)
    in
    fromList (sliceFrom values from to)


bound : Int -> Int -> Int
bound index size =
    if index < 0 then
        if size + index < 0 then
            0

        else
            size + index

    else if index > size then
        size

    else
        index


sliceFrom : Array a -> Int -> Int -> List a
sliceFrom values index end =
    if index >= end then
        []

    else
        Typed.get values index :: sliceFrom values (index + 1) end
