package org.elci;

import org.elci.driver.ElciCompiler;
import org.julclang.compiler.backend.FrontendProgram;
import org.julclang.core.*;
import org.julclang.vm.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TupleTest {
    private void integer(long expected,String code) {
        var result=new ElciCompiler().compile("module Test exposing (main)\n"+code,"main",FrontendProgram.Purpose.FUNCTION);
        var evaluated=JulcVm.create().evaluate(result.program(),result.target().ledgerTarget());
        assertInstanceOf(EvalResult.Success.class,evaluated);
        assertEquals(new Term.Const(Constant.integer(expected)),((EvalResult.Success)evaluated).resultTerm());
    }
    @Test void tupleDestructuring(){integer(8,"main : Int\nmain =\n    case ( 3, True, 5 ) of\n        ( a, yes, b ) -> if yes then a + b else 0\n");}
    @Test void listAppend(){integer(10,"main : Int\nmain = List.foldl (\\x total -> x + total) 0 ([ 1, 2 ] ++ [ 3, 4 ])\n");}
    @Test void composition(){integer(14,"inc : Int -> Int\ninc x = x + 1\ndouble : Int -> Int\ndouble x = x * 2\nmain : Int\nmain = (inc >> double) 6\n");}
}
