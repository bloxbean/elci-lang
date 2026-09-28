module Property exposing (Property, expect, holds, scenario)

{-| Properties for `elci test`: checks that should hold for every argument.

An exposed test function whose result is a `Property` runs many times, with
arguments generated from its parameter types. Every argument is a valid value of
its type, decoded as a validator would decode it. A failure is shrunk to a small
counterexample and reported with the seed that reproduces it. Functions that
return an `Expectation`, a `Bool` or a `Scenario` are helpers, not properties.

    payoutsRoundTrip : Payout -> Property
    payoutsRoundTrip payout =
        Property.expect (Expect.equal payout (Data.decode (Data.encode payout)))

-}

import Expect exposing (Expectation)
import Scenario exposing (Scenario)


type Property
    = Expecting Expectation
    | Transacting Scenario


{-| The property holds when the expectation passes.
-}
expect : Expectation -> Property
expect expectation =
    Expecting expectation


{-| The property holds when the condition is `True`.
-}
holds : Bool -> Property
holds condition =
    if condition then
        Expecting Expect.pass

    else
        Expecting (Expect.fail "the property does not hold")


{-| The property holds when the scenario's transaction has the expected outcome:
the validator accepts it, or rejects it after `Scenario.expectFailure`.
-}
scenario : Scenario -> Property
scenario transaction =
    Transacting transaction
