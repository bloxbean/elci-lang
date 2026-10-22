package org.elci;

import static org.junit.jupiter.api.Assertions.*;

import org.elci.driver.ElciCompiler;
import org.elci.syntax.Diagnostic;
import org.julclang.compiler.backend.FrontendProgram;
import org.julclang.core.Constant;
import org.julclang.core.Term;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

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

    /**
     * The corpus has a module for each area of docs/GRAMMAR.md. Each one's {@code main}
     * checks what it parsed and returns True.
     */
    @TestFactory
    Stream<DynamicTest> everyValidCorpusModuleCompilesAndChecksItself() throws IOException {
        var directory = Path.of("src/test/resources/syntax/valid");
        List<Path> modules;
        try (var files = Files.list(directory)) {
            modules =
                    files.filter(path -> path.toString().endsWith(".elm"))
                            .filter(path -> !path.getFileName().toString().equals("core-completion.elm"))
                            .sorted()
                            .toList();
        }
        assertFalse(modules.isEmpty(), "no corpus modules in " + directory);
        return modules.stream()
                .map(
                        path ->
                                DynamicTest.dynamicTest(
                                        path.getFileName().toString(),
                                        () -> {
                                            var compiled =
                                                    new ElciCompiler()
                                                            .compile(
                                                                    Files.readString(path),
                                                                    "main",
                                                                    FrontendProgram.Purpose.FUNCTION);
                                            var result =
                                                    assertInstanceOf(
                                                            EvalResult.Success.class,
                                                            JulcVm.create()
                                                                    .evaluate(
                                                                            compiled.program(),
                                                                            compiled.target().ledgerTarget()));
                                            assertEquals(
                                                    new Term.Const(Constant.bool(true)),
                                                    result.resultTerm());
                                        }));
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
