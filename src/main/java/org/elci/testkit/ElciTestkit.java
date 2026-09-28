package org.elci.testkit;

import org.elci.cli.Project;
import org.elci.driver.ElciCompiler;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.backend.ValidatorAbi;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Elci programs for JVM test suites (ADR-006). The scripts are compiled exactly as {@code elci
 * build} compiles them, so JUnit tests can use julc-testkit's {@code ScriptContextTestBuilder},
 * {@code ValidatorTest} and {@code BudgetAssertions} on what will be deployed.
 *
 * <pre>{@code
 * var treasury = ElciTestkit.validator(Path.of("examples/treasury"), "treasury", admin.toPlutusData());
 * var ctx = ScriptContextTestBuilder.rewarding(credential).signer(admin).redeemer(PlutusData.UNIT)
 *         .buildPlutusData();
 * ValidatorTest.assertValidates(treasury, ctx);
 * }</pre>
 */
public final class ElciTestkit {
    private ElciTestkit() {}

    /** A project's named validator with its deployment parameters applied, in declared order. */
    public static Program validator(Path project, String name, PlutusData... parameters) throws IOException {
        var build = build(project, name);
        if (build.parameters().size() != parameters.length)
            throw new IllegalArgumentException("Validator " + name + " takes " + build.parameters().size()
                    + (build.parameters().size() == 1 ? " parameter " : " parameters ")
                    + build.parameters().stream().map(ElciCompiler.ParameterInfo::name).toList()
                    + ", not " + parameters.length);
        return parameters.length == 0 ? build.program() : build.program().applyParams(parameters);
    }

    /**
     * A named validator's ABI: its parameter, datum and redeemer types. With julc-testkit-jqwik's
     * {@code SchemaArbitraries} it generates valid datums, redeemers and parameters for JVM
     * property tests:
     * <pre>{@code
     * @Provide
     * Arbitrary<PlutusData> payouts() throws IOException {
     *     return SchemaArbitraries.datum(ElciTestkit.abi(Path.of("examples/treasury"), "treasury"));
     * }
     * }</pre>
     */
    public static ValidatorAbi abi(Path project, String name) throws IOException {
        return build(project, name).result().abi();
    }

    private static ElciCompiler.ValidatorBuild build(Path project, String name) throws IOException {
        var configuration = Project.read(project);
        var group = configuration.validators().stream().filter(v -> v.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No [validators." + name + "] in " + project));
        return new ElciCompiler().compileValidator(configuration.sources(), group.spec(),
                configuration.javaModules(), new CompilerOptions(), configuration.librarySources());
    }

    /** A project's {@code [entrypoint]} function, as {@code elci eval} runs it. */
    public static Program entrypoint(Path project) throws IOException {
        var configuration = Project.read(project);
        return new ElciCompiler().compileProject(configuration.sources(), configuration.module(),
                configuration.function(), configuration.purpose(), configuration.javaModules(), new CompilerOptions(),
                configuration.librarySources()).program();
    }

    /**
     * An exposed, zero-argument declaration of any project or test module, compiled as a
     * function program: for evaluating helpers from JVM tests.
     */
    public static Program value(Path project, String module, String name) throws IOException {
        var configuration = Project.read(project);
        var sources = org.elci.test.TestRunner.sources(configuration);
        var values = new ElciCompiler().compileValues(sources, module, type -> true, configuration.javaModules(),
                new CompilerOptions(), configuration.librarySources());
        return values.entrySet().stream().filter(e -> e.getKey().name().equals(name)).map(Map.Entry::getValue)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("No exposed zero-argument " + module + "." + name))
                .program();
    }
}
