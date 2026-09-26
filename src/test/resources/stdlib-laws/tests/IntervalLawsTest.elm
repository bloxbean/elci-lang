module IntervalLawsTest exposing (intervalBoundsAgree, intervalChecksAgreeWithContains)

import Interval
import Julc.Ledger exposing (Interval(..), IntervalBound(..), IntervalBoundType(..))
import Laws exposing (small)
import Property exposing (Property)


intervalBoundsAgree : Int -> Int -> Int -> Property
intervalBoundsAgree rawLow rawHigh rawTime =
    let
        low =
            small rawLow

        high =
            low + small rawHigh

        time =
            small rawTime - 5
    in
    Property.holds
        ((Interval.contains time (Interval.after low) == (time >= low))
            && (Interval.contains time (Interval.before high) == (time <= high))
            && (Interval.contains time (Interval.between low high) == (time >= low && time <= high))
            && Interval.contains time Interval.everything
            && (Interval.isEntirelyAfter time (Interval.between low high) == (time < low))
            && (Interval.isEntirelyBefore time (Interval.between low high) == (high < time))
            && (Interval.start (Interval.between low high) == Just low)
            && (Interval.end (Interval.after low) == Nothing)
        )


{-| For every bound shape, "entirely after t" means every time the range contains is after
t, and "entirely before" likewise.
-}
intervalChecksAgreeWithContains : Int -> Int -> Bool -> Bool -> Int -> Property
intervalChecksAgreeWithContains rawLow rawHigh lowInclusive highInclusive rawTime =
    let
        low =
            small rawLow

        high =
            low + small rawHigh

        time =
            small rawTime

        range =
            Interval (IntervalBound (Finite low) lowInclusive) (IntervalBound (Finite high) highInclusive)

        inside =
            List.filter (\x -> Interval.contains x range) (List.range (low - 1) (high + 1))
    in
    Property.holds
        ((if Interval.isEntirelyAfter time range then
            List.all (\x -> x > time) inside

          else
            List.any (\x -> x <= time) inside || List.isEmpty inside
         )
            && (if Interval.isEntirelyBefore time range then
                    List.all (\x -> x < time) inside

                else
                    List.any (\x -> x >= time) inside || List.isEmpty inside
               )
        )
