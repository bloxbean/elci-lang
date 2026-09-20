module Finance.Pricing exposing (totalWithFee)


totalWithFee : Int -> List Int -> Int
totalWithFee fee prices =
    subtotal prices + fee


subtotal : List Int -> Int
subtotal prices =
    List.foldl (\price total -> price + total) 0 prices
