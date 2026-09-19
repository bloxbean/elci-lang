package org.elci;

import static org.junit.jupiter.api.Assertions.*;

import org.elci.driver.ElciCompiler;
import org.elci.syntax.Diagnostic;
import org.julclang.compiler.backend.FrontendProgram;
import org.julclang.core.Constant;
import org.julclang.core.Term;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.Test;

class CoreCompletionTest {
    private EvalResult eval(String source) {
        var result =
                new ElciCompiler()
                        .compile(
                                "module Test exposing (main)\n" + source,
                                "main",
                                FrontendProgram.Purpose.FUNCTION);
        return JulcVm.create().evaluate(result.program(), result.target().ledgerTarget());
    }

    private void integer(long expected, String source) {
        var success = assertInstanceOf(EvalResult.Success.class, eval(source));
        assertEquals(new Term.Const(Constant.integer(expected)), success.resultTerm());
    }

    @Test
    void nestedConstructorAndListPattern() {
        integer(
                7,
                """
                first : Maybe (List Int) -> Int
                first value =
                    case value of
                        Just (x :: _) -> x
                        Just [] -> 0
                        Nothing -> -1

                main : Int
                main = first (Just [ 7, 8 ])
                """);
    }

    @Test
    void fixedLengthListAndAliasPattern() {
        integer(
                5,
                """
                exact : List Int -> Int
                exact values =
                    case values of
                        [ a, b ] as all -> a + b + List.length all
                        _ -> 0

                main : Int
                main = exact [ 1, 2 ] + exact [ 1, 2, 3 ]
                """);
    }

    @Test
    void nestedCoverageRejectsMissingAndUnreachableCombinations() {
        assertThrows(
                Diagnostic.class,
                () ->
                        eval(
                                """
                                incomplete : Maybe (List Int) -> Int
                                incomplete value =
                                    case value of
                                        Just [] -> 0
                                        Nothing -> -1
                                main : Int
                                main = incomplete Nothing
                                """));
        assertThrows(
                Diagnostic.class,
                () ->
                        eval(
                                """
                                unreachable : List Int -> Int
                                unreachable values =
                                    case values of
                                        [ _, _ ] -> 1
                                        [ 1, 2 ] -> 2
                                        _ -> 0
                                main : Int
                                main = unreachable []
                                """));
    }

    @Test
    void destructuringParametersAndLocalBindings() {
        integer(
                10,
                """
                sumPair : ( Int, Int ) -> Int
                sumPair ( a, b ) = a + b

                main : Int
                main =
                    let
                        pair = ( 4, 6 )
                        ( left, right ) = pair
                    in
                    sumPair ( left, right )
                """);
    }

    @Test
    void recordDestructuringParameter() {
        integer(
                9,
                """
                type alias Price =
                    { amount : Int, active : Bool }

                amount : Price -> Int
                amount { amount, active } = if active then amount else 0

                main : Int
                main = amount { amount = 9, active = True }
                """);
    }

    @Test
    void stringLiteralPattern() {
        integer(
                1,
                """
                classify : String -> Int
                classify text =
                    case text of
                        "buy" -> 1
                        _ -> 0

                main : Int
                main = classify "buy"
                """);
    }

    @Test
    void unitNegativeIntegerAndNestedTuplePatterns() {
        integer(
                5,
                """
                classify : ( Int, String ) -> Int
                classify value =
                    case value of
                        ( -1, "buy" ) -> 5
                        _ -> 0

                main : Int
                main =
                    case () of
                        () -> classify ( -1, "buy" )
                """);
    }

    @Test
    void lambdaParametersCanDestructureTuples() {
        integer(
                5,
                """
                main : Int
                main = (\\( left, right ) -> left + right) ( 2, 3 )
                """);
    }

    @Test
    void genericAliasCanBeAppliedAtDifferentTypes() {
        integer(
                3,
                """
                type alias Box a =
                    { value : a }

                intValue : Box Int -> Int
                intValue box = box.value

                boolValue : Box Bool -> Bool
                boolValue box = box.value

                main : Int
                main =
                    if boolValue (Box True) then
                        intValue (Box 3)
                    else
                        0
                """);
    }

    @Test
    void recordAliasConstructorUsesDeclarationOrderAndCanonicalStorage() {
        integer(
                7,
                """
                type alias Ordered =
                    { second : Int, first : Int }

                difference : Ordered -> Int
                difference value = value.second - value.first

                main : Int
                main = difference (Ordered 10 3)
                """);
    }

    @Test
    void structuralEqualityCoversListsTuplesRecordsAndRecursiveUnions() {
        integer(
                4,
                """
                type Tree
                    = Leaf Int
                    | Branch Tree Tree

                type alias PairRecord =
                    { left : Int, right : Bool }

                main : Int
                main =
                    let
                        listEqual = [ 1, 2 ] == [ 1, 2 ]
                        tupleDifferent = ( 1, True ) /= ( 2, True )
                        recordEqual = { right = False, left = 3 } == { left = 3, right = False }
                        treeEqual = Branch (Leaf 1) (Leaf 2) == Branch (Leaf 1) (Leaf 2)
                    in
                    (if listEqual then 1 else 0)
                        + (if tupleDifferent then 1 else 0)
                        + (if recordEqual then 1 else 0)
                        + (if treeEqual then 1 else 0)
                """);
    }

    @Test
    void structuralEqualityRejectsFunctionsNestedInData() {
        assertThrows(
                Diagnostic.class,
                () ->
                        eval(
                                """
                                type Bad = Bad (Int -> Int)
                                main : Bool
                                main = Bad (\\x -> x) == Bad (\\x -> x)
                                """));
    }

    @Test
    void localFunctionIsSpecializedAtTwoTypes() {
        integer(
                1,
                """
                main : Int
                main =
                    let
                        identity value = value
                        number = identity 1
                        flag = identity True
                    in
                    if flag then number else 0
                """);
    }

    @Test
    void expansiveLocalBindingRemainsMonomorphic() {
        assertThrows(
                Diagnostic.class,
                () ->
                        eval(
                                """
                                main : Int
                                main =
                                    let
                                        identity = if True then (\\value -> value) else (\\value -> value)
                                        number = identity 1
                                        flag = identity True
                                    in
                                    if flag then number else 0
                                """));
    }

    @Test
    void threeFunctionsCanBeMutuallyRecursiveWithDifferentArities() {
        integer(
                11,
                """
                first : Int -> Int
                first n =
                    if n == 0 then 10 else second (n - 1) 1

                second : Int -> Int -> Int
                second n extra =
                    if n == 0 then 20 + extra else third (n - 1) + extra

                third : Int -> Int
                third n =
                    if n == 0 then 30 else first (n - 1)

                main : Int
                main = first 3
                """);
    }

    @Test
    void compilerRetainsGenericEqualityRequirementsUntilSpecialization() {
        integer(
                2,
                """
                equal : a -> a -> Bool
                equal left right = left == right

                main : Int
                main =
                    (if equal 1 1 then 1 else 0)
                        + (if equal [ True ] [ True ] then 1 else 0)
                """);

        assertThrows(
                Diagnostic.class,
                () ->
                        eval(
                                """
                                equal : a -> a -> Bool
                                equal left right = left == right
                                main : Bool
                                main = equal (\\x -> x) (\\x -> x)
                                """));
    }

    @Test
    void compilerRetainsGenericAppendRequirementsUntilSpecialization() {
        integer(
                4,
                """
                join : a -> a -> a
                join left right = left ++ right

                main : Int
                main = List.length (join [ 1, 2 ] [ 3, 4 ])
                """);
    }

    @Test
    void localGenericRequirementsAreInstantiatedAtEachUse() {
        integer(
                2,
                """
                main : Int
                main =
                    let
                        equal left right = left == right
                        integerEqual = equal 1 1
                        stringEqual = equal "elci" "elci"
                    in
                    (if integerEqual then 1 else 0)
                        + (if stringEqual then 1 else 0)
                """);
    }

    @Test
    void rejectsCyclicAndWrongArityAliases() {
        assertThrows(
                RuntimeException.class,
                () ->
                        eval(
                                """
                                type alias Loop a = Loop a
                                main : Int
                                main = 0
                                """));
        assertThrows(
                Diagnostic.class,
                () ->
                        eval(
                                """
                                type alias Box a = { value : a }
                                bad : Box -> Int
                                bad box = 0
                                main : Int
                                main = 0
                                """));
        var freeVariable =
                assertThrows(
                        Diagnostic.class,
                        () ->
                                eval(
                                        """
                                        type alias Broken a = { value : b }
                                        main : Int
                                        main = 0
                                        """));
        assertEquals("ELCI2012", freeVariable.code());
    }
}
