module List exposing (all, any, append, concatMap, filter, foldl, foldr, length, map, reverse)


map : (a -> b) -> List a -> List b
map transform items =
    case items of
        [] ->
            []

        first :: rest ->
            transform first :: map transform rest


filter : (a -> Bool) -> List a -> List a
filter keep items =
    case items of
        [] ->
            []

        first :: rest ->
            if keep first then
                first :: filter keep rest

            else
                filter keep rest


foldl : (a -> b -> b) -> b -> List a -> b
foldl step initial items =
    case items of
        [] ->
            initial

        first :: rest ->
            foldl step (step first initial) rest


foldr : (a -> b -> b) -> b -> List a -> b
foldr step initial items =
    case items of
        [] ->
            initial

        first :: rest ->
            step first (foldr step initial rest)


all : (a -> Bool) -> List a -> Bool
all predicate items =
    case items of
        [] ->
            True

        first :: rest ->
            predicate first && all predicate rest


any : (a -> Bool) -> List a -> Bool
any predicate items =
    case items of
        [] ->
            False

        first :: rest ->
            predicate first || any predicate rest


length : List a -> Int
length items =
    foldl (\_ count -> count + 1) 0 items


reverse : List a -> List a
reverse items =
    foldl (\item result -> item :: result) [] items


append : List a -> List a -> List a
append left right =
    case left of
        [] ->
            right

        first :: rest ->
            first :: append rest right


concatMap : (a -> List b) -> List a -> List b
concatMap transform items =
    case items of
        [] ->
            []

        first :: rest ->
            append (transform first) (concatMap transform rest)
