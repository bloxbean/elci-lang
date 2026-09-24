module Badge exposing (mint)

import Auth
import Julc.Ledger exposing (PubKeyHash, ScriptContext)


mint : PubKeyHash -> Int -> ScriptContext -> Bool
mint admin quantity ctx =
    quantity > 0 && Auth.signedBy admin ctx
