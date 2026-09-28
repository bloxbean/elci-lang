module String exposing (append, concat, fromInt, isEmpty, join)

{-| Text, as in Elm's `String`. Every module imports it.

A `String` is Plutus text: UTF-8, compared by its bytes. `Bytes.fromString` and
`Bytes.toString` convert to and from its UTF-8 bytes.

-}

import Basics exposing (remainderBy)
import Bytes
import List


{-| Whether a string is empty: `isEmpty "" == True`.
-}
isEmpty : String -> Bool
isEmpty string =
    string == ""


{-| Append two strings, the same as `++`.
-}
append : String -> String -> String
append left right =
    left ++ right


{-| Concatenate a list of strings: `concat [ "ab", "cd" ] == "abcd"`.
-}
concat : List String -> String
concat strings =
    List.foldr (\string result -> string ++ result) "" strings


{-| Concatenate strings with a separator between them: `join ", " [ "a", "b" ] == "a, b"`.
-}
join : String -> List String -> String
join separator strings =
    case strings of
        [] ->
            ""

        first :: rest ->
            first ++ List.foldr (\string result -> separator ++ string ++ result) "" rest


{-| The decimal form of an integer: `fromInt -42 == "-42"`.
-}
fromInt : Int -> String
fromInt n =
    if n < 0 then
        "-" ++ Bytes.toString (digits (0 - n) Bytes.empty)

    else
        Bytes.toString (digits n Bytes.empty)


digits : Int -> Bytes -> Bytes
digits n result =
    if n < 10 then
        Bytes.cons (48 + n) result

    else
        digits (n // 10) (Bytes.cons (48 + remainderBy 10 n) result)
