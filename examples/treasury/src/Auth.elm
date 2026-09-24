module Auth exposing (signedBy)

import Julc.Ledger exposing (PubKeyHash, ScriptContext)


signedBy : PubKeyHash -> ScriptContext -> Bool
signedBy key ctx =
    List.any (\signer -> signer == key) ctx.txInfo.signatories
