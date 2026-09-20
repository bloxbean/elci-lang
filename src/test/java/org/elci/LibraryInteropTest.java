package org.elci;

import org.elci.driver.ElciCompiler;
import org.elci.syntax.Diagnostic;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.LibrarySources;
import org.julclang.compiler.backend.FrontendProgram;
import org.julclang.core.Constant;
import org.julclang.core.Term;
import org.julclang.vm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class LibraryInteropTest {
    @TempDir Path temporary;
    static final String LIBRARY = """
        package example;
        import java.math.BigInteger;
        import org.julclang.core.types.JulcList;
        @OnchainLibrary
        public class Pricing {
            public record Quote(BigInteger amount, boolean approved) {}
            @NewType public record Price(BigInteger value) {}
            @NewType public record Quantity(BigInteger value) {}
            public sealed interface Action {
                record Buy(BigInteger amount) implements Action {}
                record Cancel() implements Action {}
            }
            public static Quote quote(BigInteger amount) { return new Quote(Helpers.adjust(amount), true); }
            public static BigInteger total(Quote quote) { return quote.amount(); }
            public static Price price(BigInteger amount) { return new Price(amount); }
            public static Quantity quantity(BigInteger amount) { return new Quantity(amount); }
            public static JulcList raw(JulcList list) { return list; }
            public static BigInteger unprice(Price price) { return price.value(); }
            public static Action buy(BigInteger amount) { return new Action.Buy(amount); }
            public static BigInteger actionAmount(Action action) {
                return switch(action) {
                    case Action.Buy b -> b.amount();
                    case Action.Cancel c -> BigInteger.ZERO;
                };
            }
            public static JulcList<Quote> quotes(BigInteger amount) { return JulcList.of(new Quote(amount, true)); }
        }
        """;

    private Map<String,String> jarSources() throws Exception {
        Path jar = temporary.resolve("pricing.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("META-INF/plutus-sources/example/Pricing.java"));
            output.write(LIBRARY.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new JarEntry("META-INF/plutus-sources/example/Helpers.java"));
            output.write("""
                package example;
                import java.math.BigInteger;
                @OnchainLibrary public class Helpers {
                    public static BigInteger adjust(BigInteger value) { return value; }
                }
                """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return LibrarySources.readJars(List.of(jar));
    }
    private ElciCompiler.Result compile(String body) throws Exception {
        return new ElciCompiler().compileProject(Map.of("Main", """
                module Main exposing (main)
                import Custom.Pricing as Pricing
                """ + body), "Main", "main", FrontendProgram.Purpose.FUNCTION,
                Map.of("Custom.Pricing", "example.Pricing"), new CompilerOptions(), jarSources());
    }
    private void evaluates(String body, long value) throws Exception {
        var compiled = compile(body);
        var result = assertInstanceOf(EvalResult.Success.class,
                JulcVm.create().evaluate(compiled.program(),compiled.target().ledgerTarget()));
        assertEquals(new Term.Const(Constant.integer(value)),result.resultTerm());
    }
    @Test void customRecordFactoriesAndFields() throws Exception {
        evaluates("""
            extract : Pricing.Quote -> Int
            extract q = q.amount
            main : Int
            main = extract (Pricing.quote 42) + Pricing.total (Pricing.quote 8)
            """,50);
    }
    @Test void customNewtypeRetainsIdentityAndProjection() throws Exception {
        evaluates("""
            extract : Pricing.Price -> Int
            extract p = p.value
            main : Int
            main = extract (Pricing.price 42)
            """,42);
        assertThrows(Diagnostic.class, () -> compile("""
            main : Int
            main = Pricing.unprice (Pricing.quantity 42)
            """));
    }
    @Test void customSumRoundTripAndNestedRecordList() throws Exception {
        evaluates("""
            main : Int
            main = Pricing.actionAmount (Pricing.buy 42)
            """,42);
        evaluates("""
            main : Int
            main = case Pricing.quotes 42 of
                first :: _ -> first.amount
                [] -> 0
            """,42);
    }
    @Test void recordIsNominalNotStructural() throws Exception {
        assertThrows(Diagnostic.class, () -> compile("""
            main : Int
            main = Pricing.total { amount = 42, approved = True }
            """));
    }
    @Test void unsupportedSignaturesHaveActionableDiagnostics() throws Exception {
        var error=assertThrows(Diagnostic.class, () -> compile("""
            main : Int
            main = List.length (Pricing.raw [])
            """));
        assertTrue(error.getMessage().contains("Raw or invalid container signature"),error.getMessage());
    }
    @Test void exposedTypeAndFunctionImports() throws Exception {
        String source="""
            module Main exposing (main)
            import Custom.Pricing exposing (Quote, quote)
            extract : Quote -> Int
            extract q = q.amount
            main : Int
            main = extract (quote 9)
            """;
        var compiled=new ElciCompiler().compileProject(Map.of("Main",source),"Main","main",
            FrontendProgram.Purpose.FUNCTION,Map.of("Custom.Pricing","example.Pricing"),
            new CompilerOptions(),jarSources());
        var result=assertInstanceOf(EvalResult.Success.class,JulcVm.create().evaluate(compiled.program(),compiled.target().ledgerTarget()));
        assertEquals(new Term.Const(Constant.integer(9)),result.resultTerm());
    }
    @Test void rejectsMissingSourcesAndDuplicateOwnership() throws Exception {
        jarSources();
        var jar=temporary.resolve("pricing.jar");
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> LibrarySources.readJars(List.of(jar,jar))).getMessage().contains("Duplicate"));
        var empty=temporary.resolve("empty.jar");
        try(var ignored=new JarOutputStream(Files.newOutputStream(empty))) {}
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> LibrarySources.readJars(List.of(empty))).getMessage().contains("No on-chain"));
    }
}
