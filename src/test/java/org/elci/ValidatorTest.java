package org.elci;

import org.elci.driver.ElciCompiler;
import org.julclang.compiler.*;
import org.julclang.compiler.backend.*;
import org.julclang.core.*;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.ledger.*;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.*;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ValidatorTest {
    private static final String JAVA="""
        import java.math.BigInteger;
        import org.julclang.ledger.ScriptContext;
        import org.julclang.stdlib.annotation.SpendingValidator;
        import org.julclang.stdlib.annotation.Entrypoint;
        import org.julclang.stdlib.lib.MathLib;
        @SpendingValidator
        public class Threshold {
            @Entrypoint
            public static boolean validate(BigInteger minimum, BigInteger offered, ScriptContext ctx) {
                return offered.compareTo(MathLib.max(minimum, BigInteger.ZERO)) >= 0;
            }
        }
        """;
    private static PlutusData context(PlutusData datum,PlutusData redeemer) {
        return ScriptContextBuilder.spending(new TxOutRef(new TxId(new byte[32]),BigInteger.ZERO),datum)
                .redeemer(redeemer).build().toPlutusData();
    }
    @Test void thresholdDifferentialAndMalformedBoundaries() throws Exception {
        String source=Files.readString(Path.of("examples/threshold/src/Example/Threshold.elm"));
        var elci=Scripts.spend(source,"validate");
        var java=new JulcCompiler(StdlibRegistry.defaultRegistry()).compile(JAVA);
        var vm=JulcVm.create();
        for(long[] pair:new long[][]{{5,6},{5,4},{-5,0},{0,0},{1,1},{-1,-1},{1000,999}}) {
            var ctx=context(PlutusData.integer(pair[0]),PlutusData.integer(pair[1]));
            var e=vm.evaluateWithArgs(elci.program(),elci.target().ledgerTarget(),List.of(ctx),null,EvalOptions.DEFAULT);
            var j=vm.evaluateWithArgs(java.program(),java.target().ledgerTarget(),List.of(ctx),null,EvalOptions.DEFAULT);
            assertEquals(j instanceof EvalResult.Success,e instanceof EvalResult.Success,Arrays.toString(pair));
            assertEquals(pair[1]>=Math.max(pair[0],0),e instanceof EvalResult.Success);
        }
        for(var bad:List.of(PlutusData.bytes(new byte[0]),PlutusData.constr(0),PlutusData.list())) {
            assertFalse(vm.evaluateWithArgs(elci.program(),elci.target().ledgerTarget(),List.of(context(bad,PlutusData.integer(10))),null,EvalOptions.DEFAULT) instanceof EvalResult.Success);
            assertFalse(vm.evaluateWithArgs(elci.program(),elci.target().ledgerTarget(),List.of(context(PlutusData.integer(0),bad)),null,EvalOptions.DEFAULT) instanceof EvalResult.Success);
        }
        assertArrayEquals(UplcFlatEncoder.encodeProgram(elci.program()),UplcFlatEncoder.encodeProgram(Scripts.spend(source,"validate").program()));
    }
    @Test void missingDatumFails() throws Exception {
        var result=Scripts.spend(Files.readString(Path.of("examples/threshold/src/Example/Threshold.elm")),"validate");
        var context=ScriptContextBuilder.spending(new TxOutRef(new TxId(new byte[32]),BigInteger.ZERO)).redeemer(PlutusData.integer(10)).build().toPlutusData();
        assertFalse(JulcVm.create().evaluateWithArgs(result.program(),result.target().ledgerTarget(),List.of(context),null,EvalOptions.DEFAULT) instanceof EvalResult.Success);
    }
}
