module Vesting exposing (VestingAction(..), VestingDatum, validate)

import Interval
import Julc.Ledger exposing (PubKeyHash, ScriptContext)
import Tx


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
            Interval.isEntirelyAfter datum.deadline ctx.txInfo.validRange
                && Tx.signedBy datum.beneficiary ctx
