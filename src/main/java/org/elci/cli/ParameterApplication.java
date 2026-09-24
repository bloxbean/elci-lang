package org.elci.cli;

import com.bloxbean.cardano.client.plutus.spec.serializers.PlutusDataJsonConverter;
import org.elci.driver.ElciCompiler;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.clientlib.ValidatorOutput;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.backend.BoundaryPrograms;
import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * {@code elci apply}: apply deployment parameters to a named validator (ADR-005 E5). Each value
 * is Plutus Data in the detailed JSON schema used by cardano-cli. Before application every value
 * is checked off-chain with Julc's strict boundary program for the parameter's type, because the
 * script itself decodes parameters without validating them.
 */
final class ParameterApplication {
    static final String APPLIED = "applied.plutus.json";

    private ParameterApplication() {}

    static int run(Project project, String validator, Map<String, String> arguments, Path output) throws Exception {
        if (!project.named())
            throw new IllegalArgumentException("ELCI4001: apply requires [validators.<name>] configuration");
        if (validator == null) throw new IllegalArgumentException("ELCI4001: apply requires --validator <name>");
        var group = project.validators().stream().filter(v -> v.name().equals(validator)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("ELCI4001: unknown validator '" + validator + "'"));
        if (group.parameters().isEmpty())
            throw new IllegalArgumentException("ELCI4001: validator " + validator + " declares no parameters");
        for (String name : arguments.keySet())
            if (!group.parameters().contains(name))
                throw new IllegalArgumentException("ELCI4001: validator " + validator + " has no parameter '" + name
                        + "'; declared: " + String.join(", ", group.parameters()));
        for (String name : group.parameters())
            if (!arguments.containsKey(name))
                throw new IllegalArgumentException("ELCI4001: missing --param " + name + "=<plutus-data-json>");
        var options = new CompilerOptions();
        var build = new ElciCompiler().compileValidator(project.sources(), group.spec(), project.javaModules(), options,
                project.librarySources());
        if (group.expectedAbi() != null && !Files.readString(project.root().resolve(group.expectedAbi()))
                .equals(org.elci.abi.Abi.validatorManifest(build)))
            throw new IllegalArgumentException("ELCI4001: validator " + validator + ": ABI drift from "
                    + group.expectedAbi() + "; parameters were not applied");
        var values = new ArrayList<PlutusData>();
        for (var parameter : build.parameters()) {
            var data = parse(project.root(), parameter.name(), arguments.get(parameter.name()));
            var check = BoundaryPrograms.check(parameter.type(), build.namedTypes(), options);
            var result = JulcVm.create().evaluateWithArgs(check, build.target().ledgerTarget(), List.of(data), null,
                    EvalOptions.DEFAULT);
            if (!(result instanceof EvalResult.Success))
                throw new IllegalArgumentException("ELCI4001: parameter " + parameter.name()
                        + " is not a well-formed " + parameter.source() + " value");
            values.add(data);
        }
        var applied = build.program().applyParams(values.toArray(PlutusData[]::new));
        String hash = JulcScriptAdapter.scriptHash(applied);
        byte[] flat = UplcFlatEncoder.encodeProgram(applied);
        var artifact = new ValidatorOutput("PlutusScriptV3", ValidatorOutputs.artifactPurpose(build), build.name(),
                JulcScriptAdapter.fromProgram(applied).getCborHex(), hash, "", flat.length);
        Path owned = project.root().resolve("build/validators").resolve(build.name()).resolve(APPLIED).toAbsolutePath().normalize();
        Path target = output != null ? output.toAbsolutePath().normalize() : owned;
        // The default location is owned (the next build removes it), however it was named.
        ValidatorOutputs.writeApplied(project.root().resolve("build"), build.name(), target, artifact.toJson(),
                target.equals(owned));
        System.out.println("validator " + build.name() + ": applied " + values.size() + " parameter(s); hash " + hash
                + "; wrote " + target);
        return 0;
    }

    /** A detailed-schema Plutus Data JSON value, or {@code @path} to a file holding one. */
    private static PlutusData parse(Path root, String name, String value) {
        String json = value;
        try {
            if (value.startsWith("@")) json = Files.readString(root.resolve(value.substring(1)));
            var data = PlutusDataJsonConverter.toPlutusData(json);
            return PlutusDataCborDecoder.decode(data.serializeToBytes());
        } catch (Exception e) {
            throw new IllegalArgumentException("ELCI4001: parameter " + name + " is not Plutus Data JSON "
                    + "(for example {\"int\": 5}, {\"bytes\": \"ab\"} or {\"constructor\": 0, \"fields\": []}): "
                    + e.getMessage());
        }
    }
}
