package org.elci;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Terminal presentation (ADR-012): colour, whole-expression underlines and long types. */
class DiagnosticPresentationTest {
    @TempDir Path project;
    private Path main;

    @BeforeEach
    void project() throws Exception {
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
        main = project.resolve("src/Main.elm");
    }

    private String check(String source, String... options) throws Exception {
        Files.writeString(main, "module Main exposing (main)\n\n\n" + source);
        var args = new String[options.length + 2];
        args[0] = "check";
        args[1] = project.toString();
        System.arraycopy(options, 0, args, 2, options.length);
        return NamedValidatorCliTest.run(args).err();
    }

    @Test
    void anArgumentIsUnderlinedWhole() throws Exception {
        String err = check("main : Int\nmain =\n    List.length (Just [ 1, 2 ])\n");
        assertTrue(err.contains("\n6|     List.length (Just [ 1, 2 ])\n" + " ".repeat(3 + 16) + "^".repeat(15) + "\n"), err);
    }

    @Test
    void aSpanOverSeveralLinesShowsEachLine() throws Exception {
        String err = check("main : List ( Int, String )\nmain =\n    [ ( 1, \"one\" )\n    , ( 2\n      , 2\n      )\n    ]\n");
        assertTrue(err.contains("\n7|     , ( 2\n         ^^^\n8|       , 2\n         ^^^\n9|       )\n         ^\n"), err);
    }

    @Test
    void aSpanPastFiveLinesEndsWithAnEllipsisLine() throws Exception {
        String err = check("main : Int\nmain =\n    List.sum\n        [ \"a\"\n        , \"b\"\n        , \"c\"\n        , \"d\"\n        , \"e\"\n        , \"f\"\n        ]\n");
        assertTrue(err.contains("\n11|         , \"e\"\n            ^^^^^\n  | …\n"), err);
    }

    @Test
    void anExpressionReportedAtItsOperatorIsNotUnderlinedFromThere() throws Exception {
        // The element is reported at ++; an underline from there would mark half of it.
        String err = check("main : List Int\nmain =\n    [ 1, \"x\" ++ \"y\" ]\n");
        assertTrue(err.contains("\n6|     [ 1, \"x\" ++ \"y\" ]\n" + " ".repeat(3 + 13) + "^^\n"), err);
    }

    @Test
    void longTypesAreLaidOutWithTheirFirstDifference() throws Exception {
        String err = check("""
                listing : { owner : String, amount : Int, note : String }
                listing =
                    { owner = "a", amount = 3, note = "x" }


                describe : { owner : String, amount : Int, note : Int } -> Int
                describe l =
                    l.amount


                main : Int
                main =
                    describe listing
                """);
        assertTrue(err.contains("""

                It is:     { amount : Int, note : String, owner : String }
                It needs:  { amount : Int, note : Int, owner : String }
                                                  ^^^
                """), err);
    }

    @Test
    void colourIsForTerminalsAndCanBeForced() throws Exception {
        String source = "main : Int\nmain =\n    Jsut 1\n";
        String plain = check(source);
        assertFalse(plain.contains("\u001b["), "Output that is not a terminal is plain");
        assertEquals(plain, check(source, "--color=never"));
        String coloured = check(source, "--color=always");
        assertTrue(coloured.contains("\u001b[1;31mELCI2005\u001b[0m"), coloured);
        assertTrue(coloured.contains("\u001b[36mHint:\u001b[0m did you mean `Just`?"), coloured);
        // Without its escapes, coloured output is the plain output.
        assertEquals(plain, coloured.replaceAll("\u001b\\[[0-9;]*m", ""));
        assertFalse(check(source, "--color=always", "--report=json").contains("\u001b["), "JSON is never coloured");
        assertTrue(check(source, "--color=sometimes").contains("--color takes auto, always or never"));
    }
}
