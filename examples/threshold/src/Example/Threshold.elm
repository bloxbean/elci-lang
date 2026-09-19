module Example.Threshold exposing (validate)

import Julc.Ledger exposing (ScriptContext)
import Julc.Math as Math


validate : Int -> Int -> ScriptContext -> Bool
validate minimum offered _ =
    offered >= Math.max minimum 0
