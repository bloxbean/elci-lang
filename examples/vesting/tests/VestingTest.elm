module VestingTest exposing (..)

import Julc.Data as Data
import Julc.Ledger exposing (PubKeyHash)
import Scenario exposing (Scenario)
import Vesting exposing (VestingAction(..), VestingDatum)


beneficiary : PubKeyHash
beneficiary =
    Scenario.key "beneficiary"


datum : VestingDatum
datum =
    { beneficiary = beneficiary, deadline = 1000 }


{-| An [entrypoint] validator is addressed as "entrypoint".
-}
claimFrom : Int -> Scenario
claimFrom time =
    Scenario.spend "entrypoint" (Scenario.outRef "vesting" 0) (Data.encode datum)
        |> Scenario.redeemer (Data.encode Claim)
        |> Scenario.validFrom time


claimsAtTheDeadline : Scenario
claimsAtTheDeadline =
    claimFrom 1000
        |> Scenario.signedBy beneficiary


claimsAfterTheDeadline : Scenario
claimsAfterTheDeadline =
    claimFrom 5000
        |> Scenario.validBefore 6000
        |> Scenario.signedBy beneficiary


earlyClaimsFail : Scenario
earlyClaimsFail =
    claimFrom 999
        |> Scenario.signedBy beneficiary
        |> Scenario.expectFailure


unsignedClaimsFail : Scenario
unsignedClaimsFail =
    claimFrom 2000
        |> Scenario.expectFailure
