module EscrowTest exposing
    ( buyerRefundsAfterTheDeadline
    , doubleSatisfactionRejected
    , paymentWithoutTagRejected
    , refundBeforeTheDeadlineRejected
    , refundIsLateEnough
    , refundWithoutBuyerRejected
    , releasePaysTheSeller
    , releaseWithoutBuyerRejected
    , underpaymentRejected
    , wrongTagRejected
    )

import Escrow exposing (Action(..), Deal)
import Julc.Data as Data
import Property exposing (Property)
import Scenario exposing (Scenario)


deal : Deal
deal =
    { buyer = Scenario.key "buyer"
    , seller = Scenario.key "seller"
    , price = 25000000
    , deadline = 1700000000000
    }


escrow : Int -> Scenario
escrow index =
    Scenario.spend "escrow" (Scenario.outRef "escrow" index) (Data.encode deal)


tag : Int -> Data.Data
tag index =
    Data.encode (Scenario.outRef "escrow" index)


release : Scenario
release =
    escrow 0
        |> Scenario.redeemer (Data.encode Release)
        |> Scenario.signedBy deal.buyer
        |> Scenario.output (Scenario.toKey deal.seller deal.price |> Scenario.withDatum (tag 0))


releasePaysTheSeller : Scenario
releasePaysTheSeller =
    release


releaseWithoutBuyerRejected : Scenario
releaseWithoutBuyerRejected =
    escrow 0
        |> Scenario.redeemer (Data.encode Release)
        |> Scenario.output (Scenario.toKey deal.seller deal.price |> Scenario.withDatum (tag 0))
        |> Scenario.expectFailure


underpaymentRejected : Scenario
underpaymentRejected =
    escrow 0
        |> Scenario.redeemer (Data.encode Release)
        |> Scenario.signedBy deal.buyer
        |> Scenario.output (Scenario.toKey deal.seller (deal.price - 1) |> Scenario.withDatum (tag 0))
        |> Scenario.expectFailure


paymentWithoutTagRejected : Scenario
paymentWithoutTagRejected =
    escrow 0
        |> Scenario.redeemer (Data.encode Release)
        |> Scenario.signedBy deal.buyer
        |> Scenario.output (Scenario.toKey deal.seller deal.price)
        |> Scenario.expectFailure


{-| A payment tagged for another escrow does not settle this one.
-}
wrongTagRejected : Scenario
wrongTagRejected =
    escrow 0
        |> Scenario.redeemer (Data.encode Release)
        |> Scenario.signedBy deal.buyer
        |> Scenario.output (Scenario.toKey deal.seller deal.price |> Scenario.withDatum (tag 1))
        |> Scenario.expectFailure


{-| Two escrows for the same seller released together with one payment, tagged for the
first: the second is not paid for.
-}
doubleSatisfactionRejected : Scenario
doubleSatisfactionRejected =
    escrow 1
        |> Scenario.input (Scenario.outRef "escrow" 0) (Scenario.toScript deal.price |> Scenario.withDatum (Data.encode deal))
        |> Scenario.redeemer (Data.encode Release)
        |> Scenario.signedBy deal.buyer
        |> Scenario.output (Scenario.toKey deal.seller deal.price |> Scenario.withDatum (tag 0))
        |> Scenario.expectFailure


refund : Int -> Scenario
refund validFrom =
    escrow 0
        |> Scenario.redeemer (Data.encode Refund)
        |> Scenario.signedBy deal.buyer
        |> Scenario.validFrom validFrom


buyerRefundsAfterTheDeadline : Scenario
buyerRefundsAfterTheDeadline =
    refund (deal.deadline + 1)


refundWithoutBuyerRejected : Scenario
refundWithoutBuyerRejected =
    escrow 0
        |> Scenario.redeemer (Data.encode Refund)
        |> Scenario.signedBy deal.seller
        |> Scenario.validFrom (deal.deadline + 1)
        |> Scenario.expectFailure


refundBeforeTheDeadlineRejected : Scenario
refundBeforeTheDeadlineRejected =
    refund deal.deadline |> Scenario.expectFailure


{-| A refund is accepted exactly when the transaction cannot run at or before the deadline.
-}
refundIsLateEnough : Int -> Property
refundIsLateEnough offset =
    let
        start =
            deal.deadline + modBy 2001 offset - 1000

        scenario =
            refund start
    in
    Property.scenario
        (if start > deal.deadline then
            scenario

         else
            Scenario.expectFailure scenario
        )
