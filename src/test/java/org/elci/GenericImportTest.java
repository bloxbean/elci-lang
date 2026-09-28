package org.elci;

import static org.elci.Contexts.*;
import static org.junit.jupiter.api.Assertions.*;

import org.elci.driver.ElciCompiler;
import org.elci.driver.ElciCompiler.HandlerSpec;
import org.elci.driver.ElciCompiler.ValidatorSpec;
import org.elci.syntax.Diagnostic;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.backend.DatumProfile;
import org.julclang.compiler.backend.FrontendProgram;
import org.julclang.compiler.schema.ContractSchema.Purpose;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Generic library exports (ADR-003 I1–I3): Julc's programmatic list functions and real generic
 * Java-source methods, instantiated at each concrete use.
 */
class GenericImportTest {
    static final Map<String, String> LISTS = Map.of("Julc.Lists", "org.julclang.stdlib.lib.ListsLib");

    /** A real generic on-chain Java library, compiled by Julc at each requested type. */
    static final String PICK = """
            package demo;
            import java.math.BigInteger;
            import org.julclang.core.types.JulcList;
            import org.julclang.stdlib.annotation.OnchainLibrary;
            @OnchainLibrary
            public class Pick {
                public static <T> T firstOr(JulcList<T> items, T fallback) {
                    if (items.isEmpty()) return fallback;
                    return items.head();
                }
                public static <T> BigInteger count(JulcList<T> items) {
                    if (items.isEmpty()) return BigInteger.ZERO;
                    return count(items.tail()).add(BigInteger.ONE);
                }
                public static <T> boolean contains(JulcList<T> items, T target) {
                    if (items.isEmpty()) return false;
                    if (items.head().equals(target)) return true;
                    return contains(items.tail(), target);
                }
                public static <T> T same(T value) { return value; }
                public static <T extends Comparable<T>> T bounded(T value) { return value; }
            }
            """;
    static final Map<String, String> PICK_MODULES = Map.of("Demo.Pick", "demo.Pick");

    static Term run(String source, Map<String, String> modules, Map<String, String> libraries) {
        var result = new ElciCompiler().compileProject(Map.of("Main", source), "Main", "main",
                FrontendProgram.Purpose.FUNCTION, modules, new CompilerOptions(), libraries);
        var evaluation = JulcVm.create().evaluate(result.program(), result.target().ledgerTarget());
        return assertInstanceOf(EvalResult.Success.class, evaluation, evaluation::toString).resultTerm();
    }

    static Term integer(long value) {
        return new Term.Const(Constant.integer(value));
    }

    static Term bool(boolean value) {
        return new Term.Const(Constant.bool(value));
    }

    @Test
    void programmaticListExportsAgreeWithTheElciCoreAtSeveralTypes() {
        var source = """
                module Main exposing (main)

                import Julc.Lists as Lists


                type alias Order =
                    { amount : Int, open : Bool }


                orders : List Order
                orders =
                    [ { amount = 3, open = True }, { amount = 9, open = False }, { amount = 4, open = True } ]


                main : Int
                main =
                    let
                        doubled =
                            Lists.map [ 1, 2, 3 ] (\\x -> x * 2)

                        open =
                            Lists.filter orders (\\order -> order.open)

                        total =
                            Lists.foldl (\\sum order -> sum + order.amount) 0 open

                        hasLarge =
                            Lists.any orders (\\order -> order.amount > 8)

                        allPositive =
                            Lists.all doubled (\\x -> x > 0)
                    in
                    if hasLarge && allPositive && doubled == List.map (\\x -> x * 2) [ 1, 2, 3 ] then
                        total * 100 + List.length open

                    else
                        -1
                """;
        assertEquals(integer(702), run(source, LISTS, Map.of()));
    }

    @Test
    void programmaticExportsEvaluateTheirFunctionArgumentOnceAndStrictly() {
        // The function argument is an expression whose evaluation fails. A strict call evaluates
        // it once before traversal, so the failure happens even for an empty list.
        var source = """
                module Main exposing (main)

                import Julc.Lists as Lists


                main : Bool
                main =
                    Lists.any [] (let broken = 1 // 0 in \\x -> x > broken)
                """;
        var result = new ElciCompiler().compileProject(Map.of("Main", source), "Main", "main",
                FrontendProgram.Purpose.FUNCTION, LISTS, new CompilerOptions(), Map.of());
        assertFalse(JulcVm.create().evaluate(result.program(), result.target().ledgerTarget()) instanceof EvalResult.Success);
    }

    @Test
    void genericJavaExportsSpecializeAtEachConcreteUse() {
        var source = """
                module Main exposing (main)

                import Demo.Pick as Pick
                import Julc.Ledger exposing (PubKeyHash)


                keys : List PubKeyHash
                keys =
                    []


                main : Int
                main =
                    let
                        first =
                            Pick.firstOr [ 5, 6 ] 0

                        fallback =
                            Pick.firstOr [] 7

                        strings =
                            Pick.count [ "a", "b" ]

                        found =
                            Pick.contains [ 1, 2, 3 ] 2

                        missing =
                            Pick.contains [ 1, 2, 3 ] 4

                        noKeys =
                            Pick.count keys
                    in
                    if found && not missing then
                        first * 1000 + fallback * 100 + strings * 10 + noKeys

                    else
                        -1
                """;
        // Int, String and the nominal ledger PubKeyHash: four specializations of three templates.
        assertEquals(integer(5720), run(source, PICK_MODULES, Map.of("demo.Pick", PICK)));
    }

    @Test
    void genericJavaExportsSpecializeAtElciRecordsAndUnions() {
        var source = """
                module Main exposing (main)

                import Demo.Pick as Pick


                type Side
                    = Buy Int
                    | Sell


                type alias Order =
                    { amount : Int, side : Side }


                main : Int
                main =
                    let
                        orders =
                            [ { amount = 3, side = Buy 1 }, { amount = 9, side = Sell } ]

                        first =
                            Pick.firstOr orders { amount = 0, side = Sell }

                        none =
                            Pick.firstOr [] { amount = 7, side = Sell }

                        hasSell =
                            Pick.contains [ Buy 1, Sell ] Sell

                        hasBuy2 =
                            Pick.contains [ Buy 1, Sell ] (Buy 2)
                    in
                    if hasSell && not hasBuy2 then
                        first.amount * 100 + none.amount * 10 + Pick.count orders

                    else
                        -1
                """;
        assertEquals(integer(372), run(source, PICK_MODULES, Map.of("demo.Pick", PICK)));
    }

    @Test
    void unsupportedInstantiationsAreDiagnosed() {
        var function = """
                module Main exposing (main)

                import Demo.Pick as Pick


                main : Int
                main =
                    (Pick.same (\\x -> x + 1)) 4
                """;
        var error = assertThrows(Diagnostic.class, () -> run(function, PICK_MODULES, Map.of("demo.Pick", PICK)));
        assertEquals("ELCI3003", error.code());
        var bounded = """
                module Main exposing (main)

                import Demo.Pick as Pick


                main : Int
                main =
                    Pick.bounded 4
                """;
        var unsupported = assertThrows(Diagnostic.class, () -> run(bounded, PICK_MODULES, Map.of("demo.Pick", PICK)));
        assertEquals("ELCI2105", unsupported.code());
        assertTrue(unsupported.getMessage().contains("bound"), unsupported.getMessage());
    }

    @Test
    void namedValidatorsLinkEachSpecializationOnce() {
        var source = """
                module Gate exposing (mint)

                import Julc.Ledger exposing (ScriptContext)
                import Julc.Lists as Lists


                mint : List Int -> ScriptContext -> Bool
                mint amounts _ =
                    Lists.all amounts (\\a -> a > 0) && Lists.any amounts (\\a -> a > 10) && Lists.all [ 1 ] (\\a -> a == 1)
                """;
        var build = new ElciCompiler().compileValidator(Map.of("Gate", source),
                new ValidatorSpec("gate", List.of(new HandlerSpec(Purpose.MINT, "Gate", "mint")), List.of(), DatumProfile.REQUIRED),
                LISTS, new CompilerOptions(), Map.of());
        var list = PlutusData.list(PlutusData.integer(3), PlutusData.integer(11));
        assertTrue(accepts(build.program(), context(Purpose.MINT, list)));
        assertFalse(accepts(build.program(), context(Purpose.MINT, PlutusData.list(PlutusData.integer(3)))));
        assertFalse(accepts(build.program(), context(Purpose.MINT, PlutusData.list(PlutusData.integer(0), PlutusData.integer(11)))));
    }
}
