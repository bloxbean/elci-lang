module LawsTest exposing
    ( bytesCompareConsistently
    , bytesOrderIsTransitive
    , clampStaysWithinBounds
    , fromIntAppendsDigits
    , fromIntNegates
    , intCompareConsistently
    , intOrderIsTransitive
    , interspersePlacesSeparators
    , listsCompareConsistently
    , listsOrderIsTransitive
    , mapIdentityIsIdentity
    , maximumAndMinimumAreBounds
    , minAndMaxPickAnArgument
    , partitionMatchesFilter
    , powersAdd
    , rangeHasTheRightLength
    , resultMapIdentityIsIdentity
    , sortByIsStable
    , sortByMatchesSortWith
    , sortIsAnOrderedPermutation
    , sortIsIdempotent
    , sortStringsIsOrdered
    , stringOrderIsUtf8ByteOrder
    , stringsCompareConsistently
    , takeAndDropSplit
    , triplesCompareConsistently
    , tuplesCompareConsistently
    , unzipThenZipIsIdentity
    )

import Bytes
import Laws exposing (consistent, isOrdered, occurrences, small, transitive)
import Property exposing (Property)


intCompareConsistently : Int -> Int -> Property
intCompareConsistently a b =
    Property.holds (consistent a b && consistent a a)


stringsCompareConsistently : String -> String -> Property
stringsCompareConsistently a b =
    Property.holds (consistent a b && consistent a a)


bytesCompareConsistently : Bytes -> Bytes -> Property
bytesCompareConsistently a b =
    Property.holds (consistent a b && consistent a a)


listsCompareConsistently : List Int -> List Int -> Property
listsCompareConsistently a b =
    Property.holds (consistent a b && consistent a a && consistent a (a ++ b))


tuplesCompareConsistently : ( Int, String ) -> ( Int, String ) -> Property
tuplesCompareConsistently a b =
    Property.holds (consistent a b && consistent a a && consistent a ( Tuple.first a, Tuple.second b ))


triplesCompareConsistently : ( Bytes, Int, List Int ) -> ( Bytes, Int, List Int ) -> Property
triplesCompareConsistently a b =
    Property.holds (consistent a b && consistent a a)


intOrderIsTransitive : Int -> Int -> Int -> Property
intOrderIsTransitive a b c =
    Property.holds (transitive a b c && transitive (small a) (small b) (small c))


bytesOrderIsTransitive : Bytes -> Bytes -> Bytes -> Property
bytesOrderIsTransitive a b c =
    Property.holds (transitive a b c && transitive a (Bytes.append a b) (Bytes.append (Bytes.append a b) c))


listsOrderIsTransitive : List Int -> List Int -> List Int -> Property
listsOrderIsTransitive a b c =
    Property.holds (transitive a b c && transitive a (a ++ b) (a ++ b ++ c))


stringOrderIsUtf8ByteOrder : String -> String -> Property
stringOrderIsUtf8ByteOrder a b =
    Property.holds (compare a b == compare (Bytes.fromString a) (Bytes.fromString b))


minAndMaxPickAnArgument : Int -> Int -> Property
minAndMaxPickAnArgument a b =
    Property.holds
        ((min a b == a || min a b == b)
            && (max a b == a || max a b == b)
            && (min a b <= max a b)
            && (min a b <= a)
            && (max a b >= b)
        )


clampStaysWithinBounds : Int -> Int -> Int -> Property
clampStaysWithinBounds a b x =
    let
        low =
            min a b

        high =
            max a b

        clamped =
            clamp low high x
    in
    Property.holds
        ((clamped >= low)
            && (clamped <= high)
            && (if x >= low && x <= high then
                    clamped == x

                else
                    clamped == low || clamped == high
               )
        )


sortIsAnOrderedPermutation : List Int -> Property
sortIsAnOrderedPermutation items =
    let
        sorted =
            List.sort items
    in
    Property.holds
        (isOrdered sorted
            && (List.length sorted == List.length items)
            && List.all (\item -> occurrences item sorted == occurrences item items) items
        )


sortIsIdempotent : List Int -> Property
sortIsIdempotent items =
    Property.holds (List.sort (List.sort items) == List.sort items)


sortStringsIsOrdered : List String -> Property
sortStringsIsOrdered items =
    Property.holds (isOrdered (List.sort items) && List.length (List.sort items) == List.length items)


{-| Elements with equal keys keep their input order.
-}
sortByIsStable : List ( Int, Int ) -> Property
sortByIsStable pairs =
    let
        key pair =
            modBy 3 (Tuple.first pair)

        sorted =
            List.sortBy key pairs

        withKey k list =
            List.filter (\pair -> key pair == k) list
    in
    Property.holds
        (isOrdered (List.map key sorted)
            && List.all (\k -> withKey k sorted == withKey k pairs) [ 0, 1, 2 ]
            && (List.length sorted == List.length pairs)
        )


sortByMatchesSortWith : List ( Int, String ) -> Property
sortByMatchesSortWith pairs =
    Property.holds
        (List.sortBy Tuple.second pairs
            == List.sortWith (\a b -> compare (Tuple.second a) (Tuple.second b)) pairs
        )


maximumAndMinimumAreBounds : List Int -> Property
maximumAndMinimumAreBounds items =
    Property.holds
        (case ( List.maximum items, List.minimum items ) of
            ( Just largest, Just smallest ) ->
                List.member largest items
                    && List.member smallest items
                    && List.all (\item -> item <= largest && item >= smallest) items

            _ ->
                List.isEmpty items
        )


takeAndDropSplit : Int -> List Int -> Property
takeAndDropSplit n items =
    let
        count =
            small n - 5
    in
    Property.holds
        ((List.take count items ++ List.drop count items == items)
            && (List.length (List.take count items) == clamp 0 (List.length items) count)
        )


rangeHasTheRightLength : Int -> Int -> Property
rangeHasTheRightLength a b =
    let
        low =
            small a - 10

        high =
            small b - 10

        range =
            List.range low high
    in
    Property.holds
        ((List.length range == max 0 (high - low + 1))
            && isOrdered range
            && (List.head range
                    == (if low <= high then
                            Just low

                        else
                            Nothing
                       )
               )
        )


powersAdd : Int -> Int -> Int -> Property
powersAdd base e1 e2 =
    let
        b =
            small base - 10

        m =
            modBy 8 e1

        n =
            modBy 8 e2
    in
    Property.holds ((b ^ (m + n) == b ^ m * b ^ n) && (b ^ 1 == b) && (b ^ 0 == 1))


partitionMatchesFilter : List Int -> Property
partitionMatchesFilter items =
    let
        even item =
            modBy 2 item == 0
    in
    Property.holds
        (List.partition even items
            == ( List.filter even items, List.filter (\item -> not (even item)) items )
        )


unzipThenZipIsIdentity : List ( Int, Bytes ) -> Property
unzipThenZipIsIdentity pairs =
    let
        ( firsts, seconds ) =
            List.unzip pairs
    in
    Property.holds (List.map2 Tuple.pair firsts seconds == pairs)


interspersePlacesSeparators : List Int -> Property
interspersePlacesSeparators items =
    let
        spread =
            List.intersperse 0 items
    in
    Property.holds
        ((List.length spread == max 0 (2 * List.length items - 1))
            && (List.filterMap
                    (\( index, item ) ->
                        if modBy 2 index == 0 then
                            Just item

                        else
                            Nothing
                    )
                    (List.indexedMap Tuple.pair spread)
                    == items
               )
        )


fromIntAppendsDigits : Int -> Property
fromIntAppendsDigits n =
    let
        positive =
            abs n + 1
    in
    Property.holds (String.fromInt (positive * 10 + 7) == String.fromInt positive ++ "7")


fromIntNegates : Int -> Property
fromIntNegates n =
    Property.holds
        (if n > 0 then
            String.fromInt (negate n) == "-" ++ String.fromInt n

         else
            String.fromInt n == "-" ++ String.fromInt (negate n) || n == 0
        )


mapIdentityIsIdentity : Maybe Int -> Property
mapIdentityIsIdentity maybe =
    Property.holds (Maybe.map identity maybe == maybe && Maybe.andThen Just maybe == maybe)


resultMapIdentityIsIdentity : Result String Int -> Property
resultMapIdentityIsIdentity result =
    Property.holds
        ((Result.map identity result == result)
            && (Result.mapError identity result == result)
            && (Result.andThen Ok result == result)
            && (Result.toMaybe result == Result.toMaybe (Result.map identity result))
        )
