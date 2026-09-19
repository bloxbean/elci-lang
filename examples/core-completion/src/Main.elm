module Main exposing (main)


type alias Box a =
    { value : a, label : String }


type Choice a
    = None
    | Some a


measure : Choice (List Int) -> Int
measure choice =
    case choice of
        Some ([ first, second ] as items) ->
            first + second + List.length items

        Some [] ->
            0

        Some (_ :: _) ->
            -1

        None ->
            -2


unbox : ( Box Int, Int ) -> Int
unbox ( { value }, extra ) =
    value + extra


same : a -> a -> Bool
same left right =
    left == right


main : Int
main =
    let
        boxed =
            Box 4 "four"

        { value } =
            boxed

        valuesMatch =
            same [ value, 2 ] [ 4, 2 ]
    in
    if valuesMatch then
        measure (Some [ value, 2 ]) + unbox ( boxed, 3 )

    else
        0
