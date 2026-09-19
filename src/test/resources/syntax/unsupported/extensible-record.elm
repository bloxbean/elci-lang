module Corpus.ExtensibleRecord exposing (value)


value : { record | value : Int } -> Int
value record =
    record.value
