package org.elci;

import static org.junit.jupiter.api.Assertions.*;

import org.elci.driver.ElciCompiler;
import org.julclang.compiler.backend.FrontendProgram;
import org.julclang.core.Constant;
import org.julclang.core.Term;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** The compiler-provided {@code Bytes} and {@code Debug} modules. */
class IntrinsicsTest {
    static EvalResult evaluate(String body) {
        var result = new ElciCompiler().compile("module Main exposing (main)\nimport Bytes\nimport Debug\n" + body,
                "main", FrontendProgram.Purpose.FUNCTION);
        return JulcVm.create().evaluate(result.program(), result.target().ledgerTarget());
    }

    static Term value(String body) {
        return assertInstanceOf(EvalResult.Success.class, evaluate(body)).resultTerm();
    }

    @Test
    void bytesFunctionsAreSingleBuiltins() throws Exception {
        assertEquals(new Term.Const(Constant.byteString("GOLD".getBytes(StandardCharsets.UTF_8))),
                value("main : Bytes\nmain = Bytes.fromString \"GOLD\"\n"));
        assertEquals(new Term.Const(Constant.string("GOLDBAR")),
                value("main : String\nmain = Bytes.toString (Bytes.append (Bytes.fromString \"GOLD\") (Bytes.fromString \"BAR\"))\n"));
        assertEquals(new Term.Const(Constant.integer(4)), value("main : Int\nmain = Bytes.length (Bytes.fromString \"GOLD\")\n"));
        assertEquals(new Term.Const(Constant.string("OL")),
                value("main : String\nmain = Bytes.toString (Bytes.slice 1 2 (Bytes.fromString \"GOLD\"))\n"));
        assertEquals(new Term.Const(Constant.integer(0)), value("main : Int\nmain = Bytes.length Bytes.empty\n"));
        var sha = MessageDigest.getInstance("SHA-256").digest("x".getBytes(StandardCharsets.UTF_8));
        assertEquals(new Term.Const(Constant.byteString(sha)), value("main : Bytes\nmain = Bytes.sha256 (Bytes.fromString \"x\")\n"));
        assertEquals(new Term.Const(Constant.integer(28)),
                value("main : Int\nmain = Bytes.length (Bytes.blake2b224 Bytes.empty)\n"));
        assertEquals(new Term.Const(Constant.integer(32)),
                value("main : Int\nmain = Bytes.length (Bytes.blake2b256 Bytes.empty)\n"));
        assertEquals(new Term.Const(Constant.bool(true)),
                value("main : Bool\nmain = Bytes.fromString \"a\" == Bytes.fromString \"a\"\n"));
    }

    @Test
    void debugTraceEmitsAndReturnsItsValue() {
        var result = assertInstanceOf(EvalResult.Success.class,
                evaluate("main : Int\nmain = Debug.trace \"first\" (Debug.trace \"second\" 1) + 1\n"));
        assertEquals(new Term.Const(Constant.integer(2)), result.resultTerm());
        assertEquals(List.of("second", "first"), result.traces());
        // A failing script still reports the traces emitted before the failure.
        var failure = evaluate("main : Int\nmain = Debug.trace \"before\" (1 // 0)\n");
        assertFalse(failure instanceof EvalResult.Success);
    }
}
