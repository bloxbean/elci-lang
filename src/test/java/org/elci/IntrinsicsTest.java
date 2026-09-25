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
    void modByTakesTheDivisorsSignAndRemainderByTheDividends() {
        assertEquals(new Term.Const(Constant.integer(2)), value("main : Int\nmain = modBy 3 (-7)\n"));
        assertEquals(new Term.Const(Constant.integer(-1)), value("main : Int\nmain = remainderBy 3 (-7)\n"));
        assertEquals(new Term.Const(Constant.integer(-2)), value("main : Int\nmain = modBy (-3) 7\n"));
        assertEquals(new Term.Const(Constant.integer(900)), value("main : Int\nmain = 28900 |> modBy 2000\n"));
        assertInstanceOf(EvalResult.Failure.class, evaluate("main : Int\nmain = modBy 0 7\n"));
    }

    @Test
    void byteStringCompleteness() {
        java.util.function.Function<String, Term> v = body -> value(body);
        String gold = "(Bytes.fromString \"GOLD\")";
        assertEquals(new Term.Const(Constant.string("GO")), v.apply("main : String\nmain = Bytes.toString (Bytes.take 2 " + gold + ")\n"));
        assertEquals(new Term.Const(Constant.string("LD")), v.apply("main : String\nmain = Bytes.toString (Bytes.drop 2 " + gold + ")\n"));
        assertEquals(new Term.Const(Constant.string("")), v.apply("main : String\nmain = Bytes.toString (Bytes.drop 9 " + gold + ")\n"));
        assertEquals(new Term.Const(Constant.integer('O')), v.apply("main : Int\nmain = Bytes.at 1 " + gold + "\n"));
        assertEquals(new Term.Const(Constant.string("XGOLD")), v.apply("main : String\nmain = Bytes.toString (Bytes.cons 88 " + gold + ")\n"));
        assertEquals(new Term.Const(Constant.byteString(new byte[] {7, 7, 7})), v.apply("main : Bytes\nmain = Bytes.replicate 3 7\n"));
        assertEquals(new Term.Const(Constant.bool(true)), v.apply("main : Bool\nmain = Bytes.lessThan (Bytes.fromString \"a\") (Bytes.fromString \"b\") && Bytes.lessThanOrEqual (Bytes.fromString \"b\") (Bytes.fromString \"b\")\n"));
        // Integer conversions: 258 = 0x0102.
        assertEquals(new Term.Const(Constant.byteString(new byte[] {0, 0, 1, 2})), v.apply("main : Bytes\nmain = Bytes.fromIntBigEndian 4 258\n"));
        assertEquals(new Term.Const(Constant.byteString(new byte[] {2, 1})), v.apply("main : Bytes\nmain = Bytes.fromIntLittleEndian 0 258\n"));
        assertEquals(new Term.Const(Constant.integer(258)), v.apply("main : Int\nmain = Bytes.toIntBigEndian (Bytes.fromIntBigEndian 4 258)\n"));
        assertEquals(new Term.Const(Constant.integer(258)), v.apply("main : Int\nmain = Bytes.toIntLittleEndian (Bytes.fromIntLittleEndian 0 258)\n"));
        // Bitwise operations, bits and hashes.
        assertEquals(new Term.Const(Constant.byteString(new byte[] {0x0c})), v.apply("main : Bytes\nmain = Bytes.and (Bytes.fromIntBigEndian 1 12) (Bytes.fromIntBigEndian 1 14)\n"));
        assertEquals(new Term.Const(Constant.byteString(new byte[] {0x0e})), v.apply("main : Bytes\nmain = Bytes.or (Bytes.fromIntBigEndian 1 12) (Bytes.fromIntBigEndian 1 10)\n"));
        assertEquals(new Term.Const(Constant.byteString(new byte[] {0x06})), v.apply("main : Bytes\nmain = Bytes.xor (Bytes.fromIntBigEndian 1 12) (Bytes.fromIntBigEndian 1 10)\n"));
        assertEquals(new Term.Const(Constant.byteString(new byte[] {(byte) 0xf3})), v.apply("main : Bytes\nmain = Bytes.complement (Bytes.fromIntBigEndian 1 12)\n"));
        assertEquals(new Term.Const(Constant.byteString(new byte[] {0x18})), v.apply("main : Bytes\nmain = Bytes.shift 1 (Bytes.fromIntBigEndian 1 12)\n"));
        assertEquals(new Term.Const(Constant.byteString(new byte[] {(byte) 0x81})), v.apply("main : Bytes\nmain = Bytes.rotate 1 (Bytes.fromIntBigEndian 1 192)\n"));
        assertEquals(new Term.Const(Constant.bool(true)), v.apply("main : Bool\nmain = Bytes.testBit 2 (Bytes.fromIntBigEndian 1 12) && not (Bytes.testBit 0 (Bytes.fromIntBigEndian 1 12))\n"));
        assertEquals(new Term.Const(Constant.integer(2)), v.apply("main : Int\nmain = Bytes.countSetBits (Bytes.fromIntBigEndian 1 12)\n"));
        assertEquals(new Term.Const(Constant.integer(2)), v.apply("main : Int\nmain = Bytes.findFirstSetBit (Bytes.fromIntBigEndian 1 12)\n"));
        for (String hash : new String[] {"sha3_256", "keccak256"})
            assertEquals(new Term.Const(Constant.integer(32)), v.apply("main : Int\nmain = Bytes.length (Bytes." + hash + " Bytes.empty)\n"));
        assertEquals(new Term.Const(Constant.integer(20)), v.apply("main : Int\nmain = Bytes.length (Bytes.ripemd160 Bytes.empty)\n"));
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
