package org.elci;

import static org.junit.jupiter.api.Assertions.*;

import org.elci.cli.Main;
import org.elci.syntax.Diagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Elm-compatible syntax and imports, readable evaluation output and located diagnostics. */
class ElmCompatibilityTest {
    @TempDir Path project;

    static long run(String body) {
        return ArrayTest.run(body);
    }

    @Test
    void aMinusTouchingItsOperandNegatesAnArgumentAsInElm() {
        // `f -1` passes -1; `a - 1` and `a-1` subtract; `-x` negates a variable.
        assertEquals(-1, run("""
                identity : Int -> Int
                identity value =
                    value


                main : Int
                main =
                    identity -1
                """));
        assertEquals(-100, run("""
                scale : Int -> Int -> Int
                scale factor value =
                    factor * value


                main : Int
                main =
                    let
                        ten =
                            10
                    in
                    scale -ten 10 + (ten - 1) + (ten-1) - 18
                """));
        // slice 0 -1 keeps [ 1, 2 ] (3); the list sums to 21.
        assertEquals(24, run("""
                main : Int
                main =
                    case Array.get -1 (Array.fromList [ 7 ]) of
                        Nothing ->
                            Array.foldl (\\v t -> v + t) 0 (Array.slice 0 -1 (Array.fromList [ 1, 2, 3 ]))
                                + List.foldl (\\v t -> v + t) 0 [ -1, 2, 20 ]

                        Just _ ->
                            0
                """));
        assertEquals(5, run("""
                describe : Int -> Int
                describe value =
                    case value of
                        -1 ->
                            5

                        _ ->
                            0


                main : Int
                main =
                    describe -1
                """));
    }

    @Test
    void coreModulesExposeTheirBuiltInTypesAsInElm() {
        assertEquals(4, run("""
                import Map


                prices : Map.Map String Int
                prices =
                    Map.fromList [ ( "gold", 5 ) ]


                squares : Array.Array Int
                squares =
                    Array.initialize 3 (\\i -> i * i)


                main : Int
                main =
                    Map.size prices + Array.length squares
                """));
        var result = MapTest.run("""
                import Array exposing (Array)
                import Map exposing (Map)


                sizes : Map String (List Int) -> Array Int
                sizes groups =
                    Array.fromList (List.map List.length (Map.values groups))


                main : Int
                main =
                    Array.length (sizes (Map.fromList [ ( "a", [ 1 ] ) ]))
                """, Map.of());
        assertEquals(new org.julclang.core.Term.Const(org.julclang.core.Constant.integer(1)), result);
    }

    @Test
    void mapAndFilterKeepMapOrder() {
        assertEquals(1, run("""
                import Map


                main : Int
                main =
                    let
                        prices =
                            Map.fromList [ ( "a", 1 ), ( "b", 2 ), ( "c", 3 ) ]

                        doubled =
                            Map.map (\\_ price -> price * 2) prices

                        cheap =
                            Map.filter (\\_ price -> price < 3) prices
                    in
                    if Map.keys doubled == Map.keys prices && Map.keys cheap == List.filter (\\key -> key /= "c") (Map.keys prices) then
                        1

                    else
                        0
                """));
    }

    void write(String relative, String text) throws Exception {
        var path = project.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }

    @Test
    void evalPrintsTheValueInElciSyntaxAndItsBudget() throws Exception {
        assertEquals(0, Main.run("init", project.toString(), "--purpose", "function"));
        write("src/Main.elm", """
                module Main exposing (main)

                import Debug


                type Shape
                    = Circle Int
                    | Rect Int Int


                main : List ( Shape, Maybe String )
                main =
                    Debug.trace "shapes" [ ( Rect 2 -3, Just "a\\"b" ), ( Circle 1, Nothing ) ]
                """);
        var result = NamedValidatorCliTest.run("eval", project.toString());
        assertEquals(0, result.status(), result.err());
        var lines = result.out().lines().toList();
        assertEquals("[( Rect 2 (-3), Just \"a\\\"b\" ), ( Circle 1, Nothing )]", lines.get(0));
        assertTrue(lines.get(1).matches("cpu \\d+, mem \\d+"), lines.get(1));
        assertEquals("trace: shapes", lines.get(2));
        var builtins = NamedValidatorCliTest.run("eval", project.toString(), "--builtins");
        assertTrue(builtins.out().contains("builtin: "), builtins.out());

        write("src/Main.elm", """
                module Main exposing (main)

                import Bytes


                main : Bytes
                main =
                    Bytes.fromString "GOLD"
                """);
        assertTrue(NamedValidatorCliTest.run("eval", project.toString()).out().startsWith("#474f4c44\n"));
        write("src/Main.elm", """
                module Main exposing (main)


                main : Int
                main =
                    1 // 0
                """);
        var failure = NamedValidatorCliTest.run("eval", project.toString());
        assertEquals(1, failure.status());
        assertTrue(failure.out().startsWith("evaluation failed: "), failure.out());
        assertTrue(NamedValidatorCliTest.run("eval", project.toString(), "--help").out().contains("Usage: elci"));
    }

    @Test
    void diagnosticsAreCodedAndLocated() throws Exception {
        assertEquals(0, Main.run("init", project.toString(), "--purpose", "function"));
        write("src/Main.elm", """
                module Main exposing (main)


                main : Int
                main =
                    List.length []
                """);
        var unconstrained = NamedValidatorCliTest.run("eval", project.toString());
        assertTrue(unconstrained.err().contains("src/Main.elm:6:5: ELCI3001: Cannot specialize List.length"), unconstrained.err());
        write("src/Main.elm", "module Mian exposing (main)\n\n\nmain : Int\nmain =\n    1\n");
        assertTrue(NamedValidatorCliTest.run("eval", project.toString()).err().contains("src/Main.elm:1:1: ELCI2104: Module header Mian"));
        write("src/Main.elm", "module Main exposing (main)\n\nimport Missing\n\n\nmain : Int\nmain =\n    1\n");
        assertTrue(NamedValidatorCliTest.run("eval", project.toString()).err().contains("src/Main.elm:3:1: ELCI2101: Missing source module: Missing"));
        write("src/Main.elm", """
                module Main exposing (main)

                import Array


                main : Int
                main =
                    List.length [ Array.fromList [ 1 ] ]
                """);
        assertTrue(NamedValidatorCliTest.run("eval", project.toString()).err().contains("ELCI2013: Arrays have no Data encoding"));
    }

    @Test
    void scenariosMustSupplyEveryParameterAndSourcesCannotImportTheTestLibrary() throws Exception {
        assertEquals(0, Main.run("init", project.toString()));
        var toml = project.resolve("elci.toml");
        Files.writeString(toml, Files.readString(toml).replace("parameters = [\"owner\"]", "parameters = [\"owner\", \"guardian\"]"));
        var main = project.resolve("src/Main.elm");
        Files.writeString(main, Files.readString(main)
                .replace("spend : PubKeyHash -> () -> () -> ScriptContext -> Bool", "spend : PubKeyHash -> PubKeyHash -> () -> () -> ScriptContext -> Bool")
                .replace("spend owner _ _ ctx =", "spend owner _ _ _ ctx ="));
        var missing = NamedValidatorCliTest.run("test", project.toString());
        assertEquals(1, missing.status());
        // The failure-expecting scenario no longer passes because the script could not decode.
        assertTrue(missing.out().contains("FAIL MainTest.othersAreRejected"), missing.out());
        assertTrue(missing.out().contains("it takes 2 parameter(s) [owner, guardian], but the scenario gives 1"), missing.out());

        Files.writeString(main, Files.readString(main).replace("import Julc.Ledger", "import Expect\nimport Julc.Ledger"));
        var leak = NamedValidatorCliTest.run("test", project.toString());
        assertTrue(leak.err().contains("src/Main.elm:3:1: ELCI2101: Only test modules can import Expect"), leak.err());
        assertThrows(Diagnostic.class, () -> org.elci.test.TestRunner.sources(org.elci.cli.Project.read(project)));
    }
}
