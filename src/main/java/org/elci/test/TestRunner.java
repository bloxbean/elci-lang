package org.elci.test;

import org.elci.cli.Project;
import org.elci.driver.ElciCompiler;
import org.elci.types.Types;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.testkit.ValidatorTest;
import org.julclang.testkit.property.DataGenerator;
import org.julclang.testkit.property.DataGenerators;
import org.julclang.testkit.property.PropertyCheck;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * {@code elci test} (ADR-006): discovers exposed {@code Bool}, {@code Expect.Expectation},
 * {@code Scenario.Scenario} and {@code Property.Property} values in test modules, compiles each
 * to its own program and evaluates it with julc-testkit. An exposed function whose result is a
 * {@code Property} is a property test: it runs over arguments generated from its parameter
 * types, and a failure is shrunk to a small counterexample.
 */
public final class TestRunner {
    public enum Kind { BOOL, EXPECTATION, SCENARIO, PROPERTY }

    /**
     * One test's result: its budget is the test program's, or the validator's for a scenario;
     * for a property, the costliest passing run's or the counterexample's. {@code runs} is 0 for
     * a plain test.
     */
    public record Outcome(String name, Kind kind, boolean passed, String detail, ExBudget budget, List<String> traces,
                          int runs) {
        public Outcome {
            traces = List.copyOf(traces);
        }

        public Outcome(String name, Kind kind, boolean passed, String detail, ExBudget budget, List<String> traces) {
            this(name, kind, passed, detail, budget, traces, 0);
        }
    }

    static final String EXPECTATION = "Expect.Expectation";
    static final String SCENARIO = "Scenario.Scenario";
    static final String PROPERTY = "Property.Property";
    /** Test-only bundled modules, written in Elci. */
    static final List<String> LIBRARY = List.of("Expect", "Scenario", "Property");

    private final Project project;
    private final CompilerOptions options = new CompilerOptions();
    private final Scenarios scenarios;
    private final PropertyCheck.Settings settings;

    public TestRunner(Project project) {
        this(project, PropertyCheck.Settings.defaults());
    }

    /** @param settings the seed and number of runs of every property */
    public TestRunner(Project project, PropertyCheck.Settings settings) {
        this.project = project;
        this.scenarios = new Scenarios(project, options);
        this.settings = settings;
    }

    /** The seed properties run with; {@code --seed} with it reproduces the run. */
    public long seed() {
        return settings.seed();
    }

    /** Run every test whose name contains {@code match} (all when null), in module and source order. */
    public List<Outcome> run(String match) throws IOException {
        var tests = project.testSources();
        var all = sources(project);
        var librarySources = project.librarySources();
        var outcomes = new ArrayList<Outcome>();
        for (String module : new TreeSet<>(tests.keySet())) {
            var values = new ElciCompiler().compileTests(all, module, TestRunner::testType, TestRunner::propertyType,
                    project.javaModules(), options, librarySources);
            for (var entry : values.entrySet()) {
                String name = module + "." + entry.getKey().name();
                if (match != null && !name.toLowerCase(Locale.ROOT).contains(match.toLowerCase(Locale.ROOT))) continue;
                var compiled = entry.getValue();
                outcomes.add(entry.getKey().property() ? property(name, entry.getKey(), compiled)
                        : evaluate(name, kind(compiled), compiled.program(), compiled.pirType()));
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
        // The test library exists only during `elci test`; a source module importing it would
        // compile here and then fail `elci build`.
        var library = java.util.regex.Pattern.compile("(?m)^import\\s+(" + String.join("|", LIBRARY) + ")\\b");
        for (var entry : sources.entrySet()) {
            var matcher = library.matcher(entry.getValue());
            if (matcher.find()) {
                int line = (int) entry.getValue().substring(0, matcher.start()).chars().filter(c -> c == '\n').count() + 1;
                throw new org.elci.syntax.Diagnostic("ELCI2101", new org.elci.syntax.Token("import",
                        org.elci.syntax.Token.Kind.NAME, matcher.start(), line, 1, entry.getKey()), "Only test modules can import "
                        + matcher.group(1) + "; it is part of the test library, not available to elci build");
            }
        }
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
        return type instanceof Types.Con c && c.args().isEmpty() && (c.name().equals("Bool")
                || c.name().equals(EXPECTATION) || c.name().equals(SCENARIO) || c.name().equals(PROPERTY));
    }

    static boolean propertyType(Types.Ty type) {
        return type instanceof Types.Con c && c.args().isEmpty() && c.name().equals(PROPERTY);
    }

    private static Kind kind(ElciCompiler.Result compiled) {
        return switch (((Types.Con) compiled.type()).name()) {
            case "Bool" -> Kind.BOOL;
            case EXPECTATION -> Kind.EXPECTATION;
            case SCENARIO -> Kind.SCENARIO;
            default -> Kind.PROPERTY;
        };
    }

    /** Evaluate a test program whose result has the Data representation {@code type}. */
    private Outcome evaluate(String name, Kind kind, Program program, PirType type) {
        var result = ValidatorTest.evaluate(program);
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
            case SCENARIO -> scenarios.run(name, ((Constant.DataConst) value).value(), type);
            case PROPERTY -> {
                // Expecting Expectation (tag 0) or Transacting Scenario (tag 1).
                var property = Data.constr(((Constant.DataConst) value).value(), -1);
                var outcome = property.tag() == 0 ? expectation(name, property.fields().getFirst(), success)
                        : scenarios.run(name, property.fields().getFirst(),
                        ((PirType.SumType) type).constructors().get(1).fields().getFirst().type());
                yield new Outcome(name, kind, outcome.passed(), outcome.detail(), outcome.budget(), outcome.traces());
            }
        };
    }

    /**
     * Run a property over generated arguments (julc-testkit's {@link PropertyCheck}): each run
     * applies Data values of the parameter types, which the program decodes strictly. The seed
     * is derived from the run's seed and the test name, so {@code --seed} reproduces every
     * property while different properties see different values.
     */
    private Outcome property(String name, ElciCompiler.Value test, ElciCompiler.Result compiled) {
        var kind = kind(compiled);
        var generators = new ArrayList<DataGenerator>();
        try {
            for (int i = 0; i < test.parameters().size(); i++)
                generators.add(canonical(generator(test.parameters().get(i), test.arguments().get(i), compiled.namedTypes()),
                        test.canonical().get(i)));
        } catch (IllegalArgumentException e) {
            return new Outcome(name, kind, false, "cannot generate arguments: " + e.getMessage(), null, List.of(), 0);
        }
        var costliest = new ExBudget[1];
        var result = PropertyCheck.check(generators, arguments -> {
            var outcome = evaluate(name, kind, apply(compiled.program(), arguments), compiled.pirType());
            if (outcome.passed() && outcome.budget() != null
                    && (costliest[0] == null || outcome.budget().cpuSteps() > costliest[0].cpuSteps()))
                costliest[0] = outcome.budget();
            return PropertyCheck.Verdict.of(outcome.passed(), outcome.detail());
        }, settings.withSeed(settings.seed() ^ (long) name.hashCode() * 0x9E3779B97F4A7C15L));
        if (result.passed()) return new Outcome(name, kind, true, "", costliest[0], List.of(), result.tries());
        // Once more on the counterexample, for its traces and budget.
        var failing = evaluate(name, kind, apply(compiled.program(), result.counterexample()), compiled.pirType());
        var call = new StringBuilder(test.name());
        for (int i = 0; i < result.counterexample().size(); i++)
            call.append(' ').append(Data.show(result.counterexample().get(i), test.arguments().get(i), compiled.namedTypes(), true,
                    test.parameters().get(i)));
        String detail = "counterexample after " + result.tries() + (result.tries() == 1 ? " run" : " runs")
                + (result.shrinks() == 0 ? "" : ", shrunk " + result.shrinks() + (result.shrinks() == 1 ? " time" : " times"))
                + ":\n    " + call + "\n" + (failing.passed() ? result.message() : failing.detail());
        return new Outcome(name, kind, false, detail, failing.budget(), failing.traces(), result.tries());
    }

    private static Program apply(Program program, List<PlutusData> arguments) {
        return program.applyParams(arguments.toArray(PlutusData[]::new));
    }

    /** Generated values and their shrinks, rewritten into values the program decodes (sorted Dict keys). */
    private static DataGenerator canonical(DataGenerator generator, java.util.function.UnaryOperator<PlutusData> rewrite) {
        return new DataGenerator() {
            @Override
            public PlutusData generate(java.util.SplittableRandom random, int size) {
                return rewrite.apply(generator.generate(random, size));
            }

            @Override
            public java.util.stream.Stream<PlutusData> shrink(PlutusData value) {
                return generator.shrink(value).map(rewrite).filter(candidate -> !candidate.equals(value));
            }
        };
    }

    /** Ledger hashes have fixed lengths; everything else follows its Data representation. */
    private static DataGenerator generator(Types.Ty parameter, PirType representation, Map<String, PirType> namedTypes) {
        String type = ((Types.Con) Types.prune(parameter)).name();
        String simple = type.substring(type.lastIndexOf('.') + 1);
        return switch (simple) {
            case "PubKeyHash", "ScriptHash", "ValidatorHash", "PolicyId" -> DataGenerators.bytes(28);
            case "TxId", "DatumHash" -> DataGenerators.bytes(32);
            case "TokenName" -> DataGenerators.bytes(0, 32);
            default -> DataGenerators.forType(representation, namedTypes);
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
