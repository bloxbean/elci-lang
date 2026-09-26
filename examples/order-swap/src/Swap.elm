module Swap exposing (Action(..), Order, spend)

{-| A limit order. A maker locks tokens and asks for other tokens at a fixed price. A taker
fills all or part of it by paying the maker; a partial fill must return everything else in
the order, lovelace and other tokens included, to the script under the same order. A full
fill pays the maker the price and everything the order held besides the offered tokens.
The maker may cancel.

  - The maker's payment carries the order's output reference as its inline datum, so one
    payment cannot fill two orders.
  - The transaction may spend only one input of this script, so one returned output
    cannot stand for two orders either.

-}

import Julc.Data as Data
import Julc.Ledger exposing (Credential(..), PolicyId, PubKeyHash, ScriptContext, TokenName, TxInInfo, TxOutRef)
import Tx
import Value exposing (Value)


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

        rest =
            Value.subtract own.resolved.value (Value.singleton order.offerPolicy order.offerName amount)

        payment =
            Value.singleton order.askPolicy order.askName (amount * order.price)
    in
    (amount > 0)
        && (amount <= offered)
        && onlyScriptInput own ctx
        && (if amount == offered then
                makerPaid order (Value.add payment rest) own.outRef ctx

            else
                makerPaid order payment own.outRef ctx && restReturned order rest ctx
           )


{-| Whether this is the only input from the script.
-}
onlyScriptInput : TxInInfo -> ScriptContext -> Bool
onlyScriptInput own ctx =
    List.length (List.filter (\input -> input.resolved.address.credential == own.resolved.address.credential) ctx.txInfo.inputs) == 1


makerPaid : Order -> Value -> TxOutRef -> ScriptContext -> Bool
makerPaid order owed ref ctx =
    List.any
        (\output -> Value.geq output.value owed && (Tx.inlineDatum output == Just (Data.encode ref)))
        (Tx.outputsTo (PubKeyCredential order.maker) ctx)


{-| Whether one output returns `rest` to the script under the same order: at least its
lovelace, and exactly its tokens, so no one can fill the order with other tokens until it
no longer fits in one output.
-}
restReturned : Order -> Value -> ScriptContext -> Bool
restReturned order rest ctx =
    case Tx.continuingOutputs ctx of
        [ output ] ->
            (Value.lovelaceOf output.value >= Value.lovelaceOf rest)
                && (Value.withoutLovelace output.value == Value.withoutLovelace rest)
                && (Tx.inlineDatum output == Just (Data.encode order))

        _ ->
            False
