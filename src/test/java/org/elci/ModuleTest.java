package org.elci;

import org.elci.driver.ElciCompiler;
import org.elci.syntax.Diagnostic;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.backend.FrontendProgram;
import org.julclang.core.*;
import org.julclang.vm.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ModuleTest {
    private ElciCompiler.Result compile(Map<String,String> sources) {
        return new ElciCompiler().compileProject(sources,"Main","main",FrontendProgram.Purpose.FUNCTION,Map.of("Julc.Math","org.julclang.stdlib.lib.MathLib"),new CompilerOptions());
    }
    @Test void importsQualifiedAndExposed() {
        var result=compile(Map.of("Helpers","module Helpers exposing (inc)\ninc : Int -> Int\ninc x = x + 1\n","Main","module Main exposing (main)\nimport Helpers as H exposing (inc)\nmain : Int\nmain = inc (H.inc 4)\n"));
        assertEquals(new Term.Const(Constant.integer(6)),((EvalResult.Success)JulcVm.create().evaluate(result.program(),result.target().ledgerTarget())).resultTerm());
    }
    @Test void sourceModuleCallsJavaLibrary() {
        var result=compile(Map.of("Helpers","module Helpers exposing (clamp)\nimport Julc.Math as Math\nclamp : Int -> Int\nclamp x = Math.max 0 x\n","Main","module Main exposing (main)\nimport Helpers exposing (clamp)\nmain : Int\nmain = clamp (-4)\n"));
        assertInstanceOf(EvalResult.Success.class,JulcVm.create().evaluate(result.program(),result.target().ledgerTarget()));
    }
    @Test void cyclesRejected(){assertThrows(IllegalArgumentException.class,()->compile(Map.of("Main","module Main exposing (main)\nimport Other\nmain : Int\nmain = 1\n","Other","module Other exposing (value)\nimport Main\nvalue : Int\nvalue = 1\n")));}
    @Test void privateNamesRejected(){assertThrows(Diagnostic.class,()->compile(Map.of("Main","module Main exposing (main)\nimport Helpers exposing (hidden)\nmain : Int\nmain = hidden\n","Helpers","module Helpers exposing (public)\npublic : Int\npublic = 1\nhidden : Int\nhidden = 2\n")));}
}
