package org.elci;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.elci.driver.ElciCompiler;
import org.elci.syntax.Diagnostic;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.backend.FrontendProgram;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every error of a phase in one run, in source order, without cascades (ADR-012). */
class MultipleDiagnosticsTest {
    @TempDir Path project;

    /** Each diagnostic of a failed compilation, as {@code Module line:col: CODE: message}. */
    private static List<String> errors(Map<String, String> sources) {
        var d = assertThrows(Diagnostic.class, () -> new ElciCompiler().compileProject(sources, "Main", "main",
                FrontendProgram.Purpose.FUNCTION, Map.of(), new CompilerOptions()));
        return d.all().stream().map(e -> e.token().source() + " " + e.token().line() + ":" + e.token().column() + ": "
                + e.code() + ": " + e.getMessage()).toList();
    }

    private static List<String> errors(String main) {
        return errors(Map.of("Main", main));
    }

    @Test
    void typeErrorsInSeveralDeclarationsAreAllReported() {
        var found = errors("""
                module Main exposing (main)


                double : Int -> Int
                double x =
                    x * "2"


                greet : String -> String
                greet name =
                    "Hello " ++ nme


                main : Int
                main =
                    double 4
                """);
        assertEquals(List.of(
                "Main 6:7: ELCI2002: The operator * works on Int values, not String",
                "Main 11:17: ELCI2005: Unknown name 'nme'"), found);
    }

    @Test
    void aFailedDeclarationKeepsItsAnnotationSoCallersDoNotCascade() {
        // `double` fails; `main` uses it at its annotated type, and a wrong use is still reported.
        var found = errors("""
                module Main exposing (main)


                double : Int -> Int
                double x =
                    x ++ x


                main : Int
                main =
                    double "4"
                """);
        assertEquals(2, found.size(), found.toString());
        assertTrue(found.get(0).startsWith("Main 6:7: ELCI2003"), found.toString());
        assertTrue(found.get(1).startsWith("Main 11:12: ELCI2002: The 1st argument to `double` is `String`"), found.toString());
    }

    @Test
    void syntaxErrorsInEveryModuleAreReported() {
        var found = errors(Map.of(
                "Main", """
                        module Main exposing (main)

                        import Helper


                        broken : Int
                        broken =
                            case of


                        alsoBroken : Int
                        alsoBroken =
                            [ 1,


                        main : Int
                        main =
                            Helper.h 1
                        """,
                "Helper", """
                        module Helper exposing (h)


                        h : Int -> Int
                        h x =
                            if x then 1
                        """));
        assertEquals(3, found.size(), found.toString());
        assertTrue(found.get(0).startsWith("Helper 7:1: ELCI1002: Expected 'else'"), found.toString());
        assertTrue(found.get(1).startsWith("Main 8:10: ELCI1002: Expected expression, found 'of'"), found.toString());
        assertTrue(found.get(2).startsWith("Main 16:6: ELCI1002: Expected ']'"), found.toString());
    }

    @Test
    void lexicalErrorsAreAllReported() {
        var found = errors("module Main exposing (main)\n\n\nmain : Int\nmain =\n    1.5 + \"a\\q\" + 2 ~ 3\n");
        assertEquals(List.of(
                "Main 6:6: ELCI1001: Float is unsupported; represent fractional amounts using an explicit integer scale",
                "Main 6:15: ELCI1001: Unsupported string escape",
                "Main 6:21: ELCI1001: Unsupported character '~'"), found);
    }

    @Test
    void nameErrorsInSeveralDeclarationsAreAllReported() {
        var found = errors("""
                module Main exposing (main)


                a : Int
                a =
                    List.lenght [ 1 ]


                b : Int
                b =
                    Maybe.withDefalt 0 Nothing


                main : Int
                main =
                    a + b
                """);
        assertEquals(2, found.size(), found.toString());
        assertTrue(found.get(0).startsWith("Main 6:5: ELCI2104: Elci's List module has no lenght"), found.toString());
        assertTrue(found.get(1).startsWith("Main 11:5: ELCI2104: Elci's Maybe module has no withDefalt"), found.toString());
    }

    @Test
    void aMistakeThatFailsSeveralChecksIsReportedOnce() {
        // List.sort compares its elements in several places; the record is not comparable once.
        var found = errors("""
                module Main exposing (main)


                type alias Person =
                    { age : Int }


                main : List Person
                main =
                    List.sort [ { age = 1 }, { age = 2 } ]
                """);
        assertEquals(1, found.size(), found.toString());
        assertTrue(found.get(0).startsWith("Main 10:5: ELCI2003: `List.sort` needs comparable values"), found.toString());
    }

    @Test
    void theFirstErrorIsTheFirstInSourceOrder() {
        // The type checker reaches `later` before `earlier` (it checks `main`'s dependencies first),
        // but the error reported first is the earlier one in the file.
        var d = assertThrows(Diagnostic.class, () -> new ElciCompiler().compileProject(Map.of("Main", """
                module Main exposing (main)


                earlier : Int
                earlier =
                    "one"


                later : Int
                later =
                    "two"


                main : Int
                main =
                    later + earlier
                """), "Main", "main", FrontendProgram.Purpose.FUNCTION, Map.of(), new CompilerOptions()));
        assertEquals(4, d.token().line(), "The annotation of `earlier`, reported at its name");
        assertEquals(2, d.all().size());
    }

    @Test
    void theCliPrintsEveryErrorACountAndJsonLines() throws Exception {
        Files.writeString(project.resolve("elci.toml"), """
                [project]
                source-directories = ["src"]
                target = "plutus-v3-pv11-uplc-1.1.0"
                julc-version = "0.1.0-gb-5a3378b"

                [entrypoint]
                module = "Main"
                function = "main"
                purpose = "function"
                boundary = "julc-strict-v1"
                """);
        Files.createDirectories(project.resolve("src"));
        var main = project.resolve("src/Main.elm");
        var source = new StringBuilder("module Main exposing (main)\n");
        for (int i = 0; i < 25; i++) source.append("\n\nv").append(i).append(" : Int\nv").append(i).append(" =\n    \"x\"\n");
        source.append("\n\nmain : Int\nmain =\n    1\n");
        Files.writeString(main, source.toString());
        var text = NamedValidatorCliTest.run("check", project.toString());
        assertEquals(1, text.status());
        assertEquals(20, text.err().split(": ELCI2002: ").length - 1, "At most 20 are shown");
        assertTrue(text.err().endsWith("\n... and 5 more\n\n25 errors\n\nFor more on ELCI2002, run `elci explain ELCI2002`.\n"),
                text.err());
        var json = NamedValidatorCliTest.run("check", project.toString(), "--report=json");
        var lines = json.err().strip().split("\n");
        assertEquals(20, lines.length);
        for (String line : lines) assertTrue(line.startsWith("{\"code\":\"ELCI2002\""), line);
    }
}
