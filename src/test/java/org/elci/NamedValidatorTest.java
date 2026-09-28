package org.elci;

import static org.elci.Contexts.*;
import static org.junit.jupiter.api.Assertions.*;

import org.elci.driver.ElciCompiler;
import org.elci.driver.ElciCompiler.HandlerSpec;
import org.elci.driver.ElciCompiler.ValidatorSpec;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.backend.DatumProfile;
import org.julclang.compiler.schema.ContractSchema.Purpose;
import org.julclang.core.PlutusData;
import org.julclang.core.flat.UplcFlatEncoder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class NamedValidatorTest {
    static final String VAULT = """
            module Vault exposing (Action(..), Lock, spend, withdraw)

            import Julc.Ledger exposing (ScriptContext)


            type alias Lock =
                { owner : Int, limit : Int }


            type Action
                = Claim Int
                | Close


            spend : Lock -> Action -> ScriptContext -> Bool
            spend lock action _ =
                case action of
                    Claim amount ->
                        amount <= lock.limit

                    Close ->
                        lock.owner == 7


            withdraw : Int -> ScriptContext -> Bool
            withdraw amount _ =
                amount > 0
            """;

    static ElciCompiler.ValidatorBuild compile(Map<String, String> sources, List<HandlerSpec> handlers) {
        return compile(sources, handlers, List.of(), DatumProfile.REQUIRED);
    }

    static ElciCompiler.ValidatorBuild compile(Map<String, String> sources, List<HandlerSpec> handlers,
                                               List<String> parameters, DatumProfile datum) {
        return new ElciCompiler().compileValidator(sources, new ValidatorSpec("vault", handlers, parameters, datum),
                Map.of(), new CompilerOptions(), Map.of());
    }

    static final List<HandlerSpec> SPEND_WITHDRAW = List.of(
            new HandlerSpec(Purpose.SPEND, "Vault", "spend"),
            new HandlerSpec(Purpose.WITHDRAW, "Vault", "withdraw"));

    /** Record fields are encoded in field-name order: limit, then owner. */
    static PlutusData lock(long owner, long limit) {
        return PlutusData.constr(0, PlutusData.integer(limit), PlutusData.integer(owner));
    }

    @Test
    void spendAndWithdrawShareOneScriptAndDispatchByPurpose() {
        var program = compile(Map.of("Vault", VAULT), SPEND_WITHDRAW).program();
        var claim = PlutusData.constr(0, PlutusData.integer(5));
        var close = PlutusData.constr(1);
        assertTrue(accepts(program, context(Purpose.SPEND, claim, lock(7, 10))));
        assertFalse(accepts(program, context(Purpose.SPEND, claim, lock(7, 4))));
        assertTrue(accepts(program, context(Purpose.SPEND, close, lock(7, 0))));
        assertFalse(accepts(program, context(Purpose.SPEND, close, lock(8, 0))));
        assertTrue(accepts(program, context(Purpose.WITHDRAW, PlutusData.integer(3))));
        assertFalse(accepts(program, context(Purpose.WITHDRAW, PlutusData.integer(0))));
        // Unconfigured purposes fail explicitly.
        for (var purpose : List.of(Purpose.MINT, Purpose.CERTIFY, Purpose.VOTE, Purpose.PROPOSE))
            assertFalse(accepts(program, context(purpose, PlutusData.integer(3))), purpose.name());
        // Only the selected handler's redeemer is decoded: the withdrawal integer is not an Action.
        assertFalse(accepts(program, context(Purpose.SPEND, PlutusData.integer(3), lock(7, 10))));
        assertFalse(accepts(program, context(Purpose.WITHDRAW, claim)));
        // A missing or malformed spending datum fails.
        assertFalse(accepts(program, context(Purpose.SPEND, claim)));
        assertFalse(accepts(program, context(Purpose.SPEND, claim, PlutusData.integer(1))));
    }

    @Test
    void handlerOrderDoesNotAffectTheScript() {
        var forward = compile(Map.of("Vault", VAULT), SPEND_WITHDRAW).program();
        var reversed = compile(Map.of("Vault", VAULT), SPEND_WITHDRAW.reversed()).program();
        assertArrayEquals(UplcFlatEncoder.encodeProgram(forward), UplcFlatEncoder.encodeProgram(reversed));
    }
}
