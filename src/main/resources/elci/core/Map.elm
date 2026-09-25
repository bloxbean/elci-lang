module Map exposing (empty, filter, foldl, fromList, get, insert, isEmpty, keys, map, member, remove, singleton, size, toList, update, values)

{-| Association maps, in the style of Elm's `Dict`.

A `Map k v` is the ledger's map representation: a list of key/value pairs compared by
their Data encoding. Lookups are O(n). Each key appears once: `insert` replaces an
existing binding. The operations are Julc's typed map library, specialized for the key
and value types.

-}

import Julc.Data as Data
import Julc.TypedMap as Typed


empty : Map k v
empty =
    Typed.empty


singleton : k -> v -> Map k v
singleton key value =
    insert key value empty


get : k -> Map k v -> Maybe v
get key bindings =
    Data.fromOptional (Typed.lookup bindings key)


member : k -> Map k v -> Bool
member key bindings =
    Typed.member bindings key


insert : k -> v -> Map k v -> Map k v
insert key value bindings =
    Typed.insert bindings key value


remove : k -> Map k v -> Map k v
remove key bindings =
    Typed.remove bindings key


{-| Change the binding of a key: `Nothing` removes it, `Just v` sets it.
-}
update : k -> (Maybe v -> Maybe v) -> Map k v -> Map k v
update key change bindings =
    case change (get key bindings) of
        Just value ->
            insert key value bindings

        Nothing ->
            remove key bindings


size : Map k v -> Int
size bindings =
    Typed.size bindings


isEmpty : Map k v -> Bool
isEmpty bindings =
    Typed.isEmpty bindings


{-| The keys, in map order.
-}
keys : Map k v -> List k
keys bindings =
    Typed.keys bindings


{-| The values, in map order.
-}
values : Map k v -> List v
values bindings =
    Typed.values bindings


{-| The bindings, in map order.
-}
toList : Map k v -> List ( k, v )
toList bindings =
    zip (keys bindings) (values bindings)


fromList : List ( k, v ) -> Map k v
fromList bindings =
    List.foldl (\( key, value ) acc -> insert key value acc) empty bindings


foldl : (k -> v -> b -> b) -> b -> Map k v -> b
foldl step initial bindings =
    List.foldl (\( key, value ) acc -> step key value acc) initial (toList bindings)


filter : (k -> v -> Bool) -> Map k v -> Map k v
filter keep bindings =
    fromList (List.filter (\( key, value ) -> keep key value) (toList bindings))


map : (k -> a -> b) -> Map k a -> Map k b
map transform bindings =
    fromList (List.map (\( key, value ) -> ( key, transform key value )) (toList bindings))


zip : List a -> List b -> List ( a, b )
zip left right =
    case ( left, right ) of
        ( a :: restLeft, b :: restRight ) ->
            ( a, b ) :: zip restLeft restRight

        _ ->
            []
