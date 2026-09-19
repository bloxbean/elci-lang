module Signed exposing (validate)

import Julc.Ledger exposing (PubKeyHash, ScriptContext)


validate : PubKeyHash -> Int -> ScriptContext -> Bool
validate owner minimumFee ctx =
    ctx.txInfo.fee
        >= minimumFee
        && List.any (\signer -> signer == owner) ctx.txInfo.signatories
