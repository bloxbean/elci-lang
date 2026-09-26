module MarketplaceTest exposing
    ( buyPaysSellerAndOperator
    , buyWithoutFeeRejected
    , cancelByOtherRejected
    , cancelBySeller
    , feeRoundsDown
    , feeSplitsThePrice
    , sellerShortchangedRejected
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


{-| The seller's share and the fee always add up to the price, and the fee never exceeds it.
-}
feeSplitsThePrice : Int -> Int -> Property
feeSplitsThePrice rawPrice rawBasisPoints =
    let
        price =
            abs rawPrice

        basisPoints =
            modBy 10001 rawBasisPoints

        cut =
            Marketplace.fee { operator = market.operator, feeBasisPoints = basisPoints } price
    in
    Property.holds (cut >= 0 && cut <= price && (price - cut) + cut == price)
