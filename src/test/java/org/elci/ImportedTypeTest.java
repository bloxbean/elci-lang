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
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.ledger.*;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * Imported types (ADR-003 I4, ADR-005 E4): approved constructors, patterns, equality, field
 * access, strict boundaries and explicit Data codecs.
 */
class ImportedTypeTest {
    static final String PRICING = """
            package example;
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.OnchainLibrary;
            @OnchainLibrary
            public class Pricing {
                public record Quote(BigInteger amount, boolean approved) {}
                public sealed interface Action {
                    record Buy(BigInteger amount) implements Action {}
                    record Cancel() implements Action {}
                }
                public static Quote quote(BigInteger amount) { return new Quote(amount, true); }
                public static boolean same(Quote a, Quote b) { return a.equals(b); }
                public static BigInteger actionAmount(Action action) {
                    return switch (action) {
                        case Action.Buy b -> b.amount();
                        case Action.Cancel c -> BigInteger.ZERO;
                    };
                }
                public static BigInteger total(Quote quote) { return quote.amount(); }
            }
            """;
    static final Map<String, String> MODULES = Map.of("Custom.Pricing", "example.Pricing",
            "Julc.Contexts", "org.julclang.stdlib.lib.ContextsLib");
    static final Map<String, String> LIBRARIES = Map.of("example.Pricing", PRICING);

    static ElciCompiler.Result function(String source) {
        return new ElciCompiler().compileProject(Map.of("Main", source), "Main", "main",
                FrontendProgram.Purpose.FUNCTION, MODULES, new CompilerOptions(), LIBRARIES);
    }

    static EvalResult run(String source, PlutusData... arguments) {
        var result = function(source);
        return JulcVm.create().evaluateWithArgs(result.program(), result.target().ledgerTarget(), List.of(arguments),
                null, org.julclang.vm.EvalOptions.DEFAULT);
    }

    static Term value(String source, PlutusData... arguments) {
        var result = run(source, arguments);
        return assertInstanceOf(EvalResult.Success.class, result, result::toString).resultTerm();
    }

    static Term integer(long value) {
        return new Term.Const(Constant.integer(value));
    }

    static Program validator(String source, List<HandlerSpec> handlers, List<String> parameters) {
        return new ElciCompiler().compileValidator(Map.of("Main", source),
                new ValidatorSpec("v", handlers, parameters, DatumProfile.REQUIRED), MODULES, new CompilerOptions(), LIBRARIES)
                .program();
    }

    @Test
    void javaRecordAndSumConstructorsPatternsAndEqualityAgreeWithJava() {
        var source = """
                module Main exposing (main)

                import Custom.Pricing as Pricing exposing (Action(..), Quote)
                import Julc.Data as Data


                describe : Action -> Int
                describe action =
                    case action of
                        Buy amount ->
                            amount

                        Cancel ->
                            0


                main : Data -> Int
                main raw =
                    let
                        n : Int
                        n =
                            Data.decode raw

                        quote =
                            Pricing.Quote n True

                        actions =
                            [ Buy n, Cancel, Pricing.Buy (n + 1) ]

                        agree =
                            List.all (\\a -> describe a == Pricing.actionAmount a) actions

                        equal =
                            (quote == Pricing.quote n) == Pricing.same quote (Pricing.quote n)
                    in
                    if agree && equal && quote.approved && quote /= Pricing.Quote n False then
                        Pricing.total quote + List.foldl (\\a sum -> describe a + sum) 0 actions

                    else
                        -1
                """;
        for (long n : new long[] {0, 5, -3})
            assertEquals(integer(n + n + n + 1), value(source, PlutusData.integer(n)), "n=" + n);
    }

    @Test
    void ledgerSumsAreConstructedAndMatchedExhaustively() {
        var source = """
                module Main exposing (main)

                import Julc.Data as Data
                import Julc.Ledger as Ledger exposing (Credential(..), PubKeyHash)


                kind : Credential -> Int
                kind credential =
                    case credential of
                        PubKeyCredential _ ->
                            1

                        ScriptCredential _ ->
                            2


                main : Data -> Int
                main raw =
                    let
                        key : PubKeyHash
                        key =
                            Data.decode raw

                        credential =
                            PubKeyCredential key

                        script =
                            Ledger.ScriptCredential (Ledger.ScriptHash key.hash)
                    in
                    kind credential * 10 + kind script + (if credential == Ledger.PubKeyCredential key then 100 else 0)
                """;
        var key = new PubKeyHash(new byte[28]);
        assertEquals(integer(112), value(source, key.toPlutusData()));
        var partial = source.replace("""

                        ScriptCredential _ ->
                            2
                """, "\n");
        assertEquals("ELCI2007", assertThrows(Diagnostic.class, () -> function(partial)).code());
        var arity = source.replace("PubKeyCredential _ ->", "PubKeyCredential _ _ ->");
        assertEquals("ELCI2001", assertThrows(Diagnostic.class, () -> function(arity)).code());
        // Constructors stay private unless exposed with (..) or qualified.
        var unexposed = source.replace("exposing (Credential(..), PubKeyHash)", "exposing (Credential, PubKeyHash)");
        assertThrows(Diagnostic.class, () -> function(unexposed));
    }

    static final String DATUM_READER = """
            module Main exposing (Order, main)

            import Julc.Data as Data
            import Julc.Ledger exposing (OutputDatum(..), TxOut)


            type alias Order =
                { amount : Int, owner : Bytes }


            main : Data -> Int
            main raw =
                let
                    order : Order
                    order =
                        Data.decode raw
                in
                order.amount
            """;

    @Test
    void strictDecodingRejectsWrongTagsAritiesAndNestedKinds() {
        var good = PlutusData.constr(0, PlutusData.integer(7), PlutusData.bytes(new byte[] {1}));
        assertEquals(integer(7), value(DATUM_READER, good));
        for (var bad : List.of(
                PlutusData.constr(1, PlutusData.integer(7), PlutusData.bytes(new byte[] {1})),
                PlutusData.constr(0, PlutusData.integer(7)),
                PlutusData.constr(0, PlutusData.integer(7), PlutusData.bytes(new byte[] {1}), PlutusData.integer(0)),
                PlutusData.constr(0, PlutusData.bytes(new byte[] {7}), PlutusData.bytes(new byte[] {1})),
                PlutusData.constr(0, PlutusData.integer(7), PlutusData.integer(1)),
                PlutusData.integer(7)))
            assertFalse(run(DATUM_READER, bad) instanceof EvalResult.Success, bad.toString());
    }

    @Test
    void encodingIsPinnedAndRoundTrips() {
        var source = """
                module Main exposing (main)

                import Custom.Pricing as Pricing
                import Julc.Data as Data


                main : Data
                main =
                    Data.encode (Pricing.Quote 5 True)
                """;
        // A Java record's fields keep declaration order: Constr 0 [I 5, Constr 1 []].
        assertEquals(new Term.Const(Constant.data(PlutusData.constr(0, PlutusData.integer(5), PlutusData.constr(1)))),
                value(source));
        var roundTrip = """
                module Main exposing (main)

                import Custom.Pricing as Pricing
                import Julc.Data as Data


                quotes : List Pricing.Quote
                quotes =
                    [ Pricing.Quote 5 True, Pricing.Quote 9 False ]


                main : Bool
                main =
                    let
                        decoded : List Pricing.Quote
                        decoded =
                            Data.decode (Data.encode quotes)
                    in
                    decoded == quotes && List.length decoded == 2
                """;
        assertEquals(new Term.Const(Constant.bool(true)), value(roundTrip));
    }

    @Test
    void optionalAdaptersConvertBetweenJulcOptionalAndMaybe() {
        var source = """
                module Main exposing (main)

                import Julc.Data as Data
                import Julc.Lists as Lists


                main : Int
                main =
                    let
                        found =
                            Data.fromOptional (Lists.find [ 3, 8, 12 ] (\\x -> x > 5))

                        missing =
                            Data.fromOptional (Lists.find [ 3 ] (\\x -> x > 5))

                        back =
                            Data.fromOptional (Data.toOptional (Just 4))
                    in
                    case ( found, missing, back ) of
                        ( Just x, Nothing, Just y ) ->
                            x * 10 + y

                        _ ->
                            -1
                """;
        var result = new ElciCompiler().compileProject(Map.of("Main", source), "Main", "main",
                FrontendProgram.Purpose.FUNCTION, Map.of("Julc.Lists", "org.julclang.stdlib.lib.ListsLib"),
                new CompilerOptions(), Map.of());
        assertEquals(integer(84), assertInstanceOf(EvalResult.Success.class,
                JulcVm.create().evaluate(result.program(), result.target().ledgerTarget())).resultTerm());
    }

    static final String ONE_SHOT = """
            module Main exposing (mint, spend)

            import Julc.Contexts as Contexts
            import Julc.Data as Data
            import Julc.Ledger exposing (Credential(..), OutputDatum(..), ScriptContext, TxOutRef)


            {-| A one-shot policy: minting requires spending the seed output. -}
            mint : TxOutRef -> Credential -> ScriptContext -> Bool
            mint seed credential ctx =
                List.any (\\input -> input.outRef == seed) ctx.txInfo.inputs
                    && (case credential of
                            PubKeyCredential _ ->
                                True

                            ScriptCredential _ ->
                                False
                       )


            spend : TxOutRef -> Int -> () -> ScriptContext -> Bool
            spend _ limit _ ctx =
                case Data.fromOptional (Contexts.findOwnInput ctx) of
                    Just input ->
                        case input.resolved.datum of
                            OutputDatumInline raw ->
                                Data.decode raw <= limit

                            _ ->
                                False

                    Nothing ->
                        False
            """;

    @Test
    void namedValidatorsUseApprovedImportedTypesAtTheBoundary() {
        var program = validator(ONE_SHOT, List.of(new HandlerSpec(Purpose.MINT, "Main", "mint"),
                new HandlerSpec(Purpose.SPEND, "Main", "spend")), List.of("seed"));
        var seed = new TxOutRef(new TxId(new byte[32]), BigInteger.TWO);
        var applied = program.applyParams(seed.toPlutusData());
        var key = new Credential.PubKeyCredential(new PubKeyHash(new byte[28]));
        var script = new Credential.ScriptCredential(new ScriptHash(new byte[28]));
        var input = new TxInInfo(seed, new TxOut(new Address(key, java.util.Optional.empty()), Value.lovelace(BigInteger.TEN),
                new OutputDatum.NoOutputDatum(), java.util.Optional.empty()));
        var mintWithSeed = builder(Purpose.MINT, null).input(input).redeemer(key.toPlutusData()).build().toPlutusData();
        assertTrue(accepts(applied, mintWithSeed));
        assertFalse(accepts(applied, builder(Purpose.MINT, null).input(input).redeemer(script.toPlutusData()).build().toPlutusData()));
        assertFalse(accepts(applied, builder(Purpose.MINT, null).redeemer(key.toPlutusData()).build().toPlutusData()));
        // A malformed Credential redeemer is rejected by the strict boundary.
        assertFalse(accepts(applied, builder(Purpose.MINT, null).input(input).redeemer(PlutusData.constr(2, PlutusData.bytes(new byte[28]))).build().toPlutusData()));
        // Spending reads its own input's inline datum through the optional adapter and a strict decode.
        var own = new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO);
        for (long datum : new long[] {5, 50}) {
            var ownInput = new TxInInfo(own, new TxOut(new Address(script, java.util.Optional.empty()), Value.lovelace(BigInteger.TEN),
                    new OutputDatum.OutputDatumInline(PlutusData.integer(datum)), java.util.Optional.empty()));
            var spend = ScriptContextBuilder.spending(own, PlutusData.integer(10)).input(ownInput).redeemer(PlutusData.UNIT).build().toPlutusData();
            assertEquals(datum <= 10, accepts(applied, spend), "datum " + datum);
        }
    }

    @Test
    void unapprovedOperationsAreRejected() {
        // TxOut contains the map-encoded Value: no equality, strict decoding or boundary use.
        var equality = """
                module Main exposing (main)

                import Julc.Ledger exposing (TxOut)


                main : TxOut -> Bool
                main out =
                    out == out
                """;
        assertEquals("ELCI2003", assertThrows(Diagnostic.class, () -> function(equality)).code());
        var decode = """
                module Main exposing (main)

                import Julc.Data as Data
                import Julc.Ledger exposing (TxOut)


                main : Data -> Int
                main raw =
                    let
                        out : TxOut
                        out =
                            Data.decode raw
                    in
                    1
                """;
        assertEquals("ELCI3003", assertThrows(Diagnostic.class, () -> function(decode)).code());
        var boundary = """
                module Main exposing (mint)

                import Julc.Ledger exposing (ScriptContext, TxOut)


                mint : TxOut -> ScriptContext -> Bool
                mint _ _ =
                    True
                """;
        var error = assertThrows(Diagnostic.class, () -> validator(boundary,
                List.of(new HandlerSpec(Purpose.MINT, "Main", "mint")), List.of()));
        assertEquals("ELCI2013", error.code());
        // Value has no approved constructors.
        var value = """
                module Main exposing (main)

                import Julc.Ledger exposing (Value(..))


                main : Int
                main =
                    1
                """;
        assertEquals("ELCI2104", assertThrows(Diagnostic.class, () -> function(value)).code());
        // [entrypoint] validators keep the Elci-data-only boundary.
        assertThrows(Diagnostic.class, () -> new ElciCompiler().compileProject(Map.of("Main", """
                module Main exposing (mint)

                import Julc.Ledger exposing (Credential, ScriptContext)


                mint : Credential -> ScriptContext -> Bool
                mint _ _ =
                    True
                """), "Main", "mint", FrontendProgram.Purpose.MINT, MODULES, new CompilerOptions(), LIBRARIES));
    }
}
