package org.elci;

import static org.junit.jupiter.api.Assertions.*;

import org.elci.driver.ElciCompiler;
import org.elci.syntax.Diagnostic;
import org.julclang.compiler.backend.FrontendProgram;
import org.julclang.core.Constant;
import org.julclang.core.Term;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

class SyntaxCorpusTest {
    private String resource(String path) throws IOException {
        try (var input = getClass().getResourceAsStream(path)) {
            assertNotNull(input, "missing syntax corpus resource " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void acceptsAndEvaluatesSupportedElmSyntax() throws IOException {
        var compiled =
                new ElciCompiler()
                        .compile(
                                resource("/syntax/valid/core-completion.elm"),
                                "main",
                                FrontendProgram.Purpose.FUNCTION);
        var result =
                assertInstanceOf(
                        EvalResult.Success.class,
                        JulcVm.create()
                                .evaluate(compiled.program(), compiled.target().ledgerTarget()));
        assertEquals(new Term.Const(Constant.integer(16)), result.resultTerm());
    }

    @Test
    void rejectsElmValidButDeferredSyntaxWithAFocusedDiagnostic() throws IOException {
        var error =
                assertThrows(
                        Diagnostic.class,
                        () ->
                                new ElciCompiler()
                                        .compile(
                                                resource(
                                                        "/syntax/unsupported/extensible-record.elm"),
                                                "value",
                                                FrontendProgram.Purpose.FUNCTION));
        assertEquals("ELCI1002", error.code());
        assertTrue(error.getMessage().contains("Extensible records are unsupported"));
    }

    @Test
    void rejectsMalformedSyntaxAtItsSourceLocation() throws IOException {
        var error =
                assertThrows(
                        Diagnostic.class,
                        () ->
                                new ElciCompiler()
                                        .compile(
                                                resource(
                                                        "/syntax/malformed/missing-case-arrow.elm"),
                                                "main",
                                                FrontendProgram.Purpose.FUNCTION));
        assertEquals("ELCI1002", error.code());
        assertEquals(8, error.token().line());
        assertTrue(error.token().column() > 0);
    }
}
