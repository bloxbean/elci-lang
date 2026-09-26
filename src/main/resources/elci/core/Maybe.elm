module Maybe exposing (Maybe(..), andThen, map, map2, map3, withDefault)

{-| Optional values, as in Elm's `Maybe`. `Maybe`, `Just` and `Nothing` are built in; this
module adds the functions. Every module imports it with `exposing (Maybe(..))`.
-}


{-| The value inside a `Just`, or a default: `withDefault 0 (Just 7) == 7`.
-}
withDefault : a -> Maybe a -> a
withDefault default maybe =
    case maybe of
        Just value ->
            value

        Nothing ->
            default


{-| Transform the value inside a `Just`.
-}
map : (a -> b) -> Maybe a -> Maybe b
map transform maybe =
    case maybe of
        Just value ->
            Just (transform value)

        Nothing ->
            Nothing


{-| Combine two optional values, when both are present.
-}
map2 : (a -> b -> value) -> Maybe a -> Maybe b -> Maybe value
map2 transform first second =
    case first of
        Just a ->
            case second of
                Just b ->
                    Just (transform a b)

                Nothing ->
                    Nothing

        Nothing ->
            Nothing


{-| Combine three optional values, when all are present.
-}
map3 : (a -> b -> c -> value) -> Maybe a -> Maybe b -> Maybe c -> Maybe value
map3 transform first second third =
    case first of
        Just a ->
            case second of
                Just b ->
                    case third of
                        Just c ->
                            Just (transform a b c)

                        Nothing ->
                            Nothing

                Nothing ->
                    Nothing

        Nothing ->
            Nothing


{-| Chain computations that may produce nothing: `andThen List.head (Just [ 1 ]) == Just 1`.
-}
andThen : (a -> Maybe b) -> Maybe a -> Maybe b
andThen callback maybe =
    case maybe of
        Just value ->
            callback value

        Nothing ->
            Nothing
