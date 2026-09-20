package org.elci;

import static org.junit.jupiter.api.Assertions.*;

import org.elci.driver.ElciCompiler;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.backend.FrontendProgram;
import org.julclang.core.PlutusData;
import org.julclang.ledger.Interval;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptContextBuilder;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxOutRef;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class VestingExampleTest {
    private static final BigInteger DEADLINE = BigInteger.valueOf(1_000);

    @Test
    void beneficiaryCanClaimAtOrAfterDeadline() throws Exception {
        var program = compile();
        var beneficiary = beneficiary();

        assertInstanceOf(
                EvalResult.Success.class,
                evaluate(program, beneficiary, Interval.after(DEADLINE), true));
        assertInstanceOf(
                EvalResult.Success.class,
                evaluate(program, beneficiary, Interval.after(DEADLINE.add(BigInteger.ONE)), true));
    }

    @Test
    void rejectsEarlyOrUnsignedClaims() throws Exception {
        var program = compile();
        var beneficiary = beneficiary();

        assertFalse(
                evaluate(
                                program,
                                beneficiary,
                                Interval.before(DEADLINE.subtract(BigInteger.ONE)),
                                true)
                        instanceof EvalResult.Success);
        assertFalse(
                evaluate(program, beneficiary, Interval.after(DEADLINE), false)
                        instanceof EvalResult.Success);
    }

    private ElciCompiler.Result compile() throws Exception {
        var source = Files.readString(Path.of("examples/vesting/src/Vesting.elm"));
        return new ElciCompiler()
                .compile(
                        source,
                        "validate",
                        FrontendProgram.Purpose.SPEND,
                        Map.of("Julc.Interval", "org.julclang.stdlib.lib.IntervalLib"),
                        new CompilerOptions());
    }

    private EvalResult evaluate(
            ElciCompiler.Result program,
            PubKeyHash beneficiary,
            Interval validRange,
            boolean signed) {
        var datum = PlutusData.constr(0, beneficiary.toPlutusData(), PlutusData.integer(DEADLINE));
        var redeemer = PlutusData.constr(0);
        var builder =
                ScriptContextBuilder.spending(
                                new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO), datum)
                        .redeemer(redeemer)
                        .validRange(validRange);
        if (signed) builder.signer(beneficiary);
        return JulcVm.create()
                .evaluateWithArgs(
                        program.program(),
                        program.target().ledgerTarget(),
                        List.of(builder.build().toPlutusData()),
                        null,
                        EvalOptions.DEFAULT);
    }

    private PubKeyHash beneficiary() {
        var bytes = new byte[28];
        bytes[0] = 1;
        return new PubKeyHash(bytes);
    }
}
