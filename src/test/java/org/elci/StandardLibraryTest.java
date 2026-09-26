package org.elci;

import static org.junit.jupiter.api.Assertions.*;

import org.elci.driver.ElciCompiler;
import org.elci.driver.ElciValues;
import org.elci.syntax.Diagnostic;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.backend.FrontendProgram;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The prelude, {@code comparable} and Elm's core modules (ADR-008): Elm's semantics and edge cases,
 * the scoping of default imports, and the diagnostics for their misuse. The algebraic laws are
 * property tests in {@code src/test/resources/stdlib-laws}, run by {@code elci test}.
 */
class StandardLibraryTest {
    @TempDir Path project;

    private static final String HEADER =
            "module Main exposing (main)\n\nimport Bytes\nimport Dict exposing (Dict)\nimport Julc.Data as Data\n"
                    + "import Julc.Ledger exposing (PolicyId(..), PubKeyHash, TokenName(..))\nimport Map exposing (Map)\nimport Value exposing (Value)\n\n\n";

    /** The value of {@code main : type}, in Elci syntax, or {@code failed} when evaluation fails. */
    private static String eval(String type, String body) {
        return eval("", type, body);
    }

    private static String eval(String declarations, String type, String body) {
        var result = compile(Map.of("Main", HEADER + declarations + "main : " + type + "\nmain =\n    " + body + "\n"));
        var evaluation = JulcVm.create().evaluate(result.program(), result.target().ledgerTarget());
        if (!(evaluation instanceof EvalResult.Success success)) return "failed";
        return ElciValues.show(success.resultTerm(), result.pirType(), result.namedTypes(), result.type());
    }

    private static ElciCompiler.Result compile(Map<String, String> sources) {
        return compile(sources, Map.of());
    }

    private static ElciCompiler.Result compile(Map<String, String> sources, Map<String, String> javaModules) {
        return new ElciCompiler().compileProject(sources, "Main", "main", FrontendProgram.Purpose.FUNCTION,
                javaModules, new CompilerOptions());
    }

    /** {@code line:column: CODE: message} of the diagnostic compiling {@code sources} reports. */
    private static String diagnostic(Map<String, String> sources) {
        var d = assertThrows(Diagnostic.class, () -> compile(sources));
        return d.token().line() + ":" + d.token().column() + ": " + d.code() + ": " + d.getMessage();
    }

    private static String diagnostic(String declarations) {
        String main = declarations.contains("main :") ? "" : "\n\nmain : Int\nmain =\n    1\n";
        return diagnostic(Map.of("Main", "module Main exposing (main)\n\n\n" + declarations + main));
    }

    @Test
    void basics() {
        assertEquals("[-5, 5, 0, 4, 4, 0]", eval("List Int", "[ negate 5, negate -5, negate 0, abs -4, abs 4, abs 0 ]"));
        assertEquals("[3, 3, 7, 7, -1]", eval("List Int", "[ min 3 7, min 7 3, max 3 7, max 7 3, min -1 -1 ]"));
        assertEquals("[\"apple\", \"pear\", \"a\"]",
                eval("List String", "[ min \"pear\" \"apple\", max \"pear\" \"apple\", max \"\" \"a\" ]"));
        assertEquals("[0, 5, 10, 0, 10]",
                eval("List Int", "[ clamp 0 10 -5, clamp 0 10 5, clamp 0 10 15, clamp 0 10 0, clamp 0 10 10 ]"));
        assertEquals("[LT, EQ, GT, GT, EQ]",
                eval("List Order", "[ compare 1 2, compare 2 2, compare 3 2, compare \"b\" \"a\", compare [ 1 ] [ 1 ] ]"));
        assertEquals("[False, True, True, False]",
                eval("List Bool", "[ xor True True, xor True False, xor False True, xor False False ]"));
        assertEquals("( 4, 5 )", eval("( Int, Int )", "( identity 4, always 5 \"ignored\" )"));
        // The built-in prelude functions are also reachable qualified.
        assertEquals("[2, -1, 1]",
                eval("List Int", "[ Basics.modBy 3 -7, Basics.remainderBy 3 -7, if Basics.not False then 1 else 0 ]"));
    }

    @Test
    void powerIsRightAssociativeAndBindsTighterThanMultiplication() {
        assertEquals("[1024, 1, 1, 0, -8, 4, 512, 12, 1]",
                eval("List Int", "[ 2 ^ 10, 0 ^ 0, 5 ^ 0, 0 ^ 5, (-2) ^ 3, -2 ^ 2, 2 ^ 3 ^ 2, 3 * 2 ^ 2, 1 ^ 100 ]"));
        assertEquals("10000000000000000000000000000000000000000", eval("Int", "10 ^ 40"));
        // A negative exponent fails evaluation, where Elm would produce a fraction.
        assertEquals("failed", eval("Int", "2 ^ -1"));
    }

    @Test
    void orderingOfEveryComparableType() {
        assertEquals("[True, True, True, True, True, True, True]", eval("List Bool",
                "[ \"a\" < \"b\", \"\" < \"a\", \"ab\" < \"b\", \"Z\" < \"a\", \"é\" > \"z\", \"abc\" <= \"abc\", \"b\" >= \"a\" ]"));
        // Bytes are unsigned and a proper prefix comes first.
        assertEquals("[True, True, True, True]", eval("List Bool",
                "[ Bytes.fromString \"ab\" < Bytes.fromString \"abc\", Bytes.empty < Bytes.fromString \"a\""
                        + ", Bytes.fromString \"b\" > Bytes.fromString \"abc\", Bytes.cons 255 Bytes.empty > Bytes.cons 1 Bytes.empty ]"));
        assertEquals("[True, True, True, True, True, True]", eval("List Bool",
                "[ [] < [ 0 ], [ 1, 2 ] < [ 1, 3 ], [ 1, 2 ] < [ 1, 2, 0 ], [ 2 ] > [ 1, 5 ], [ 1, 2 ] <= [ 1, 2 ], not ([ 1, 2 ] < [ 1, 2 ]) ]"));
        assertEquals("[True, True, True, True, True, True]", eval("List Bool",
                "[ ( 1, \"b\" ) < ( 2, \"a\" ), ( 1, \"a\" ) < ( 1, \"b\" ), ( 1, 2, 3 ) <= ( 1, 2, 3 ), not (( 1, 2, 3 ) < ( 1, 2, 3 ))"
                        + ", ( 1, 2, 4 ) > ( 1, 2, 3 ), ( [ 1 ], \"a\" ) < ( [ 1, 0 ], \"a\" ) ]"));
        // Ledger hashes order by their bytes.
        assertEquals("[True, False, True]", eval("""
                        key : String -> PubKeyHash
                        key name =
                            Data.decode (Data.encode (Bytes.fromString name))


                        """, "List Bool",
                "[ key \"a\" < key \"b\", List.maximum [ key \"a\", key \"c\" ] == Just (key \"a\")"
                        + ", List.sort [ key \"b\", key \"a\" ] == [ key \"a\", key \"b\" ] ]"));
    }

    @Test
    void maybe() {
        assertEquals("[7, 0]", eval("List Int", "[ Maybe.withDefault 0 (Just 7), Maybe.withDefault 0 Nothing ]"));
        assertEquals("[Just 2, Nothing, Just 3, Nothing, Nothing, Just 6, Nothing, Just 5, Nothing, Nothing]", eval("List (Maybe Int)",
                "[ Maybe.map (\\x -> x + 1) (Just 1), Maybe.map (\\x -> x + 1) Nothing, Maybe.map2 (+) (Just 1) (Just 2)"
                        + ", Maybe.map2 (+) (Just 1) Nothing, Maybe.map2 (+) Nothing (Just 2)"
                        + ", Maybe.map3 (\\a b c -> a + b + c) (Just 1) (Just 2) (Just 3), Maybe.map3 (\\a b c -> a + b + c) (Just 1) Nothing (Just 3)"
                        + ", Maybe.andThen List.head (Just [ 5 ]), Maybe.andThen List.head (Just []), Maybe.andThen List.head Nothing ]"));
    }

    @Test
    void result() {
        assertEquals("[Ok 4, Err \"e\", Ok 3, Err \"a\", Err \"b\", Ok 6, Err \"b\", Err \"negative\", Err \"first\", Ok 3, Err \"none\", Err \"bad!\", Ok 1]",
                eval("List (Result String Int)",
                        "[ Result.map (\\x -> x * 2) (Ok 2), Result.map (\\x -> x * 2) (Err \"e\"), Result.map2 (+) (Ok 1) (Ok 2)"
                                + ", Result.map2 (+) (Err \"a\") (Err \"b\"), Result.map2 (+) (Ok 1) (Err \"b\")"
                                + ", Result.map3 (\\a b c -> a + b + c) (Ok 1) (Ok 2) (Ok 3), Result.map3 (\\a b c -> a + b + c) (Ok 1) (Err \"b\") (Err \"c\")"
                                + ", Result.andThen (\\x -> if x > 0 then Ok x else Err \"negative\") (Ok -1), Result.andThen (\\x -> Ok (x + 1)) (Err \"first\")"
                                + ", Result.fromMaybe \"none\" (Just 3), Result.fromMaybe \"none\" Nothing"
                                + ", Result.mapError (\\e -> e ++ \"!\") (Err \"bad\"), Result.mapError (\\e -> e ++ \"!\") (Ok 1) ]"));
        assertEquals("( [5, 0], [Just 1, Nothing] )", eval("( List Int, List (Maybe Int) )",
                "( [ Result.withDefault 0 (Ok 5), Result.withDefault 0 (Err \"x\") ], [ Result.toMaybe (Ok 1), Result.toMaybe (Err \"x\") ] )"));
    }

    @Test
    void resultAndOrderEncodeInDeclarationOrder() {
        assertEquals("[Constr 0 [1], Constr 1 [2], Constr 0, Constr 1, Constr 2]", eval("""
                        ok : Result Int Int
                        ok =
                            Ok 1


                        err : Result Int Int
                        err =
                            Err 2


                        """, "List Data", "[ Data.encode ok, Data.encode err, Data.encode LT, Data.encode EQ, Data.encode GT ]"));
    }

    @Test
    void tuple() {
        assertEquals("( ( 1, \"one\" ), [1, 2, 10, 20, 1] )", eval("( ( Int, String ), List Int )",
                "( Tuple.pair 1 \"one\", [ Tuple.first ( 1, 2 ), Tuple.second ( 1, 2 ), Tuple.first (Tuple.mapFirst (\\x -> x * 10) ( 1, 2 ))"
                        + ", Tuple.second (Tuple.mapSecond (\\x -> x * 10) ( 1, 2 ))"
                        + ", Tuple.first (Tuple.mapBoth negate abs ( 3, -4 )) + Tuple.second (Tuple.mapBoth negate abs ( 3, -4 )) ] )"));
    }

    @Test
    void string() {
        assertEquals("[\"0\", \"7\", \"-7\", \"10\", \"100\", \"-1000\", \"1234567890123456789012345678901234567890\"]", eval("List String",
                "[ String.fromInt 0, String.fromInt 7, String.fromInt -7, String.fromInt 10, String.fromInt 100, String.fromInt -1000"
                        + ", String.fromInt 1234567890123456789012345678901234567890 ]"));
        assertEquals("( [True, False], [\"abcd\", \"\", \"abc\", \"\", \"a\", \"a, b, c\"] )", eval("( List Bool, List String )",
                "( [ String.isEmpty \"\", String.isEmpty \" \" ], [ String.append \"ab\" \"cd\", String.concat [], String.concat [ \"a\", \"b\", \"c\" ]"
                        + ", String.join \", \" [], String.join \", \" [ \"a\" ], String.join \", \" [ \"a\", \"b\", \"c\" ] ] )"));
    }

    @Test
    void listConstructionAndSlicing() {
        assertEquals("[[1], [0, 0, 0], [], [], [1, 2, 3, 4], [3], [], [-2, -1, 0, 1, 2]]", eval("List (List Int)",
                "[ List.singleton 1, List.repeat 3 0, List.repeat 0 1, List.repeat -1 1, List.range 1 4, List.range 3 3, List.range 5 1, List.range -2 2 ]"));
        assertEquals("[[1, 2], [], [], [1, 2], [3], [1], [1], []]", eval("List (List Int)",
                "[ List.take 2 [ 1, 2, 3 ], List.take 0 [ 1 ], List.take -1 [ 1 ], List.take 10 [ 1, 2 ]"
                        + ", List.drop 2 [ 1, 2, 3 ], List.drop 0 [ 1 ], List.drop -1 [ 1 ], List.drop 10 [ 1, 2 ] ]"));
        assertEquals("( [True, False, True, False, False], [Just 1, Nothing], [Just [2], Nothing] )",
                eval("( List Bool, List (Maybe Int), List (Maybe (List Int)) )",
                        "( [ List.isEmpty [], List.isEmpty [ 1 ], List.member 2 [ 1, 2 ], List.member 3 [ 1, 2 ], List.member 1 [] ]"
                                + ", [ List.head [ 1, 2 ], List.head [] ], [ List.tail [ 1, 2 ], List.tail [] ] )"));
    }

    @Test
    void listAggregatesAndTransformations() {
        assertEquals("[0, 6, 1, 24]", eval("List Int", "[ List.sum [], List.sum [ 1, 2, 3 ], List.product [], List.product [ 2, 3, 4 ] ]"));
        assertEquals("( [Just 9, Nothing, Just (-9), Nothing], [Just \"c\", Just \"a\"] )",
                eval("( List (Maybe Int), List (Maybe String) )",
                        "( [ List.maximum [ 3, 9, 1 ], List.maximum [], List.minimum [ 3, -9, 1 ], List.minimum [] ]"
                                + ", [ List.maximum [ \"b\", \"c\", \"a\" ], List.minimum [ \"b\", \"c\", \"a\" ] ] )"));
        assertEquals("( [1, 12, 23], [1, 3], ( [2, 4], [1, 3, 5] ) )", eval("( List Int, List Int, ( List Int, List Int ) )",
                "( List.indexedMap (\\i x -> i * 10 + x) [ 1, 2, 3 ], List.filterMap List.head [ [ 1 ], [], [ 3 ] ]"
                        + ", List.partition (\\x -> modBy 2 x == 0) [ 1, 2, 3, 4, 5 ] )"));
        // map2 and map3 stop at the shortest list, as in Elm.
        assertEquals("[[1, 2, 3], [], [], [1], [1, 0, 2, 0, 3], [11, 22], [111]]", eval("List (List Int)",
                "[ List.concat [ [ 1 ], [], [ 2, 3 ] ], List.concat [], List.intersperse 0 [], List.intersperse 0 [ 1 ]"
                        + ", List.intersperse 0 [ 1, 2, 3 ], List.map2 (+) [ 1, 2, 3 ] [ 10, 20 ]"
                        + ", List.map3 (\\a b c -> a + b + c) [ 1, 2 ] [ 10, 20, 30 ] [ 100 ] ]"));
        assertEquals("( [1, 2], [\"a\", \"b\"] )", eval("( List Int, List String )", "List.unzip [ ( 1, \"a\" ), ( 2, \"b\" ) ]"));
    }

    @Test
    void sortingIsStable() {
        assertEquals("( [-2, 0, 1, 3, 3, 5, 9], [\"Fig\", \"apple\", \"fig\", \"pear\"], [[], [1], [1, 5], [2]] )",
                eval("( List Int, List String, List (List Int) )",
                        "( List.sort [ 5, 3, 9, 1, 3, -2, 0 ], List.sort [ \"pear\", \"apple\", \"Fig\", \"fig\" ], List.sort [ [ 2 ], [], [ 1, 5 ], [ 1 ] ] )"));
        assertEquals("[[], [1], [1, 2], [1, 1, 1]]", eval("List (List Int)",
                "[ List.sort [], List.sort [ 1 ], List.sort [ 2, 1 ], List.sort [ 1, 1, 1 ] ]"));
        assertEquals("[\"b\", \"d\", \"a\", \"c\"]", eval("type alias Person =\n    { name : String, age : Int }\n\n\n", "List String",
                "List.map (\\p -> p.name) (List.sortBy (\\p -> p.age) [ { name = \"a\", age = 30 }, { name = \"b\", age = 20 }"
                        + ", { name = \"c\", age = 30 }, { name = \"d\", age = 20 } ])"));
        assertEquals("[\"b\", \"d\", \"a\", \"c\"]", eval("List String",
                "List.map Tuple.second (List.sortWith (\\a b -> compare (Tuple.first b) (Tuple.first a))"
                        + " [ ( 1, \"a\" ), ( 2, \"b\" ), ( 1, \"c\" ), ( 2, \"d\" ) ])"));
    }

    @Test
    void operatorsAsFunctions() {
        assertEquals("( [6, 24, 7, 3, 32], [True, False], ( [1, 2], \"ab\" ) )", eval("( List Int, List Bool, ( List Int, String ) )",
                "( [ List.foldl (+) 0 [ 1, 2, 3 ], List.foldl (*) 1 [ 2, 3, 4 ], (-) 10 3, (//) 7 2, (^) 2 5 ]"
                        + ", List.map2 (<) [ 1, 5 ] [ 2, 2 ], ( List.foldr (::) [] [ 1, 2 ], List.foldr (++) \"\" [ \"a\", \"b\" ] ) )"));
        assertTrue(diagnostic("main : Int\nmain =\n    (/) 1 2\n").contains("ELCI2001: Float division is unsupported; use (//)"));
    }

    @Test
    void typesNothingDeterminesNeedNoAnnotation() {
        // As in Elm: no value of the element type exists, so any representation serves.
        assertEquals("[True, True, True, True, True]", eval("List Bool",
                "[ [] == [], Nothing == Nothing, List.sort [] == [], List.maximum [] == Nothing, List.length [] == 0 ]"));
        assertEquals("5", eval("Int", "Result.withDefault 0 (Ok 5)"));
    }

    @Test
    void stringLiteralsNeverReadAsSymbols() {
        assertEquals("[\"-1\", \"(\", \")\", \"of\", \"then\", \"::\", \"++\"]",
                eval("List String", "[ \"-\" ++ \"1\", \"(\", \")\", \"of\", \"then\", \"::\", \"++\" ]"));
        assertEquals("1", eval("Int", "case \"of\" of\n        \"of\" ->\n            1\n\n        _ ->\n            0"));
    }

    @Test
    void ownDeclarationsShadowThePrelude() {
        var result = compile(Map.of("Main", """
                module Main exposing (main)


                not : Int -> Int
                not x =
                    x * 2


                max : Int
                max =
                    99


                main : List Int
                main =
                    [ not 21, max, Basics.max 1 2, if Basics.not False then 1 else 0 ]
                """));
        var evaluation = JulcVm.create().evaluate(result.program(), result.target().ledgerTarget());
        assertEquals("[42, 99, 2, 1]", ElciValues.show(((EvalResult.Success) evaluation).resultTerm(), result.pirType(), result.namedTypes()));
    }

    private static final String UTILS = """
            module Utils exposing (clamp, helper, max)


            clamp : Int -> Int -> Int -> Int
            clamp x low high =
                Basics.clamp low high x


            max : Int -> Int
            max x =
                x + 1000


            helper : Int
            helper =
                1
            """;

    private static String run(Map<String, String> sources) {
        var result = compile(sources);
        var evaluation = JulcVm.create().evaluate(result.program(), result.target().ledgerTarget());
        return ElciValues.show(((EvalResult.Success) evaluation).resultTerm(), result.pirType(), result.namedTypes());
    }

    @Test
    void importsFollowElmPrecedence() {
        // A named exposure takes precedence over the prelude.
        assertEquals("3", run(Map.of("Utils", UTILS, "Main",
                "module Main exposing (main)\n\nimport Utils exposing (clamp)\n\n\nmain : Int\nmain =\n    clamp 5 0 3\n")));
        // exposing (..) and the prelude both provide max: ambiguous only where it is used.
        assertEquals("3", run(Map.of("Utils", UTILS, "Main",
                "module Main exposing (main)\n\nimport Utils exposing (..)\n\n\nmain : Int\nmain =\n    helper + Basics.max 1 2\n")));
        assertEquals("8:5: ELCI2102: max is ambiguous: it could be Basics.max or Utils.max. Write the one you mean, like"
                        + " Basics.max, or stop exposing the other",
                diagnostic(Map.of("Utils", UTILS, "Main",
                        "module Main exposing (main)\n\nimport Utils exposing (..)\n\n\nmain : Int\nmain =\n    max 1\n")));
        assertEquals("3:1: ELCI2104: The alias String names a core module that every module imports; choose another alias for Utils",
                diagnostic(Map.of("Utils", UTILS, "Main",
                        "module Main exposing (main)\n\nimport Utils as String\n\n\nmain : Int\nmain =\n    1\n")));
        // Nor any other core module, or a module of the project: their names would merge.
        assertEquals("3:1: ELCI2104: The alias Interval names Elci's core module Interval; choose another alias for Utils",
                diagnostic(Map.of("Utils", UTILS, "Main",
                        "module Main exposing (main)\n\nimport Utils as Interval\n\n\nmain : Int\nmain =\n    1\n")));
        assertEquals("3:1: ELCI2104: The alias Other names this project's module Other; choose another alias for Utils",
                diagnostic(Map.of("Utils", UTILS, "Other", "module Other exposing (x)\n\n\nx : Int\nx =\n    1\n", "Main",
                        "module Main exposing (main)\n\nimport Utils as Other\n\n\nmain : Int\nmain =\n    1\n")));
    }

    @Test
    void coreModuleNamesAreReserved() {
        assertTrue(diagnostic(Map.of("String", "module String exposing (x)\n\n\nx : Int\nx =\n    1\n",
                        "Main", "module Main exposing (main)\n\n\nmain : Int\nmain =\n    1\n"))
                .contains("ELCI2104: The module name String is taken by Elci's core library; rename this module"));
        assertTrue(diagnostic(Map.of("Set", "module Set exposing (x)\n\n\nx : Int\nx =\n    1\n",
                        "Main", "module Main exposing (main)\n\n\nmain : Int\nmain =\n    1\n"))
                .contains("ELCI2104: The module name Set is reserved for Elci's core library; rename this module"));
        var mapped = assertThrows(IllegalArgumentException.class, () -> compile(
                Map.of("Main", "module Main exposing (main)\n\n\nmain : Int\nmain =\n    1\n"),
                Map.of("Value", "org.julclang.stdlib.lib.ValuesLib")));
        assertTrue(mapped.getMessage().startsWith("ELCI4001: [java-modules] may not map Value"), mapped.getMessage());
    }

    @Test
    void comparableDiagnostics() {
        assertEquals("6:10: ELCI2003: The operator > compares values of type `a`, but the annotation of `larger` says `a` can"
                        + " be any type. Rename it `comparable` (as in Elm) to require comparable values",
                diagnostic("larger : a -> a -> a\nlarger x y =\n    if x > y then\n        x\n\n    else\n        y\n"));
        assertEquals("6:10: ELCI2003: The operator < needs comparable values, but Bool is not comparable. Comparable types are"
                        + " Int, String, Bytes, byte-string ledger types such as PubKeyHash, and lists and tuples of comparable types",
                diagnostic("main : Bool\nmain =\n    True < False\n"));
        assertTrue(diagnostic("main : Bool\nmain =\n    ( 1, True ) < ( 2, False )\n")
                .contains("but Bool, in ( Int, Bool ), is not comparable"));
        assertTrue(diagnostic("main : List { a : Int }\nmain =\n    List.sort [ { a = 1 } ]\n")
                .contains("`List.sort` needs comparable values, but { a : Int } is not comparable. Comparable types are Int,"));
        assertTrue(diagnostic("main : List { a : Int }\nmain =\n    List.sort [ { a = 1 } ]\n")
                .endsWith("Compare a comparable field instead, or sort with List.sortBy or List.sortWith"));
        // Elm's constrained variables: compappend is comparable and appendable; number is not Elci's.
        assertEquals("[\"ab\", \"abab\"]", eval("""
                smallestJoined : compappend -> compappend -> compappend
                smallestJoined a b =
                    if a < b then
                        a ++ b

                    else
                        b ++ a


                twice : appendable -> appendable
                twice x =
                    x ++ x


                """, "List String", "[ smallestJoined \"b\" \"a\", twice \"ab\" ]"));
        assertTrue(diagnostic("twice : a -> a\ntwice x =\n    x ++ x\n")
                .endsWith("Rename it `appendable` (as in Elm) to require String or List values"));
        assertTrue(diagnostic("twice : appendable -> appendable\ntwice x =\n    x\n\n\nmain : Int\nmain =\n    twice 1\n")
                .contains("ELCI2003: `twice` appends String or List values, not Int"));
        assertEquals("4:1: ELCI2003: The annotation of `total` uses `number`, Elm's type variable for Int or Float. Elci has one"
                        + " number type: write Int",
                diagnostic("total : List number -> number\ntotal xs =\n    List.foldl (+) 0 xs\n"));
    }

    @Test
    void equalityDiagnosticsNameTheFunction() {
        assertTrue(diagnostic("main : Bool\nmain =\n    [ \\x -> x + 1 ] == []\n")
                .contains("ELCI2003: The operator == cannot compare List (Int -> Int) values: Int -> Int is part of them, and"
                        + " functions have no equality"));
    }

    @Test
    void elmNamesElciDoesNotProvide() {
        assertTrue(diagnostic("main : Int\nmain =\n    Debug.log \"x\" 1\n")
                .contains("ELCI2104: Elci has no Debug.log; use Debug.trace message value, which returns value"));
        assertTrue(diagnostic("main : Maybe Int\nmain =\n    List.find (\\x -> x > 1) [ 1, 2 ]\n")
                .contains("ELCI2104: Elci's List module has no find. It has all, any, append, concat,"));
        assertTrue(diagnostic("main : Bytes\nmain =\n    Bytes.fromString \"a\"\n")
                .contains("ELCI2104: Bytes.fromString needs the Bytes module; add `import Bytes`"));
        assertTrue(diagnostic("f : Tuple Int Int -> Int\nf pair =\n    Tuple.first pair\n")
                .contains("ELCI2012: Write tuple types as ( a, b ) or ( a, b, c )"));
        assertTrue(diagnostic("type Choice\n    = Nothing\n    | Just Int\n\n\nmain : Int\nmain =\n    1\n")
                .contains("ELCI2011: Nothing is a constructor of the built-in Maybe type; choose another name"));
    }

    @Test
    void localFunctionsKeepTheEnclosingTypeVariables() {
        assertEquals("( 10, True )", eval("""
                        score : a -> List a -> Int
                        score x xs =
                            let
                                pick yes no =
                                    if List.member x xs then
                                        yes

                                    else
                                        no
                            in
                            pick 10 0


                        check : comparable -> List comparable -> comparable -> Bool
                        check x xs z =
                            let
                                pick yes no =
                                    if [ x ] == xs && [ x ] < [ z ] && x < z && x == x then
                                        yes

                                    else
                                        no
                            in
                            pick True False


                        """, "( Int, Bool )", "( score 3 [ 1, 2, 3 ], check 1 [ 1 ] 2 )"));
    }

    @Test
    void requirementsReachEveryMemberOfARecursiveGroup() {
        String f = "f : comparable -> comparable -> Int -> Bool\nf x y n =\n    if n <= 0 then\n        x < y\n\n    else\n"
                + "        g x y (n - 1)\n\n\n";
        assertTrue(diagnostic(f + "g : a -> a -> Int -> Bool\ng x y n =\n    f x y n\n")
                .contains("ELCI2003: The operator < compares values of type `a`, but the annotation of `g` says `a` can be any type"));
        assertTrue(diagnostic(f + "g : comparable -> comparable -> Int -> Bool\ng x y n =\n    f x y n\n\n\nmain : Bool\nmain =\n    g True False 3\n")
                .contains("ELCI2003: `g` needs comparable values, but Bool is not comparable"));
        // A requirement no type can meet is reported at its definition, as in Elm.
        assertTrue(diagnostic("f : Maybe comparable -> Maybe comparable -> Bool\nf x y =\n    x < y\n")
                .startsWith("6:7: ELCI2003: The operator < needs comparable values, but Maybe"));
    }

    /** Elm's accessor functions, .field, and field access on a qualified value. */
    @Test
    void accessorsAndQualifiedFields() {
        assertEquals("[1, 2]", eval("List Int", "List.map .amount [ { amount = 1 }, { amount = 2 } ]"));
        assertEquals("[\"a\"]", eval("List String", "[ { owner = \"a\" } ] |> List.map .owner"));
        assertEquals("6", eval("Int", "let\n        r =\n            { a = { b = 3 } }\n    in\n    r.a.b + (.a >> .b) r"));
        var result = compile(Map.of("Config", "module Config exposing (settings)\n\n\nsettings : { amount : Int }\nsettings =\n"
                        + "    { amount = 7 }\n",
                "Main", "module Main exposing (main)\n\nimport Config\n\n\nmain : Int\nmain =\n    Config.settings.amount + 1\n"));
        var evaluation = JulcVm.create().evaluate(result.program(), result.target().ledgerTarget());
        assertEquals("8", ElciValues.show(((EvalResult.Success) evaluation).resultTerm(), result.pirType(), result.namedTypes(),
                result.type()));
    }

    @Test
    void stringPatternsAreLiterals() {
        assertEquals("[1, 2, 3, 4, 5, 6, 0]", eval("""
                        classify : String -> Int
                        classify s =
                            case s of
                                "_" ->
                                    1

                                "$record" ->
                                    2

                                "True" ->
                                    3

                                "Nothing" ->
                                    4

                                "[]" ->
                                    5

                                "::" ->
                                    6

                                _ ->
                                    0


                        """, "List Int", "List.map classify [ \"_\", \"$record\", \"True\", \"Nothing\", \"[]\", \"::\", \"abc\" ]"));
        assertTrue(diagnostic("main : Int\nmain =\n    case \"x\" of\n        \"_\" ->\n            1\n")
                .contains("ELCI2007: This case does not cover every possibility; it is missing `_`"));
    }

    @Test
    void builtInNamesCannotBeRedeclared() {
        assertEquals("4:12: ELCI2011: Maybe is a built-in type; choose another name",
                diagnostic("type alias Maybe a =\n    List a\n"));
        assertEquals("4:12: ELCI2011: String is a built-in type; choose another name",
                diagnostic("type alias String =\n    Int\n"));
        assertTrue(diagnostic(Map.of("Elci", "module Elci exposing (x)\n\n\nx : Int\nx =\n    1\n",
                        "Main", "module Main exposing (main)\n\n\nmain : Int\nmain =\n    1\n"))
                .contains("ELCI2104: The module name Elci is reserved for Elci's core library; rename this module"));
    }

    @Test
    void scopeRanksApplyToTypesAndConstructors() {
        var outcome = "module Trade exposing (Order(..), Outcome(..))\n\n\ntype Order\n    = Buy\n    | Sell\n\n\n"
                + "type Outcome\n    = Ok Int\n    | Rejected\n";
        // exposing (..) against the prelude's Order: ambiguous where the type is used, and located.
        assertEquals("6:1: ELCI2102: The type Order is ambiguous: it could be Basics.Order or Trade.Order. Write the one you"
                        + " mean, like Basics.Order, or stop exposing the other",
                diagnostic(Map.of("Trade", outcome, "Main",
                        "module Main exposing (main)\n\nimport Trade exposing (..)\n\n\nmain : Order\nmain =\n    Buy\n")));
        // A named exposure beats the prelude, for types and constructors.
        assertEquals("Ok 3", run(Map.of("Trade", outcome, "Main",
                "module Main exposing (main)\n\nimport Trade exposing (Outcome(..))\n\n\nmain : Outcome\nmain =\n    Ok 3\n")));
        assertEquals("True", run(Map.of("Trade", outcome, "Main",
                "module Main exposing (main)\n\nimport Trade exposing (Outcome(..))\n\n\nmain : Bool\nmain =\n"
                        + "    Result.withDefault 0 (Result.Ok 1) == 1\n")));
        // A non-root module sees the prelude even where the root module shadows a name.
        assertEquals("( 99, 7 )", run(Map.of("Helper", "module Helper exposing (larger)\n\n\nlarger : Int -> Int -> Int\n"
                        + "larger a b =\n    max a b\n", "Main",
                "module Main exposing (main)\n\nimport Helper\n\n\nmax : Int\nmax =\n    99\n\n\nmain : ( Int, Int )\n"
                        + "main =\n    ( max, Helper.larger 3 7 )\n")));
    }

    @Test
    void libraryConstructorsSharingAnAliasAreAmbiguous() {
        assertTrue(diagnostic(Map.of("Utils", "module Utils exposing (Cred(..))\n\n\ntype Cred\n    = PubKeyCredential Int\n",
                        "Main", "module Main exposing (main)\n\nimport Julc.Ledger as L\nimport Utils as L\n\n\nmain : Int\n"
                                + "main =\n    case L.PubKeyCredential 1 of\n        _ ->\n            1\n"))
                .contains("ELCI2102: L.PubKeyCredential is ambiguous: it could be Julc.Ledger.PubKeyCredential or"
                        + " Utils.PubKeyCredential. Give the imports different aliases"));
    }

    @Test
    void operatorFunctionsAndComposition() {
        assertEquals("[5, 5, 1, 7, 9]", eval("List Int",
                "[ (|>) 4 (\\x -> x + 1), (<|) (\\x -> x + 1) 4, if (&&) True ((==) 1 1) then 1 else 0"
                        + ", ((>>) (\\x -> x + 1) (\\x -> x * 2)) 2 + 1, ((\\x -> x + 1) << (\\x -> x * 2) << (\\x -> x + 1)) 3 ]"));
        assertTrue(diagnostic("main : Int\nmain =\n    1 |> (\\x -> x * 2) <| 3\n")
                .contains("ELCI1002: Mixing (|>) and (<|) needs parentheses: they associate in opposite directions, as in Elm"));
        assertTrue(diagnostic("main : Int\nmain =\n    ((\\x -> x) << (\\x -> x) >> (\\x -> x)) 1\n")
                .contains("ELCI1002: Mixing (<<) and (>>) needs parentheses"));
        // Only decoding needs a known type: an ambiguous type has no values to encode.
        assertEquals("[Constr 0, Constr 0 [1]]", eval("List Data", "[ Data.encode Nothing, Data.encode (Ok 1) ]"));
    }

    @Test
    void importHints() {
        assertTrue(diagnostic("main : Bool\nmain =\n    Bytes.lessThan Bytes.empty Bytes.empty\n")
                .contains("ELCI2104: Elci has no Bytes.lessThan; bytes are comparable: write a < b"));
        assertTrue(diagnostic(Map.of("Main", "module Main exposing (main)\n\nimport Map as M\n\n\nmain : Maybe Int\nmain =\n"
                        + "    Map.get 1 M.empty\n"))
                .contains("ELCI2104: Map is imported as M; write M.get"));
        assertTrue(diagnostic("main : Nope\nmain =\n    1\n")
                .startsWith("4:1: ELCI2012: Type is not declared or imported in this module: Nope"));
    }

    @Test
    void validatorsUseThePreludeAndOrderingHelpers() throws Exception {
        Files.writeString(project.resolve("elci.toml"), """
                [project]
                source-directories = ["src"]
                target = "plutus-v3-pv11-uplc-1.1.0"
                julc-version = "0.1.0-gb-5a3378b"

                [validators.ranked]
                boundary = "julc-strict-v1"
                spend = "Ranked.spend"
                """);
        Files.createDirectories(project.resolve("src"));
        Files.createDirectories(project.resolve("tests"));
        Files.writeString(project.resolve("src/Ranked.elm"), """
                module Ranked exposing (spend)

                import Julc.Ledger exposing (ScriptContext)


                spend : List ( Int, String ) -> Int -> ScriptContext -> Bool
                spend scores floor ctx =
                    let
                        best =
                            List.maximum (List.map Tuple.first scores)

                        signers =
                            List.sort ctx.txInfo.signatories
                    in
                    (Maybe.withDefault 0 best >= floor)
                        && (List.sortBy Tuple.second scores |> List.head |> Maybe.map (\\pair -> pair > ( floor, "" )) |> Maybe.withDefault False)
                        && (List.minimum signers == List.head signers)
                        && (compare (List.length signers) 0 /= LT)
                """);
        Files.writeString(project.resolve("tests/RankedTest.elm"), """
                module RankedTest exposing (acceptsAHighScore, rejectsALowScore)

                import Julc.Data as Data
                import Scenario exposing (Scenario)


                run : Int -> Scenario
                run floor =
                    Scenario.spend "ranked" (Scenario.outRef "ranked" 0) (Data.encode [ ( 7, "b" ), ( 3, "a" ) ])
                        |> Scenario.redeemer (Data.encode floor)
                        |> Scenario.signedBy (Scenario.key "alice")
                        |> Scenario.signedBy (Scenario.key "bob")


                acceptsAHighScore : Scenario
                acceptsAHighScore =
                    run 2


                rejectsALowScore : Scenario
                rejectsALowScore =
                    run 9 |> Scenario.expectFailure
                """);
        var result = NamedValidatorCliTest.run("test", project.toString());
        assertEquals(0, result.status(), result.out() + result.err());
        assertTrue(result.out().contains("2 passed, 0 failed"), result.out());
    }

    @Test
    void secondReviewRegressions() {
        // ++ on `List a` constrains nothing; only a bare variable needs `appendable`.
        assertEquals("[1, 1, 2, 3]", eval("""
                        dup : a -> List a
                        dup x =
                            [ x ] ++ [ x ]


                        joinAll : List (List a) -> List a
                        joinAll lists =
                            case lists of
                                [] ->
                                    []

                                xs :: rest ->
                                    xs ++ joinAll rest


                        """, "List Int", "dup 1 ++ joinAll [ [ 2 ], [ 3 ] ]"));
        // `Maybe a` can never be ordered: said directly, not "rename it comparable".
        assertTrue(diagnostic("bad : Maybe a -> Maybe a -> Bool\nbad x y =\n    x < y\n")
                .contains("ELCI2003: The operator < needs comparable values, but Maybe a is not comparable"));
        // An unknown type passed into a generic function is reported at the outermost call.
        assertEquals("13:5: ELCI3001: `count` is used here at a type that is not known (Map a b -> Int); add a type"
                        + " annotation, for example to a let binding",
                diagnostic(Map.of("Main", "module Main exposing (main)\n\nimport Map\n\n\ncount : Map.Map k v -> Int\n"
                        + "count m =\n    Map.size m\n\n\nmain : Int\nmain =\n    count Map.empty\n")));
        // Polymorphic recursion in a type no longer overflows the equality check.
        assertEquals("1", eval("type Nested a\n    = Leaf a\n    | Node (Nested (List a))\n\n\nsame : Nested a -> Nested a -> Bool\n"
                + "same x y =\n    x == y\n\n\n", "Int", "1"));
        // A variable another member of a recursive group quantifies is ambiguous here.
        assertEquals("0", eval("f : Int -> Int\nf n =\n    if n == 0 then\n        0\n\n    else\n        g [] n\n\n\n"
                + "g : List a -> Int -> Int\ng xs n =\n    f (n - 1) + List.length xs\n\n\n", "Int", "f 3"));
        // The naming rule does not depend on declaration order within a recursive group.
        String f = "f : comparable -> List comparable -> Int\nf x xs =\n    case xs of\n        [] ->\n            0\n\n"
                + "        y :: rest ->\n            g x rest\n\n\n";
        String g = "g : a -> List a -> Int\ng x xs =\n    f x xs\n\n\n";
        assertTrue(diagnostic(f + g).contains("says `a` can be any type. Rename it `comparable`"));
        assertTrue(diagnostic(g + f).contains("says `a` can be any type. Rename it `comparable`"));
        assertTrue(diagnostic(Map.of("Main", "module Main exposing (main)\n\nimport Julc.Data as D\n\n\nmain : Data\nmain =\n"
                        + "    Data.encode 1\n")).contains("ELCI2104: Julc.Data is imported as D; write D.encode"));
        // A user constructor named Tuple is an ordinary constructor.
        assertEquals("3", eval("type Pair\n    = Tuple Int Int\n\n\n", "Int",
                "case Tuple 1 2 of\n        Tuple a b ->\n            a + b"));
    }

    @Test
    void dictFollowsElm() {
        assertEquals("( [( 1, \"a\" ), ( 2, \"b\" ), ( 3, \"c\" )], [Just \"b\", Nothing], [True, False] )",
                eval("( List ( Int, String ), List (Maybe String), List Bool )",
                        "( Dict.toList (Dict.fromList [ ( 3, \"c\" ), ( 1, \"x\" ), ( 2, \"b\" ), ( 1, \"a\" ) ])"
                                + ", [ Dict.get 2 (Dict.fromList [ ( 2, \"b\" ) ]), Dict.get 9 (Dict.fromList [ ( 2, \"b\" ) ]) ]"
                                + ", [ Dict.member 2 (Dict.singleton 2 \"b\"), Dict.isEmpty (Dict.singleton 2 \"b\") ] )"));
        assertEquals("[[( 1, 10 ), ( 2, 20 ), ( 3, 30 )], [( 1, 10 ), ( 3, 30 )], [( 1, 11 ), ( 2, 20 )], [( 2, 20 ), ( 3, 30 )]]",
                eval("List (List ( Int, Int ))",
                        "[ Dict.toList (Dict.insert 2 20 (Dict.fromList [ ( 3, 30 ), ( 1, 10 ) ]))"
                                + ", Dict.toList (Dict.remove 2 (Dict.fromList [ ( 1, 10 ), ( 2, 20 ), ( 3, 30 ) ]))"
                                + ", Dict.toList (Dict.update 1 (Maybe.map (\\v -> v + 1)) (Dict.fromList [ ( 1, 10 ), ( 2, 20 ) ]))"
                                + ", Dict.toList (Dict.update 1 (\\_ -> Nothing) (Dict.fromList [ ( 1, 10 ), ( 2, 20 ), ( 3, 30 ) ])) ]"));
        // union prefers the left; intersect keeps the left's values; diff keeps the left's other keys.
        assertEquals("[[( 1, \"L\" ), ( 2, \"L\" ), ( 3, \"R\" )], [( 2, \"L\" )], [( 1, \"L\" )]]", eval("""
                        left : Dict Int String
                        left =
                            Dict.fromList [ ( 1, "L" ), ( 2, "L" ) ]


                        right : Dict Int String
                        right =
                            Dict.fromList [ ( 2, "R" ), ( 3, "R" ) ]


                        """, "List (List ( Int, String ))",
                "[ Dict.toList (Dict.union left right), Dict.toList (Dict.intersect left right), Dict.toList (Dict.diff left right) ]"));
        // Strings order by their UTF-8 bytes: "Z" before "a", "é" after "z".
        assertEquals("[\"Z\", \"a\", \"z\", \"é\"]", eval("List String",
                "Dict.keys (Dict.fromList [ ( \"é\", 1 ), ( \"z\", 2 ), ( \"a\", 3 ), ( \"Z\", 4 ) ])"));
        assertEquals("( [\"apple\", \"fig\"], 3, [True, False] )", eval("( List String, Int, List Bool )",
                "( Dict.keys (Dict.fromList [ ( \"fig\", 1 ), ( \"apple\", 2 ) ])"
                        + ", Dict.foldl (\\_ v acc -> v + acc) 0 (Dict.fromList [ ( \"fig\", 1 ), ( \"apple\", 2 ) ])"
                        + ", [ Dict.fromList [ ( 2, 0 ), ( 1, 0 ) ] == Dict.fromList [ ( 1, 0 ), ( 2, 0 ) ]"
                        + ", Dict.fromList [ ( 1, 0 ) ] == Dict.empty ] )"));
    }

    @Test
    void mapKeepsItsOrderAndSemantics() {
        // Newest first: fromList inserts in list order, so a repeated key takes its last value.
        assertEquals("( [( 3, 40 ), ( 1, 30 ), ( 2, 20 )], [( 5, 50 ), ( 2, 20 ), ( 1, 10 )], [( 3, 33 ), ( 2, 22 ), ( 1, 11 )] )",
                eval("( List ( Int, Int ), List ( Int, Int ), List ( Int, Int ) )",
                        "( Map.toList (Map.fromList [ ( 1, 10 ), ( 2, 20 ), ( 1, 30 ), ( 3, 40 ) ])"
                                + ", Map.toList (Map.insert 5 50 (Map.fromList [ ( 1, 10 ), ( 2, 20 ) ]))"
                                + ", Map.toList (Map.map (\\k v -> v + k) (Map.fromList [ ( 1, 10 ), ( 2, 20 ), ( 3, 30 ) ])) )"));
        assertEquals("( [Just \"b\", Nothing], [2, 1] )", eval("( List (Maybe String), List Int )",
                "( [ Map.get \"k2\" (Map.fromList [ ( \"k1\", \"a\" ), ( \"k2\", \"b\" ) ]), Map.get \"k3\" (Map.fromList [ ( \"k1\", \"a\" ) ]) ]"
                        + ", Map.foldr (\\k _ acc -> k :: acc) [] (Map.fromList [ ( 1, 0 ), ( 2, 0 ) ]) )"));
    }

    @Test
    void dictsDecodeOnlyWithAscendingKeys() {
        // Map.insert puts the new key first, so these build maps in a chosen order.
        assertEquals("[( 1, 10 ), ( 2, 20 )]", eval("List ( Int, Int )",
                "Dict.toList (Data.decode (Data.encode (Map.insert 1 10 (Map.insert 2 20 Map.empty))))"));
        assertEquals("failed", eval("List ( Int, Int )",
                "Dict.toList (Data.decode (Data.encode (Map.insert 2 20 (Map.insert 1 10 Map.empty))))"));
        // Repeated keys can only come from outside: apply raw Data.
        var compiled = compile(Map.of("Main", HEADER + "main : Data -> Int\nmain raw =\n    let\n        dict : Dict Int Int\n"
                + "        dict =\n            Data.decode raw\n    in\n    Dict.size dict\n"));
        var program = compiled.program();
        java.util.function.Function<List<Integer>, EvalResult> apply = keys -> JulcVm.create().evaluate(
                program.applyParams(new org.julclang.core.PlutusData.MapData(keys.stream().map(k -> new org.julclang.core.PlutusData.Pair(
                        new org.julclang.core.PlutusData.IntData(java.math.BigInteger.valueOf(k)),
                        new org.julclang.core.PlutusData.IntData(java.math.BigInteger.ONE))).toList())),
                compiled.target().ledgerTarget());
        assertInstanceOf(EvalResult.Success.class, apply.apply(List.of(1, 2, 3)));
        assertInstanceOf(EvalResult.Failure.class, apply.apply(List.of(1, 1)));
        assertInstanceOf(EvalResult.Failure.class, apply.apply(List.of(2, 1)));
        String book = "type alias Book =\n    { name : String, votes : Dict String Int }\n\n\n";
        assertEquals("1", eval(book, "Int", """
                let
                        raw : List { name : String, votes : Map String Int }
                        raw =
                            [ { name = "x", votes = Map.insert "a" 1 (Map.insert "b" 2 Map.empty) } ]

                        books : List Book
                        books =
                            Data.decode (Data.encode raw)
                    in
                    List.length books"""));
        assertEquals("failed", eval(book, "Int", """
                let
                        raw : List { name : String, votes : Map String Int }
                        raw =
                            [ { name = "x", votes = Map.insert "b" 2 (Map.insert "a" 1 Map.empty) } ]

                        books : List Book
                        books =
                            Data.decode (Data.encode raw)
                    in
                    List.length books"""));
    }

    @Test
    void validatorsRejectUnsortedDictDatums() throws Exception {
        Files.writeString(project.resolve("elci.toml"), """
                [project]
                source-directories = ["src"]
                target = "plutus-v3-pv11-uplc-1.1.0"
                julc-version = "0.1.0-gb-5a3378b"

                [validators.ballot]
                boundary = "julc-strict-v1"
                spend = "Ballot.spend"
                """);
        Files.createDirectories(project.resolve("src"));
        Files.createDirectories(project.resolve("tests"));
        Files.writeString(project.resolve("src/Ballot.elm"), """
                module Ballot exposing (spend)

                import Dict exposing (Dict)
                import Julc.Ledger exposing (ScriptContext)


                {-| Accept a voter who has not voted. -}
                spend : Dict String Int -> String -> ScriptContext -> Bool
                spend votes voter _ =
                    not (Dict.member voter votes)
                """);
        Files.writeString(project.resolve("tests/BallotTest.elm"), """
                module BallotTest exposing (newVoterAccepted, repeatVoterRejected, unsortedDatumRejected)

                import Julc.Data as Data
                import Map
                import Scenario exposing (Scenario)


                vote : Data.Data -> String -> Scenario
                vote datum voter =
                    Scenario.spend "ballot" (Scenario.outRef "ballot" 0) datum
                        |> Scenario.redeemer (Data.encode voter)


                sorted : Data.Data
                sorted =
                    Data.encode (Map.insert "alice" 1 (Map.insert "carol" 1 Map.empty))


                newVoterAccepted : Scenario
                newVoterAccepted =
                    vote sorted "bob"


                repeatVoterRejected : Scenario
                repeatVoterRejected =
                    vote sorted "carol" |> Scenario.expectFailure


                {-| Out of order, carol hides behind dave from a scan that stops at the first larger key: without
                the check at decoding, carol would vote twice.
                -}
                unsortedDatumRejected : Scenario
                unsortedDatumRejected =
                    vote (Data.encode (Map.insert "dave" 1 (Map.insert "carol" 1 Map.empty))) "carol" |> Scenario.expectFailure
                """);
        var result = NamedValidatorCliTest.run("test", project.toString());
        assertEquals(0, result.status(), result.out() + result.err());
        assertTrue(result.out().contains("3 passed, 0 failed"), result.out());
    }

    @Test
    void mapsAndDictsNest() {
        assertEquals("( [2, 1], Just 20, True )", eval("( List Int, Maybe Int, Bool )", """
                let
                        nested : Map Int (Map String Int)
                        nested =
                            Map.fromList [ ( 1, Map.fromList [ ( "x", 10 ) ] ), ( 2, Map.fromList [ ( "y", 20 ) ] ) ]
                    in
                    ( Map.keys nested, Map.get 2 nested |> Maybe.andThen (Map.get "y"), Map.member 1 nested )"""));
        assertEquals("( Just \"b\", False, 1 )", eval("( Maybe String, Bool, Int )", """
                let
                        byMap : Map (Map Int Int) String
                        byMap =
                            Map.fromList [ ( Map.singleton 1 1, "a" ), ( Map.singleton 2 2, "b" ) ]
                    in
                    ( Map.get (Map.singleton 2 2) byMap, Map.member (Map.singleton 3 3) byMap, Map.size (Map.remove (Map.singleton 1 1) byMap) )"""));
        assertEquals("( 2, [1, 2], 5 )", eval("( Int, List Int, Int )", """
                let
                        nested : Dict Int (Dict String Int)
                        nested =
                            Dict.insert 2 (Dict.singleton "b" 2) (Dict.singleton 1 (Dict.singleton "a" 5))
                    in
                    ( Dict.size nested, Dict.keys nested, Maybe.withDefault 0 (Dict.get 1 nested |> Maybe.andThen (Dict.get "a")) )"""));
    }

    @Test
    void dictsInsideJulcOptionalsAreChecked() {
        var compiled = compile(Map.of("Main", HEADER + "main : Data -> Int\nmain raw =\n    let\n        dict : Maybe (Dict Int Int)\n"
                + "        dict =\n            Data.fromOptional (Data.decode raw)\n    in\n"
                + "    Maybe.withDefault 0 (Maybe.map Dict.size dict)\n"));
        java.util.function.Function<List<Integer>, EvalResult> apply = keys -> JulcVm.create().evaluate(
                compiled.program().applyParams(new org.julclang.core.PlutusData.ConstrData(0, List.of(
                        new org.julclang.core.PlutusData.MapData(keys.stream().map(k -> new org.julclang.core.PlutusData.Pair(
                                new org.julclang.core.PlutusData.IntData(java.math.BigInteger.valueOf(k)),
                                new org.julclang.core.PlutusData.IntData(java.math.BigInteger.ONE))).toList())))),
                compiled.target().ledgerTarget());
        assertInstanceOf(EvalResult.Success.class, apply.apply(List.of(1, 2)));
        assertInstanceOf(EvalResult.Failure.class, apply.apply(List.of(2, 1)));
        assertInstanceOf(EvalResult.Failure.class, apply.apply(List.of(1, 1)));
    }

    @Test
    void decodedMapsWithRepeatedKeysLoseEveryBindingOnRemove() {
        var compiled = compile(Map.of("Main", HEADER + "main : Data -> ( List ( Int, Int ), Bool, List ( Int, Int ) )\nmain raw =\n"
                + "    let\n        m : Map Int Int\n        m =\n            Data.decode raw\n    in\n"
                + "    ( Map.toList (Map.remove 1 m), Map.member 1 (Map.remove 1 m), Map.toList (Map.insert 1 99 m) )\n"));
        var raw = new org.julclang.core.PlutusData.MapData(List.of(
                new org.julclang.core.PlutusData.Pair(new org.julclang.core.PlutusData.IntData(java.math.BigInteger.ONE),
                        new org.julclang.core.PlutusData.IntData(java.math.BigInteger.TEN)),
                new org.julclang.core.PlutusData.Pair(new org.julclang.core.PlutusData.IntData(java.math.BigInteger.TWO),
                        new org.julclang.core.PlutusData.IntData(java.math.BigInteger.valueOf(20))),
                new org.julclang.core.PlutusData.Pair(new org.julclang.core.PlutusData.IntData(java.math.BigInteger.ONE),
                        new org.julclang.core.PlutusData.IntData(java.math.BigInteger.valueOf(30)))));
        var result = assertInstanceOf(EvalResult.Success.class,
                JulcVm.create().evaluate(compiled.program().applyParams(raw), compiled.target().ledgerTarget()));
        // ( [( 2, 20 )], False, [( 1, 99 ), ( 2, 20 )] ), shown as Data.
        assertEquals("Constr 0 [[Constr 0 [2, 20]], Constr 0, [Constr 0 [1, 99], Constr 0 [2, 20]]]",
                ElciValues.show(result.resultTerm(), compiled.pirType(), compiled.namedTypes()));
    }

    @Test
    void javaLibrariesCannotCarryDicts() {
        String library = """
                package example;
                import org.julclang.core.PlutusData;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class Cast {
                    @SuppressWarnings("unchecked")
                    public static <T> T cast(PlutusData data) { return (T) data; }
                }
                """;
        var d = assertThrows(Diagnostic.class, () -> new ElciCompiler().compileProject(Map.of("Main", HEADER.replace(
                        "import Map exposing (Map)\n", "import Map exposing (Map)\nimport Custom.Cast as Cast\n")
                        + "main : Int\nmain =\n    let\n        dict : Dict Int Int\n        dict =\n"
                        + "            Cast.cast (Data.encode (Map.insert 2 1 (Map.insert 1 2 Map.empty)))\n    in\n"
                        + "    Dict.size dict\n"), "Main", "main", FrontendProgram.Purpose.FUNCTION,
                Map.of("Custom.Cast", "example.Cast"), new CompilerOptions(), Map.of("example.Cast", library)));
        assertEquals("ELCI3003", d.code());
        assertTrue(d.getMessage().contains("contains a Dict, which a Java library cannot keep sorted"), d.getMessage());
    }

    @Test
    void dictBoundariesEverywhere() throws Exception {
        Files.writeString(project.resolve("elci.toml"), """
                [project]
                source-directories = ["src"]
                target = "plutus-v3-pv11-uplc-1.1.0"
                julc-version = "0.1.0-gb-5a3378b"

                [validators.caps]
                boundary = "julc-strict-v1"
                parameters = ["limits"]
                spend = "Caps.spend"
                spend-datum = "optional"
                mint = "Caps.mint"
                """);
        Files.createDirectories(project.resolve("src"));
        Files.createDirectories(project.resolve("tests"));
        Files.writeString(project.resolve("src/Caps.elm"), """
                module Caps exposing (mint, spend)

                import Dict exposing (Dict)
                import Julc.Ledger exposing (ScriptContext)


                spend : Dict Int Int -> Maybe (Dict Int Int) -> Int -> ScriptContext -> Bool
                spend limits held key _ =
                    Dict.member key limits && Maybe.withDefault True (Maybe.map (Dict.member key) held)


                mint : Dict Int Int -> Dict String Int -> ScriptContext -> Bool
                mint limits request _ =
                    Dict.size request <= Dict.size limits
                """);
        Files.writeString(project.resolve("tests/CapsTest.elm"), """
                module CapsTest exposing (mintAccepted, mintUnsortedRedeemerRejected, spendAccepted, spendUnsortedDatumRejected, unsortedParameterRejected)

                import Julc.Data as Data
                import Map
                import Scenario exposing (Scenario)


                sorted : Data.Data
                sorted =
                    Data.encode (Map.insert 1 1 (Map.insert 2 2 Map.empty))


                unsorted : Data.Data
                unsorted =
                    Data.encode (Map.insert 2 2 (Map.insert 1 1 Map.empty))


                spendWith : Data.Data -> Data.Data -> Scenario
                spendWith limits datum =
                    Scenario.spend "caps" (Scenario.outRef "caps" 0) datum
                        |> Scenario.parameters [ limits ]
                        |> Scenario.redeemer (Data.encode 2)


                spendAccepted : Scenario
                spendAccepted =
                    spendWith sorted sorted


                spendUnsortedDatumRejected : Scenario
                spendUnsortedDatumRejected =
                    spendWith sorted unsorted |> Scenario.expectFailure


                unsortedParameterRejected : Scenario
                unsortedParameterRejected =
                    spendWith unsorted sorted |> Scenario.expectFailure


                mintWith : Data.Data -> Scenario
                mintWith request =
                    Scenario.mint "caps"
                        |> Scenario.parameters [ sorted ]
                        |> Scenario.redeemer request


                mintAccepted : Scenario
                mintAccepted =
                    mintWith (Data.encode (Map.insert "a" 1 (Map.insert "b" 2 Map.empty)))


                mintUnsortedRedeemerRejected : Scenario
                mintUnsortedRedeemerRejected =
                    mintWith (Data.encode (Map.insert "b" 2 (Map.insert "a" 1 Map.empty))) |> Scenario.expectFailure
                """);
        var result = NamedValidatorCliTest.run("test", project.toString());
        assertEquals(0, result.status(), result.out() + result.err());
        assertTrue(result.out().contains("5 passed, 0 failed"), result.out());
        assertEquals(0, NamedValidatorCliTest.run("build", project.toString()).status());
        var sortedParam = "limits={\"map\":[{\"k\":{\"int\":1},\"v\":{\"int\":1}},{\"k\":{\"int\":2},\"v\":{\"int\":2}}]}";
        assertEquals(0, NamedValidatorCliTest.run("apply", project.toString(), "--validator", "caps", "--param", sortedParam).status());
        var unsortedApply = NamedValidatorCliTest.run("apply", project.toString(), "--validator", "caps", "--param",
                "limits={\"map\":[{\"k\":{\"int\":2},\"v\":{\"int\":2}},{\"k\":{\"int\":1},\"v\":{\"int\":1}}]}");
        assertEquals(1, unsortedApply.status());
        assertTrue(unsortedApply.err().contains("ELCI4001: parameter limits has a Dict whose keys are not strictly ascending"),
                unsortedApply.err());
    }

    @Test
    void dictsFromDataNeedComparableKeys() {
        assertTrue(diagnostic(Map.of("Main", HEADER + "main : Data -> Int\nmain raw =\n    let\n        dict : Dict Bool Int\n"
                        + "        dict =\n            Data.decode raw\n    in\n    Dict.size dict\n"))
                .contains("ELCI2003: A Dict decoded from Data needs comparable keys, to check that they are in order; Bool is not"
                        + " comparable. Use a Map instead"));
    }

    private static final String ASSETS = """
            gold : PolicyId
            gold =
                PolicyId (Bytes.fromString "gold")


            coin : TokenName
            coin =
                TokenName (Bytes.fromString "coin")


            """;

    @Test
    void valueFollowsTheLedger() {
        assertEquals("( [5000000, 3, 0, -5000000], [True, False, True, True, False] )", eval(ASSETS, "( List Int, List Bool )", """
                let
                        paid =
                            Value.add (Value.lovelace 5000000) (Value.singleton gold coin 3)
                    in
                    ( [ Value.lovelaceOf paid, Value.quantityOf gold coin paid, Value.lovelaceOf (Value.withoutLovelace paid), Value.lovelaceOf (Value.negate paid) ]
                    , [ Value.geq paid (Value.lovelace 1000000), Value.geq (Value.lovelace 1) paid, paid == Value.fromList [ ( gold, coin, 3 ), ( PolicyId Bytes.empty, TokenName Bytes.empty, 5000000 ) ], Value.isZero (Value.subtract paid paid), Value.isZero paid ]
                    )"""));
        // A mint that burns: geq is total with negative quantities, and positive and negative split it.
        assertEquals("( [False, True, True], [3, -2] )", eval(ASSETS, "( List Bool, List Int )", """
                let
                        mint =
                            Value.add (Value.singleton gold coin 3) (Value.singleton gold (TokenName (Bytes.fromString "old")) -2)
                    in
                    ( [ Value.geq mint Value.zero, Value.geq (Value.positive mint) Value.zero, Value.leq (Value.negative mint) Value.zero ]
                    , List.map (\\( _, _, q ) -> q) (Value.toList mint)
                    )"""));
        assertEquals("( 2, Just 3, 0 )", eval(ASSETS, "( Int, Maybe Int, Int )", """
                let
                        paid =
                            Value.add (Value.lovelace 5) (Value.singleton gold coin 3)
                    in
                    ( List.length (Value.policies paid), Dict.get coin (Value.tokens gold paid), Value.quantityOf gold coin (Value.insert gold coin 0 paid) )"""));
        assertEquals("Value.fromList [( #(empty), #(empty), 5 ), ( #676f6c64, #636f696e, 3 )]", eval(ASSETS, "Value",
                "Value.add (Value.singleton gold coin 3) (Value.lovelace 5)"));
        // Inside a constructor the Value is recognised by its representation.
        assertEquals("( Just (Value.fromList [( #(empty), #(empty), 5 )]), Box (Value.fromList [( #676f6c64, #636f696e, 3 )]) 1 )",
                eval(ASSETS + "\n\ntype Box\n    = Box Value Int\n\n\n", "( Maybe Value, Box )",
                        "( Just (Value.lovelace 5), Box (Value.singleton gold coin 3) 1 )"));
    }

    @Test
    void valuesDecodeOnlyInCanonicalForm() {
        var compiled = compile(Map.of("Main", HEADER + "main : Data -> Int\nmain raw =\n    let\n        value : Value\n"
                + "        value =\n            Data.decode raw\n    in\n    Value.lovelaceOf value\n"));
        java.util.function.Function<org.julclang.core.PlutusData, EvalResult> apply = raw -> JulcVm.create().evaluate(
                compiled.program().applyParams(raw), compiled.target().ledgerTarget());
        java.util.function.BiFunction<String, Long, org.julclang.core.PlutusData.Pair> token = (name, quantity) ->
                new org.julclang.core.PlutusData.Pair(new org.julclang.core.PlutusData.BytesData(name.getBytes()),
                        new org.julclang.core.PlutusData.IntData(java.math.BigInteger.valueOf(quantity)));
        java.util.function.BiFunction<String, List<org.julclang.core.PlutusData.Pair>, org.julclang.core.PlutusData.Pair> policy =
                (name, tokens) -> new org.julclang.core.PlutusData.Pair(new org.julclang.core.PlutusData.BytesData(name.getBytes()),
                        new org.julclang.core.PlutusData.MapData(tokens));
        var canonical = new org.julclang.core.PlutusData.MapData(List.of(policy.apply("", List.of(token.apply("", 7L))),
                policy.apply("a", List.of(token.apply("x", 1L), token.apply("y", 2L)))));
        assertInstanceOf(EvalResult.Success.class, apply.apply(canonical));
        // Unsorted policies, unsorted tokens, a zero quantity and an empty token map are all rejected.
        assertInstanceOf(EvalResult.Failure.class, apply.apply(new org.julclang.core.PlutusData.MapData(List.of(
                policy.apply("b", List.of(token.apply("x", 1L))), policy.apply("a", List.of(token.apply("x", 1L)))))));
        assertInstanceOf(EvalResult.Failure.class, apply.apply(new org.julclang.core.PlutusData.MapData(List.of(
                policy.apply("a", List.of(token.apply("y", 1L), token.apply("x", 1L)))))));
        assertInstanceOf(EvalResult.Failure.class, apply.apply(new org.julclang.core.PlutusData.MapData(List.of(
                policy.apply("a", List.of(token.apply("x", 0L)))))));
        assertInstanceOf(EvalResult.Failure.class, apply.apply(new org.julclang.core.PlutusData.MapData(List.of(
                policy.apply("a", List.of())))));
    }

    @Test
    void javaLibrariesCannotReturnValues() {
        var d = assertThrows(Diagnostic.class, () -> new ElciCompiler().compileProject(Map.of("Main", HEADER.replace(
                        "import Map exposing (Map)\n", "import Map exposing (Map)\nimport Julc.Values as Values\n")
                        + "main : Int\nmain =\n    Value.lovelaceOf (Values.add (Value.lovelace 1) (Value.lovelace 2))\n"),
                "Main", "main", FrontendProgram.Purpose.FUNCTION,
                Map.of("Julc.Values", "org.julclang.stdlib.lib.ValuesLib"), new CompilerOptions()));
        assertEquals("ELCI2105", d.code());
        assertTrue(d.getMessage().contains("returns a Value, which Java libraries do not keep in the ledger's canonical order;"
                + " use the core Value module"), d.getMessage());
    }

    @Test
    void validatorsReadOutputsAndMintsAsValues() throws Exception {
        Files.writeString(project.resolve("elci.toml"), """
                [project]
                source-directories = ["src"]
                target = "plutus-v3-pv11-uplc-1.1.0"
                julc-version = "0.1.0-gb-5a3378b"

                [validators.shop]
                boundary = "julc-strict-v1"
                spend = "Shop.spend"
                mint = "Shop.mint"
                """);
        Files.createDirectories(project.resolve("src"));
        Files.createDirectories(project.resolve("tests"));
        Files.writeString(project.resolve("src/Shop.elm"), """
                module Shop exposing (mint, spend)

                import Julc.Ledger exposing (ScriptContext)
                import Value


                {-| The outputs together must pay at least the price. -}
                spend : Int -> () -> ScriptContext -> Bool
                spend price _ ctx =
                    Value.geq (Value.sum (List.map (\\out -> out.value) ctx.txInfo.outputs)) (Value.lovelace price)


                {-| Mint at most one token of each name, and burn nothing. -}
                mint : () -> ScriptContext -> Bool
                mint _ ctx =
                    Value.isZero (Value.negative ctx.txInfo.mint)
                        && List.all (\\( _, _, quantity ) -> quantity == 1) (Value.toList ctx.txInfo.mint)
                """);
        Files.writeString(project.resolve("tests/ShopTest.elm"), """
                module ShopTest exposing (burnRejected, mintOne, mintTwoRejected, paidInFull, underpaidRejected)

                import Bytes
                import Julc.Data as Data
                import Scenario exposing (Scenario)


                buy : Int -> Scenario
                buy paid =
                    Scenario.spend "shop" (Scenario.outRef "shop" 0) (Data.encode 5000000)
                        |> Scenario.redeemer Scenario.unit
                        |> Scenario.output (Scenario.toScript paid)


                paidInFull : Scenario
                paidInFull =
                    buy 5000000


                underpaidRejected : Scenario
                underpaidRejected =
                    buy 4999999 |> Scenario.expectFailure


                minting : Int -> Scenario
                minting quantity =
                    Scenario.mint "shop"
                        |> Scenario.redeemer Scenario.unit
                        |> Scenario.minting (Bytes.fromString "ticket") quantity


                mintOne : Scenario
                mintOne =
                    minting 1


                mintTwoRejected : Scenario
                mintTwoRejected =
                    minting 2 |> Scenario.expectFailure


                burnRejected : Scenario
                burnRejected =
                    minting -1 |> Scenario.expectFailure
                """);
        var result = NamedValidatorCliTest.run("test", project.toString());
        assertEquals(0, result.status(), result.out() + result.err());
        assertTrue(result.out().contains("5 passed, 0 failed"), result.out());
    }

    @Test
    void valueReviewRegressions() {
        // The empty policy may hold a named token; lovelace is only ADA's empty name.
        assertEquals("[0, 5, 7, 7]", eval(ASSETS, "List Int", """
                let
                        odd =
                            Value.singleton (PolicyId Bytes.empty) (TokenName (Bytes.fromString "x")) 5

                        mixed =
                            Value.add odd (Value.lovelace 7)
                    in
                    [ Value.lovelaceOf odd, Value.quantityOf (PolicyId Bytes.empty) (TokenName (Bytes.fromString "x")) odd, Value.lovelaceOf mixed, Value.lovelaceOf (Value.withoutLovelace mixed) + 7 ]"""));
        // The filters keep order and drop emptied policies, without converting.
        assertEquals("[[3], [-2], [5000000], [3, -2]]", eval(ASSETS, "List (List Int)", """
                let
                        v =
                            Value.add (Value.lovelace 5000000) (Value.add (Value.singleton gold coin 3) (Value.singleton gold (TokenName (Bytes.fromString "old")) -2))

                        quantities x =
                            List.map (\\( _, _, q ) -> q) (Value.toList x)
                    in
                    [ quantities (Value.restrictedTo [ gold ] (Value.positive v)), quantities (Value.negative v), quantities (Value.restrictedTo [ PolicyId Bytes.empty ] v), quantities (Value.withoutLovelace v) ]"""));
    }

    @Test
    void valuesDecodeRejectsEveryNonCanonicalShape() {
        var compiled = compile(Map.of("Main", HEADER + "main : Data -> Int\nmain raw =\n    let\n        value : Value\n"
                + "        value =\n            Data.decode raw\n    in\n    Value.lovelaceOf value\n"));
        java.util.function.Function<org.julclang.core.PlutusData, EvalResult> apply = raw -> JulcVm.create().evaluate(
                compiled.program().applyParams(raw), compiled.target().ledgerTarget());
        java.util.function.BiFunction<byte[], Long, org.julclang.core.PlutusData.Pair> token = (name, quantity) ->
                new org.julclang.core.PlutusData.Pair(new org.julclang.core.PlutusData.BytesData(name),
                        new org.julclang.core.PlutusData.IntData(java.math.BigInteger.valueOf(quantity)));
        java.util.function.BiFunction<byte[], List<org.julclang.core.PlutusData.Pair>, org.julclang.core.PlutusData.Pair> policy =
                (name, tokens) -> new org.julclang.core.PlutusData.Pair(new org.julclang.core.PlutusData.BytesData(name),
                        new org.julclang.core.PlutusData.MapData(tokens));
        byte[] a = {'a'}, x = {'x'};
        // Repeated policy, repeated token, a 33-byte token name, and not a map at all.
        assertInstanceOf(EvalResult.Failure.class, apply.apply(new org.julclang.core.PlutusData.MapData(List.of(
                policy.apply(a, List.of(token.apply(x, 1L))), policy.apply(a, List.of(token.apply(new byte[] {'y'}, 1L)))))));
        assertInstanceOf(EvalResult.Failure.class, apply.apply(new org.julclang.core.PlutusData.MapData(List.of(
                policy.apply(a, List.of(token.apply(x, 1L), token.apply(x, 2L)))))));
        assertInstanceOf(EvalResult.Failure.class, apply.apply(new org.julclang.core.PlutusData.MapData(List.of(
                policy.apply(a, List.of(token.apply(new byte[33], 1L)))))));
        assertInstanceOf(EvalResult.Failure.class, apply.apply(new org.julclang.core.PlutusData.IntData(java.math.BigInteger.ONE)));
        assertInstanceOf(EvalResult.Success.class, apply.apply(new org.julclang.core.PlutusData.MapData(List.of(
                policy.apply(new byte[0], List.of(token.apply(new byte[0], 3L), token.apply(x, 1L)))))));
    }

    @Test
    void javaLibrariesCannotSmuggleValues() {
        String library = """
                package example;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.ledger.Value;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                import org.julclang.stdlib.lib.ValuesLib;
                import org.julclang.core.types.JulcList;
                @OnchainLibrary
                public class Shop {
                    public record Listing(Value price, BigInteger count) {}
                    public static Listing listing(Value a, Value b) { return new Listing(ValuesLib.add(a, b), BigInteger.ONE); }
                    public static <T> Value tagged(JulcList<T> items, Value a, Value b) { return ValuesLib.add(a, b); }
                    @SuppressWarnings("unchecked")
                    public static <T> T coerce(PlutusData data) { return (T) data; }
                    public static ScriptContext context(PlutusData data) { return (ScriptContext)(Object) data; }
                    public static JulcList<ScriptContext> contexts(PlutusData data) { return (JulcList<ScriptContext>)(Object) data; }
                }
                """;
        java.util.function.Function<String, Diagnostic> compileWith = body -> assertThrows(Diagnostic.class, () ->
                new ElciCompiler().compileProject(Map.of("Main", HEADER.replace("import Map exposing (Map)\n",
                                "import Map exposing (Map)\nimport Custom.Shop as Shop\n") + body), "Main", "main",
                        FrontendProgram.Purpose.FUNCTION, Map.of("Custom.Shop", "example.Shop"), new CompilerOptions(),
                        Map.of("example.Shop", library)));
        var record = compileWith.apply("main : Int\nmain =\n    (Shop.listing (Value.lovelace 5) (Value.lovelace 1)).count\n");
        assertEquals("ELCI2105", record.code(), record.getMessage());
        var generic = compileWith.apply("main : Int\nmain =\n    Value.lovelaceOf (Shop.tagged [ 1 ] (Value.lovelace 5) (Value.lovelace 1))\n");
        assertEquals("ELCI2105", generic.code(), generic.getMessage());
        var coerced = compileWith.apply("main : Int\nmain =\n    let\n        v : Value\n        v =\n"
                + "            Shop.coerce (Data.encode 1)\n    in\n    Value.lovelaceOf v\n");
        assertEquals("ELCI3003", coerced.code(), coerced.getMessage());
        assertTrue(coerced.getMessage().contains("contains a Value, which a Java library does not keep in the ledger's canonical order"),
                coerced.getMessage());
        // A context holds Values: only the ledger supplies one.
        var context = compileWith.apply("main : Int\nmain =\n    Value.lovelaceOf (Shop.context (Data.encode 1)).txInfo.mint\n");
        assertEquals("ELCI2105", context.code(), context.getMessage());
        var contexts = compileWith.apply("main : Int\nmain =\n    List.length (Shop.contexts (Data.encode 1))\n");
        assertEquals("ELCI2105", contexts.code(), contexts.getMessage());
    }

    @Test
    void txAnswersQuestionsAboutTheTransaction() throws Exception {
        Files.writeString(project.resolve("elci.toml"), """
                [project]
                source-directories = ["src"]
                target = "plutus-v3-pv11-uplc-1.1.0"
                julc-version = "0.1.0-gb-5a3378b"

                [validators.probe]
                boundary = "julc-strict-v1"
                spend = "Probe.spend"
                mint = "Probe.mint"
                withdraw = "Probe.withdraw"
                """);
        Files.createDirectories(project.resolve("src"));
        Files.createDirectories(project.resolve("tests"));
        Files.writeString(project.resolve("src/Probe.elm"), """
                module Probe exposing (Check(..), mint, spend, withdraw)

                import Bytes
                import Dict
                import Interval
                import Julc.Data as Data
                import Julc.Ledger exposing (Credential(..), PubKeyHash, ScriptContext, TokenName(..))
                import Tx
                import Value


                type Check
                    = Signed PubKeyHash
                    | PaidAtLeast PubKeyHash Int
                    | Continues Int
                    | SpendsOwn
                    | Balanced
                    | StartsAfter Int
                    | InlineDatumIs Int
                    | HashedDatumIs Int
                    | NoDatums
                    | NoPolicyWhenSpending
                    | PaidExactly PubKeyHash Int
                    | Laws PubKeyHash PubKeyHash


                spend : () -> Check -> ScriptContext -> Bool
                spend _ check ctx =
                    case check of
                        Signed key ->
                            Tx.signedBy key ctx

                        PaidAtLeast key amount ->
                            Value.lovelaceOf (Tx.valuePaidTo (PubKeyCredential key) ctx) >= amount

                        Continues count ->
                            List.length (Tx.continuingOutputs ctx) == count

                        SpendsOwn ->
                            case Tx.ownRef ctx of
                                Just ref ->
                                    Tx.spends ref ctx
                                        && Maybe.map (\\input -> input.outRef) (Tx.findInput ref ctx) == Just ref
                                        && Maybe.map (\\input -> input.outRef) (Tx.ownInput ctx) == Just ref

                                Nothing ->
                                    False

                        Balanced ->
                            Value.lovelaceOf (Tx.valueSpent ctx) == Value.lovelaceOf (Tx.valueProduced ctx) + ctx.txInfo.fee

                        StartsAfter time ->
                            Interval.isEntirelyAfter time ctx.txInfo.validRange

                        InlineDatumIs n ->
                            List.any (\\output -> Tx.inlineDatum output == Just (Data.encode n) && Tx.datumOf output ctx == Just (Data.encode n))
                                ctx.txInfo.outputs

                        HashedDatumIs n ->
                            List.any (\\output -> Tx.inlineDatum output == Nothing && Tx.datumOf output ctx == Just (Data.encode n))
                                ctx.txInfo.outputs

                        NoDatums ->
                            List.all (\\output -> Tx.datumOf output ctx == Nothing) ctx.txInfo.outputs

                        NoPolicyWhenSpending ->
                            Tx.ownPolicy ctx == Nothing && Tx.ownRef ctx /= Nothing

                        PaidExactly key amount ->
                            Value.lovelaceOf (Tx.valuePaidTo (PubKeyCredential key) ctx) == amount

                        Laws a b ->
                            let
                                paid key =
                                    Tx.valuePaidTo (PubKeyCredential key) ctx
                            in
                            (paid a == Value.sum (List.map (\\output -> output.value) (Tx.outputsTo (PubKeyCredential a) ctx)))
                                && (Tx.valueProduced ctx == Value.add (paid a) (paid b))


                {-| The redeemer is the quantity of B the transaction mints, 0 for none.
                -}
                mint : Int -> ScriptContext -> Bool
                mint burn ctx =
                    case Tx.ownPolicy ctx of
                        Just policy ->
                            let
                                minted token =
                                    Dict.get (TokenName (Bytes.fromString token)) (Tx.mintedOf policy ctx)
                            in
                            (Tx.ownRef ctx == Nothing)
                                && (Maybe.map (\\input -> input.outRef) (Tx.ownInput ctx) == Nothing)
                                && List.isEmpty (Tx.continuingOutputs ctx)
                                && (minted "T" == Just 2)
                                && (minted "B"
                                        == (if burn == 0 then
                                                Nothing

                                            else
                                                Just burn
                                           )
                                   )

                        Nothing ->
                            False


                withdraw : () -> ScriptContext -> Bool
                withdraw _ ctx =
                    (Tx.ownRef ctx == Nothing) && (Tx.ownPolicy ctx == Nothing) && List.isEmpty (Tx.continuingOutputs ctx)
                """);
        Files.writeString(project.resolve("tests/ProbeTest.elm"), """
                module ProbeTest exposing (balanced, burned, burnNotDropped, burnSignKept, continues, datums, hashedDatums, laws, minted, noPolicyWhenSpending, paid, paidIgnoresStakingPart, signed, spendsOwn, stakedScriptOutputIsNotContinuing, startsAfter, underpaid, unsigned, unwitnessedDatums, withdrawn, wrongMint)

                import Bytes
                import Julc.Data as Data
                import Julc.Ledger as Ledger exposing (Credential(..), OutputDatum(..), PubKeyHash, StakingCredential(..))
                import Property exposing (Property)
                import Probe exposing (Check(..))
                import Scenario exposing (Policy(..), Scenario)


                check : Check -> Scenario
                check c =
                    Scenario.spend "probe" (Scenario.outRef "probe" 0) Scenario.unit
                        |> Scenario.redeemer (Data.encode c)


                signed : Scenario
                signed =
                    check (Signed (Scenario.key "a")) |> Scenario.signedBy (Scenario.key "a")


                unsigned : Scenario
                unsigned =
                    check (Signed (Scenario.key "a")) |> Scenario.signedBy (Scenario.key "b") |> Scenario.expectFailure


                paid : Scenario
                paid =
                    check (PaidAtLeast (Scenario.key "a") 7)
                        |> Scenario.output (Scenario.toKey (Scenario.key "a") 3)
                        |> Scenario.output (Scenario.toKey (Scenario.key "a") 4)
                        |> Scenario.output (Scenario.toKey (Scenario.key "b") 100)


                underpaid : Scenario
                underpaid =
                    check (PaidAtLeast (Scenario.key "a") 8)
                        |> Scenario.output (Scenario.toKey (Scenario.key "a") 7)
                        |> Scenario.expectFailure


                continues : Scenario
                continues =
                    check (Continues 2)
                        |> Scenario.output (Scenario.toScript 5)
                        |> Scenario.output (Scenario.toScript 6)
                        |> Scenario.output (Scenario.toKey (Scenario.key "a") 7)


                spendsOwn : Scenario
                spendsOwn =
                    check SpendsOwn


                balanced : Scenario
                balanced =
                    check Balanced
                        |> Scenario.worth 10000000
                        |> Scenario.fee 200000
                        |> Scenario.output (Scenario.toKey (Scenario.key "a") 9800000)


                startsAfter : Scenario
                startsAfter =
                    check (StartsAfter 99) |> Scenario.validFrom 100


                datums : Scenario
                datums =
                    check (InlineDatumIs 42)
                        |> Scenario.output (Scenario.toKey (Scenario.key "a") 1 |> Scenario.withDatum (Data.encode 42))


                minting : Int -> Scenario
                minting burn =
                    Scenario.mint "probe"
                        |> Scenario.redeemer (Data.encode burn)
                        |> Scenario.minting (Bytes.fromString "T") 2


                minted : Scenario
                minted =
                    minting 0


                wrongMint : Scenario
                wrongMint =
                    Scenario.mint "probe"
                        |> Scenario.redeemer (Data.encode 0)
                        |> Scenario.minting (Bytes.fromString "T") 1
                        |> Scenario.expectFailure


                burned : Scenario
                burned =
                    minting -3 |> Scenario.minting (Bytes.fromString "B") -3


                burnSignKept : Scenario
                burnSignKept =
                    minting -3 |> Scenario.minting (Bytes.fromString "B") 3 |> Scenario.expectFailure


                burnNotDropped : Scenario
                burnNotDropped =
                    minting -3 |> Scenario.expectFailure


                stakedScriptOutputIsNotContinuing : Scenario
                stakedScriptOutputIsNotContinuing =
                    check (Continues 1)
                        |> Scenario.output (Scenario.toScript 5)
                        |> Scenario.output (Scenario.toStakedScript (StakingHash (PubKeyCredential (Scenario.key "stake"))) 6)


                withdrawn : Scenario
                withdrawn =
                    Scenario.withdraw "probe" |> Scenario.redeemer Scenario.unit


                noPolicyWhenSpending : Scenario
                noPolicyWhenSpending =
                    check NoPolicyWhenSpending


                hashedDatums : Scenario
                hashedDatums =
                    check (HashedDatumIs 42)
                        |> Scenario.output (Scenario.toKey (Scenario.key "a") 1 |> Scenario.withDatumHash (Data.encode 42))


                unwitnessedDatums : Scenario
                unwitnessedDatums =
                    let
                        out =
                            Scenario.toKey (Scenario.key "a") 1
                    in
                    check NoDatums
                        |> Scenario.output { out | datum = OutputDatumHash (Ledger.DatumHash (Bytes.blake2b256 (Bytes.fromString "d"))) }


                staked : PubKeyHash -> Ledger.Address
                staked owner =
                    Ledger.Address (PubKeyCredential owner) (Data.toOptional (Just (StakingHash (PubKeyCredential (Scenario.key "stake")))))


                paidIgnoresStakingPart : Scenario
                paidIgnoresStakingPart =
                    check (PaidExactly (Scenario.key "a") 7)
                        |> Scenario.output (Scenario.toAddress (staked (Scenario.key "a")) 3)
                        |> Scenario.output (Scenario.toKey (Scenario.key "a") 4)
                        |> Scenario.output (Scenario.toKey (Scenario.key "b") 9)


                {-| valuePaidTo sums outputsTo, and every output paid to a or b makes up valueProduced.
                -}
                laws : Int -> Int -> Int -> Bool -> Property
                laws x y z stake =
                    let
                        a =
                            Scenario.key "a"

                        b =
                            Scenario.key "b"

                        toA amount =
                            if stake then
                                Scenario.toAddress (staked a) amount

                            else
                                Scenario.toKey a amount
                    in
                    Property.scenario
                        (check (Laws a b)
                            |> Scenario.output (toA (modBy 1000 x + 1))
                            |> Scenario.output (Scenario.toKey b (modBy 1000 y + 1))
                            |> Scenario.output (Scenario.toKey a (modBy 1000 z + 1) |> Scenario.withToken (Policy (Bytes.fromString "p")) (Bytes.fromString "t") (modBy 5 z + 1))
                        )
                """);
        var result = NamedValidatorCliTest.run("test", project.toString());
        assertEquals(0, result.status(), result.out() + result.err());
        assertTrue(result.out().contains("21 passed, 0 failed"), result.out());
    }

    @Test
    void dataComparesStructurally() {
        assertEquals("[True, False, True]", eval("List Bool",
                "[ Data.encode ( 1, \"a\" ) == Data.encode ( 1, \"a\" ), Data.encode 1 == Data.encode 2, Just (Data.encode [ 1 ]) == Just (Data.encode [ 1 ]) ]"));
    }

    @Test
    void lawsHoldForGeneratedValues() throws Exception {
        var fixture = Path.of("src/test/resources/stdlib-laws");
        try (var files = Files.walk(fixture)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                var target = project.resolve(fixture.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
        var result = NamedValidatorCliTest.run("test", project.toString(), "--seed", "2026");
        assertEquals(0, result.status(), result.out() + result.err());
        assertTrue(result.out().contains("51 passed, 0 failed (seed 2026)"), result.out());
    }

    /** Budget snapshots: cost changes to the standard library must be deliberate. */
    @Test
    void budgetsMatchTheSnapshot() throws Exception {
        var programs = new LinkedHashMap<String, String[]>();
        programs.put("min", new String[] {"Int", "min 3 7"});
        programs.put("compare-int", new String[] {"Order", "compare 3 7"});
        programs.put("compare-string", new String[] {"Order", "compare \"pear\" \"apple\""});
        programs.put("compare-list", new String[] {"Order", "compare [ 1, 2, 3 ] [ 1, 2, 4 ]"});
        programs.put("power", new String[] {"Int", "2 ^ 64"});
        programs.put("fromInt", new String[] {"String", "String.fromInt 1234567890"});
        programs.put("join", new String[] {"String", "String.join \", \" [ \"a\", \"b\", \"c\", \"d\" ]"});
        programs.put("range-sum", new String[] {"Int", "List.sum (List.range 1 100)"});
        programs.put("member", new String[] {"Bool", "List.member 50 (List.range 1 100)"});
        programs.put("maximum", new String[] {"Maybe Int", "List.maximum [ 5, 3, 9, 1, 7, 2, 8, 4, 6, 0 ]"});
        programs.put("sort-20", new String[] {"List Int", "List.sort (List.reverse (List.range 1 20))"});
        programs.put("sortBy-20", new String[] {"List Int", "List.sortBy (\\x -> 0 - x) (List.range 1 20)"});
        programs.put("sortWith-20", new String[] {"List Int", "List.sortWith (\\a b -> compare b a) (List.range 1 20)"});
        programs.put("take-drop", new String[] {"Int", "List.length (List.take 50 (List.range 1 100)) + List.length (List.drop 50 (List.range 1 100))"});
        programs.put("maybe-map2", new String[] {"Maybe Int", "Maybe.map2 (+) (Just 1) (Just 2)"});
        programs.put("result-andThen", new String[] {"Result String Int", "Result.andThen (\\x -> Ok (x + 1)) (Ok 1)"});
        programs.put("dict-fromList-20", new String[] {"Int", "Dict.size (Dict.fromList (List.map (\\k -> ( modBy 23 (k * 7), k )) (List.range 1 20)))"});
        programs.put("dict-get", new String[] {"Maybe Int", "Dict.get 15 (Dict.fromList (List.map (\\k -> ( k, k )) (List.range 1 20)))"});
        programs.put("dict-insert", new String[] {"Int", "Dict.size (Dict.insert 10 0 (Dict.fromList (List.map (\\k -> ( k, k )) (List.range 1 20))))"});
        programs.put("dict-union", new String[] {"Int", "Dict.size (Dict.union (Dict.fromList [ ( 1, 1 ), ( 3, 3 ) ]) (Dict.fromList [ ( 2, 2 ), ( 3, 0 ) ]))"});
        programs.put("map-fromList-20", new String[] {"Int", "Map.size (Map.fromList (List.map (\\k -> ( modBy 23 (k * 7), k )) (List.range 1 20)))"});
        programs.put("map-get", new String[] {"Maybe Int", "Map.get 15 (Map.fromList (List.map (\\k -> ( k, k )) (List.range 1 20)))"});
        programs.put("value-add", new String[] {"Value", "Value.add (Value.lovelace 5000000) (Value.singleton (PolicyId (Bytes.fromString \"gold\")) (TokenName (Bytes.fromString \"coin\")) 3)"});
        programs.put("value-lovelaceOf", new String[] {"Int", "Value.lovelaceOf (Value.lovelace 5000000)"});
        programs.put("value-quantityOf", new String[] {"Int", "Value.quantityOf (PolicyId (Bytes.fromString \"gold\")) (TokenName (Bytes.fromString \"coin\")) (Value.singleton (PolicyId (Bytes.fromString \"gold\")) (TokenName (Bytes.fromString \"coin\")) 3)"});
        programs.put("value-geq", new String[] {"Bool", "Value.geq (Value.lovelace 5000000) (Value.lovelace 1000000)"});
        programs.put("value-sum-5", new String[] {"Value", "Value.sum (List.map Value.lovelace (List.range 1 5))"});
        programs.put("value-geq-10-assets", new String[] {"Bool", "Value.geq (Value.fromList (List.map (\\i -> ( PolicyId (Bytes.fromString (String.fromInt (i // 3))), TokenName (Bytes.fromString (String.fromInt i)), i )) (List.range 1 10))) (Value.lovelace 0)"});
        programs.put("value-positive-10-assets", new String[] {"Value", "Value.positive (Value.fromList (List.map (\\i -> ( PolicyId (Bytes.fromString (String.fromInt (i // 3))), TokenName (Bytes.fromString (String.fromInt i)), i )) (List.range 1 10)))"});
        var csv = new StringBuilder("program,flat_bytes,cpu,memory\n");
        for (var entry : programs.entrySet()) {
            var result = compile(Map.of("Main", HEADER + "main : " + entry.getValue()[0] + "\nmain =\n    " + entry.getValue()[1] + "\n"));
            var evaluation = assertInstanceOf(EvalResult.Success.class,
                    JulcVm.create().evaluate(result.program(), result.target().ledgerTarget()), entry.getKey());
            csv.append(entry.getKey()).append(',')
                    .append(org.julclang.core.flat.UplcFlatEncoder.encodeProgram(result.program()).length).append(',')
                    .append(evaluation.consumed().cpuSteps()).append(',')
                    .append(evaluation.consumed().memoryUnits()).append('\n');
        }
        var snapshot = Path.of("src/test/resources/stdlib-budgets.csv");
        if (System.getenv("ELCI_UPDATE_SNAPSHOTS") != null || !Files.exists(snapshot)) Files.writeString(snapshot, csv);
        assertEquals(Files.readString(snapshot), csv.toString(),
                "Standard library costs changed; rerun with ELCI_UPDATE_SNAPSHOTS=1 if the change is intended");
    }
}
