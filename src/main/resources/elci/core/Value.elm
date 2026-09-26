module Value exposing (Value, add, fromList, geq, insert, isZero, leq, lovelace, lovelaceOf, negate, negative, policies, positive, quantityOf, restrictedTo, scale, singleton, subtract, sum, toList, tokens, withoutLovelace, zero)

{-| Multi-asset values: the ledger's `Value`, on the protocol's native value builtins
(CIP-153).

Every `Value` is canonical: policies and token names ascend by their bytes, and no
quantity is zero. The ledger's values are canonical, these functions keep them so, and
decoding a `Value` from Data rejects any other form. So `==` compares values exactly.

Quantities may be negative, as in a minted value that burns tokens. `lovelaceOf`,
`isZero`, `policies`, `tokens` and `toList` read the value directly; the others convert
it to the native form and back, which costs about a million CPU units for a value of a
few assets.

-}

import Dict exposing (Dict)
import Elci.Value as Native
import Julc.Ledger exposing (PolicyId, TokenName, Value)
import List


{-| The empty value.
-}
zero : Value
zero =
    Native.toLedger Native.empty


{-| An amount of lovelace.
-}
lovelace : Int -> Value
lovelace amount =
    Native.toLedger (Native.insertCoin Native.adaPolicy Native.adaToken amount Native.empty)


{-| A quantity of one token.
-}
singleton : PolicyId -> TokenName -> Int -> Value
singleton policy token quantity =
    Native.toLedger (Native.insertCoin policy token quantity Native.empty)


{-| Set the quantity of one token, replacing what the value held; 0 removes the token.
-}
insert : PolicyId -> TokenName -> Int -> Value -> Value
insert policy token quantity value =
    Native.toLedger (Native.insertCoin policy token quantity (Native.fromLedger value))


{-| A value of the listed quantities; repeated tokens add up.
-}
fromList : List ( PolicyId, TokenName, Int ) -> Value
fromList entries =
    Native.toLedger
        (List.foldl
            (\( policy, token, quantity ) acc ->
                Native.insertCoin policy token (quantity + Native.lookupCoin policy token acc) acc
            )
            Native.empty
            entries
        )


{-| The lovelace in a value; 0 when it has none, as in a minted value.
-}
lovelaceOf : Value -> Int
lovelaceOf value =
    Native.lovelaceOf value


{-| The quantity of one token; 0 when the value has none.
-}
quantityOf : PolicyId -> TokenName -> Value -> Int
quantityOf policy token value =
    Native.lookupCoin policy token (Native.fromLedger value)


{-| Whether the value holds nothing.
-}
isZero : Value -> Bool
isZero value =
    Native.isZero value


{-| The policies in a value, in ascending order; the empty policy is ADA's.
-}
policies : Value -> List PolicyId
policies value =
    Native.policies value


{-| The tokens of one policy and their quantities.
-}
tokens : PolicyId -> Value -> Dict TokenName Int
tokens policy value =
    Native.tokens policy value


{-| Every token and its quantity, in ascending order.
-}
toList : Value -> List ( PolicyId, TokenName, Int )
toList value =
    Native.toList value


{-| The sum of two values.
-}
add : Value -> Value -> Value
add left right =
    Native.toLedger (Native.union (Native.fromLedger left) (Native.fromLedger right))


{-| The first value minus the second.
-}
subtract : Value -> Value -> Value
subtract left right =
    Native.toLedger (Native.union (Native.fromLedger left) (Native.scale -1 (Native.fromLedger right)))


{-| The value with every quantity negated.
-}
negate : Value -> Value
negate value =
    Native.toLedger (Native.scale -1 (Native.fromLedger value))


{-| The value with every quantity multiplied.
-}
scale : Int -> Value -> Value
scale factor value =
    Native.toLedger (Native.scale factor (Native.fromLedger value))


{-| The sum of the values; each is converted once.
-}
sum : List Value -> Value
sum values =
    Native.toLedger (List.foldl (\value acc -> Native.union (Native.fromLedger value) acc) Native.empty values)


{-| `geq a b`: whether `a` holds at least as much of every token as `b`. Negative quantities
count as less than none.
-}
geq : Value -> Value -> Bool
geq left right =
    Native.nonNegative (subtract left right)


{-| `leq a b`: whether `a` holds at most as much of every token as `b`.
-}
leq : Value -> Value -> Bool
leq left right =
    geq right left


{-| The value without its lovelace.
-}
withoutLovelace : Value -> Value
withoutLovelace value =
    Native.filter (\policy token _ -> policy /= Native.adaPolicy || token /= Native.adaToken) value


{-| Only the tokens of the given policies.
-}
restrictedTo : List PolicyId -> Value -> Value
restrictedTo kept value =
    Native.filter (\policy _ _ -> List.member policy kept) value


{-| Only the positive quantities: in a minted value, what is minted.
-}
positive : Value -> Value
positive value =
    Native.filter (\_ _ quantity -> quantity > 0) value


{-| Only the negative quantities: in a minted value, what is burned.
-}
negative : Value -> Value
negative value =
    Native.filter (\_ _ quantity -> quantity < 0) value
