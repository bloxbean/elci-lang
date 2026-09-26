module SwapTest exposing
    ( cancelByMaker
    , cancelByOtherRejected
    , fillCompletely
    , fillPartially
    , fillsAreChecked
    , overfillRejected
    , partialFillMustReturnTheRest
    , secondOrderInTheSameTransactionRejected
    , underpaidRejected
    )

import Bytes
import Julc.Data as Data
import Julc.Ledger exposing (PolicyId(..), TokenName(..))
import Property exposing (Property)
import Scenario exposing (Policy(..), Scenario)
import Swap exposing (Action(..), Order)


gold : Bytes
gold =
    Bytes.fromString "gold-policy-0123456789abcdef"


silver : Bytes
silver =
    Bytes.fromString "silver-policy-0123456789abcd"


order : Order
order =
    { maker = Scenario.key "maker"
    , offerPolicy = PolicyId gold
    , offerName = TokenName (Bytes.fromString "GOLD")
    , askPolicy = PolicyId silver
    , askName = TokenName (Bytes.fromString "SILVER")
    , price = 3
    }


{-| The order's output: 10 GOLD at the script.
-}
locked : Int -> Scenario -> Scenario
locked index =
    Scenario.input (Scenario.outRef "order" index)
        (Scenario.toScript 2000000
            |> Scenario.withToken (Policy gold) (Bytes.fromString "GOLD") 10
            |> Scenario.withDatum (Data.encode order)
        )


take : Int -> Int -> Scenario
take amount payment =
    Scenario.spend "swap" (Scenario.outRef "order" 0) (Data.encode order)
        |> locked 0
        |> Scenario.redeemer (Data.encode (Take amount))
        |> Scenario.output
            (Scenario.toKey order.maker 2000000
                |> Scenario.withToken (Policy silver) (Bytes.fromString "SILVER") payment
                |> Scenario.withDatum (Data.encode (Scenario.outRef "order" 0))
            )


rest : Int -> Scenario -> Scenario
rest quantity =
    Scenario.output
        (Scenario.toScript 2000000
            |> Scenario.withToken (Policy gold) (Bytes.fromString "GOLD") quantity
            |> Scenario.withDatum (Data.encode order)
        )


fillCompletely : Scenario
fillCompletely =
    take 10 30


fillPartially : Scenario
fillPartially =
    take 4 12 |> rest 6


partialFillMustReturnTheRest : Scenario
partialFillMustReturnTheRest =
    take 4 12 |> Scenario.expectFailure


underpaidRejected : Scenario
underpaidRejected =
    take 10 29 |> Scenario.expectFailure


overfillRejected : Scenario
overfillRejected =
    take 11 33 |> Scenario.expectFailure


{-| Two orders spent together cannot share one returned output.
-}
secondOrderInTheSameTransactionRejected : Scenario
secondOrderInTheSameTransactionRejected =
    take 4 12 |> rest 6 |> locked 1 |> Scenario.expectFailure


cancel : String -> Scenario
cancel signer =
    Scenario.spend "swap" (Scenario.outRef "order" 0) (Data.encode order)
        |> locked 0
        |> Scenario.redeemer (Data.encode Cancel)
        |> Scenario.signedBy (Scenario.key signer)


cancelByMaker : Scenario
cancelByMaker =
    cancel "maker"


cancelByOtherRejected : Scenario
cancelByOtherRejected =
    cancel "taker" |> Scenario.expectFailure


{-| Every fill of 1 to 10 succeeds with exact payment and the rest returned; one unit less
of payment always fails.
-}
fillsAreChecked : Int -> Bool -> Property
fillsAreChecked raw short =
    let
        amount =
            modBy 10 raw + 1

        payment =
            if short then
                amount * order.price - 1

            else
                amount * order.price

        scenario =
            if amount == 10 then
                take amount payment

            else
                take amount payment |> rest (10 - amount)
    in
    Property.scenario
        (if short then
            Scenario.expectFailure scenario

         else
            scenario
        )
