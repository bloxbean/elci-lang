module Expect exposing (Expectation(..), atLeast, atMost, equal, every, fail, greaterThan, lessThan, notEqual, onFail, pass)

{-| Expectations for `elci test`, in the style of elm-test.

A failure carries a message and the Data encodings of the values involved, which
the test runner prints.

-}

import Julc.Data as Data


type Expectation
    = Pass
    | Fail String (List Data)


pass : Expectation
pass =
    Pass


fail : String -> Expectation
fail message =
    Fail message []


{-| Replace the message of a failing expectation.
-}
onFail : String -> Expectation -> Expectation
onFail message expectation =
    case expectation of
        Pass ->
            Pass

        Fail _ values ->
            Fail message values


equal : a -> a -> Expectation
equal expected actual =
    if expected == actual then
        Pass

    else
        Fail "Expect.equal" [ Data.encode expected, Data.encode actual ]


notEqual : a -> a -> Expectation
notEqual unexpected actual =
    if unexpected /= actual then
        Pass

    else
        Fail "Expect.notEqual" [ Data.encode actual ]


compare : String -> (comparable -> comparable -> Bool) -> comparable -> comparable -> Expectation
compare name holds bound actual =
    if holds actual bound then
        Pass

    else
        Fail name [ Data.encode bound, Data.encode actual ]


{-| `atMost bound actual` passes when `actual <= bound`.
-}
atMost : comparable -> comparable -> Expectation
atMost =
    compare "Expect.atMost" (\actual bound -> actual <= bound)


{-| `atLeast bound actual` passes when `actual >= bound`.
-}
atLeast : comparable -> comparable -> Expectation
atLeast =
    compare "Expect.atLeast" (\actual bound -> actual >= bound)


{-| `lessThan bound actual` passes when `actual < bound`.
-}
lessThan : comparable -> comparable -> Expectation
lessThan =
    compare "Expect.lessThan" (\actual bound -> actual < bound)


{-| `greaterThan bound actual` passes when `actual > bound`.
-}
greaterThan : comparable -> comparable -> Expectation
greaterThan =
    compare "Expect.greaterThan" (\actual bound -> actual > bound)


{-| Combine expectations; the first failure is the result. Elci lists hold data, not
functions, so this takes evaluated expectations rather than elm-test's `all` checks.
-}
every : List Expectation -> Expectation
every expectations =
    case expectations of
        [] ->
            Pass

        Pass :: rest ->
            every rest

        failure :: _ ->
            failure
