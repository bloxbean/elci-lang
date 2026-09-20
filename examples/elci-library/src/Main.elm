module Main exposing (main)

import Finance.Pricing as Pricing


main : Int
main =
    Pricing.totalWithFee 5 [ 40, 60 ]
