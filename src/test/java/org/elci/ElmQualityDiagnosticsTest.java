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

/** Elm-quality diagnostics (ADR-011): context in mismatches, missing patterns, suggestions, hints. */
class ElmQualityDiagnosticsTest {
    private static final String HEADER = "module Main exposing (main)\n\nimport Bytes\nimport Helper\n"
            + "import Julc.Ledger exposing (PubKeyHash(..))\nimport Value exposing (Value)\n\n\n";
    private static final String HELPER = "module Helper exposing (double)\n\n\ndouble : Int -> Int\ndouble x =\n    x * 2\n";

    @TempDir Path project;

    /** The diagnostic for {@code declarations}, as {@code line:col: CODE: message}, then its hints. */
    private static String report(String declarations) {
        var d = assertThrows(Diagnostic.class, () -> new ElciCompiler().compileProject(
                Map.of("Main", HEADER + declarations, "Helper", HELPER), "Main", "main",
                FrontendProgram.Purpose.FUNCTION, Map.of(), new CompilerOptions()));
        var text = new StringBuilder(d.token().line() + ":" + d.token().column() + ": " + d.code() + ": " + d.getMessage());
        d.hints().forEach(h -> text.append("\nHint: ").append(h));
        return text.toString();
    }

    private static String main(String type, String body) {
        return report("main : " + type + "\nmain =\n    " + body + "\n");
    }

    @Test
    void applicationsNameTheArgument() {
        assertEquals("11:20: ELCI2002: The 1st argument to `String.fromInt` is `String`, but `String.fromInt` needs `Int`",
                main("String", "String.fromInt \"42\""));
        assertEquals("11:29: ELCI2002: The 2nd argument to `List.map` is `Maybe Int`, but `List.map` needs `List Int`\n"
                        + "Hint: this is a Maybe: handle `Nothing` with a case, or use `Maybe.withDefault`",
                main("List String", "List.map String.fromInt (Just 1)"));
        assertEquals("11:19: ELCI2002: The 1st argument to this function is `String`, but it needs `Int`",
                main("Int", "(\\x -> x + 1) \"one\""));
        assertEquals("11:11: ELCI2002: The 1st argument to `Helper.double`, the value piped in, is `String`, but"
                        + " `Helper.double` needs `Int`",
                main("Int", "\"one\" |> Helper.double"));
        assertEquals("11:11: ELCI2002: The 2nd argument to `max`, the value piped in, is `String`, but `max` needs `Int`",
                main("Int", "\"one\" |> max 1"));
    }

    @Test
    void tooManyArgumentsAndNonFunctions() {
        assertEquals("11:13: ELCI2002: `max` takes 2 arguments, but it is given 3\n"
                        + "Hint: a missing comma or missing parentheses can make values look like extra arguments",
                main("Int", "max 1 2 3"));
        assertEquals("15:7: ELCI2002: `x` is `Int`, not a function, so it cannot take an argument",
                main("Int", "let\n        x =\n            5\n    in\n    x 1"));
        assertEquals("11:17: ELCI2002: The 1st argument to `List.length` is `Int -> Int -> List Int`, but `List.length` needs"
                        + " `List a`\nHint: this is a function: is an argument missing?",
                main("Int", "List.length List.range"));
    }

    @Test
    void branchesAndElementsSayWhichDiffers() {
        assertEquals("11:5: ELCI2002: The condition of this if is `Int`, but it must be `Bool`",
                main("Int", "if 1 then\n        1\n\n    else\n        2"));
        assertEquals("11:5: ELCI2002: The branches of this if have different types: the then branch is `Int`, but the else"
                        + " branch is `String`",
                main("Int", "if True then\n        1\n\n    else\n        \"two\""));
        assertEquals("16:13: ELCI2002: The 2nd branch of this case is `String`, but the first branch is `Int`",
                main("Int", "case 3 of\n        1 ->\n            1\n\n        _ ->\n            \"many\""));
        assertEquals("19:13: ELCI2002: The 3rd branch of this case is `String`, but the branches before it are `Int`",
                main("Int", "case 3 of\n        1 ->\n            1\n\n        2 ->\n            2\n\n        _ ->\n            \"many\""));
        assertEquals("11:10: ELCI2002: The 2nd element of this list is `String`, but the first element is `Int`",
                main("List Int", "[ 1, \"two\" ]"));
        assertEquals("11:13: ELCI2002: The 3rd element of this list is `String`, but the elements before it are `Int`",
                main("List Int", "[ 1, 2, \"three\" ]"));
    }

    @Test
    void operatorsSayWhichSideDiffers() {
        assertEquals("11:9: ELCI2002: The left side of :: is `String`, but the list on the right holds `Int`",
                main("List Int", "\"a\" :: [ 1 ]"));
        assertEquals("11:7: ELCI2002: The right side of :: must be a List, but it is `Int`", main("List Int", "1 :: 2"));
        assertEquals("11:9: ELCI2002: The two sides of ++ have different types: the left is `String`, but the right is"
                + " `List Int`", main("String", "\"a\" ++ [ 1 ]"));
        assertEquals("11:9: ELCI2002: The two sides of == have different types: the left is `String`, but the right is `Int`\n"
                + "Hint: `String.fromInt` turns an Int into a String", main("Bool", "\"a\" == 1"));
        assertEquals("11:7: ELCI2002: The left side of && is `Int`, but && works on `Bool` values",
                main("Bool", "1 && True"));
        assertEquals("11:20: ELCI2002: The left side of >> returns `String`, but the right side takes `Int`",
                main("Int -> Int", "String.fromInt >> negate"));
        assertEquals("11:12: ELCI2002: The operator + works on Int values, not Maybe Int\n"
                + "Hint: this is a Maybe: handle `Nothing` with a case, or use `Maybe.withDefault`", main("Int", "Just 1 + 1"));
    }

    @Test
    void annotationsSayWhatTheyPromise() {
        assertEquals("9:1: ELCI2002: The annotation of `main` says it is `Int`, but its definition is `String`",
                main("Int", "\"five\""));
        assertEquals("9:1: ELCI2002: The annotation of `add` says it returns `Int`, but its body is `String`",
                report("add : Int -> Int -> Int\nadd x y =\n    String.fromInt (x + y)\n\n\nmain : Int\nmain =\n    1\n"));
        assertEquals("9:1: ELCI2002: The annotation of `add` gives it 1 argument, but its definition has 2 parameters",
                report("add : Int -> Int\nadd x y =\n    x + y\n\n\nmain : Int\nmain =\n    1\n"));
        assertEquals("9:1: ELCI2002: The annotation of `same` says `a` can be any type, but its definition needs `Int`\n"
                        + "Hint: write `Int` in the annotation, or make the definition work for any type",
                report("same : a -> a\nsame x =\n    x + 1\n\n\nmain : Int\nmain =\n    1\n"));
        assertEquals("9:1: ELCI2002: The annotation of `pick` says `a` and `b` can be different types, but its definition"
                        + " needs them to be the same\nHint: use one type variable for both, or change the definition",
                report("pick : a -> b -> a\npick x y =\n    if True then\n        x\n\n    else\n        y\n\n\n"
                        + "main : Int\nmain =\n    1\n"));
    }

    @Test
    void fieldsAndPatternsSayWhatTheyAre() {
        String record = "let\n        r =\n            { amount = 1, owner = \"a\" }\n    in\n    ";
        assertEquals("15:6: ELCI2002: The field `owner` is `String`, but it is used here as `Int`",
                main("Int", record + "r.owner + 1"));
        assertEquals("15:6: ELCI2010: Missing field 'amont'; available fields: [amount, owner]\nHint: did you mean `amount`?",
                main("Int", record + "r.amont"));
        assertEquals("12:9: ELCI2002: This pattern matches `Maybe a`, but the value it is matched against is `Int`",
                main("Int", "case 1 of\n        Just x ->\n            x\n\n        _ ->\n            0"));
    }

    @Test
    void missingPatternsAreListed() {
        assertEquals("11:5: ELCI2007: This case does not cover every possibility; it is missing `Nothing`\n"
                + "Hint: add a branch for each missing pattern", main("Int", "case Just 1 of\n        Just n ->\n            n"));
        assertEquals("11:5: ELCI2007: This case does not cover every possibility; it is missing `Just (Err _)`\n"
                        + "Hint: add a branch for each missing pattern",
                main("Int", "case Just (Ok 1) of\n        Just (Ok n) ->\n            n\n\n        Nothing ->\n            0"));
        assertTrue(main("Int", "case True of\n        True ->\n            1")
                .startsWith("11:5: ELCI2007: This case does not cover every possibility; it is missing `False`"));
        assertTrue(main("Int", "case [ 1 ] of\n        [] ->\n            0")
                .contains("it is missing `_ :: _`"));
        assertTrue(main("Int", "case [ 1 ] of\n        [] ->\n            0\n\n        [ x ] ->\n            x")
                .contains("it is missing `_ :: _ :: _`"));
        assertTrue(main("Int", "case [ 1 ] of\n        [] ->\n            0\n\n        _ :: _ :: _ ->\n            1")
                .contains("it is missing `[ _ ]`"));
        assertTrue(main("Int", "case ( True, False ) of\n        ( True, True ) ->\n            0\n\n        ( False, _ ) ->\n            1")
                .contains("it is missing `( True, False )`"));
        assertTrue(main("Int", "case 3 of\n        1 ->\n            1").contains("it is missing `_`"));
        assertTrue(main("Int", "case \"a\" of\n        \"a\" ->\n            1").contains("it is missing `_`"));
        String shapes = "type Shape\n    = Circle Int\n    | Square Int\n    | Triangle Int Int Int\n    | Line\n    | Dot\n\n\n";
        assertTrue(report(shapes + "main : Int\nmain =\n    case ( Just (Circle 1), 0 ) of\n        ( Just (Circle r), _ ) ->\n"
                + "            r\n\n        ( Nothing, _ ) ->\n            0\n").contains("it is missing `( Just (Square _), _ )`,"
                + " `( Just (Triangle _ _ _), _ )`, `( Just Line, _ )` and 1 more"), shapes);
    }

    @Test
    void unknownNamesSuggestCloseOnes() {
        assertEquals("11:5: ELCI2005: Unknown name 'doubel'\nHint: did you mean `Helper.double`?", main("Int", "doubel 4"));
        assertEquals("15:19: ELCI2005: Unknown name 'valeu'\nHint: did you mean `value`?",
                main("Int", "let\n        value =\n            4\n    in\n    Helper.double valeu"));
        assertEquals("11:5: ELCI2005: Unknown name 'Jsut'\nHint: did you mean `Just`?", main("Maybe Int", "Jsut 1"));
        assertEquals("11:5: ELCI2104: Module Helper does not expose 'doubel'\nHint: did you mean `Helper.double`?",
                main("Int", "Helper.doubel 4"));
        assertEquals("11:5: ELCI2104: Module Hepler is not imported; add `import Hepler`\nHint: did you mean `Helper.double`?",
                main("Int", "Hepler.double 4"));
        assertTrue(main("Int", "List.lenght [ 1 ]").endsWith("\nHint: did you mean `List.length`?"));
        assertEquals("9:1: ELCI2012: Type is not declared or imported in this module: Strin\nHint: did you mean `String`?",
                main("Strin", "\"x\""));
        assertEquals("12:9: ELCI2001: Unknown constructor Jsut in this pattern\nHint: did you mean `Just`?",
                main("Int", "case Just 1 of\n        Jsut n ->\n            n\n\n        Nothing ->\n            0"));
        assertEquals("4:1: ELCI2104: Julc.Data does not expose 'decod'; it provides decode, encode, fromOptional, toOptional\n"
                        + "Hint: did you mean `decode`?",
                reportWithImport("import Julc.Data exposing (decod)"));
    }

    private static String reportWithImport(String line) {
        var d = assertThrows(Diagnostic.class, () -> new ElciCompiler().compileProject(
                Map.of("Main", HEADER.replace("import Bytes\n", "import Bytes\n" + line + "\n") + "main : Int\nmain =\n    1\n",
                        "Helper", HELPER), "Main", "main", FrontendProgram.Purpose.FUNCTION, Map.of(), new CompilerOptions()));
        return d.token().line() + ":" + d.token().column() + ": " + d.code() + ": " + d.getMessage()
                + String.join("", d.hints().stream().map(h -> "\nHint: " + h).toList());
    }

    @Test
    void hintsForCommonConfusions() {
        String owner = "owner : PubKeyHash\nowner =\n    PubKeyHash (Bytes.fromString \"k\")\n\n\n";
        assertTrue(report(owner + "main : Bytes.Bytes\nmain =\n    Bytes.append owner Bytes.empty\n")
                .endsWith("\nHint: the `.hash` field is the Bytes inside a PubKeyHash"));
        assertTrue(report("check : PubKeyHash -> Bool\ncheck key =\n    key == Bytes.fromString \"abc\"\n\n\nmain : Int\nmain =\n    1\n")
                .endsWith("\nHint: wrap the bytes with the constructor, `PubKeyHash bytes`, imported with `exposing (PubKeyHash(..))`"));
        assertTrue(main("Int", "Value.lovelace 5 + 1").endsWith("\nHint: `Value.lovelaceOf` reads the lovelace in a Value"));
        assertTrue(main("Value", "5").endsWith("\nHint: `Value.lovelace` makes a Value of an amount of lovelace"));
        assertTrue(main("String", "7").endsWith("\nHint: `String.fromInt` turns an Int into a String"));
        assertTrue(main("Maybe Int", "7").endsWith("\nHint: wrap the value with `Just`"));
        assertTrue(main("Bytes.Bytes", "\"k\"").endsWith("\nHint: `Bytes.fromString` encodes a String as UTF-8 Bytes"));
    }

    @Test
    void theCliShowsTheCodeAndReportsJson() throws Exception {
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
        Path main = project.resolve("src/Main.elm");
        Files.writeString(main, "module Main exposing (main)\n\n\nmain : Int\nmain =\n    Jsut 1\n");
        var text = NamedValidatorCliTest.run("check", project.toString());
        assertEquals(1, text.status());
        assertEquals(main + ":6:5: ELCI2005: Unknown name 'Jsut'\n\n6|     Jsut 1\n       ^^^^\nHint: did you mean `Just`?\n",
                text.err());
        var json = NamedValidatorCliTest.run("check", project.toString(), "--report=json");
        assertEquals(1, json.status());
        assertEquals("{\"code\":\"ELCI2005\",\"path\":" + Diagnostic.quote(main.toString()) + ",\"line\":6,\"column\":5,"
                + "\"message\":\"Unknown name 'Jsut'\",\"hints\":[\"did you mean `Just`?\"],"
                + "\"excerpt\":\"6|     Jsut 1\\n       ^^^^\"}\n", json.err());
        // A string literal is underlined with its quotes; the end of the file is shown at the last line.
        Files.writeString(main, "module Main exposing (main)\n\n\nmain : Int\nmain =\n    String.fromInt \"42\"\n");
        assertTrue(NamedValidatorCliTest.run("check", project.toString()).err().contains("\n6|     String.fromInt \"42\"\n"
                + " ".repeat(3 + 19) + "^^^^\n"));
        Files.writeString(main, "module Main exposing (main)\n\n\nmain : Int\nmain =\n    ( 1, 2\n");
        assertTrue(NamedValidatorCliTest.run("check", project.toString()).err().contains("\n6|     ( 1, 2\n" + " ".repeat(3 + 10) + "^\n"));
        // Failures without a location keep the report's shape.
        var unknown = NamedValidatorCliTest.run("init", "--report=json");
        assertEquals("{\"code\":\"ELCI4001\",\"path\":null,\"line\":null,\"column\":null,"
                + "\"message\":\"--report=json applies to check, build, eval and test\",\"hints\":[],\"excerpt\":null}\n", unknown.err());
    }

    @Test
    void suggestionsStayClose() {
        assertEquals(List.of("length"), org.elci.syntax.Suggestions.closest("lenght", List.of("length", "head", "last")));
        assertEquals(List.of(), org.elci.syntax.Suggestions.closest("xs", List.of("x", "ys")));
        assertEquals(List.of("True"), org.elci.syntax.Suggestions.closest("true", List.of("True", "tree")).subList(0, 1));
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", Diagnostic.quote("a\"b\\c\nd\u0001"));
    }
}
