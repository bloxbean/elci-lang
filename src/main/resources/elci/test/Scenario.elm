module Scenario exposing
    ( Destination(..)
    , Input
    , Output
    , Policy(..)
    , Purpose(..)
    , Scenario(..)
    , Spec
    , Token
    , certify
    , expectFailure
    , fee
    , input
    , key
    , mint
    , minting
    , outRef
    , output
    , parameters
    , propose
    , redeemer
    , referenceInput
    , signedBy
    , spend
    , spendWithoutDatum
    , toAddress
    , toKey
    , toScript
    , toStakedScript
    , unit
    , validBefore
    , validFrom
    , vote
    , withDatum
    , withDatumHash
    , withToken
    , withdraw
    , withinBudget
    , worth
    )

{-| Transactions against this project's validators, for `elci test`.

A scenario is plain data. The test runner compiles the named validator as `elci build`
does, applies the scenario's parameters, builds the complete ledger context with
julc-testkit and runs the deployed script. The script's own address, minting policy
and withdrawal credential are filled in from its hash.

-}

import Bytes
import Julc.Data as Data
import Julc.Ledger as Ledger exposing (Address, OutputDatum(..), ProposalProcedure, PubKeyHash, StakingCredential, TxCert, TxOutRef, Voter)


{-| The ledger purpose, with what the ledger supplies for it.
-}
type Purpose
    = Spend TxOutRef (Maybe Data)
    | Mint
    | Withdraw
    | Certify Int TxCert
    | Vote Voter
    | Propose Int ProposalProcedure


{-| A token's policy: the validator's own script hash, or a given policy id.
-}
type Policy
    = Own
    | Policy Bytes


type alias Token =
    { policy : Policy
    , name : Bytes
    , quantity : Int
    }


{-| Where an output is paid: the validator's own script address, or an address.
-}
type Destination
    = ToScript
    | ToAddress Address
    | ToStakedScript StakingCredential


{-| An output. `hashedDatum` holds a datum by hash: the runner puts the datum's hash in
the output and the datum in the transaction's witnesses.
-}
type alias Output =
    { destination : Destination
    , lovelace : Int
    , tokens : List Token
    , datum : OutputDatum
    , hashedDatum : Maybe Data
    }


type alias Input =
    { ref : TxOutRef
    , output : Output
    }


type alias Spec =
    { validator : String
    , purpose : Purpose
    , redeemer : Data
    , parameters : List Data
    , signers : List PubKeyHash
    , inputs : List Input
    , referenceInputs : List Input
    , outputs : List Output
    , minted : List Token
    , fee : Int
    , validFrom : Maybe Int
    , validBefore : Maybe Int
    , ownLovelace : Int
    , expectSuccess : Bool
    , budget : Maybe ( Int, Int )
    }


type Scenario
    = Scenario Spec


{-| Encodes as `Constr 0 []`, the Data of `()`.
-}
type Default
    = Default


{-| The Data of `()`: the default redeemer, and the datum of a `()`-datum spend.
-}
unit : Data
unit =
    Data.encode Default


new : String -> Purpose -> Scenario
new validator purpose =
    Scenario
        { validator = validator
        , purpose = purpose
        , redeemer = unit
        , parameters = []
        , signers = []
        , inputs = []
        , referenceInputs = []
        , outputs = []
        , minted = []
        , fee = 200000
        , validFrom = Nothing
        , validBefore = Nothing
        , ownLovelace = 2000000
        , expectSuccess = True
        , budget = Nothing
        }


update : (Spec -> Spec) -> Scenario -> Scenario
update change scenario =
    case scenario of
        Scenario spec ->
            Scenario (change spec)



-- PURPOSES


{-| Spend `ref`, held at the validator's address with an inline `datum`.
-}
spend : String -> TxOutRef -> Data -> Scenario
spend validator ref datum =
    new validator (Spend ref (Just datum))


{-| Spend `ref`, held at the validator's address without a datum.
-}
spendWithoutDatum : String -> TxOutRef -> Scenario
spendWithoutDatum validator ref =
    new validator (Spend ref Nothing)


{-| Mint or burn under the validator's own policy; add tokens with `minting`.
-}
mint : String -> Scenario
mint validator =
    new validator Mint


{-| Withdraw the rewards of the validator's own staking credential.
-}
withdraw : String -> Scenario
withdraw validator =
    new validator Withdraw


certify : String -> Int -> TxCert -> Scenario
certify validator index certificate =
    new validator (Certify index certificate)


vote : String -> Voter -> Scenario
vote validator voter =
    new validator (Vote voter)


propose : String -> Int -> ProposalProcedure -> Scenario
propose validator index procedure =
    new validator (Propose index procedure)



-- TRANSACTION


redeemer : Data -> Scenario -> Scenario
redeemer value =
    update (\spec -> { spec | redeemer = value })


{-| Deployment parameters, in the validator's declared order.
-}
parameters : List Data -> Scenario -> Scenario
parameters values =
    update (\spec -> { spec | parameters = values })


signedBy : PubKeyHash -> Scenario -> Scenario
signedBy signer =
    update (\spec -> { spec | signers = spec.signers ++ [ signer ] })


input : TxOutRef -> Output -> Scenario -> Scenario
input ref paid =
    update (\spec -> { spec | inputs = spec.inputs ++ [ { ref = ref, output = paid } ] })


referenceInput : TxOutRef -> Output -> Scenario -> Scenario
referenceInput ref paid =
    update (\spec -> { spec | referenceInputs = spec.referenceInputs ++ [ { ref = ref, output = paid } ] })


output : Output -> Scenario -> Scenario
output paid =
    update (\spec -> { spec | outputs = spec.outputs ++ [ paid ] })


{-| Mint (positive) or burn (negative) `quantity` of `name` under the own policy.
-}
minting : Bytes -> Int -> Scenario -> Scenario
minting name quantity =
    update (\spec -> { spec | minted = spec.minted ++ [ { policy = Own, name = name, quantity = quantity } ] })


fee : Int -> Scenario -> Scenario
fee lovelace =
    update (\spec -> { spec | fee = lovelace })


{-| The transaction is valid from this time (inclusive).
-}
validFrom : Int -> Scenario -> Scenario
validFrom time =
    update (\spec -> { spec | validFrom = Just time })


{-| The transaction is valid before this time (exclusive).
-}
validBefore : Int -> Scenario -> Scenario
validBefore time =
    update (\spec -> { spec | validBefore = Just time })


{-| Lovelace held by the spent output (default 2 ADA).
-}
worth : Int -> Scenario -> Scenario
worth lovelace =
    update (\spec -> { spec | ownLovelace = lovelace })



-- EXPECTATIONS


{-| The validator must reject this transaction.
-}
expectFailure : Scenario -> Scenario
expectFailure =
    update (\spec -> { spec | expectSuccess = False })


{-| The execution must cost at most this CPU and memory.
-}
withinBudget : Int -> Int -> Scenario -> Scenario
withinBudget cpu memory =
    update (\spec -> { spec | budget = Just ( cpu, memory ) })



-- OUTPUTS AND TEST DATA


toScript : Int -> Output
toScript lovelace =
    { destination = ToScript, lovelace = lovelace, tokens = [], datum = NoOutputDatum, hashedDatum = Nothing }


{-| An output to the validator's own payment credential with a staking part: a different
address from `toScript`'s.
-}
toStakedScript : StakingCredential -> Int -> Output
toStakedScript stake lovelace =
    { destination = ToStakedScript stake, lovelace = lovelace, tokens = [], datum = NoOutputDatum, hashedDatum = Nothing }


toAddress : Address -> Int -> Output
toAddress address lovelace =
    { destination = ToAddress address, lovelace = lovelace, tokens = [], datum = NoOutputDatum, hashedDatum = Nothing }


{-| An output paid to a key's address, without a staking part.
-}
toKey : PubKeyHash -> Int -> Output
toKey owner lovelace =
    toAddress (Ledger.Address (Ledger.PubKeyCredential owner) (Data.toOptional Nothing)) lovelace


withDatum : Data -> Output -> Output
withDatum datum paid =
    { paid | datum = OutputDatumInline datum, hashedDatum = Nothing }


{-| Hold a datum by hash, as older contracts do: the output carries the datum's hash, and the
transaction carries the datum among its witnesses.
-}
withDatumHash : Data -> Output -> Output
withDatumHash datum paid =
    { paid | datum = NoOutputDatum, hashedDatum = Just datum }


withToken : Policy -> Bytes -> Int -> Output -> Output
withToken policy name quantity paid =
    { paid | tokens = paid.tokens ++ [ { policy = policy, name = name, quantity = quantity } ] }


{-| A deterministic 28-byte test key hash derived from a name.
-}
key : String -> PubKeyHash
key name =
    Ledger.PubKeyHash (Bytes.blake2b224 (Bytes.fromString name))


{-| A deterministic test output reference derived from a name and index.
-}
outRef : String -> Int -> TxOutRef
outRef name index =
    Ledger.TxOutRef (Ledger.TxId (Bytes.blake2b256 (Bytes.fromString name))) index
