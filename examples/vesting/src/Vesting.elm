module Vesting exposing (VestingAction(..), VestingDatum, validate)

import Julc.Interval as Interval
import Julc.Ledger exposing (PubKeyHash, ScriptContext)


type alias VestingDatum =
    { beneficiary : PubKeyHash
    , deadline : Int
    }


type VestingAction
    = Claim


validate : VestingDatum -> VestingAction -> ScriptContext -> Bool
validate datum action ctx =
    case action of
        Claim ->
            Interval.finiteLowerBound ctx.txInfo.validRange
                >= datum.deadline
                && List.any
                    (\signer -> signer == datum.beneficiary)
                    ctx.txInfo.signatories
