module Treasury exposing (Action(..), Payout, spend, withdraw)

import Auth exposing (signedBy)
import Julc.Ledger exposing (PubKeyHash, ScriptContext)


type alias Payout =
    { beneficiary : PubKeyHash
    , amount : Int
    }


type Action
    = Release
    | Cancel


spend : PubKeyHash -> Payout -> Action -> ScriptContext -> Bool
spend admin payout action ctx =
    case action of
        Release ->
            payout.amount > 0 && signedBy payout.beneficiary ctx

        Cancel ->
            signedBy admin ctx


withdraw : PubKeyHash -> () -> ScriptContext -> Bool
withdraw admin _ ctx =
    signedBy admin ctx
