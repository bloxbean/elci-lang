module Interval exposing (Interval, after, before, between, contains, end, everything, isEntirelyAfter, isEntirelyBefore, start)

{-| Time ranges: the ledger's `Interval`, such as a transaction's validity range. Times are
POSIX milliseconds.

A script cannot read the clock; the ledger accepts a transaction only inside its validity
range. To require that something happens after a time, check that the whole range is after
it; before a time, that the whole range is before it. A check needs a finite bound on its
side: an unbounded side never satisfies it, and neither does an infinite bound on the wrong
side, which only an empty range has. This follows Aiken.

-}

import Julc.Ledger exposing (Interval(..), IntervalBound(..), IntervalBoundType(..))


{-| Whether every time in the range is after `time`: `isEntirelyAfter deadline
ctx.txInfo.validRange` holds only when the transaction cannot run at or before the
deadline.
-}
isEntirelyAfter : Int -> Interval -> Bool
isEntirelyAfter time range =
    case range.from.boundType of
        Finite low ->
            if range.from.isInclusive then
                time < low

            else
                time <= low

        _ ->
            False


{-| Whether every time in the range is before `time`: `isEntirelyBefore deadline
ctx.txInfo.validRange` holds only when the transaction cannot run at or after the deadline.
-}
isEntirelyBefore : Int -> Interval -> Bool
isEntirelyBefore time range =
    case range.to.boundType of
        Finite high ->
            if range.to.isInclusive then
                high < time

            else
                high <= time

        _ ->
            False


{-| Whether `time` is in the range.
-}
contains : Int -> Interval -> Bool
contains time range =
    startsBy time range.from && endsFrom time range.to


startsBy : Int -> IntervalBound -> Bool
startsBy time bound =
    case bound.boundType of
        Finite low ->
            if bound.isInclusive then
                low <= time

            else
                low < time

        NegInf ->
            True

        PosInf ->
            False


endsFrom : Int -> IntervalBound -> Bool
endsFrom time bound =
    case bound.boundType of
        Finite high ->
            if bound.isInclusive then
                time <= high

            else
                time < high

        PosInf ->
            True

        NegInf ->
            False


{-| The range's finite start, or `Nothing` when it has none. Whether the start itself is
included is in `range.from.isInclusive`; the checks above account for it.
-}
start : Interval -> Maybe Int
start range =
    case range.from.boundType of
        Finite low ->
            Just low

        _ ->
            Nothing


{-| The range's finite end, or `Nothing` when it has none. Whether the end itself is included
is in `range.to.isInclusive`: a transaction's validity range excludes its end, so the end is
the first time outside it.
-}
end : Interval -> Maybe Int
end range =
    case range.to.boundType of
        Finite high ->
            Just high

        _ ->
            Nothing


{-| From `time` on, inclusive.
-}
after : Int -> Interval
after time =
    Interval (IntervalBound (Finite time) True) (IntervalBound PosInf True)


{-| Up to `time`, inclusive.
-}
before : Int -> Interval
before time =
    Interval (IntervalBound NegInf True) (IntervalBound (Finite time) True)


{-| From `low` to `high`, both inclusive.
-}
between : Int -> Int -> Interval
between low high =
    Interval (IntervalBound (Finite low) True) (IntervalBound (Finite high) True)


{-| All time.
-}
everything : Interval
everything =
    Interval (IntervalBound NegInf True) (IntervalBound PosInf True)
