package org.elci;

import static org.junit.jupiter.api.Assertions.*;

import org.elci.driver.ElciCompiler;
import org.elci.syntax.Diagnostic;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.backend.FrontendProgram;
import org.julclang.core.*;
import org.julclang.vm.*;
import org.junit.jupiter.api.Test;

import java.util.Map;

class ModuleTest {
    private ElciCompiler.Result compile(Map<String, String> sources) {
        return new ElciCompiler()
                .compileProject(
                        sources,
                        "Main",
                        "main",
                        FrontendProgram.Purpose.FUNCTION,
                        Map.of("Julc.Math", "org.julclang.stdlib.lib.MathLib"),
                        new CompilerOptions());
    }

    @Test
    void importsQualifiedAndExposed() {
        var result =
                compile(
                        Map.of(
                                "Helpers",
                                "module Helpers exposing (inc)\ninc : Int -> Int\ninc x = x + 1\n",
                                "Main",
                                "module Main exposing (main)\n"
                                        + "import Helpers as H exposing (inc)\n"
                                        + "main : Int\n"
                                        + "main = inc (H.inc 4)\n"));
        assertEquals(
                new Term.Const(Constant.integer(6)),
                ((EvalResult.Success)
                                JulcVm.create()
                                        .evaluate(result.program(), result.target().ledgerTarget()))
                        .resultTerm());
    }

    @Test
    void sourceModuleCallsJavaLibrary() {
        var result =
                compile(
                        Map.of(
                                "Helpers",
                                "module Helpers exposing (clamp)\n"
                                        + "import Julc.Math as Math\n"
                                        + "clamp : Int -> Int\n"
                                        + "clamp x = Math.max 0 x\n",
                                "Main",
                                "module Main exposing (main)\n"
                                        + "import Helpers exposing (clamp)\n"
                                        + "main : Int\n"
                                        + "main = clamp (-4)\n"));
        assertInstanceOf(
                EvalResult.Success.class,
                JulcVm.create().evaluate(result.program(), result.target().ledgerTarget()));
    }

    @Test
    void cyclesRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        compile(
                                Map.of(
                                        "Main",
                                        "module Main exposing (main)\n"
                                                + "import Other\n"
                                                + "main : Int\n"
                                                + "main = 1\n",
                                        "Other",
                                        "module Other exposing (value)\n"
                                                + "import Main\n"
                                                + "value : Int\n"
                                                + "value = 1\n")));
    }

    @Test
    void privateNamesRejected() {
        assertThrows(
                Diagnostic.class,
                () ->
                        compile(
                                Map.of(
                                        "Main",
                                        "module Main exposing (main)\n"
                                                + "import Helpers exposing (hidden)\n"
                                                + "main : Int\n"
                                                + "main = hidden\n",
                                        "Helpers",
                                        "module Helpers exposing (public)\n"
                                                + "public : Int\n"
                                                + "public = 1\n"
                                                + "hidden : Int\n"
                                                + "hidden = 2\n")));
    }

    @Test
    void genericRecordAliasAndConstructorCanBeImported() {
        var result =
                compile(
                        Map.of(
                                "Box",
                                        "module Box exposing (Box)\n"
                                                + "type alias Box a = { value : a }\n",
                                "Main",
                                        "module Main exposing (main)\n"
                                                + "import Box exposing (Box)\n"
                                                + "value : Box Int -> Int\n"
                                                + "value box = box.value\n"
                                                + "main : Int\n"
                                                + "main = value (Box 8)\n"));
        assertEquals(
                new Term.Const(Constant.integer(8)),
                ((EvalResult.Success)
                                JulcVm.create()
                                        .evaluate(result.program(), result.target().ledgerTarget()))
                        .resultTerm());
    }
}
