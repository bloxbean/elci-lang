package org.elci;

import org.elci.driver.ElciCompiler;
import org.elci.syntax.*;
import org.julclang.compiler.backend.FrontendProgram;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticsTest {
    private void rejects(String declarations) {
        assertThrows(RuntimeException.class,()->new ElciCompiler().compile("module Test exposing (main)\n"+declarations,"main",FrontendProgram.Purpose.FUNCTION));
    }
    @Test void incompleteSourcesHaveLocations(){for(String source:new String[]{"module","module Test exposing (", "module Test exposing (main)\nmain : Int\nmain = [", "module Test exposing (main)\nmain : Int\nmain = if True then"}) {var error=assertThrows(Diagnostic.class,()->new Parser(source).parse());assertTrue(error.token().line()>0);}}
    @Test void unsupportedAndInvalidTypes(){rejects("main : Float\nmain = 1\n");rejects("main : List\nmain = []\n");rejects("main : Int Bool\nmain = 1\n");rejects("main : Unknown\nmain = 1\n");}
    @Test void rejectsNonAssociativeComparison(){rejects("main : Bool\nmain = True == False == False\n");}
    @Test void rejectsDuplicatePatternVariables(){rejects("main : Int\nmain =\n    case ( 1, 2 ) of\n        ( x, x ) -> x\n");}
    @Test void rejectsEagerCycles(){rejects("main : Int\nmain =\n    let\n        a = b\n        b = a\n    in\n    a\n");}
    @Test void localTypeMismatch(){rejects("main : Int\nmain =\n    let\n        x = True\n    in\n    x + 1\n");}
    @Test void bytesAndContextAreNotImplicitTypes(){rejects("main : ScriptContext -> ScriptContext\nmain x = x\n");}
    @Test void commentsAndFloatDiagnostic(){new Parser("module Test exposing (main)\n{- outer {- nested -} comment -}\nmain : Int\nmain = 1 -- comment\n").parse();var e=assertThrows(Diagnostic.class,()->new Lexer("1.5").lex());assertTrue(e.getMessage().contains("Float"));}
}
