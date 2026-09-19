package org.elci;

import org.elci.driver.ElciCompiler;
import org.elci.syntax.Diagnostic;
import org.julclang.compiler.backend.FrontendProgram;
import org.julclang.core.*;
import org.julclang.vm.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecordTest {
    private EvalResult eval(String source) {
        var r=new ElciCompiler().compile("module Test exposing (main)\n"+source,"main",FrontendProgram.Purpose.FUNCTION);
        return JulcVm.create().evaluate(r.program(),r.target().ledgerTarget());
    }
    @Test void recordAliasAndUpdate() {
        var r=eval("""
            type alias Price =
                { amount : Int, valid : Bool }

            total : Price -> Int
            total price = if price.valid then price.amount else 0

            main : Int
            main =
                let
                    p = { valid = True, amount = 6 }
                    q = { p | amount = 9 }
                in
                total q
            """);
        assertInstanceOf(EvalResult.Success.class,r);
        assertEquals(new Term.Const(Constant.integer(9)),((EvalResult.Success)r).resultTerm());
    }
    @Test void genericRecordClaimRejected(){assertThrows(Diagnostic.class,()->eval("get : a -> Int\nget x = x.amount\n\nmain : Int\nmain = get { amount = 7 }\n"));}
    @Test void missingFieldRejected(){assertThrows(Diagnostic.class,()->eval("main : Int\nmain = { value = 7 }.missing\n"));}
    @Test void strictRecordField(){assertFalse(eval("main : Int\nmain = { broken = 1 // 0, value = 7 }.value\n") instanceof EvalResult.Success);}
}
