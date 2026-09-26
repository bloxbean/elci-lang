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
import java.util.Map;

/**
 * The prelude, {@code comparable} and Elm's core modules (ADR-008): Elm's semantics and edge cases,
 * the scoping of default imports, and the diagnostics for their misuse. The algebraic laws are
 * property tests in {@code src/test/resources/stdlib-laws}, run by {@code elci test}.
 */
class StandardLibraryTest {
    @TempDir Path project;

    private static final String HEADER =
            "module Main exposing (main)\n\nimport Bytes\nimport Julc.Data as Data\nimport Julc.Ledger exposing (PubKeyHash)\n\n\n";

    /** The value of {@code main : type}, in Elci syntax, or {@code failed} when evaluation fails. */
    private static String eval(String type, String body) {
        return eval("", type, body);
    }

    private static String eval(String declarations, String type, String body) {
        var result = compile(Map.of("Main", HEADER + declarations + "main : " + type + "\nmain =\n    " + body + "\n"));
        var evaluation = JulcVm.create().evaluate(result.program(), result.target().ledgerTarget());
        if (!(evaluation instanceof EvalResult.Success success)) return "failed";
        return ElciValues.show(success.resultTerm(), result.pirType(), result.namedTypes());
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
    }

    @Test
    void coreModuleNamesAreReserved() {
        assertTrue(diagnostic(Map.of("String", "module String exposing (x)\n\n\nx : Int\nx =\n    1\n",
                        "Main", "module Main exposing (main)\n\n\nmain : Int\nmain =\n    1\n"))
                .contains("ELCI2104: The module name String is taken by Elci's core library; rename this module"));
        assertTrue(diagnostic(Map.of("Dict", "module Dict exposing (x)\n\n\nx : Int\nx =\n    1\n",
                        "Main", "module Main exposing (main)\n\n\nmain : Int\nmain =\n    1\n"))
                .contains("ELCI2104: The module name Dict is reserved for Elci's core library; rename this module"));
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
                .contains("ELCI2007: Non-exhaustive case"));
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
        assertTrue(result.out().contains("28 passed, 0 failed (seed 2026)"), result.out());
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
