package org.elci.test;

import org.elci.cli.Project;
import org.elci.driver.ElciCompiler;
import org.elci.types.Types;
import org.julclang.compiler.CompilerOptions;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.testkit.ValidatorTest;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * {@code elci test} (ADR-006): discovers exposed, zero-argument {@code Bool},
 * {@code Expect.Expectation} and {@code Scenario.Scenario} declarations in test modules, compiles
 * each to its own program and evaluates it with julc-testkit.
 */
public final class TestRunner {
    public enum Kind { BOOL, EXPECTATION, SCENARIO }

    /** One test's result: its budget is the test program's, or the validator's for a scenario. */
    public record Outcome(String name, Kind kind, boolean passed, String detail, ExBudget budget, List<String> traces) {
        public Outcome {
            traces = List.copyOf(traces);
        }
    }

    static final String EXPECTATION = "Expect.Expectation";
    static final String SCENARIO = "Scenario.Scenario";
    /** Test-only bundled modules, written in Elci. */
    static final List<String> LIBRARY = List.of("Expect", "Scenario");

    private final Project project;
    private final CompilerOptions options = new CompilerOptions();
    private final Scenarios scenarios;

    public TestRunner(Project project) {
        this.project = project;
        this.scenarios = new Scenarios(project, options);
    }

    /** Run every test whose name contains {@code match} (all when null), in module and source order. */
    public List<Outcome> run(String match) throws IOException {
        var tests = project.testSources();
        var all = sources(project);
        var librarySources = project.librarySources();
        var outcomes = new ArrayList<Outcome>();
        for (String module : new TreeSet<>(tests.keySet())) {
            var values = new ElciCompiler().compileValues(all, module, TestRunner::testType, project.javaModules(),
                    options, librarySources);
            for (var entry : values.entrySet()) {
                String name = module + "." + entry.getKey().name();
                if (match != null && !name.toLowerCase(Locale.ROOT).contains(match.toLowerCase(Locale.ROOT))) continue;
                outcomes.add(run(name, entry.getValue()));
            }
        }
        return outcomes;
    }

    /**
     * The modules a test run compiles: the project's sources, its test modules and the bundled
     * test library. Test modules may not shadow either.
     */
    public static Map<String, String> sources(Project project) throws IOException {
        var sources = project.sources();
        var tests = project.testSources();
        for (String module : tests.keySet())
            if (sources.containsKey(module))
                throw new IllegalArgumentException("ELCI4001: module " + module + " is both a source and a test module");
        var all = new LinkedHashMap<>(sources);
        all.putAll(tests);
        for (String module : LIBRARY) {
            if (all.containsKey(module))
                throw new IllegalArgumentException("ELCI4001: module " + module + " is owned by the Elci test library");
            all.put(module, bundled(module));
        }
        return all;
    }

    static boolean testType(Types.Ty type) {
        return type instanceof Types.Con c && c.args().isEmpty()
                && (c.name().equals("Bool") || c.name().equals(EXPECTATION) || c.name().equals(SCENARIO));
    }

    private Outcome run(String name, ElciCompiler.Result compiled) {
        var kind = switch (((Types.Con) compiled.type()).name()) {
            case "Bool" -> Kind.BOOL;
            case EXPECTATION -> Kind.EXPECTATION;
            default -> Kind.SCENARIO;
        };
        var result = ValidatorTest.evaluate(compiled.program());
        if (!(result instanceof EvalResult.Success success))
            return new Outcome(name, kind, false, "evaluation failed: " + error(result), result.budgetConsumed(),
                    result.traces());
        var value = ((Term.Const) success.resultTerm()).value();
        return switch (kind) {
            case BOOL -> {
                boolean passed = ((Constant.BoolConst) value).value();
                yield new Outcome(name, kind, passed, passed ? "" : "evaluated to False", success.consumed(), success.traces());
            }
            case EXPECTATION -> expectation(name, ((Constant.DataConst) value).value(), success);
            case SCENARIO -> scenarios.run(name, ((Constant.DataConst) value).value(), compiled.pirType());
        };
    }

    /** {@code Pass} is {@code Constr 0 []}; {@code Fail message values} is {@code Constr 1 [B, List]}. */
    private static Outcome expectation(String name, PlutusData data, EvalResult.Success success) {
        var constr = (PlutusData.ConstrData) data;
        if (constr.tag() == 0) return new Outcome(name, Kind.EXPECTATION, true, "", success.consumed(), success.traces());
        String message = new String(((PlutusData.BytesData) constr.fields().get(0)).value(), StandardCharsets.UTF_8);
        var values = ((PlutusData.ListData) constr.fields().get(1)).items();
        String detail = switch (message) {
            case "Expect.equal" -> message + "\n    expected: " + Data.show(values.get(0)) + "\n    actual:   "
                    + Data.show(values.get(1));
            default -> values.isEmpty() ? message : message + "\n    values: "
                    + String.join(", ", values.stream().map(Data::show).toList());
        };
        return new Outcome(name, Kind.EXPECTATION, false, detail, success.consumed(), success.traces());
    }

    static String error(EvalResult result) {
        return switch (result) {
            case EvalResult.Failure failure -> failure.error();
            case EvalResult.BudgetExhausted _ -> "budget exhausted";
            default -> result.toString();
        };
    }

    private static String bundled(String module) throws IOException {
        try (InputStream input = TestRunner.class.getResourceAsStream("/elci/test/" + module + ".elm")) {
            if (input == null) throw new IllegalStateException("Bundled test module " + module + " is missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
