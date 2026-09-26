module Result exposing (Result(..), andThen, fromMaybe, map, map2, map3, mapError, toMaybe, withDefault)

{-| Computations that may fail with an error, as in Elm's `Result`. Every module imports
it with `exposing (Result(..))`.

As Data, `Ok` is constructor 0 and `Err` constructor 1.

-}


{-| Either a value (`Ok`) or an error (`Err`).
-}
type Result error value
    = Ok value
    | Err error


{-| Transform an `Ok` value.
-}
map : (a -> value) -> Result x a -> Result x value
map transform result =
    case result of
        Ok value ->
            Ok (transform value)

        Err error ->
            Err error


{-| Combine two results; the first error wins.
-}
map2 : (a -> b -> value) -> Result x a -> Result x b -> Result x value
map2 transform first second =
    case first of
        Ok a ->
            case second of
                Ok b ->
                    Ok (transform a b)

                Err error ->
                    Err error

        Err error ->
            Err error


{-| Combine three results; the first error wins.
-}
map3 : (a -> b -> c -> value) -> Result x a -> Result x b -> Result x c -> Result x value
map3 transform first second third =
    case first of
        Ok a ->
            case second of
                Ok b ->
                    case third of
                        Ok c ->
                            Ok (transform a b c)

                        Err error ->
                            Err error

                Err error ->
                    Err error

        Err error ->
            Err error


{-| Chain computations that may fail.
-}
andThen : (a -> Result x b) -> Result x a -> Result x b
andThen callback result =
    case result of
        Ok value ->
            callback value

        Err error ->
            Err error


{-| The `Ok` value, or a default.
-}
withDefault : a -> Result x a -> a
withDefault default result =
    case result of
        Ok value ->
            value

        Err _ ->
            default


{-| `Just` the `Ok` value, or `Nothing`.
-}
toMaybe : Result x a -> Maybe a
toMaybe result =
    case result of
        Ok value ->
            Just value

        Err _ ->
            Nothing


{-| `Ok` with the value of a `Just`, or `Err` with the given error.
-}
fromMaybe : x -> Maybe a -> Result x a
fromMaybe error maybe =
    case maybe of
        Just value ->
            Ok value

        Nothing ->
            Err error


{-| Transform an `Err` error.
-}
mapError : (x -> y) -> Result x a -> Result y a
mapError transform result =
    case result of
        Ok value ->
            Ok value

        Err error ->
            Err (transform error)
