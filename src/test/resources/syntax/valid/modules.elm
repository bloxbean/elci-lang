module Corpus.Modules exposing (..)

{-| Module headers, imports and qualified names. -}

import Dict exposing (Dict)
import Interval
import Julc.Data as Data
import Maybe exposing (Maybe(..), withDefault)


type alias Pair =
    ( Maybe.Maybe Int, Dict Int Int )


pair : Pair
pair =
    ( Maybe.Just 2, Dict.fromList [ ( 1, 10 ) ] )


main : Bool
main =
    case pair of
        ( Maybe.Just two, table ) ->
            Basics.max two 1
                == 2
                && withDefault 0 (Dict.get 1 table)
                == 10
                && Interval.everything.from.isInclusive
                && Data.encode (Maybe.Just 1)
                /= Data.encode Maybe.Nothing

        ( Nothing, _ ) ->
            False
