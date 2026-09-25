package org.elci.test;

import org.elci.cli.Project;
import org.elci.driver.ElciCompiler;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.ledger.*;
import org.julclang.testkit.BudgetAssertions;
import org.julclang.testkit.ScriptContextTestBuilder;
import org.julclang.testkit.ValidatorTest;
import org.julclang.vm.EvalResult;

import java.math.BigInteger;
import java.util.*;

/**
 * Runs a {@code Scenario.Scenario} (ADR-006): the named validator is compiled as {@code elci
 * build} compiles it, its parameters are applied, and julc-testkit builds the ledger context in
 * which the deployed script is evaluated.
 */
final class Scenarios {
    private final Project project;
    private final CompilerOptions options;
    private final Map<String, Program> compiled = new HashMap<>();

    Scenarios(Project project, CompilerOptions options) {
        this.project = project;
        this.options = options;
    }

    TestRunner.Outcome run(String name, PlutusData value, PirType scenarioType) {
        var specType = ((PirType.SumType) scenarioType).constructors().getFirst().fields().getFirst().type();
        var spec = Data.record(Data.constr(value, 0).fields().getFirst(), specType);
        String validator = Data.string(spec.get("validator"));
        Program applied;
        try {
            var parameters = Data.list(spec.get("parameters"));
            applied = parameters.isEmpty() ? program(validator)
                    : program(validator).applyParams(parameters.toArray(PlutusData[]::new));
        } catch (Exception e) {
            return new TestRunner.Outcome(name, TestRunner.Kind.SCENARIO, false, "cannot prepare validator '"
                    + validator + "': " + e.getMessage(), null, List.of());
        }
        var hash = new ScriptHash(HexFormat.of().parseHex(JulcScriptAdapter.scriptHash(applied)));
        var context = context(spec, specType, hash);
        var result = ValidatorTest.evaluate(applied, context);
        boolean expectSuccess = Data.bool(spec.get("expectSuccess"));
        boolean succeeded = result instanceof EvalResult.Success;
        var problems = new ArrayList<String>();
        if (succeeded != expectSuccess)
            problems.add(expectSuccess ? "validator " + validator + " rejected the transaction: " + TestRunner.error(result)
                    : "validator " + validator + " accepted a transaction expected to fail");
        var budget = Data.maybe(spec.get("budget"));
        if (budget != null) {
            var limits = Data.constr(budget, 0).fields();
            try {
                BudgetAssertions.assertBudgetUnder(result, Data.integer(limits.get(0)).longValueExact(),
                        Data.integer(limits.get(1)).longValueExact());
            } catch (AssertionError e) {
                problems.add(e.getMessage());
            }
        }
        return new TestRunner.Outcome(name, TestRunner.Kind.SCENARIO, problems.isEmpty(), String.join("; ", problems),
                result.budgetConsumed(), result.traces());
    }

    /** The unapplied script, compiled once per run as {@code elci build} would. */
    private Program program(String validator) throws Exception {
        var cached = compiled.get(validator);
        if (cached != null) return cached;
        if (!project.named())
            throw new IllegalArgumentException("this project has no validators; configure [validators." + validator + "]");
        var group = project.validators().stream().filter(v -> v.name().equals(validator)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no [validators." + validator + "] in elci.toml"));
        var program = new ElciCompiler().compileValidator(project.sources(), group.spec(), project.javaModules(), options,
                project.librarySources()).program();
        compiled.put(validator, program);
        return program;
    }

    private static PlutusData context(Map<String, PlutusData> spec, PirType specType, ScriptHash hash) {
        var credential = new Credential.ScriptCredential(hash);
        var ownAddress = new Address(credential, Optional.empty());
        var policy = new PolicyId(hash.hash());
        var purpose = Data.constr(spec.get("purpose"), -1);
        var inputType = Data.element(Data.field(specType, "inputs"));
        var outputType = Data.element(Data.field(specType, "outputs"));
        var tokenType = Data.element(Data.field(specType, "minted"));
        var inputs = new ArrayList<TxInInfo>();
        for (var input : Data.list(spec.get("inputs"))) inputs.add(input(input, inputType, ownAddress, policy));
        ScriptContextTestBuilder builder = switch (purpose.tag()) {
            case 0 -> {
                var ref = TxOutRef.fromPlutusData(purpose.fields().get(0));
                var datum = Data.maybe(purpose.fields().get(1));
                // The spent output is an input at the script address unless the scenario lists it.
                if (inputs.stream().noneMatch(i -> i.outRef().equals(ref)))
                    inputs.addFirst(new TxInInfo(ref, new TxOut(ownAddress, Value.lovelace(Data.integer(spec.get("ownLovelace"))),
                            datum == null ? new OutputDatum.NoOutputDatum() : new OutputDatum.OutputDatumInline(datum),
                            Optional.empty())));
                yield datum == null ? ScriptContextTestBuilder.spending(ref) : ScriptContextTestBuilder.spending(ref, datum);
            }
            case 1 -> ScriptContextTestBuilder.minting(policy);
            case 2 -> ScriptContextTestBuilder.rewarding(credential).withdrawal(credential, BigInteger.ZERO);
            case 3 -> {
                var certificate = TxCert.fromPlutusData(purpose.fields().get(1));
                yield ScriptContextTestBuilder.certifying(Data.integer(purpose.fields().get(0)), certificate)
                        .certificate(certificate);
            }
            case 4 -> ScriptContextTestBuilder.voting(Voter.fromPlutusData(purpose.fields().get(0)));
            case 5 -> ScriptContextTestBuilder.proposing(Data.integer(purpose.fields().get(0)),
                    ProposalProcedure.fromPlutusData(purpose.fields().get(1)));
            default -> throw new IllegalArgumentException("Unknown scenario purpose " + purpose.tag());
        };
        builder.redeemer(spec.get("redeemer")).fee(Data.integer(spec.get("fee")));
        for (var signer : Data.list(spec.get("signers"))) builder.signer(PubKeyHash.fromPlutusData(signer));
        for (var input : inputs) builder.input(input);
        for (var reference : Data.list(spec.get("referenceInputs")))
            builder.referenceInput(input(reference, inputType, ownAddress, policy));
        for (var output : Data.list(spec.get("outputs"))) builder.output(output(output, outputType, ownAddress, policy));
        var minted = Value.zero();
        for (var token : Data.list(spec.get("minted"))) minted = minted.merge(token(token, tokenType, policy));
        if (!Data.list(spec.get("minted")).isEmpty()) builder.mint(minted);
        var from = Data.maybe(spec.get("validFrom"));
        var before = Data.maybe(spec.get("validBefore"));
        if (from != null || before != null)
            builder.validRange(new Interval(
                    new IntervalBound(from == null ? new IntervalBoundType.NegInf()
                            : new IntervalBoundType.Finite(Data.integer(from)), true),
                    new IntervalBound(before == null ? new IntervalBoundType.PosInf()
                            : new IntervalBoundType.Finite(Data.integer(before)), before == null)));
        return builder.buildPlutusData();
    }

    private static TxInInfo input(PlutusData data, PirType type, Address own, PolicyId policy) {
        var fields = Data.record(data, type);
        return new TxInInfo(TxOutRef.fromPlutusData(fields.get("ref")),
                output(fields.get("output"), Data.field(type, "output"), own, policy));
    }

    private static TxOut output(PlutusData data, PirType type, Address own, PolicyId policy) {
        var fields = Data.record(data, type);
        var destination = Data.constr(fields.get("destination"), -1);
        var address = destination.tag() == 0 ? own : Address.fromPlutusData(destination.fields().getFirst());
        var value = Value.lovelace(Data.integer(fields.get("lovelace")));
        var tokenType = Data.element(Data.field(type, "tokens"));
        for (var token : Data.list(fields.get("tokens"))) value = value.merge(token(token, tokenType, policy));
        return new TxOut(address, value, OutputDatum.fromPlutusData(fields.get("datum")), Optional.empty());
    }

    /** {@code Own} is the script's policy; {@code Policy bytes} a given one. */
    private static Value token(PlutusData data, PirType type, PolicyId own) {
        var fields = Data.record(data, type);
        var policy = Data.constr(fields.get("policy"), -1);
        var id = policy.tag() == 0 ? own : new PolicyId(Data.bytes(policy.fields().getFirst()));
        return Value.singleton(id, new TokenName(Data.bytes(fields.get("name"))), Data.integer(fields.get("quantity")));
    }
}
