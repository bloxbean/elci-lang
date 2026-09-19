package org.elci;

import org.elci.driver.ElciCompiler;
import org.elci.syntax.Diagnostic;
import org.julclang.compiler.backend.FrontendProgram;
import org.julclang.core.*;
import org.julclang.ledger.*;
import org.julclang.vm.*;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LedgerTest {
    private static final String SIGNED="""
        module Signed exposing (validate)
        import Julc.Ledger exposing (PubKeyHash, ScriptContext)

        validate : PubKeyHash -> Int -> ScriptContext -> Bool
        validate owner minimumFee ctx =
            ctx.txInfo.fee >= minimumFee
                && List.any (\\signer -> signer == owner) ctx.txInfo.signatories
        """;
    @Test void ledgerFieldsAndNominalHashEquality() {
        var result=new ElciCompiler().compile(SIGNED,"validate",FrontendProgram.Purpose.SPEND);
        byte[] hash=new byte[28];hash[0]=1;
        var owner=new PubKeyHash(hash);
        var ref=new TxOutRef(new TxId(new byte[32]),BigInteger.ZERO);
        var yes=ScriptContextBuilder.spending(ref,owner.toPlutusData()).redeemer(PlutusData.integer(10)).fee(BigInteger.valueOf(20)).signer(owner).build().toPlutusData();
        var no=ScriptContextBuilder.spending(ref,owner.toPlutusData()).redeemer(PlutusData.integer(10)).fee(BigInteger.valueOf(20)).build().toPlutusData();
        var vm=JulcVm.create();
        assertInstanceOf(EvalResult.Success.class,vm.evaluateWithArgs(result.program(),result.target().ledgerTarget(),List.of(yes),null,EvalOptions.DEFAULT));
        assertFalse(vm.evaluateWithArgs(result.program(),result.target().ledgerTarget(),List.of(no),null,EvalOptions.DEFAULT) instanceof EvalResult.Success);
    }
    @Test void hashesDoNotUnifyWithBytes(){assertThrows(Diagnostic.class,()->new ElciCompiler().compile(SIGNED.replace("signer == owner","signer.hash == owner"),"validate",FrontendProgram.Purpose.SPEND));}
}
