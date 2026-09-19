package org.elci;

import org.elci.driver.ElciCompiler;
import org.elci.syntax.Diagnostic;
import org.julclang.compiler.backend.FrontendProgram;
import org.julclang.core.*;
import org.julclang.vm.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UnionTest {
    private EvalResult eval(String source) {
        var r=new ElciCompiler().compile("module Test exposing (main)\n"+source,"main",FrontendProgram.Purpose.FUNCTION);
        return JulcVm.create().evaluate(r.program(),r.target().ledgerTarget());
    }
    @Test void genericMaybe() {
        var r=eval("""
            getOr : a -> Maybe a -> a
            getOr fallback value =
                case value of
                    Nothing -> fallback
                    Just x -> x

            main : Int
            main = getOr 3 (Just 9)
            """);
        assertInstanceOf(EvalResult.Success.class,r);
        assertEquals(new Term.Const(Constant.integer(9)),((EvalResult.Success)r).resultTerm());
    }
    @Test void recursiveTree() {
        var r=eval("""
            type Tree
                = Leaf Int
                | Branch Tree Tree

            sum : Tree -> Int
            sum tree =
                case tree of
                    Leaf n -> n
                    Branch left right -> sum left + sum right

            main : Int
            main = sum (Branch (Leaf 4) (Branch (Leaf 2) (Leaf 8)))
            """);
        assertInstanceOf(EvalResult.Success.class,r);
        assertEquals(new Term.Const(Constant.integer(14)),((EvalResult.Success)r).resultTerm());
    }
    @Test void nonExhaustiveUnionFails(){assertThrows(Diagnostic.class,()->eval("type Action = Buy Int | Cancel\nmain : Int\nmain =\n    case Buy 3 of\n        Buy n -> n\n"));}
    @Test void wrongConstructorArityFails(){assertThrows(Diagnostic.class,()->eval("main : Int\nmain =\n    case Just 3 of\n        Just -> 1\n        Nothing -> 0\n"));}
}
