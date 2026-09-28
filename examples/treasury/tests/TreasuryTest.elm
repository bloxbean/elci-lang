module TreasuryTest exposing (..)

import Bytes
import Expect exposing (Expectation)
import Julc.Data as Data
import Julc.Ledger exposing (PubKeyHash)
import Property exposing (Property)
import Scenario exposing (Scenario)
import Treasury exposing (Action(..), Payout)


admin : PubKeyHash
admin =
    Scenario.key "admin"


beneficiary : PubKeyHash
beneficiary =
    Scenario.key "beneficiary"


payout : Payout
payout =
    { beneficiary = beneficiary, amount = 50 }


claim : Action -> Scenario
claim action =
    Scenario.spend "treasury" (Scenario.outRef "treasury" 0) (Data.encode payout)
        |> Scenario.parameters [ Data.encode admin ]
        |> Scenario.redeemer (Data.encode action)


beneficiaryReleases : Scenario
beneficiaryReleases =
    claim Release
        |> Scenario.signedBy beneficiary
        |> Scenario.withinBudget 20000000 100000


strangerCannotRelease : Scenario
strangerCannotRelease =
    claim Release
        |> Scenario.signedBy admin
        |> Scenario.expectFailure


adminCancels : Scenario
adminCancels =
    claim Cancel
        |> Scenario.signedBy admin


adminWithdrawsRewards : Scenario
adminWithdrawsRewards =
    Scenario.withdraw "treasury"
        |> Scenario.parameters [ Data.encode admin ]
        |> Scenario.signedBy admin


rewardsNeedTheAdmin : Scenario
rewardsNeedTheAdmin =
    adminWithdrawsRewards
        |> Scenario.parameters [ Data.encode beneficiary ]
        |> Scenario.expectFailure


badge : Int -> Scenario
badge quantity =
    Scenario.mint "badge"
        |> Scenario.parameters [ Data.encode admin ]
        |> Scenario.minting (Bytes.fromString "BADGE") quantity
        |> Scenario.redeemer (Data.encode quantity)
        |> Scenario.signedBy admin


adminMintsBadges : Scenario
adminMintsBadges =
    badge 3


zeroBadgesAreRejected : Scenario
zeroBadgesAreRejected =
    badge 0
        |> Scenario.expectFailure


keysAreDistinct : Bool
keysAreDistinct =
    admin /= beneficiary


payoutsRoundTripThroughData : Expectation
payoutsRoundTripThroughData =
    Expect.equal payout (Data.decode (Data.encode payout))


{-| Properties run with generated arguments; a failure shrinks to a small counterexample.
-}
payoutsAlwaysRoundTrip : Payout -> Property
payoutsAlwaysRoundTrip generated =
    Property.expect (Expect.equal generated (Data.decode (Data.encode generated)))


onlyTheBeneficiaryReleases : PubKeyHash -> Property
onlyTheBeneficiaryReleases signer =
    let
        release =
            claim Release
                |> Scenario.signedBy signer
    in
    if signer == beneficiary then
        Property.scenario release

    else
        Property.scenario (Scenario.expectFailure release)


badgesNeedAPositiveQuantity : Int -> Property
badgesNeedAPositiveQuantity quantity =
    if quantity > 0 then
        Property.scenario (badge quantity)

    else
        Property.scenario (Scenario.expectFailure (badge quantity))
