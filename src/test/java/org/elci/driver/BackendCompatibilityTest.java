package org.elci.driver;

import static org.junit.jupiter.api.Assertions.*;

import org.elci.driver.ElciCompiler.HandlerSpec;
import org.elci.driver.ElciCompiler.ValidatorSpec;
import org.elci.syntax.Ast;
import org.elci.syntax.Diagnostic;
import org.elci.syntax.Token;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.backend.*;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.schema.ContractSchema.Purpose;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** ADR-005 E6: capability negotiation before lowering and backend diagnostics at source locations. */
class BackendCompatibilityTest {
    static final String VOTE = """
            module Gov exposing (vote)

            import Julc.Ledger exposing (ScriptContext)


            vote : Int -> ScriptContext -> Bool
            vote redeemer _ =
                redeemer == True
            """;

    /** The real backend's capabilities without {@code removed}, or at another revision. */
    static ElciCompiler without(int revision, String... removed) {
        return new ElciCompiler(options -> {
            var real = new CompilerBackend().capabilities(options);
            var kept = new TreeSet<>(real.capabilities());
            for (String id : removed) kept.remove(new BackendCapability(id));
            return new BackendCapabilities(revision, real.minimumRevision(), real.target(), kept);
        });
    }

    static ValidatorSpec spec(Purpose purpose, String module, String function) {
        return new ValidatorSpec("v", List.of(new HandlerSpec(purpose, module, function)), List.of(), DatumProfile.REQUIRED);
    }

    @Test
    void missingRoleCapabilitiesFailBeforeTypeCheckingOrLowering() {
        // VOTE also has a type error; the capability check must report first.
        var error = assertThrows(IllegalArgumentException.class, () -> without(2, "purpose.vote")
                .compileValidator(Map.of("Gov", VOTE), spec(Purpose.VOTE, "Gov", "vote"), Map.of(), new CompilerOptions(), Map.of()));
        assertTrue(error.getMessage().startsWith("ELCI4003"), error.getMessage());
        assertTrue(error.getMessage().contains("purpose.vote"), error.getMessage());
        var revision = assertThrows(IllegalArgumentException.class, () -> without(1)
                .compileValidator(Map.of("Gov", VOTE), spec(Purpose.VOTE, "Gov", "vote"), Map.of(), new CompilerOptions(), Map.of()));
        assertTrue(revision.getMessage().contains("contract revision 2"), revision.getMessage());
        var optional = assertThrows(IllegalArgumentException.class, () -> without(2, "spend.datum.optional")
                .compileValidator(Map.of("Gov", VOTE), new ValidatorSpec("v", List.of(new HandlerSpec(Purpose.SPEND, "Gov", "vote")),
                        List.of(), DatumProfile.OPTIONAL), Map.of(), new CompilerOptions(), Map.of()));
        assertTrue(optional.getMessage().contains("spend.datum.optional"), optional.getMessage());
    }

    @Test
    void missingLibraryCapabilitiesFailAtTheUseBeforeMaterialization() {
        var source = """
                module Main exposing (mint)

                import Julc.Ledger exposing (ScriptContext)
                import Julc.Lists as Lists


                mint : List Int -> ScriptContext -> Bool
                mint amounts _ =
                    Lists.any amounts (\\a -> a > 0)
                """;
        var error = assertThrows(Diagnostic.class, () -> without(2, "library.request.instantiate")
                .compileValidator(Map.of("Main", source), spec(Purpose.MINT, "Main", "mint"),
                        Map.of("Julc.Lists", "org.julclang.stdlib.lib.ListsLib"), new CompilerOptions(), Map.of()));
        assertEquals("ELCI4003", error.code());
        assertEquals(9, error.token().line());
        assertEquals("Main", error.token().source());
        assertTrue(error.getMessage().contains("library.request.instantiate"), error.getMessage());
        // With the capability, the same program compiles and records what it relies on.
        var build = new ElciCompiler().compileValidator(Map.of("Main", source), spec(Purpose.MINT, "Main", "mint"),
                Map.of("Julc.Lists", "org.julclang.stdlib.lib.ListsLib"), new CompilerOptions(), Map.of());
        assertNotNull(build.program());
    }

    @Test
    void malformedPirRejectionsPointAtTheGeneratedDeclaration() {
        var helper = new Token("helper", Token.Kind.NAME, 40, 7, 1, "Shared");
        var fallback = new Token("mint", Token.Kind.NAME, 90, 12, 1, "Main");
        var declarations = Map.of(
                "Shared.helper", new Ast.Decl("Shared.helper", List.of(), null, new Ast.Literal(1, helper), helper),
                "Main.mint", new Ast.Decl("Main.mint", List.of(), null, new Ast.Literal(1, fallback), fallback));
        var mismatch = new BackendException(DiagnosticCodes.PIR_TYPE_MISMATCH, "$elci$Shared.helper$3",
                "$elci$Shared.helper$3", "Int versus Bool");
        var located = ElciCompiler.located(mismatch, declarations, fallback);
        assertEquals("ELCI4002", located.code());
        assertSame(helper, located.token());
        assertTrue(located.getMessage().contains("JULC0049"), located.getMessage());
        // Handler symbols name their declaration directly.
        var descriptor = new BackendException(DiagnosticCodes.BACKEND_INVALID_DESCRIPTOR, "validator v",
                "validator v", "MINT handler Main.mint must have type redeemer -> context -> Bool");
        assertSame(fallback, ElciCompiler.located(descriptor, declarations, helper).token());
        var unknown = new BackendException(DiagnosticCodes.BACKEND_INVALID_DESCRIPTOR, "validator v", "validator v", "other");
        assertSame(fallback, ElciCompiler.located(unknown, declarations, fallback).token());
    }
}
