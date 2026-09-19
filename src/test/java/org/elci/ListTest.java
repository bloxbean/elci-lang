package org.elci;

import org.elci.driver.ElciCompiler;
import org.julclang.compiler.backend.FrontendProgram;
import org.julclang.core.*;
import org.julclang.vm.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ListTest {
    @Test void genericHigherOrderTraversal() {
        var source="""
            module Test exposing (main)

            main : Int
            main =
                [ 1, 2, 3, 4 ]
                    |> List.filter (\\x -> x > 1)
                    |> List.map (\\x -> x * 2)
                    |> List.foldl (\\x total -> x + total) 0
            """;
        var r=new ElciCompiler().compile(source,"main",FrontendProgram.Purpose.FUNCTION);
        var result=JulcVm.create().evaluate(r.program(),r.target().ledgerTarget());
        assertInstanceOf(EvalResult.Success.class,result);
        assertEquals(new Term.Const(Constant.integer(18)),((EvalResult.Success)result).resultTerm());
    }
    @Test void recordLambdaContext() {
        String source="module Test exposing (main)\nmain : Int\nmain = List.foldl (\\item total -> item.amount + total) 0 [ { amount = 3 }, { amount = 4 } ]\n";
        var r=new ElciCompiler().compile(source,"main",FrontendProgram.Purpose.FUNCTION);
        assertEquals(new Term.Const(Constant.integer(7)),((EvalResult.Success)JulcVm.create().evaluate(r.program(),r.target().ledgerTarget())).resultTerm());
    }
}
