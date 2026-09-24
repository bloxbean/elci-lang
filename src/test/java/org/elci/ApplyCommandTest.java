package org.elci;

import static org.elci.Contexts.*;
import static org.junit.jupiter.api.Assertions.*;

import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.clientlib.ValidatorOutput;
import org.julclang.compiler.schema.ContractSchema.Purpose;
import org.julclang.core.PlutusData;
import org.julclang.ledger.PubKeyHash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

/** {@code elci apply}: off-chain parameter checks and application for named validators. */
class ApplyCommandTest {
    @TempDir Path project;

    static final String ADMIN_HEX = "01" + "00".repeat(27);

    void copyTreasury() throws Exception {
        for (String file : new String[] {"elci.toml", "src/Auth.elm", "src/Badge.elm", "src/Treasury.elm"}) {
            Files.createDirectories(project.resolve(file).getParent());
            Files.copy(Path.of("examples/treasury").resolve(file), project.resolve(file));
        }
    }

    Path applied(String group) {
        return project.resolve("build/validators").resolve(group).resolve("applied.plutus.json");
    }

    @Test
    void appliesCheckedParametersAndRecordsTheHash() throws Exception {
        copyTreasury();
        assertEquals(0, NamedValidatorCliTest.run("build", project.toString()).status());
        var result = NamedValidatorCliTest.run("apply", project.toString(), "--validator", "badge",
                "--param", "admin={\"bytes\": \"" + ADMIN_HEX + "\"}");
        assertEquals(0, result.status(), result.err());
        var output = ValidatorOutput.fromJson(Files.readString(applied("badge")));
        assertFalse(output.isParameterized());
        var program = JulcScriptAdapter.toProgram(output.cborHex());
        assertEquals(JulcScriptAdapter.scriptHash(program), output.hash());
        assertTrue(result.out().contains(output.hash()), result.out());
        // The same script and hash as applying the built artifact directly.
        var built = ValidatorOutput.fromJson(Files.readString(project.resolve("build/validators/badge/validator.plutus.json")));
        var admin = new PubKeyHash(HexFormat.of().parseHex(ADMIN_HEX));
        assertEquals(JulcScriptAdapter.scriptHash(JulcScriptAdapter.toProgram(built.cborHex()).applyParams(admin.toPlutusData())),
                output.hash());
        var mint = builder(Purpose.MINT, null).signer(admin).redeemer(PlutusData.integer(1)).build().toPlutusData();
        assertTrue(accepts(program, mint));
        // Rebuilding the group removes the applied script, which belonged to the previous build.
        assertEquals(0, NamedValidatorCliTest.run("build", project.toString()).status());
        assertFalse(Files.exists(applied("badge")));
    }

    @Test
    void rejectsMalformedMissingAndUnknownParameters() throws Exception {
        copyTreasury();
        String[][] cases = {
                {"admin={\"int\": 5}", "not a well-formed"},
                {"admin=not json", "not Plutus Data JSON"},
                {"owner={\"int\": 5}", "has no parameter 'owner'"},
        };
        for (var test : cases) {
            var result = NamedValidatorCliTest.run("apply", project.toString(), "--validator", "badge", "--param", test[0]);
            assertEquals(1, result.status(), test[0]);
            assertTrue(result.err().contains(test[1]), result.err());
        }
        assertTrue(NamedValidatorCliTest.run("apply", project.toString(), "--validator", "badge").err().contains("missing --param admin"));
        assertTrue(NamedValidatorCliTest.run("apply", project.toString(), "--param", "admin={\"int\": 1}").err().contains("requires --validator"));
        assertFalse(Files.exists(applied("badge")));
        // A value from a file, written to an explicit output that Elci does not own.
        Files.writeString(project.resolve("admin.json"), "{\"bytes\": \"" + ADMIN_HEX + "\"}");
        var explicit = project.resolve("deploy/badge.plutus.json");
        var result = NamedValidatorCliTest.run("apply", project.toString(), "--validator", "badge", "--param", "admin=@admin.json",
                "--output", explicit.toString());
        assertEquals(0, result.status(), result.err());
        assertTrue(Files.exists(explicit));
        assertEquals(0, NamedValidatorCliTest.run("build", project.toString()).status());
        assertTrue(Files.exists(explicit), "an explicit output is not an owned build output");
    }

    @Test
    void recordParametersAreCheckedStrictly() throws Exception {
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/Policy.elm"), """
                module Policy exposing (Limits, mint)

                import Julc.Ledger exposing (ScriptContext)


                type alias Limits =
                    { low : Int, high : Int }


                mint : Limits -> Int -> ScriptContext -> Bool
                mint limits amount _ =
                    amount >= limits.low && amount <= limits.high
                """);
        Files.writeString(project.resolve("elci.toml"), NamedValidatorCliTest.HEADER + """

                [validators.policy]
                boundary = "julc-strict-v1"
                parameters = ["limits"]
                mint = "Policy.mint"
                """);
        String good = "limits={\"constructor\": 0, \"fields\": [{\"int\": 9}, {\"int\": 2}]}";
        String wrongArity = "limits={\"constructor\": 0, \"fields\": [{\"int\": 9}]}";
        String wrongTag = "limits={\"constructor\": 1, \"fields\": [{\"int\": 9}, {\"int\": 2}]}";
        assertEquals(1, NamedValidatorCliTest.run("apply", project.toString(), "--validator", "policy", "--param", wrongArity).status());
        assertEquals(1, NamedValidatorCliTest.run("apply", project.toString(), "--validator", "policy", "--param", wrongTag).status());
        var result = NamedValidatorCliTest.run("apply", project.toString(), "--validator", "policy", "--param", good);
        assertEquals(0, result.status(), result.err());
        var program = JulcScriptAdapter.toProgram(ValidatorOutput.fromJson(Files.readString(applied("policy"))).cborHex());
        assertTrue(accepts(program, context(Purpose.MINT, PlutusData.integer(5))));
        assertFalse(accepts(program, context(Purpose.MINT, PlutusData.integer(10))));
    }

    @Test
    void anExplicitDefaultOutputIsStillOwned() throws Exception {
        copyTreasury();
        assertEquals(0, NamedValidatorCliTest.run("build", project.toString()).status());
        var result = NamedValidatorCliTest.run("apply", project.toString(), "--validator", "badge",
                "--param", "admin={\"bytes\": \"" + ADMIN_HEX + "\"}", "--output", applied("badge").toString());
        assertEquals(0, result.status(), result.err());
        assertEquals(0, NamedValidatorCliTest.run("build", project.toString()).status());
        assertFalse(Files.exists(applied("badge")), "a rebuild removes the stale applied script");
    }

    @Test
    void applyChecksTheExpectedAbi() throws Exception {
        copyTreasury();
        Files.writeString(project.resolve("expected.json"), "{}\n");
        Files.writeString(project.resolve("elci.toml"), Files.readString(project.resolve("elci.toml"))
                .replace("mint = \"Badge.mint\"", "mint = \"Badge.mint\"\nexpected-abi = \"expected.json\""));
        var result = NamedValidatorCliTest.run("apply", project.toString(), "--validator", "badge",
                "--param", "admin={\"bytes\": \"" + ADMIN_HEX + "\"}");
        assertEquals(1, result.status());
        assertTrue(result.err().contains("ABI drift"), result.err());
        assertFalse(Files.exists(applied("badge")));
    }
}
