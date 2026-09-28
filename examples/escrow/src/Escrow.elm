module Escrow exposing (Action(..), Deal, spend)

{-| A buyer locks a payment for a seller.

  - **Release**: the buyer approves, and the seller is paid the price.
  - **Refund**: after the deadline, the buyer takes the payment back.

The seller's payment must carry this escrow's output reference as its inline datum. Two
escrows for the same seller spent in one transaction would otherwise be satisfied by a
single payment (the "double satisfaction" attack).

-}

import Interval
import Julc.Data as Data
import Julc.Ledger exposing (Credential(..), PubKeyHash, ScriptContext)
import Tx
import Value


type alias Deal =
    { buyer : PubKeyHash
    , seller : PubKeyHash
    , price : Int
    , deadline : Int
    }


type Action
    = Release
    | Refund


spend : Deal -> Action -> ScriptContext -> Bool
spend deal action ctx =
    case action of
        Release ->
            Tx.signedBy deal.buyer ctx && sellerPaid deal ctx

        Refund ->
            Tx.signedBy deal.buyer ctx && Interval.isEntirelyAfter deal.deadline ctx.txInfo.validRange


{-| An output to the seller of at least the price, tagged with this escrow's reference.
-}
sellerPaid : Deal -> ScriptContext -> Bool
sellerPaid deal ctx =
    case Tx.ownRef ctx of
        Just ref ->
            List.any
                (\output ->
                    (Value.lovelaceOf output.value >= deal.price)
                        && (Tx.inlineDatum output == Just (Data.encode ref))
                )
                (Tx.outputsTo (PubKeyCredential deal.seller) ctx)

        Nothing ->
            False
