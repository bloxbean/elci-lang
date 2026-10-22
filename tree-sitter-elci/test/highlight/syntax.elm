module Example.Shapes exposing (Shape(..), area)
-- <- keyword
--     ^ module
--                    ^ keyword
--                               ^ type
--                                         ^ function

import Julc.Data as Data exposing (encode)
-- <- keyword
--      ^ module
--               ^ keyword
--                   ^ module
--                         ^ keyword
--                                  ^ function


{-| Shapes with integer sides.
-}
-- <- comment.documentation
type Shape
-- <- keyword
--   ^ type
    = Circle Int
    --  ^ constructor
    --       ^ type
    | Rect { width : Int, height : Int }
    --       ^ property


type alias Pair a =
-- <- keyword
--    ^ keyword
--          ^ type
--              ^ type
    ( a, a )


area : Shape -> Int
-- <- function
--       ^ type
--           ^ operator
area shape =
-- <- function
--    ^ variable.parameter
    case shape of
    -- <- keyword
    --         ^ keyword
        Circle radius ->
        -- <- constructor
        --      ^ variable
            3 * radius * radius
            -- <- number
            --^ operator

        Rect { width, height } ->
        --       ^ property
            width * height -- the area
            --             ^ comment


label : Bool -> String
label flag =
    if flag && True then
    -- <- keyword
    --         ^ constant.builtin
        "yes\n"
        -- <- string
        --  ^ string.escape

    else
        String.fromInt (List.length [ -1 ] |> max 0)
        -- <- module
        --     ^ function
        --                                 ^ operator


widths : List { width : Int } -> List Int
widths items =
    let
    -- <- keyword
        first =
            List.map .width items
            --        ^ property
    in
    -- <- keyword
    first ++ List.map (\item -> item.width) items
    --                 ^ keyword
    --                               ^ property
