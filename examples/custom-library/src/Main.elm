module Main exposing (main)

import Custom.Pricing as Pricing exposing (Quote)


amount : Quote -> Int
amount quote =
    quote.amount


main : Int
main =
    amount (Pricing.quote 42)
