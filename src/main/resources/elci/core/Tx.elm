module Tx exposing (continuingOutputs, datumOf, findInput, inlineDatum, mintedOf, outputsTo, ownInput, ownPolicy, ownRef, signedBy, spends, valuePaidTo, valueProduced, valueSpent)

{-| Questions about the transaction a script validates. Each takes the `ScriptContext`
last: `Tx.signedBy owner ctx`.

Owners are identified by payment credential: `outputsTo` and `valuePaidTo` accept an output
whatever its staking part. Datums stay `Data`; decode them with an annotated
`Data.decode`, which fails the script on a malformed datum.

-}

import Dict exposing (Dict)
import Julc.Ledger exposing (Credential, OutputDatum(..), PolicyId, PubKeyHash, ScriptContext, ScriptInfo(..), TokenName, TxInInfo, TxOut, TxOutRef)
import List
import Map
import Value exposing (Value)


{-| Whether the key signed the transaction.
-}
signedBy : PubKeyHash -> ScriptContext -> Bool
signedBy key ctx =
    List.member key ctx.txInfo.signatories


{-| Whether the transaction spends the output.
-}
spends : TxOutRef -> ScriptContext -> Bool
spends ref ctx =
    List.any (\input -> input.outRef == ref) ctx.txInfo.inputs


{-| A spent output, by reference.
-}
findInput : TxOutRef -> ScriptContext -> Maybe TxInInfo
findInput ref ctx =
    firstInput ref ctx.txInfo.inputs


firstInput : TxOutRef -> List TxInInfo -> Maybe TxInInfo
firstInput ref inputs =
    case inputs of
        [] ->
            Nothing

        input :: rest ->
            if input.outRef == ref then
                Just input

            else
                firstInput ref rest


{-| When spending, the reference of the output being spent.
-}
ownRef : ScriptContext -> Maybe TxOutRef
ownRef ctx =
    case ctx.scriptInfo of
        SpendingScript ref _ ->
            Just ref

        _ ->
            Nothing


{-| When spending, the output being spent, with its address, value and datum.
-}
ownInput : ScriptContext -> Maybe TxInInfo
ownInput ctx =
    case ownRef ctx of
        Just ref ->
            findInput ref ctx

        Nothing ->
            Nothing


{-| When minting, the script's own policy id.
-}
ownPolicy : ScriptContext -> Maybe PolicyId
ownPolicy ctx =
    case ctx.scriptInfo of
        MintingScript policy ->
            Just policy

        _ ->
            Nothing


{-| The outputs paid to a payment credential, in order. Whoever builds the transaction
chooses the payee's staking part.

When two inputs of a script can each require a payment to the same party, one output can
satisfy both: double satisfaction. Tag each payment, for example with the spent output's
reference as its inline datum, and look for the tagged output here.

-}
outputsTo : Credential -> ScriptContext -> List TxOut
outputsTo credential ctx =
    List.filter (\output -> output.address.credential == credential) ctx.txInfo.outputs


{-| The total value of the outputs paid to a payment credential. On its own this is open to
double satisfaction when two inputs of a script can each require a payment to the same
party; see `outputsTo`.
-}
valuePaidTo : Credential -> ScriptContext -> Value
valuePaidTo credential ctx =
    Value.sum (List.map (\output -> output.value) (outputsTo credential ctx))


{-| When spending, the outputs paid back to the spent output's address; empty otherwise.
-}
continuingOutputs : ScriptContext -> List TxOut
continuingOutputs ctx =
    case ownInput ctx of
        Just input ->
            List.filter (\output -> output.address == input.resolved.address) ctx.txInfo.outputs

        Nothing ->
            []


{-| The total value of the spent outputs.
-}
valueSpent : ScriptContext -> Value
valueSpent ctx =
    Value.sum (List.map (\input -> input.resolved.value) ctx.txInfo.inputs)


{-| The total value of the outputs.
-}
valueProduced : ScriptContext -> Value
valueProduced ctx =
    Value.sum (List.map (\output -> output.value) ctx.txInfo.outputs)


{-| The tokens a policy mints in this transaction; negative quantities burn.
-}
mintedOf : PolicyId -> ScriptContext -> Dict TokenName Int
mintedOf policy ctx =
    Value.tokens policy ctx.txInfo.mint


{-| An output's inline datum.
-}
inlineDatum : TxOut -> Maybe Data
inlineDatum output =
    case output.datum of
        OutputDatumInline raw ->
            Just raw

        _ ->
            Nothing


{-| An output's datum: inline, or by its hash from the datums the transaction supplies.
-}
datumOf : TxOut -> ScriptContext -> Maybe Data
datumOf output ctx =
    case output.datum of
        OutputDatumInline raw ->
            Just raw

        OutputDatumHash hash ->
            Map.get hash ctx.txInfo.datums

        NoOutputDatum ->
            Nothing
