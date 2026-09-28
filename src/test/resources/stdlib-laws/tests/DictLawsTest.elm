module DictLawsTest exposing
    ( dictEqualityAgreesWithToList
    , dictFromListKeepsTheLastBinding
    , dictGetAfterInsert
    , dictMapFilterAndFold
    , dictRemoveRemoves
    , dictRoundTripsThroughData
    , dictSetOperations
    , dictStringKeys
    , dictStructuredKeys
    , mapFromListMatchesInsertion
    , mapGetMatchesToList
    , mapMapAndFilterKeepOrder
    )

import Bytes
import Dict exposing (Dict)
import Julc.Data as Data
import Laws exposing (lastValue, strictlyAscending)
import Map exposing (Map)
import Property exposing (Property)


dictFromListKeepsTheLastBinding : List ( Int, Int ) -> Property
dictFromListKeepsTheLastBinding pairs =
    let
        dict =
            Dict.fromList (List.map (\( k, v ) -> ( modBy 7 k, v )) pairs)

        narrowed =
            List.map (\( k, v ) -> ( modBy 7 k, v )) pairs
    in
    Property.holds
        (strictlyAscending (Dict.keys dict)
            && List.all (\( k, _ ) -> Dict.get k dict == lastValue k narrowed) narrowed
            && (Dict.size dict == List.length (List.filter (\k -> Dict.member k dict) (List.range 0 6)))
        )


dictGetAfterInsert : Dict Int Int -> Int -> Int -> Property
dictGetAfterInsert dict key value =
    let
        inserted =
            Dict.insert key value dict
    in
    Property.holds
        ((Dict.get key inserted == Just value)
            && strictlyAscending (Dict.keys inserted)
            && List.all (\( k, v ) -> k == key || Dict.get k inserted == Just v) (Dict.toList dict)
            && (Dict.size inserted
                    == (if Dict.member key dict then
                            Dict.size dict

                        else
                            Dict.size dict + 1
                       )
               )
        )


dictRemoveRemoves : Dict Int Int -> Int -> Property
dictRemoveRemoves dict key =
    let
        removed =
            Dict.remove key dict

        present =
            List.head (Dict.keys dict)
    in
    Property.holds
        (not (Dict.member key removed)
            && strictlyAscending (Dict.keys removed)
            && (case present of
                    Just k ->
                        not (Dict.member k (Dict.remove k dict)) && Dict.size (Dict.remove k dict) == Dict.size dict - 1

                    Nothing ->
                        Dict.isEmpty dict
               )
        )


dictSetOperations : Dict Int Int -> Dict Int Int -> Property
dictSetOperations left right =
    let
        union =
            Dict.union left right

        intersect =
            Dict.intersect left right

        diff =
            Dict.diff left right

        keys =
            Dict.keys left ++ Dict.keys right
    in
    Property.holds
        (strictlyAscending (Dict.keys union)
            && strictlyAscending (Dict.keys intersect)
            && strictlyAscending (Dict.keys diff)
            && List.all
                (\k ->
                    (Dict.get k union
                        == (if Dict.member k left then
                                Dict.get k left

                            else
                                Dict.get k right
                           )
                    )
                        && (Dict.member k intersect == (Dict.member k left && Dict.member k right))
                        && (Dict.member k diff == (Dict.member k left && not (Dict.member k right)))
                )
                keys
            && (Dict.merge (\_ _ n -> n + 1) (\_ _ _ n -> n + 1) (\_ _ n -> n + 1) left right 0 == Dict.size union)
        )


dictEqualityAgreesWithToList : Dict Int Int -> Dict Int Int -> Property
dictEqualityAgreesWithToList left right =
    Property.holds
        ((left == right)
            == (Dict.toList left == Dict.toList right)
            && (left == Dict.fromList (List.reverse (Dict.toList left)))
        )


dictRoundTripsThroughData : Dict String Int -> Property
dictRoundTripsThroughData dict =
    let
        decoded : Dict String Int
        decoded =
            Data.decode (Data.encode dict)
    in
    Property.holds (decoded == dict)


dictStringKeys : List ( String, Int ) -> String -> Property
dictStringKeys pairs key =
    let
        dict =
            Dict.fromList pairs
    in
    Property.holds
        (strictlyAscending (Dict.keys dict)
            && (Dict.get key dict == lastValue key pairs)
            && (Dict.get key (Dict.insert key 1 dict) == Just 1)
        )


dictMapFilterAndFold : Dict Int Int -> Property
dictMapFilterAndFold dict =
    Property.holds
        ((Dict.toList (Dict.map (\k v -> k + v) dict) == List.map (\( k, v ) -> ( k, k + v )) (Dict.toList dict))
            && (Dict.toList (Dict.filter (\_ v -> modBy 2 v == 0) dict)
                    == List.filter (\( _, v ) -> modBy 2 v == 0) (Dict.toList dict)
               )
            && (Dict.foldr (\k _ acc -> k :: acc) [] dict == Dict.keys dict)
            && (Dict.foldl (\k _ acc -> k :: acc) [] dict == List.reverse (Dict.keys dict))
            && (Tuple.first (Dict.partition (\k _ -> k > 0) dict) == Dict.filter (\k _ -> k > 0) dict)
        )


mapFromListMatchesInsertion : List ( Int, Int ) -> Property
mapFromListMatchesInsertion pairs =
    let
        narrowed =
            List.map (\( k, v ) -> ( modBy 5 k, v )) pairs
    in
    Property.holds
        (Map.toList (Map.fromList narrowed)
            == Map.toList (List.foldl (\( k, v ) acc -> Map.insert k v acc) Map.empty narrowed)
        )


mapGetMatchesToList : Map Int Int -> Int -> Property
mapGetMatchesToList bindings key =
    let
        pairs =
            Map.toList bindings

        probe =
            case List.head pairs of
                Just ( k, _ ) ->
                    k

                Nothing ->
                    key
    in
    Property.holds
        ((Map.get key bindings == lastValue key (List.reverse pairs))
            && (Map.get probe bindings == lastValue probe (List.reverse pairs))
            && (Map.member probe (Map.remove probe bindings) == List.member probe (List.drop 1 (List.map (\( k, _ ) -> k) pairs)))
        )


mapMapAndFilterKeepOrder : Map Int Int -> Property
mapMapAndFilterKeepOrder bindings =
    Property.holds
        ((Map.toList (Map.map (\k v -> k * v) bindings) == List.map (\( k, v ) -> ( k, k * v )) (Map.toList bindings))
            && (Map.toList (Map.filter (\k _ -> k > 0) bindings) == List.filter (\( k, _ ) -> k > 0) (Map.toList bindings))
            && (Map.foldr (\k _ acc -> k :: acc) [] bindings == Map.keys bindings)
        )


{-| Keys of every comparable shape keep the Dict sorted, agree with `<`, and round-trip.
-}
dictStructuredKeys : List ( ( Bytes, String ), Int ) -> List ( List String, Int ) -> Property
dictStructuredKeys pairs listPairs =
    let
        byPair =
            Dict.fromList pairs

        byList =
            Dict.fromList listPairs

        pairRoundTrip : Dict ( Bytes, String ) Int
        pairRoundTrip =
            Data.decode (Data.encode byPair)

        listRoundTrip : Dict (List String) Int
        listRoundTrip =
            Data.decode (Data.encode byList)
    in
    Property.holds
        (strictlyAscending (Dict.keys byPair)
            && strictlyAscending (Dict.keys byList)
            && List.all (\( k, _ ) -> Dict.get k byPair == lastValue k pairs) pairs
            && List.all (\( k, _ ) -> Dict.get k byList == lastValue k listPairs) listPairs
            && (pairRoundTrip == byPair)
            && (listRoundTrip == byList)
        )
