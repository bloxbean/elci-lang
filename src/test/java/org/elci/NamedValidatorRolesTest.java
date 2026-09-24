package org.elci;

import static org.elci.Contexts.*;
import static org.junit.jupiter.api.Assertions.*;

import org.elci.driver.ElciCompiler;
import org.elci.driver.ElciCompiler.HandlerSpec;
import org.elci.driver.ElciCompiler.ValidatorSpec;
import org.elci.syntax.Diagnostic;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.backend.DatumProfile;
import org.julclang.compiler.schema.ContractSchema.Purpose;
import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Every purpose, deployment parameters, datum profiles and Java multi-validator parity. */
class NamedValidatorRolesTest {
    static ElciCompiler.ValidatorBuild compile(Map<String, String> sources, ValidatorSpec spec) {
        return compile(sources, spec, Map.of());
    }

    static ElciCompiler.ValidatorBuild compile(Map<String, String> sources, ValidatorSpec spec, Map<String, String> java) {
        return new ElciCompiler().compileValidator(sources, spec, java, new CompilerOptions(), Map.of());
    }

    static ValidatorSpec spec(List<HandlerSpec> handlers) {
        return new ValidatorSpec("v", handlers, List.of(), DatumProfile.REQUIRED);
    }

    static final String ALL = """
            module All exposing (certify, mint, propose, spend, vote, withdraw)

            import Julc.Ledger exposing (ScriptContext)


            spend : Int -> Int -> ScriptContext -> Bool
            spend datum redeemer _ =
                datum + redeemer == 6


            mint : Int -> ScriptContext -> Bool
            mint redeemer _ =
                redeemer == 0


            withdraw : Int -> ScriptContext -> Bool
            withdraw redeemer _ =
                redeemer == 2


            certify : Int -> ScriptContext -> Bool
            certify redeemer _ =
                redeemer == 3


            vote : Int -> ScriptContext -> Bool
            vote redeemer _ =
                redeemer == 4


            propose : Int -> ScriptContext -> Bool
            propose redeemer _ =
                redeemer == 5
            """;

    @Test
    void everyPurposeDispatchesToItsHandler() {
        var handlers = Arrays.stream(Purpose.values())
                .map(p -> new HandlerSpec(p, "All", ElciCompiler.purpose(p))).toList();
        var build = compile(Map.of("All", ALL), spec(handlers));
        assertEquals(List.of(Purpose.MINT, Purpose.SPEND, Purpose.WITHDRAW, Purpose.CERTIFY, Purpose.VOTE, Purpose.PROPOSE),
                build.handlers().stream().map(ElciCompiler.HandlerInfo::purpose).toList());
        assertEquals(List.of(0, 1, 2, 3, 4, 5), build.handlers().stream().map(ElciCompiler.HandlerInfo::tag).toList());
        var program = build.program();
        assertTrue(accepts(program, context(Purpose.SPEND, PlutusData.integer(1), PlutusData.integer(5))));
        assertFalse(accepts(program, context(Purpose.SPEND, PlutusData.integer(1), PlutusData.integer(6))));
        int expected = 0;
        for (var purpose : List.of(Purpose.MINT, Purpose.WITHDRAW, Purpose.CERTIFY, Purpose.VOTE, Purpose.PROPOSE)) {
            expected = switch (purpose) {case MINT -> 0; case WITHDRAW -> 2; case CERTIFY -> 3; case VOTE -> 4; default -> 5;};
            assertTrue(accepts(program, context(purpose, PlutusData.integer(expected))), purpose.name());
            assertFalse(accepts(program, context(purpose, PlutusData.integer(expected + 1))), purpose.name());
            assertFalse(accepts(program, context(purpose, PlutusData.bytes(new byte[1]))), purpose.name());
        }
    }

    @Test
    void singleNonSpendingPurposesRejectEveryOtherPurpose() {
        for (var purpose : List.of(Purpose.WITHDRAW, Purpose.CERTIFY, Purpose.VOTE, Purpose.PROPOSE)) {
            var program = compile(Map.of("All", ALL),
                    spec(List.of(new HandlerSpec(purpose, "All", ElciCompiler.purpose(purpose))))).program();
            int good = switch (purpose) {case WITHDRAW -> 2; case CERTIFY -> 3; case VOTE -> 4; default -> 5;};
            assertTrue(accepts(program, context(purpose, PlutusData.integer(good))));
            for (var other : Purpose.values())
                if (other != purpose)
                    assertFalse(accepts(program, context(other, PlutusData.integer(good), PlutusData.integer(0))),
                            purpose + " script must reject " + other);
        }
    }

    static final String PARITY = """
            module Parity exposing (Action(..), mint, spend, withdraw)

            import Julc.Ledger exposing (ScriptContext)


            type Action
                = Action Int


            spend : Int -> Action -> ScriptContext -> Bool
            spend datum action _ =
                case action of
                    Action amount ->
                        datum <= amount


            mint : Int -> ScriptContext -> Bool
            mint redeemer _ =
                redeemer == 10


            withdraw : Int -> ScriptContext -> Bool
            withdraw redeemer _ =
                redeemer > 0
            """;

    static final String JAVA_PARITY = """
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.MultiValidator;
            import org.julclang.stdlib.annotation.Purpose;
            @MultiValidator
            class Parity {
                record Action(BigInteger amount) {}
                @Entrypoint(purpose = Purpose.SPEND)
                static boolean spend(BigInteger datum, Action action, ScriptContext ctx) {
                    return datum.compareTo(action.amount()) <= 0;
                }
                @Entrypoint(purpose = Purpose.MINT)
                static boolean mint(BigInteger redeemer, ScriptContext ctx) {
                    return redeemer.equals(BigInteger.TEN);
                }
                @Entrypoint(purpose = Purpose.WITHDRAW)
                static boolean withdraw(BigInteger redeemer, ScriptContext ctx) {
                    return redeemer.signum() > 0;
                }
            }
            """;

    @Test
    void outcomesAgreeWithTheJavaMultiValidator() {
        var java = new JulcCompiler().compile(JAVA_PARITY);
        assertFalse(java.hasErrors(), java::toString);
        var elci = compile(Map.of("Parity", PARITY), spec(List.of(new HandlerSpec(Purpose.SPEND, "Parity", "spend"),
                new HandlerSpec(Purpose.MINT, "Parity", "mint"), new HandlerSpec(Purpose.WITHDRAW, "Parity", "withdraw")))).program();
        var redeemers = List.of(PlutusData.integer(10), PlutusData.integer(11), PlutusData.integer(-1),
                PlutusData.constr(0, PlutusData.integer(5)), PlutusData.constr(0, PlutusData.integer(1)),
                PlutusData.constr(1, PlutusData.integer(5)), PlutusData.constr(0),
                PlutusData.constr(0, PlutusData.integer(5), PlutusData.integer(6)), PlutusData.bytes(new byte[2]));
        var datums = Arrays.asList(PlutusData.integer(3), PlutusData.integer(7), null, PlutusData.bytes(new byte[0]));
        int checked = 0, accepted = 0;
        for (var purpose : Purpose.values())
            for (var redeemer : redeemers)
                for (var datum : purpose == Purpose.SPEND ? datums : Arrays.asList((PlutusData) null)) {
                    var ctx = context(purpose, redeemer, datum);
                    boolean elciAccepts = accepts(elci, ctx);
                    assertEquals(accepts(java.program(), ctx), elciAccepts, purpose + " redeemer=" + redeemer + " datum=" + datum);
                    checked++;
                    if (elciAccepts) accepted++;
                }
        assertEquals(81, checked);
        assertEquals(4, accepted, "the matrix must exercise both outcomes");
    }

    @Test
    void parametersAreDecodedOnceAndPassedToEveryHandlerInOrder() {
        var gate = """
                module Gate exposing (mint, spend)

                import Julc.Ledger exposing (ScriptContext)


                spend : Int -> Int -> Int -> Int -> ScriptContext -> Bool
                spend threshold bonus datum redeemer _ =
                    datum + redeemer + bonus >= threshold


                mint : Int -> Int -> Int -> ScriptContext -> Bool
                mint threshold bonus redeemer _ =
                    redeemer + bonus == threshold
                """;
        var spec = new ValidatorSpec("gate", List.of(new HandlerSpec(Purpose.SPEND, "Gate", "spend"),
                new HandlerSpec(Purpose.MINT, "Gate", "mint")), List.of("threshold", "bonus"), DatumProfile.REQUIRED);
        var build = compile(Map.of("Gate", gate), spec);
        assertEquals(List.of("threshold", "bonus"), build.parameters().stream().map(ElciCompiler.ParameterInfo::name).toList());
        var applied = build.program().applyParams(PlutusData.integer(10), PlutusData.integer(1));
        assertTrue(accepts(applied, context(Purpose.MINT, PlutusData.integer(9))));
        assertFalse(accepts(applied, context(Purpose.MINT, PlutusData.integer(10))));
        assertTrue(accepts(applied, context(Purpose.SPEND, PlutusData.integer(4), PlutusData.integer(5))));
        assertFalse(accepts(applied, context(Purpose.SPEND, PlutusData.integer(3), PlutusData.integer(5))));
        // Order matters: threshold=1, bonus=10.
        var swapped = build.program().applyParams(PlutusData.integer(1), PlutusData.integer(10));
        assertFalse(accepts(swapped, context(Purpose.MINT, PlutusData.integer(9))));
        String unapplied = JulcScriptAdapter.scriptHash(build.program());
        String hash = JulcScriptAdapter.scriptHash(applied);
        assertNotEquals(unapplied, hash);
        assertNotEquals(hash, JulcScriptAdapter.scriptHash(swapped));
        assertEquals(hash, JulcScriptAdapter.scriptHash(compile(Map.of("Gate", gate), spec).program()
                .applyParams(PlutusData.integer(10), PlutusData.integer(1))));
    }

    static final String DATUMS = """
            module Datums exposing (absent, optional)

            import Julc.Ledger exposing (ScriptContext)


            optional : Maybe Int -> Int -> ScriptContext -> Bool
            optional datum redeemer _ =
                case datum of
                    Just value ->
                        value == redeemer

                    Nothing ->
                        redeemer == 0


            absent : Int -> ScriptContext -> Bool
            absent redeemer _ =
                redeemer == 1
            """;

    @Test
    void optionalDatumReachesTheHandlerAsMaybe() {
        var program = compile(Map.of("Datums", DATUMS), new ValidatorSpec("d",
                List.of(new HandlerSpec(Purpose.SPEND, "Datums", "optional")), List.of(), DatumProfile.OPTIONAL)).program();
        assertTrue(accepts(program, context(Purpose.SPEND, PlutusData.integer(4), PlutusData.integer(4))));
        assertFalse(accepts(program, context(Purpose.SPEND, PlutusData.integer(5), PlutusData.integer(4))));
        assertTrue(accepts(program, context(Purpose.SPEND, PlutusData.integer(0))));
        assertFalse(accepts(program, context(Purpose.SPEND, PlutusData.integer(4))));
        // A present datum is still strictly checked.
        assertFalse(accepts(program, context(Purpose.SPEND, PlutusData.integer(0), PlutusData.bytes(new byte[1]))));
    }

    @Test
    void absentDatumIsNeverInspected() {
        var program = compile(Map.of("Datums", DATUMS), new ValidatorSpec("d",
                List.of(new HandlerSpec(Purpose.SPEND, "Datums", "absent")), List.of(), DatumProfile.ABSENT)).program();
        assertTrue(accepts(program, context(Purpose.SPEND, PlutusData.integer(1))));
        assertTrue(accepts(program, context(Purpose.SPEND, PlutusData.integer(1), PlutusData.bytes(new byte[3]))));
        assertFalse(accepts(program, context(Purpose.SPEND, PlutusData.integer(2), PlutusData.integer(1))));
    }

    static void rejects(String code, Runnable compile) {
        var error = assertThrows(RuntimeException.class, compile::run);
        String message = error instanceof Diagnostic d ? d.code() + ": " + d.getMessage() : error.getMessage();
        assertTrue(message.contains(code), message);
    }

    @Test
    void handlerRolesAndConfigurationAreChecked() {
        var sources = Map.of("All", ALL, "Datums", DATUMS);
        // A minting handler where a spending signature is required.
        rejects("ELCI2014", () -> compile(sources, spec(List.of(new HandlerSpec(Purpose.SPEND, "All", "mint")))));
        rejects("ELCI2014", () -> compile(sources, spec(List.of(new HandlerSpec(Purpose.MINT, "All", "spend")))));
        // Optional datums must be written as Maybe.
        rejects("ELCI2014", () -> compile(sources, new ValidatorSpec("v", List.of(new HandlerSpec(Purpose.SPEND, "All", "spend")),
                List.of(), DatumProfile.OPTIONAL)));
        rejects("does not declare", () -> compile(sources, spec(List.of(new HandlerSpec(Purpose.MINT, "All", "missing")))));
        rejects("no source module", () -> compile(sources, spec(List.of(new HandlerSpec(Purpose.MINT, "Nope", "mint")))));
        rejects("more than once", () -> compile(sources, spec(List.of(new HandlerSpec(Purpose.MINT, "All", "mint"),
                new HandlerSpec(Purpose.MINT, "All", "vote")))));
        rejects("without a spend handler", () -> compile(sources, new ValidatorSpec("v",
                List.of(new HandlerSpec(Purpose.MINT, "All", "mint")), List.of(), DatumProfile.ABSENT)));
        var hidden = """
                module Hidden exposing (visible)

                import Julc.Ledger exposing (ScriptContext)


                visible : Int
                visible =
                    1


                mint : Int -> ScriptContext -> Bool
                mint _ _ =
                    True
                """;
        rejects("ELCI2103", () -> compile(Map.of("Hidden", hidden), spec(List.of(new HandlerSpec(Purpose.MINT, "Hidden", "mint")))));
        var generic = """
                module Generic exposing (mint)

                import Julc.Ledger exposing (ScriptContext)


                mint : a -> ScriptContext -> Bool
                mint _ _ =
                    True
                """;
        rejects("ELCI2009", () -> compile(Map.of("Generic", generic), spec(List.of(new HandlerSpec(Purpose.MINT, "Generic", "mint")))));
        var function = """
                module Fn exposing (mint)

                import Julc.Ledger exposing (ScriptContext)


                mint : (Int -> Int) -> ScriptContext -> Bool
                mint _ _ =
                    True
                """;
        rejects("ELCI2013", () -> compile(Map.of("Fn", function), spec(List.of(new HandlerSpec(Purpose.MINT, "Fn", "mint")))));
        // Handlers disagree on the parameter type.
        var mismatch = """
                module Mismatch exposing (mint, withdraw)

                import Julc.Ledger exposing (ScriptContext)


                mint : Int -> Int -> ScriptContext -> Bool
                mint _ _ _ =
                    True


                withdraw : Bytes -> Int -> ScriptContext -> Bool
                withdraw _ _ _ =
                    True
                """;
        rejects("ELCI2014", () -> compile(Map.of("Mismatch", mismatch), new ValidatorSpec("v",
                List.of(new HandlerSpec(Purpose.MINT, "Mismatch", "mint"), new HandlerSpec(Purpose.WITHDRAW, "Mismatch", "withdraw")),
                List.of("p"), DatumProfile.REQUIRED)));
        // A parameter that cannot be decoded from Data.
        var functionParameter = """
                module FnParam exposing (mint)

                import Julc.Ledger exposing (ScriptContext)


                mint : (Int -> Int) -> Int -> ScriptContext -> Bool
                mint _ _ _ =
                    True
                """;
        rejects("ELCI2013", () -> compile(Map.of("FnParam", functionParameter), new ValidatorSpec("v",
                List.of(new HandlerSpec(Purpose.MINT, "FnParam", "mint")), List.of("f"), DatumProfile.REQUIRED)));
    }
}
