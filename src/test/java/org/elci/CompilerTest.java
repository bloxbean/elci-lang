package org.elci;

import org.elci.driver.ElciCompiler;
import org.elci.syntax.Diagnostic;
import org.julclang.compiler.backend.FrontendProgram;
import org.julclang.core.*;
import org.julclang.vm.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompilerTest {
    private EvalResult eval(String declarations) {
        var source="module Test exposing (main)\n\n"+declarations;
        var result=new ElciCompiler().compile(source,"main",FrontendProgram.Purpose.FUNCTION);
        return JulcVm.create().evaluate(result.program(),result.target().ledgerTarget());
    }
    private void integer(long expected,String expression) {
        var result=eval("main : Int\nmain = "+expression+"\n");
        assertInstanceOf(EvalResult.Success.class,result,result.toString());
        assertEquals(new Term.Const(Constant.integer(expected)),((EvalResult.Success)result).resultTerm());
    }
    @Test void arithmetic(){integer(14,"2 + 3 * 4");integer(-2,"-7 // 3");integer(-2,"7 // -3");integer(2,"-7 // -3");integer(2,"7 // 3");}
    @Test void branches(){integer(7,"if True then 7 else 1 // 0");}
    @Test void recursion(){var r=eval("factorial : Int -> Int\nfactorial n = if n == 0 then 1 else n * factorial (n - 1)\n\nmain : Int\nmain = factorial 6\n");assertInstanceOf(EvalResult.Success.class,r);assertEquals(new Term.Const(Constant.integer(720)),((EvalResult.Success)r).resultTerm());}
    @Test void generic(){var r=eval("identity : a -> a\nidentity x = x\n\nmain : Int\nmain = if identity True then identity 7 else 0\n");assertInstanceOf(EvalResult.Success.class,r);assertEquals(new Term.Const(Constant.integer(7)),((EvalResult.Success)r).resultTerm());}
    @Test void rejectsFalseGenericSignature(){assertThrows(Diagnostic.class,()->eval("bad : a -> a\nbad x = x + 1\n\nmain : Int\nmain = bad 1\n"));}
    @Test void strictUnusedBinding(){assertFalse(eval("main : Int\nmain =\n    let\n        bad = 1 // 0\n    in\n    7\n") instanceof EvalResult.Success);}
    @Test void listRecursion(){var r=eval("sum : List Int -> Int\nsum xs =\n    case xs of\n        [] -> 0\n        x :: rest -> x + sum rest\n\nmain : Int\nmain = sum [ 1, 2, 3 ]\n");assertInstanceOf(EvalResult.Success.class,r);assertEquals(new Term.Const(Constant.integer(6)),((EvalResult.Success)r).resultTerm());}
    @Test void javaLibrary(){var source="module Test exposing (main)\nimport Julc.Math as Math\nmain : Int\nmain = Math.max 5 9\n";var r=new ElciCompiler().compile(source,"main",FrontendProgram.Purpose.FUNCTION);assertEquals(new Term.Const(Constant.integer(9)),((EvalResult.Success)JulcVm.create().evaluate(r.program(),r.target().ledgerTarget())).resultTerm());}
    @Test void mutualRecursion(){var r=eval("even : Int -> Bool\neven n = if n == 0 then True else odd (n - 1)\n\nodd : Int -> Bool\nodd n = if n == 0 then False else even (n - 1)\n\nmain : Int\nmain = if even 10 then 1 else 0\n");assertInstanceOf(EvalResult.Success.class,r);assertEquals(new Term.Const(Constant.integer(1)),((EvalResult.Success)r).resultTerm());}
    @Test void strictPartialCapture(){assertFalse(eval("keep : Int -> Int -> Int\nkeep x y = y\n\nmain : Int\nmain =\n    let\n        captured = keep (1 // 0)\n    in\n    7\n") instanceof EvalResult.Success);}
    @Test void higherOrder(){var r=eval("apply : (a -> b) -> a -> b\napply f x = f x\n\nmain : Int\nmain = apply (\\x -> x + 1) 8\n");assertInstanceOf(EvalResult.Success.class,r);assertEquals(new Term.Const(Constant.integer(9)),((EvalResult.Success)r).resultTerm());}
    @Test void rejectsNonExhaustive(){assertThrows(Diagnostic.class,()->eval("main : Int\nmain = case True of\n    True -> 1\n"));}
    @Test void rejectsInfiniteType(){assertThrows(Diagnostic.class,()->eval("omega : a -> a\nomega x = x x\n\nmain : Int\nmain = 0\n"));}
    @Test void forwardLocalBinding(){integer(7,"let\n    a = b + 1\n    b = 6\nin\na");}
}
