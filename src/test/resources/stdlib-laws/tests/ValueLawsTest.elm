module ValueLawsTest exposing
    ( valueAddIsACommutativeMonoid
    , valueEveryValueIsCanonical
    , valueGeqIsAPartialOrder
    , valueListAndDataRoundTrip
    , valueQueriesAgree
    , valueScaleAndSum
    , valueSplitsIntoPositiveAndNegative
    , valueSubtractAndNegate
    )

import Bytes
import Dict
import Julc.Data as Data
import Julc.Ledger exposing (PolicyId(..), TokenName(..))
import Laws exposing (strictlyAscending)
import Property exposing (Property)
import Value exposing (Value)


valueAddIsACommutativeMonoid : Value -> Value -> Value -> Property
valueAddIsACommutativeMonoid a b c =
    Property.holds
        ((Value.add a b == Value.add b a)
            && (Value.add (Value.add a b) c == Value.add a (Value.add b c))
            && (Value.add a Value.zero == a)
        )


valueSubtractAndNegate : Value -> Value -> Property
valueSubtractAndNegate a b =
    Property.holds
        (Value.isZero (Value.subtract a a)
            && (Value.negate (Value.negate a) == a)
            && (Value.subtract a b == Value.add a (Value.negate b))
            && (Value.add (Value.subtract a b) b == a)
        )


valueScaleAndSum : Value -> Value -> Property
valueScaleAndSum a b =
    Property.holds
        ((Value.scale 2 a == Value.add a a)
            && Value.isZero (Value.scale 0 a)
            && (Value.scale -1 a == Value.negate a)
            && (Value.sum [ a, b ] == Value.add a b)
            && (Value.sum [] == Value.zero)
        )


valueGeqIsAPartialOrder : Value -> Value -> Property
valueGeqIsAPartialOrder a b =
    let
        more =
            Value.add a (Value.positive b)
    in
    Property.holds
        (Value.geq a a
            && Value.geq more a
            && Value.leq a more
            && (if Value.geq a b && Value.geq b a then
                    a == b

                else
                    True
               )
            && (Value.geq a b == Value.geq (Value.subtract a b) Value.zero)
        )


valueQueriesAgree : Value -> Property
valueQueriesAgree a =
    let
        ada =
            PolicyId Bytes.empty

        lovelaceName =
            TokenName Bytes.empty
    in
    Property.holds
        ((Value.lovelaceOf a == Value.quantityOf ada lovelaceName a)
            && List.all
                (\( policy, token, quantity ) ->
                    (Value.quantityOf policy token a == quantity)
                        && (Dict.get token (Value.tokens policy a) == Just quantity)
                        && List.member policy (Value.policies a)
                )
                (Value.toList a)
            && (Value.isZero a == List.isEmpty (Value.toList a))
            && (Value.lovelaceOf (Value.withoutLovelace a) == 0)
        )


valueEveryValueIsCanonical : Value -> Value -> Property
valueEveryValueIsCanonical a b =
    let
        canonical value =
            strictlyAscending (List.map (\( p, t, _ ) -> ( p, t )) (Value.toList value))
                && List.all (\( _, _, q ) -> q /= 0) (Value.toList value)
    in
    Property.holds
        (canonical a
            && canonical (Value.add a b)
            && canonical (Value.subtract a b)
            && canonical (Value.positive a)
            && canonical (Value.fromList (List.reverse (Value.toList a)))
        )


valueListAndDataRoundTrip : Value -> Property
valueListAndDataRoundTrip a =
    let
        decoded : Value
        decoded =
            Data.decode (Data.encode a)
    in
    Property.holds
        ((Value.fromList (Value.toList a) == a)
            && (Value.fromList (Value.toList a ++ Value.toList a) == Value.scale 2 a)
            && (decoded == a)
        )


valueSplitsIntoPositiveAndNegative : Value -> Property
valueSplitsIntoPositiveAndNegative a =
    Property.holds
        ((Value.add (Value.positive a) (Value.negative a) == a)
            && Value.geq (Value.positive a) Value.zero
            && Value.leq (Value.negative a) Value.zero
        )
