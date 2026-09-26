module Marketplace exposing (Action(..), Listing, Market, fee, spend)

{-| An NFT marketplace. A seller locks an NFT with a listing; anyone may buy it by paying
the seller the price, less the marketplace's fee, and paying the operator the fee. The
seller may cancel at any time.

Each payment carries the listing's output reference as its inline datum, so one payment
cannot settle two listings in the same transaction.

-}

import Julc.Data as Data
import Julc.Ledger exposing (Credential(..), PubKeyHash, ScriptContext, TxOutRef)
import Tx
import Value


{-| The marketplace, fixed when the script is deployed.
-}
type alias Market =
    { operator : PubKeyHash
    , feeBasisPoints : Int
    }


{-| A listing: who sells, and for how much lovelace.
-}
type alias Listing =
    { seller : PubKeyHash
    , price : Int
    }


type Action
    = Buy
    | Cancel


spend : Market -> Listing -> Action -> ScriptContext -> Bool
spend market listing action ctx =
    case action of
        Buy ->
            case Tx.ownRef ctx of
                Just ref ->
                    let
                        cut =
                            fee market listing.price
                    in
                    paid listing.seller (listing.price - cut) ref ctx
                        && (cut == 0 || paid market.operator cut ref ctx)

                Nothing ->
                    False

        Cancel ->
            Tx.signedBy listing.seller ctx


{-| The marketplace's fee, rounded down.
-}
fee : Market -> Int -> Int
fee market price =
    price * market.feeBasisPoints // 10000


{-| Whether an output pays the key at least the amount, tagged with the listing's reference.
-}
paid : PubKeyHash -> Int -> TxOutRef -> ScriptContext -> Bool
paid key amount ref ctx =
    List.any
        (\output ->
            (Value.lovelaceOf output.value >= amount)
                && (Tx.inlineDatum output == Just (Data.encode ref))
        )
        (Tx.outputsTo (PubKeyCredential key) ctx)
