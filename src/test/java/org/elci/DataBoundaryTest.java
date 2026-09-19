package org.elci;

import org.elci.abi.Abi;
import org.elci.driver.ElciCompiler;
import org.julclang.compiler.backend.FrontendProgram;
import org.julclang.core.*;
import org.julclang.ledger.*;
import org.julclang.vm.*;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DataBoundaryTest {
    private static final String SOURCE="""
        module DataBoundary exposing (validate)
        import Julc.Ledger exposing (ScriptContext)

        type alias Listing = { price : Int, allowed : Bool }
        type Action = Buy Int | Cancel

        validate : Listing -> Action -> ScriptContext -> Bool
        validate listing action _ =
            case action of
                Buy amount -> listing.allowed && amount >= listing.price
                Cancel -> False
        """;
    private EvalResult evaluate(ElciCompiler.Result script,PlutusData datum,PlutusData redeemer) {
        var ctx=ScriptContextBuilder.spending(new TxOutRef(new TxId(new byte[32]),BigInteger.ZERO),datum).redeemer(redeemer).build().toPlutusData();
        return JulcVm.create().evaluateWithArgs(script.program(),script.target().ledgerTarget(),List.of(ctx),null,EvalOptions.DEFAULT);
    }
    @Test void recordOrderUnionTagsAndMalformedData() {
        var script=new ElciCompiler().compile(SOURCE,"validate",FrontendProgram.Purpose.SPEND);
        var datum=PlutusData.constr(0,PlutusData.constr(1),PlutusData.integer(5));
        var buy=PlutusData.constr(0,PlutusData.integer(6));
        assertInstanceOf(EvalResult.Success.class,evaluate(script,datum,buy));
        assertFalse(evaluate(script,datum,PlutusData.constr(1)) instanceof EvalResult.Success);
        for(var bad:List.of(
            PlutusData.constr(0,PlutusData.integer(5),PlutusData.constr(1)),
            PlutusData.constr(7,PlutusData.constr(1),PlutusData.integer(5)),
            PlutusData.constr(0,PlutusData.constr(2),PlutusData.integer(5)),
            PlutusData.constr(0,PlutusData.constr(1,PlutusData.integer(0)),PlutusData.integer(5)),
            PlutusData.constr(0,PlutusData.constr(1)),
            PlutusData.constr(0,PlutusData.constr(1),PlutusData.integer(5),PlutusData.integer(9)))) {
            assertFalse(evaluate(script,bad,buy) instanceof EvalResult.Success,bad.toString());
        }
        for(var bad:List.of(PlutusData.constr(0),PlutusData.constr(0,PlutusData.bytes(new byte[0])),PlutusData.constr(0,PlutusData.integer(6),PlutusData.integer(7)),PlutusData.constr(9)))
            assertFalse(evaluate(script,datum,bad) instanceof EvalResult.Success,bad.toString());
        String abi=Abi.manifest(script,FrontendProgram.Purpose.SPEND);
        String reordered=Abi.manifest(new ElciCompiler().compile(SOURCE.replace("price : Int, allowed : Bool","allowed : Bool, price : Int"),"validate",FrontendProgram.Purpose.SPEND),FrontendProgram.Purpose.SPEND);
        assertEquals(abi,reordered,"Record declaration order must not change its ABI");
        String changed=Abi.manifest(new ElciCompiler().compile(SOURCE.replace("Buy Int | Cancel","Cancel | Buy Int"),"validate",FrontendProgram.Purpose.SPEND),FrontendProgram.Purpose.SPEND);
        assertNotEquals(abi,changed,"Reordered union tags must change the ABI");
    }
    @Test void nestedListElementsCheckedEvenWhenUnused() {
        String source="module Lists exposing (validate)\nimport Julc.Ledger exposing (ScriptContext)\nvalidate : List Int -> Int -> ScriptContext -> Bool\nvalidate _ _ _ = True\n";
        var script=new ElciCompiler().compile(source,"validate",FrontendProgram.Purpose.SPEND);
        assertInstanceOf(EvalResult.Success.class,evaluate(script,PlutusData.list(PlutusData.integer(1)),PlutusData.integer(0)));
        assertFalse(evaluate(script,PlutusData.list(PlutusData.bytes(new byte[0])),PlutusData.integer(0)) instanceof EvalResult.Success);
    }
    @Test void recursiveDatumChecked() {
        String source="module Trees exposing (validate)\nimport Julc.Ledger exposing (ScriptContext)\ntype Tree = Leaf Int | Branch Tree Tree\nvalidate : Tree -> Int -> ScriptContext -> Bool\nvalidate _ _ _ = True\n";
        var script=new ElciCompiler().compile(source,"validate",FrontendProgram.Purpose.SPEND);
        var good=PlutusData.constr(1,PlutusData.constr(0,PlutusData.integer(1)),PlutusData.constr(0,PlutusData.integer(2)));
        var bad=PlutusData.constr(1,PlutusData.constr(0,PlutusData.bytes(new byte[0])),PlutusData.constr(0,PlutusData.integer(2)));
        assertInstanceOf(EvalResult.Success.class,evaluate(script,good,PlutusData.integer(0)));
        assertFalse(evaluate(script,bad,PlutusData.integer(0)) instanceof EvalResult.Success);
    }
}
