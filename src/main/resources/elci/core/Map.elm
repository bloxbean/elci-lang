module Map exposing (empty, filter, foldl, foldr, fromList, get, insert, isEmpty, keys, map, member, remove, singleton, size, toList, update, values)

{-| Association maps, in the style of Elm's `Dict`.

A `Map k v` is the ledger's map representation: a list of key/value pairs whose keys
compare by their Data encoding, newest first. Lookups are O(n). A map built here has each
key once: `insert` replaces an existing binding. A map decoded from Data may repeat a key:
`get` returns its first binding, and `remove` and `insert` remove every binding of it.

For keys that are comparable, `Dict` keeps them sorted, looks them up faster and builds
from a list in O(n log n).

A `Map` has no `==`: two maps with the same bindings may list them in different orders. For
the same reason, `Data.encode` of two such maps may differ, so compare their `toList`
after sorting, or use a `Dict`.

-}

import Elci.Assoc as Assoc
import Julc.TypedMap as Typed
import List


empty : Map k v
empty =
    Typed.empty


singleton : k -> v -> Map k v
singleton key value =
    insert key value empty


get : k -> Map k v -> Maybe v
get key bindings =
    Assoc.find key bindings


member : k -> Map k v -> Bool
member key bindings =
    Assoc.has key bindings


{-| Bind a key, replacing any binding; the key comes first in map order.
-}
insert : k -> v -> Map k v -> Map k v
insert key value bindings =
    Assoc.cons key value (Assoc.delete key bindings)


remove : k -> Map k v -> Map k v
remove key bindings =
    Assoc.delete key bindings


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
    Assoc.toList bindings


{-| Insert the pairs in list order, so a repeated key takes its last value and the most
recently inserted key comes first. Scanning from the end keeps the first pair seen for each
key without deleting and rebuilding.
-}
fromList : List ( k, v ) -> Map k v
fromList bindings =
    Assoc.reverse
        (List.foldr
            (\( key, value ) acc ->
                if member key acc then
                    acc

                else
                    Assoc.cons key value acc
            )
            empty
            bindings
        )


foldl : (k -> v -> b -> b) -> b -> Map k v -> b
foldl step initial bindings =
    List.foldl (\( key, value ) acc -> step key value acc) initial (toList bindings)


foldr : (k -> v -> b -> b) -> b -> Map k v -> b
foldr step initial bindings =
    List.foldr (\( key, value ) acc -> step key value acc) initial (toList bindings)


{-| The bindings that pass the test, in map order.
-}
filter : (k -> v -> Bool) -> Map k v -> Map k v
filter keep bindings =
    Assoc.fromDistinct (List.filter (\( key, value ) -> keep key value) (toList bindings))


{-| Transform every value, keeping map order.
-}
map : (k -> a -> b) -> Map k a -> Map k b
map transform bindings =
    Assoc.fromDistinct (List.map (\( key, value ) -> ( key, transform key value )) (toList bindings))
