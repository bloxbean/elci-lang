package org.elci;

import static org.elci.Contexts.*;
import static org.junit.jupiter.api.Assertions.*;

import org.elci.driver.ElciCompiler;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.backend.FrontendProgram;
import org.julclang.compiler.schema.ContractSchema.Purpose;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** The core {@code Map} module, backed by Julc's typed map templates, and {@code Julc.ByteString}. */
class MapTest {
    static Term run(String body, Map<String, String> modules) {
        var result = new ElciCompiler().compileProject(Map.of("Main", "module Main exposing (main)\n" + body), "Main", "main",
                FrontendProgram.Purpose.FUNCTION, modules, new CompilerOptions(), Map.of());
        var evaluation = JulcVm.create().evaluate(result.program(), result.target().ledgerTarget());
        return assertInstanceOf(EvalResult.Success.class, evaluation, evaluation::toString).resultTerm();
    }

    static Term run(String body) {
        return run(body, Map.of());
    }

    static Term integer(long value) {
        return new Term.Const(Constant.integer(value));
    }

    @Test
    void dictStyleOperations() {
        var source = """
                import Map

                prices : Map String Int
                prices =
                    Map.fromList [ ( "gold", 5 ), ( "silver", 2 ), ( "gold", 7 ) ]

                main : Int
                main =
                    let
                        withBronze =
                            Map.insert "bronze" 1 prices

                        doubled =
                            Map.map (\\_ price -> price * 2) withBronze

                        cheap =
                            Map.filter (\\_ price -> price < 5) doubled

                        bumped =
                            Map.update "silver" (Maybe.map (\\p -> p + 100)) withBronze

                        total =
                            Map.foldl (\\_ price acc -> price + acc) 0 withBronze
                    in
                    case ( Map.get "gold" prices, Map.get "iron" prices ) of
                        ( Just gold, Nothing ) ->
                            gold * 1000000 + Map.size withBronze * 100000 + Map.size cheap * 10000 + total * 100
                                + (if Map.member "bronze" withBronze && not (Map.member "bronze" (Map.remove "bronze" withBronze)) then 1 else 0)
                                + (case Map.get "silver" bumped of
                                    Just silver -> silver - 102

                                    Nothing -> 50
                                  )

                        _ ->
                            -1
                """.replace("Maybe.map (\\p -> p + 100)", "(\\found -> case found of\n                            Just p -> Just (p + 100)\n\n                            Nothing -> Nothing)");
        // gold 7 (the later binding replaces), 3 keys, 2 cheap (silver 4, bronze 2), total 10.
        assertEquals(integer(7 * 1000000 + 3 * 100000 + 2 * 10000 + 10 * 100 + 1 + 0), run(source));
    }

    @Test
    void keysValuesAndToListFollowInsertionOrderWithoutDuplicates() {
        var source = """
                import Map

                main : List Int
                main =
                    let
                        m =
                            Map.empty
                                |> Map.insert 1 10
                                |> Map.insert 2 20
                                |> Map.insert 1 11
                    in
                    Map.keys m ++ Map.values m ++ List.map (\\( k, v ) -> k + v) (Map.toList m)
                """;
        // insert prepends after removing an existing key: order is 1, 2.
        var expected = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(11),
                PlutusData.integer(20), PlutusData.integer(12), PlutusData.integer(22));
        var term = run(source);
        assertEquals(expected.toString(), String.valueOf(listData(term)));
    }

    static Object listData(Term term) {
        var value = ((Term.Const) term).value();
        if (value instanceof Constant.ListConst list)
            return PlutusData.list(list.values().stream().map(c -> ((Constant.DataConst) c).value()).toArray(PlutusData[]::new));
        return value;
    }

    @Test
    void recordValuesAndLedgerKeysInAValidator() {
        var source = """
                module Registry exposing (mint)

                import Julc.Ledger exposing (PubKeyHash, ScriptContext)
                import Map


                type alias Grant =
                    { limit : Int, active : Bool }


                grants : PubKeyHash -> Map PubKeyHash Grant
                grants admin =
                    Map.singleton admin { limit = 5, active = True }


                mint : PubKeyHash -> Int -> ScriptContext -> Bool
                mint admin amount ctx =
                    List.any
                        (\\signer ->
                            case Map.get signer (grants admin) of
                                Just grant ->
                                    grant.active && amount <= grant.limit

                                Nothing ->
                                    False
                        )
                        ctx.txInfo.signatories
                """;
        var build = new ElciCompiler().compileValidator(Map.of("Registry", source),
                new ElciCompiler.ValidatorSpec("registry", java.util.List.of(new ElciCompiler.HandlerSpec(Purpose.MINT, "Registry", "mint")),
                        java.util.List.of("admin"), org.julclang.compiler.backend.DatumProfile.REQUIRED),
                Map.of(), new CompilerOptions(), Map.of());
        var admin = new org.julclang.ledger.PubKeyHash(new byte[28]);
        var applied = build.program().applyParams(admin.toPlutusData());
        assertTrue(accepts(applied, builder(Purpose.MINT, null).signer(admin).redeemer(PlutusData.integer(5)).build().toPlutusData()));
        assertFalse(accepts(applied, builder(Purpose.MINT, null).signer(admin).redeemer(PlutusData.integer(6)).build().toPlutusData()));
        assertFalse(accepts(applied, builder(Purpose.MINT, null).redeemer(PlutusData.integer(1)).build().toPlutusData()));
    }

    @Test
    void theCoreMapAliasCannotBeRemapped() {
        var error = assertThrows(IllegalArgumentException.class, () -> run("main : Int\nmain = 1\n",
                Map.of("Julc.TypedMap", "org.julclang.stdlib.lib.MapLib")));
        assertTrue(error.getMessage().contains("may not remap Julc.TypedMap"), error.getMessage());
    }

    @Test
    void byteStringLibraryIsImportable() {
        var source = """
                import Bytes
                import Julc.ByteString as ByteString

                main : String
                main =
                    Bytes.toString (ByteString.toHex (Bytes.fromString "GO"))
                """;
        assertEquals(new Term.Const(Constant.string("474f")),
                run(source, Map.of("Julc.ByteString", "org.julclang.stdlib.lib.ByteStringLib")));
        var decimal = """
                import Bytes
                import Julc.ByteString as ByteString

                main : String
                main =
                    Bytes.toString (ByteString.intToDecimalString 1205)
                """;
        assertEquals(new Term.Const(Constant.string("1205")),
                run(decimal, Map.of("Julc.ByteString", "org.julclang.stdlib.lib.ByteStringLib")));
    }
}
