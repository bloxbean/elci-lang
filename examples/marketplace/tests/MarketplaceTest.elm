module MarketplaceTest exposing
    ( buyPaysSellerAndOperator
    , buyWithoutFeeRejected
    , buyingPaysExactly
    , cancelByOtherRejected
    , cancelBySeller
    , feeOutOfRangeRejected
    , feeRoundsDown
    , negativeFeeRejected
    , onePaymentForTwoListingsRejected
    , operatorSellingIsPaidInFull
    , operatorSellingShortchangedRejected
    , sellerShortchangedRejected
    , untaggedPaymentRejected
    )

import Julc.Data as Data
import Marketplace exposing (Action(..), Listing, Market)
import Property exposing (Property)
import Scenario exposing (Scenario)


market : Market
market =
    { operator = Scenario.key "operator", feeBasisPoints = 250 }


listing : Listing
listing =
    { seller = Scenario.key "seller", price = 100000000 }


tag : Data.Data
tag =
    Data.encode (Scenario.outRef "listing" 0)


buy : Int -> Int -> Scenario
buy toSeller toOperator =
    Scenario.spend "marketplace" (Scenario.outRef "listing" 0) (Data.encode listing)
        |> Scenario.parameters [ Data.encode market ]
        |> Scenario.redeemer (Data.encode Buy)
        |> Scenario.output (Scenario.toKey listing.seller toSeller |> Scenario.withDatum tag)
        |> Scenario.output (Scenario.toKey market.operator toOperator |> Scenario.withDatum tag)


buyPaysSellerAndOperator : Scenario
buyPaysSellerAndOperator =
    buy 97500000 2500000


buyWithoutFeeRejected : Scenario
buyWithoutFeeRejected =
    buy 97500000 2499999 |> Scenario.expectFailure


sellerShortchangedRejected : Scenario
sellerShortchangedRejected =
    buy 97499999 2500000 |> Scenario.expectFailure


{-| Two listings by one seller, bought together with one pair of payments tagged for the
first: the second is not paid for.
-}
onePaymentForTwoListingsRejected : Scenario
onePaymentForTwoListingsRejected =
    Scenario.spend "marketplace" (Scenario.outRef "listing" 1) (Data.encode listing)
        |> Scenario.parameters [ Data.encode market ]
        |> Scenario.redeemer (Data.encode Buy)
        |> Scenario.input (Scenario.outRef "listing" 0) (Scenario.toScript 2000000 |> Scenario.withDatum (Data.encode listing))
        |> Scenario.output (Scenario.toKey listing.seller 97500000 |> Scenario.withDatum tag)
        |> Scenario.output (Scenario.toKey market.operator 2500000 |> Scenario.withDatum tag)
        |> Scenario.expectFailure


untaggedPaymentRejected : Scenario
untaggedPaymentRejected =
    Scenario.spend "marketplace" (Scenario.outRef "listing" 0) (Data.encode listing)
        |> Scenario.parameters [ Data.encode market ]
        |> Scenario.redeemer (Data.encode Buy)
        |> Scenario.output (Scenario.toKey listing.seller 97500000)
        |> Scenario.output (Scenario.toKey market.operator 2500000)
        |> Scenario.expectFailure


{-| The operator selling their own NFT, paid in one output.
-}
operatorSells : Int -> Scenario
operatorSells amount =
    Scenario.spend "marketplace" (Scenario.outRef "listing" 0) (Data.encode { listing | seller = market.operator })
        |> Scenario.parameters [ Data.encode market ]
        |> Scenario.redeemer (Data.encode Buy)
        |> Scenario.output (Scenario.toKey market.operator amount |> Scenario.withDatum tag)


operatorSellingIsPaidInFull : Scenario
operatorSellingIsPaidInFull =
    operatorSells 100000000


operatorSellingShortchangedRejected : Scenario
operatorSellingShortchangedRejected =
    operatorSells 97500000 |> Scenario.expectFailure


{-| A purchase that pays the seller and operator what a fee of `basisPoints` would ask.
-}
buyAtFee : Int -> Scenario
buyAtFee basisPoints =
    let
        charging =
            { market | feeBasisPoints = basisPoints }

        cut =
            Marketplace.fee charging listing.price
    in
    Scenario.spend "marketplace" (Scenario.outRef "listing" 0) (Data.encode listing)
        |> Scenario.parameters [ Data.encode charging ]
        |> Scenario.redeemer (Data.encode Buy)
        |> Scenario.output (Scenario.toKey listing.seller (max 1000000 (listing.price - cut)) |> Scenario.withDatum tag)
        |> Scenario.output (Scenario.toKey market.operator (max 1000000 cut) |> Scenario.withDatum tag)


{-| A marketplace deployed with a fee over 100% rejects every purchase, even one that pays
what that fee asks.
-}
feeOutOfRangeRejected : Scenario
feeOutOfRangeRejected =
    buyAtFee 10001 |> Scenario.expectFailure


negativeFeeRejected : Scenario
negativeFeeRejected =
    buyAtFee -1 |> Scenario.expectFailure


cancel : String -> Scenario
cancel signer =
    Scenario.spend "marketplace" (Scenario.outRef "listing" 0) (Data.encode listing)
        |> Scenario.parameters [ Data.encode market ]
        |> Scenario.redeemer (Data.encode Cancel)
        |> Scenario.signedBy (Scenario.key signer)


cancelBySeller : Scenario
cancelBySeller =
    cancel "seller"


cancelByOtherRejected : Scenario
cancelByOtherRejected =
    cancel "thief" |> Scenario.expectFailure


feeRoundsDown : Bool
feeRoundsDown =
    Marketplace.fee { operator = market.operator, feeBasisPoints = 250 } 399 == 9


{-| At any price and fee below 100%, paying the seller exactly the price less the fee and
the operator the fee buys the NFT; one lovelace less to the seller does not.
-}
buyingPaysExactly : Int -> Int -> Bool -> Property
buyingPaysExactly rawPrice rawBasisPoints short =
    let
        priced =
            { listing | price = modBy 1000000000 (abs rawPrice) + 1 }

        charging =
            { market | feeBasisPoints = modBy 10000 rawBasisPoints }

        cut =
            Marketplace.fee charging priced.price

        toSeller =
            if short then
                priced.price - cut - 1

            else
                priced.price - cut

        scenario =
            Scenario.spend "marketplace" (Scenario.outRef "listing" 0) (Data.encode priced)
                |> Scenario.parameters [ Data.encode charging ]
                |> Scenario.redeemer (Data.encode Buy)
                |> Scenario.output (Scenario.toKey priced.seller toSeller |> Scenario.withDatum tag)
                |> Scenario.output (Scenario.toKey charging.operator cut |> Scenario.withDatum tag)
    in
    Property.scenario
        (if short then
            Scenario.expectFailure scenario

         else
            scenario
        )
