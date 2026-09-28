module VestingTest exposing (..)

import Julc.Data as Data
import Julc.Ledger exposing (PubKeyHash)
import Property exposing (Property)
import Scenario exposing (Scenario)
import Vesting exposing (VestingAction(..), VestingDatum)


beneficiary : PubKeyHash
beneficiary =
    Scenario.key "beneficiary"


datum : VestingDatum
datum =
    { beneficiary = beneficiary, deadline = 1000 }


claimFrom : Int -> Scenario
claimFrom time =
    Scenario.spend "vesting" (Scenario.outRef "vesting" 0) (Data.encode datum)
        |> Scenario.redeemer (Data.encode Claim)
        |> Scenario.validFrom time


claimsAfterTheDeadline : Scenario
claimsAfterTheDeadline =
    claimFrom 1001
        |> Scenario.signedBy beneficiary


claimsInAClosedRange : Scenario
claimsInAClosedRange =
    claimFrom 5000
        |> Scenario.validBefore 6000
        |> Scenario.signedBy beneficiary


claimsAtTheDeadlineFail : Scenario
claimsAtTheDeadlineFail =
    claimFrom 1000
        |> Scenario.signedBy beneficiary
        |> Scenario.expectFailure


earlyClaimsFail : Scenario
earlyClaimsFail =
    claimFrom 999
        |> Scenario.signedBy beneficiary
        |> Scenario.expectFailure


unsignedClaimsFail : Scenario
unsignedClaimsFail =
    claimFrom 2000
        |> Scenario.expectFailure


{-| Whenever a signed claim's validity range starts, it succeeds exactly when that is
after the deadline.
-}
claimsSucceedExactlyAfterTheDeadline : Int -> Property
claimsSucceedExactlyAfterTheDeadline time =
    let
        signed =
            claimFrom time
                |> Scenario.signedBy beneficiary
    in
    if time > datum.deadline then
        Property.scenario signed

    else
        Property.scenario (Scenario.expectFailure signed)
