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
        var cycle = assertThrows(
                org.elci.syntax.Diagnostic.class,
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
        assertEquals("ELCI2104", cycle.code());
        assertTrue(cycle.getMessage().contains("Import cycle: Main -> Other -> Main"), cycle.getMessage());
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
    void qualifiedReferencesRespectExposureAndImports() {
        var helpers =
                "module Helpers exposing (public)\n"
                        + "type Hidden = Hidden Int\n"
                        + "public : Int\n"
                        + "public = 1\n"
                        + "hidden : Int\n"
                        + "hidden = 2\n";
        // A private declaration is not reachable through its module qualifier.
        var qualified =
                assertThrows(
                        Diagnostic.class,
                        () ->
                                compile(
                                        Map.of(
                                                "Main",
                                                "module Main exposing (main)\n"
                                                        + "import Helpers\n"
                                                        + "main : Int\n"
                                                        + "main = Helpers.hidden\n",
                                                "Helpers",
                                                helpers)));
        assertEquals("ELCI2104", qualified.code());
        // Nor is a module that this module does not import, even if another module does.
        var unimported =
                assertThrows(
                        Diagnostic.class,
                        () ->
                                compile(
                                        Map.of(
                                                "Main",
                                                "module Main exposing (main)\n"
                                                        + "import Middle\n"
                                                        + "main : Int\n"
                                                        + "main = Middle.value + Helpers.public\n",
                                                "Middle",
                                                "module Middle exposing (value)\n"
                                                        + "import Helpers\n"
                                                        + "value : Int\n"
                                                        + "value = Helpers.public\n",
                                                "Helpers",
                                                helpers)));
        assertEquals("ELCI2104", unimported.code());
        // Constructors of a type exposed without (..) stay private in patterns too.
        var constructor =
                assertThrows(
                        Diagnostic.class,
                        () ->
                                compile(
                                        Map.of(
                                                "Main",
                                                "module Main exposing (main)\n"
                                                        + "import Helpers\n"
                                                        + "main : Int\n"
                                                        + "main = case Helpers.public of\n"
                                                        + "    Helpers.Hidden -> 1\n"
                                                        + "    _ -> 2\n",
                                                "Helpers",
                                                helpers)));
        assertEquals("ELCI2104", constructor.code());
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

    @Test
    void otherModulesCannotReachTheRootModulesNames() {
        // The root module's declarations are unqualified internally; a module the root imports
        // must still not see them.
        var error =
                assertThrows(
                        Diagnostic.class,
                        () ->
                                compile(
                                        Map.of(
                                                "Main",
                                                "module Main exposing (main)\n"
                                                        + "import Helper\n"
                                                        + "hidden : Int\n"
                                                        + "hidden = 41\n"
                                                        + "main : Int\n"
                                                        + "main = Helper.useIt\n",
                                                "Helper",
                                                "module Helper exposing (useIt)\n"
                                                        + "useIt : Int\n"
                                                        + "useIt = hidden + 1\n")));
        assertEquals("ELCI2005", error.code());
    }
}
