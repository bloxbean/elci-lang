package org.elci;

import static org.elci.Contexts.*;
import static org.junit.jupiter.api.Assertions.*;

import org.elci.driver.ElciCompiler;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.schema.ContractSchema.Purpose;
import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/** The core {@code Array} module, backed by Julc's typed array templates (PV11 arrays). */
class ArrayTest {
    static long run(String body) {
        var term = MapTest.run("import Array\n\n" + body);
        return ((org.julclang.core.Constant.IntegerConst) ((org.julclang.core.Term.Const) term).value()).value().longValueExact();
    }

    @Test
    void elmStyleOperations() {
        assertEquals(30, run("""
                main : Int
                main =
                    case Array.get 2 (Array.fromList [ 10, 20, 30 ]) of
                        Just value -> value

                        Nothing -> -1
                """));
        assertEquals(-1, run("""
                main : Int
                main =
                    case ( Array.get 3 (Array.fromList [ 10, 20, 30 ]), Array.get -1 (Array.fromList [ 10 ]) ) of
                        ( Nothing, Nothing ) -> -1

                        _ -> 0
                """));
        var summary = run("""
                values : Array Int
                values =
                    Array.fromList [ 3, 1, 4, 1, 5 ]


                none : Array Int
                none =
                    Array.empty


                digits : List Int -> Int
                digits items =
                    List.foldl (\\digit total -> total * 10 + digit) 0 items


                main : Int
                main =
                    digits (Array.toList values)
                        * 1000000000000
                        + Array.length values * 10000000000
                        + digits (Array.toList (Array.map (\\v -> v + 1) values)) * 100000
                        + Array.foldl (\\v total -> v + total) 0 values * 1000
                        + (if Array.isEmpty none && not (Array.isEmpty values) then 100 else 0)
                        + Array.length (Array.filter (\\v -> v > 2) values) * 10
                        + Array.foldr (\\v total -> total - v) 0 (Array.slice 1 -1 values)
                """);
        // 31415, length 5, mapped 42526, sum 14, empty check, 3 values above 2, -(1 + 4 + 1).
        assertEquals(31415L * 1000000000000L + 5L * 10000000000L + 42526L * 100000 + 14 * 1000 + 100 + 30 - 6, summary);
        // squares 0 + 1 + 4 + 9 = 14; indexed pairs (0, 1000), (1, 1001), (2, 1002) sum to 3006.
        assertEquals(14 + 3006 - 1000 - 3, run("""
                main : Int
                main =
                    let
                        squares =
                            Array.initialize 4 (\\i -> i * i)

                        indexed =
                            Array.toIndexedList (Array.indexedMap (\\i v -> i + v) (Array.repeat 3 1000))
                    in
                    Array.foldl (\\v total -> v + total) 0 squares
                        + List.foldl (\\( i, v ) total -> i + v + total) 0 indexed
                        - 1000
                        - 3
                """));
    }

    @Test
    void recordElementsDecodeAtTheirType() {
        assertEquals(708, run("""
                type alias Tier =
                    { floor : Int, name : String }


                tiers : Array Tier
                tiers =
                    Array.fromList [ { floor = 7, name = "bronze" }, { floor = 8, name = "silver" } ]


                main : Int
                main =
                    case ( Array.get 0 tiers, Array.get 1 tiers ) of
                        ( Just bronze, Just silver ) ->
                            bronze.floor * 100 + silver.floor

                        _ ->
                            -1
                """));
    }

    @Test
    void validatorsIndexTheirDatumInConstantTime() {
        var source = """
                module Tiers exposing (spend)

                import Array
                import Julc.Ledger exposing (ScriptContext)


                type alias Claim =
                    { amount : Int, tier : Int }


                spend : List Int -> Claim -> ScriptContext -> Bool
                spend floors claim _ =
                    case Array.get claim.tier (Array.fromList floors) of
                        Just floor ->
                            claim.amount >= floor

                        Nothing ->
                            False
                """;
        var build = new ElciCompiler().compileValidator(Map.of("Tiers", source),
                new ElciCompiler.ValidatorSpec("tiers", List.of(new ElciCompiler.HandlerSpec(Purpose.SPEND, "Tiers", "spend")),
                        List.of(), org.julclang.compiler.backend.DatumProfile.REQUIRED),
                Map.of(), new CompilerOptions(), Map.of());
        var floors = PlutusData.list(PlutusData.integer(100), PlutusData.integer(500));
        java.util.function.BiFunction<Long, Long, PlutusData> claim = (amount, tier) ->
                PlutusData.constr(0, PlutusData.integer(amount), PlutusData.integer(tier));
        assertTrue(accepts(build.program(), builder(Purpose.SPEND, floors).redeemer(claim.apply(500L, 1L)).build().toPlutusData()));
        assertFalse(accepts(build.program(), builder(Purpose.SPEND, floors).redeemer(claim.apply(499L, 1L)).build().toPlutusData()));
        assertFalse(accepts(build.program(), builder(Purpose.SPEND, floors).redeemer(claim.apply(10_000L, 2L)).build().toPlutusData()));
    }

    static String rejection(String body) {
        var error = assertThrows(RuntimeException.class, () -> run(body));
        return error.getMessage();
    }

    @Test
    void arraysStayOutOfData() {
        var field = rejection("""
                type alias Book =
                    { pages : Array Int }


                main : Int
                main =
                    Array.length { pages = Array.fromList [ 1 ] }.pages
                """);
        assertTrue(field.contains("Arrays have no Data encoding"), field);
        var list = rejection("""
                main : Int
                main =
                    List.length [ Array.fromList [ 1 ] ]
                """);
        assertTrue(list.contains("Arrays have no Data encoding"), list);
        var encoded = rejection("""
                import Julc.Data as Data


                main : Int
                main =
                    case Data.encode (Array.fromList [ 1 ]) of
                        _ -> 0
                """);
        assertTrue(encoded.contains("No encoder for Array Int: arrays have no Data encoding"), encoded);
        var equal = rejection("""
                main : Int
                main =
                    if Array.fromList [ 1 ] == Array.fromList [ 1 ] then 1 else 0
                """);
        assertFalse(equal.isBlank(), equal);
    }
}
