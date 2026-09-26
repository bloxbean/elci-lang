module Swap exposing (Action(..), Order, spend)

{-| A limit order. A maker locks tokens and asks for other tokens at a fixed price. A taker
fills all or part of it by paying the maker; a partial fill must return the rest to the
script under the same order. The maker may cancel.

  - The maker's payment carries the order's output reference as its inline datum, so one
    payment cannot fill two orders.
  - The transaction may spend only one output of this script, so one returned output
    cannot stand for two orders either.

-}

import Julc.Data as Data
import Julc.Ledger exposing (Credential(..), PolicyId, PubKeyHash, ScriptContext, TokenName, TxInInfo, TxOutRef)
import Tx
import Value


{-| The maker offers one token and asks `price` of another for each unit.
-}
type alias Order =
    { maker : PubKeyHash
    , offerPolicy : PolicyId
    , offerName : TokenName
    , askPolicy : PolicyId
    , askName : TokenName
    , price : Int
    }


type Action
    = Take Int
    | Cancel


spend : Order -> Action -> ScriptContext -> Bool
spend order action ctx =
    case action of
        Cancel ->
            Tx.signedBy order.maker ctx

        Take amount ->
            case Tx.ownInput ctx of
                Just own ->
                    fill order amount own ctx

                Nothing ->
                    False


fill : Order -> Int -> TxInInfo -> ScriptContext -> Bool
fill order amount own ctx =
    let
        offered =
            Value.quantityOf order.offerPolicy order.offerName own.resolved.value

        left =
            offered - amount
    in
    (amount > 0)
        && (amount <= offered)
        && onlyScriptInput own ctx
        && makerPaid order (amount * order.price) own.outRef ctx
        && (left == 0 || restReturned order left ctx)


onlyScriptInput : TxInInfo -> ScriptContext -> Bool
onlyScriptInput own ctx =
    List.length (List.filter (\input -> input.resolved.address == own.resolved.address) ctx.txInfo.inputs) == 1


makerPaid : Order -> Int -> TxOutRef -> ScriptContext -> Bool
makerPaid order owed ref ctx =
    List.any
        (\output ->
            (Value.quantityOf order.askPolicy order.askName output.value >= owed)
                && (Tx.inlineDatum output == Just (Data.encode ref))
        )
        (Tx.outputsTo (PubKeyCredential order.maker) ctx)


restReturned : Order -> Int -> ScriptContext -> Bool
restReturned order left ctx =
    case Tx.continuingOutputs ctx of
        [ output ] ->
            (Value.quantityOf order.offerPolicy order.offerName output.value >= left)
                && (Tx.inlineDatum output == Just (Data.encode order))

        _ ->
            False
