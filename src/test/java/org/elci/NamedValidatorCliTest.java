package org.elci;

import static org.junit.jupiter.api.Assertions.*;

import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import org.elci.cli.Main;
import org.elci.driver.ElciCompiler;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.clientlib.ValidatorOutput;
import org.julclang.core.flat.UplcFlatEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

class NamedValidatorCliTest {
    @TempDir Path project;

    static final String VAULT = """
            module Vault exposing (spend, withdraw)

            import Julc.Ledger exposing (ScriptContext)
            import Shared


            spend : Int -> Int -> ScriptContext -> Bool
            spend datum redeemer _ =
                Shared.atLeast datum redeemer


            withdraw : Int -> ScriptContext -> Bool
            withdraw amount _ =
                Shared.atLeast 1 amount
            """;
    static final String TOKEN = """
            module Token exposing (mint)

            import Julc.Ledger exposing (ScriptContext)
            import Shared


            mint : Int -> ScriptContext -> Bool
            mint amount _ =
                Shared.atLeast 10 amount
            """;
    static final String SHARED = """
            module Shared exposing (atLeast)

            import Julc.Math as Math


            atLeast : Int -> Int -> Bool
            atLeast minimum value =
                value >= Math.max minimum 0
            """;
    static final String HEADER = """
            [project]
            source-directories = ["src"]
            target = "plutus-v3-pv11-uplc-1.1.0"
            julc-version = "%s"

            [java-modules]
            "Julc.Math" = "org.julclang.stdlib.lib.MathLib"
            """.formatted(ElciCompiler.JULC_VERSION);
    static final String VAULT_TABLE = """

            [validators.vault]
            boundary = "julc-strict-v1"
            spend = "Vault.spend"
            withdraw = "Vault.withdraw"
            """;
    static final String TOKEN_TABLE = """

            [validators.token]
            boundary = "julc-strict-v1"
            mint = "Token.mint"
            """;

    void sources() throws Exception {
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/Vault.elm"), VAULT);
        Files.writeString(project.resolve("src/Token.elm"), TOKEN);
        Files.writeString(project.resolve("src/Shared.elm"), SHARED);
    }

    void config(String... tables) throws Exception {
        Files.writeString(project.resolve("elci.toml"), HEADER + String.join("", tables));
    }

    Path out(String group, String file) {
        return project.resolve("build/validators").resolve(group).resolve(file);
    }

    record Run(int status, String out, String err) {}

    static Run run(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var previousOut = System.out;
        var previousErr = System.err;
        System.setOut(new PrintStream(out));
        System.setErr(new PrintStream(err));
        try {
            return new Run(Main.run(args), out.toString(), err.toString());
        } finally {
            System.setOut(previousOut);
            System.setErr(previousErr);
        }
    }

    @Test
    void buildsOneScriptPerGroupWithJulcArtifactsAndVersionedAbi() throws Exception {
        sources();
        config(VAULT_TABLE, TOKEN_TABLE);
        var result = run("build", project.toString());
        assertEquals(0, result.status(), result.err());
        assertTrue(result.out().contains("validator token (mint): checked"), result.out());
        assertTrue(result.out().contains("validator vault (spend, withdraw): checked"), result.out());
        var vault = ValidatorOutput.fromJson(Files.readString(out("vault", "validator.plutus.json")));
        var token = ValidatorOutput.fromJson(Files.readString(out("token", "validator.plutus.json")));
        assertEquals("multi", vault.purpose());
        assertEquals("minting", token.purpose());
        assertEquals("vault", vault.description());
        assertNotEquals(vault.hash(), token.hash());
        for (var output : new ValidatorOutput[] {vault, token}) {
            var script = PlutusV3Script.builder().cborHex(output.cborHex()).build();
            assertEquals(output.hash(), HexFormat.of().formatHex(script.getScriptHash()));
            assertFalse(output.isParameterized());
        }
        byte[] flat = Files.readAllBytes(out("vault", "validator.flat"));
        assertEquals(flat.length, vault.sizeBytes());
        assertArrayEquals(flat, UplcFlatEncoder.encodeProgram(JulcScriptAdapter.toProgram(vault.cborHex())));
        String abi = Files.readString(out("vault", "abi.json"));
        assertTrue(abi.contains("\"formatVersion\":2"), abi);
        assertTrue(abi.contains("\"purpose\":\"spend\",\"redeemer\""), abi);
        assertTrue(abi.contains("\"handler\":\"Vault.withdraw\""), abi);
        assertTrue(abi.contains("\"profile\":\"required\""), abi);
        assertTrue(Files.readString(project.resolve("build/validators/elci-outputs.json")).contains("\"token\""));
        // Legacy root artifacts are not produced for named validators.
        assertFalse(Files.exists(project.resolve("build/validator.flat")));
    }

    @Test
    void outputsAreDeterministicAndIndependentOfConfigurationOrder() throws Exception {
        sources();
        config(VAULT_TABLE, TOKEN_TABLE);
        assertEquals(0, run("build", project.toString()).status());
        byte[] first = Files.readAllBytes(out("vault", "validator.flat"));
        String abi = Files.readString(out("vault", "abi.json"));
        config(TOKEN_TABLE, """

                [validators.vault]
                withdraw = "Vault.withdraw"
                spend = "Vault.spend"
                boundary = "julc-strict-v1"
                """);
        assertEquals(0, run("build", project.toString()).status());
        assertArrayEquals(first, Files.readAllBytes(out("vault", "validator.flat")));
        assertEquals(abi, Files.readString(out("vault", "abi.json")));
    }

    @Test
    void selectionBuildsOnlyTheNamedGroup() throws Exception {
        sources();
        config(VAULT_TABLE, TOKEN_TABLE);
        var result = run("build", project.toString(), "--validator", "token");
        assertEquals(0, result.status(), result.err());
        assertTrue(Files.exists(out("token", "validator.flat")));
        assertFalse(Files.exists(out("vault", "validator.flat")));
        var unknown = run("build", project.toString(), "--validator", "vaults");
        assertEquals(1, unknown.status());
        assertTrue(unknown.err().contains("unknown validator 'vaults'"), unknown.err());
        assertEquals(1, run("check", project.toString(), "--validator").status());
        assertEquals(1, run("build", project.toString(), "--validator", "token", "--validator", "token").status());
    }

    @Test
    void driftOrAFailedGroupPublishesNothing() throws Exception {
        sources();
        config(VAULT_TABLE, TOKEN_TABLE);
        assertEquals(0, run("build", project.toString()).status());
        byte[] before = Files.readAllBytes(out("token", "validator.flat"));
        Files.writeString(project.resolve("expected-vault.json"), "{}\n");
        Files.writeString(project.resolve("src/Token.elm"), TOKEN.replace("Shared.atLeast 10 amount", "Shared.atLeast 11 amount"));
        config(VAULT_TABLE + "expected-abi = \"expected-vault.json\"\n", TOKEN_TABLE);
        var drift = run("build", project.toString());
        assertEquals(1, drift.status());
        assertTrue(drift.err().contains("ABI drift"), drift.err());
        assertArrayEquals(before, Files.readAllBytes(out("token", "validator.flat")), "token must not be republished");
        // A type error in one group also blocks the other group's publication.
        config(VAULT_TABLE, TOKEN_TABLE);
        Files.writeString(project.resolve("src/Vault.elm"), VAULT.replace("Shared.atLeast 1 amount", "Shared.atLeast True amount"));
        var failed = run("build", project.toString());
        assertEquals(1, failed.status());
        assertTrue(failed.err().contains("[validators.vault]"), failed.err());
        assertTrue(failed.err().contains("ELCI2002"), failed.err());
        assertArrayEquals(before, Files.readAllBytes(out("token", "validator.flat")));
        // An accepted drift check publishes.
        Files.writeString(project.resolve("src/Vault.elm"), VAULT);
        assertEquals(0, run("build", project.toString()).status());
        Files.copy(out("vault", "abi.json"), project.resolve("expected-vault.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        config(VAULT_TABLE + "expected-abi = \"expected-vault.json\"\n", TOKEN_TABLE);
        assertEquals(0, run("check", project.toString()).status());
    }

    @Test
    void diagnosticsNameTheModuleFileTheyOccurIn() throws Exception {
        sources();
        config(VAULT_TABLE, TOKEN_TABLE);
        Files.writeString(project.resolve("src/Shared.elm"), SHARED.replace("value >= Math.max minimum 0", "value >= True"));
        var named = run("check", project.toString());
        assertEquals(1, named.status());
        assertTrue(named.err().contains("src/Shared.elm:8:"), named.err());
        // The same holds for an [entrypoint] project whose dependency has the error.
        Files.writeString(project.resolve("elci.toml"), HEADER + """

                [entrypoint]
                module = "Token"
                function = "mint"
                purpose = "function"
                """);
        var legacy = run("check", project.toString());
        assertEquals(1, legacy.status());
        assertTrue(legacy.err().contains("src/Shared.elm:8:"), legacy.err());
    }

    @Test
    void removedGroupsLoseOnlyTheirOwnedOutputs() throws Exception {
        sources();
        config(VAULT_TABLE, TOKEN_TABLE);
        assertEquals(0, run("build", project.toString()).status());
        Files.writeString(out("token", "notes.txt"), "mine");
        config(VAULT_TABLE);
        assertEquals(0, run("build", project.toString(), "--validator", "vault").status());
        assertTrue(Files.exists(out("token", "validator.flat")), "a selected build keeps unselected outputs");
        assertEquals(0, run("build", project.toString()).status());
        assertFalse(Files.exists(out("token", "validator.flat")));
        assertFalse(Files.exists(out("token", "abi.json")));
        assertEquals("mine", Files.readString(out("token", "notes.txt")));
        assertTrue(Files.exists(out("vault", "validator.flat")));
        assertFalse(Files.readString(project.resolve("build/validators/elci-outputs.json")).contains("token"));
    }

    @Test
    void configurationErrorsAreRejected() throws Exception {
        sources();
        String legacy = """

                [entrypoint]
                module = "Token"
                function = "mint"
                purpose = "function"
                """;
        String[][] cases = {
                {legacy + VAULT_TABLE, "mixes [entrypoint]"},
                {"", "must configure at least one [validators.<name>]"},
                {"\n[validators.Vault]\nboundary = \"julc-strict-v1\"\nmint = \"Token.mint\"\n", "invalid validator name"},
                {"\n[validators.../x]\nboundary = \"julc-strict-v1\"\nmint = \"Token.mint\"\n", "invalid validator name"},
                {"\n[validators.v]\nboundary = \"julc-strict-v1\"\n", "configures no handler"},
                {"\n[validators.v]\nmint = \"Token.mint\"\n", "Missing configuration key: [validators.v] boundary"},
                {"\n[validators.v]\nboundary = \"julc-strict-v1\"\nmint = \"mint\"\n", "must name an exposed function"},
                {"\n[validators.v]\nboundary = \"julc-strict-v1\"\nmint = \"Token.mint\"\nfallback = \"Token.mint\"\n", "Unknown configuration key"},
                {"\n[validators.v]\nboundary = \"julc-strict-v1\"\nmint = \"Token.mint\"\nspend-datum = \"optional\"\n", "spend-datum requires a spend handler"},
                {"\n[validators.v]\nboundary = \"julc-strict-v1\"\nmint = \"Token.mint\"\nparameters = [\"a\", \"a\"]\n", "duplicate parameter"},
                {"\n[validators.v]\nboundary = \"julc-lenient\"\nmint = \"Token.mint\"\n", "unsupported boundary policy"},
                {"\n[validators.v]\nboundary = \"julc-strict-v1\"\nmint = \"Token.mint\"\n\n[validators.v]\nboundary = \"julc-strict-v1\"\n", "duplicate section"},
                {"\n[validators.v]\nboundary = \"julc-strict-v1\"\nmint = \"Token.missing\"\n", "does not declare missing"},
        };
        for (var test : cases) {
            config(test[0]);
            var result = run("check", project.toString());
            assertEquals(1, result.status(), test[0]);
            assertTrue(result.err().contains(test[1]), test[1] + " / " + result.err());
        }
        config(VAULT_TABLE);
        assertTrue(run("eval", project.toString()).err().contains("eval runs an [entrypoint] function"));
        config(legacy);
        var selected = run("check", project.toString(), "--validator", "vault");
        assertEquals(1, selected.status());
        assertTrue(selected.err().contains("requires [validators.<name>]"), selected.err());
        Files.writeString(project.resolve("elci.toml"), HEADER.replace("[project]", "[project]\nexpected-abi = \"abi.json\"") + VAULT_TABLE);
        assertTrue(run("check", project.toString()).err().contains("set expected-abi in each"));
    }

    @Test
    void recordParametersAreOneJulcParameter() throws Exception {
        sources();
        Files.writeString(project.resolve("src/Config.elm"), """
                module Config exposing (mint)

                import Julc.Ledger exposing (PubKeyHash, ScriptContext)


                mint : { owner : PubKeyHash, limits : { low : Int, high : Int } } -> Int -> ScriptContext -> Bool
                mint config amount _ =
                    amount >= config.limits.low
                """);
        config("\n[validators.config]\nboundary = \"julc-strict-v1\"\nparameters = [\"config\"]\nmint = \"Config.mint\"\n");
        assertEquals(0, run("build", project.toString(), "--validator", "config").status());
        var output = ValidatorOutput.fromJson(Files.readString(out("config", "validator.plutus.json")));
        assertEquals("config:Record", output.params());
        assertEquals(1, output.paramList().size());
    }

    @Test
    void anUnreplaceableTargetPublishesNothing() throws Exception {
        sources();
        config(VAULT_TABLE, TOKEN_TABLE);
        assertEquals(0, run("build", project.toString()).status());
        byte[] token = Files.readAllBytes(out("token", "validator.flat"));
        byte[] vault = Files.readAllBytes(out("vault", "validator.flat"));
        // Change both scripts, and make one target impossible to replace.
        Files.writeString(project.resolve("src/Shared.elm"), SHARED.replace("value >= Math.max minimum 0", "value > Math.max minimum 0"));
        Files.delete(out("vault", "abi.json"));
        Files.createDirectories(out("vault", "abi.json").resolve("occupied"));
        var failed = run("build", project.toString());
        assertEquals(1, failed.status(), failed.out());
        assertTrue(failed.err().contains("not a regular file"), failed.err());
        assertTrue(Files.isDirectory(out("vault", "abi.json").resolve("occupied")), "nothing is deleted");
        assertArrayEquals(token, Files.readAllBytes(out("token", "validator.flat")));
        assertArrayEquals(vault, Files.readAllBytes(out("vault", "validator.flat")));
        try (var files = Files.walk(project.resolve("build/validators"))) {
            assertTrue(files.noneMatch(f -> f.getFileName().toString().endsWith(".tmp")
                    || f.getFileName().toString().endsWith(".old")), "no staging files remain");
        }
    }

    @Test
    void outputsGetDefaultFilePermissions() throws Exception {
        if (!java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return;
        sources();
        config(TOKEN_TABLE);
        assertEquals(0, run("build", project.toString()).status());
        var reference = project.resolve("reference");
        Files.writeString(reference, "x");
        var expected = Files.getPosixFilePermissions(reference);
        assertEquals(expected, Files.getPosixFilePermissions(out("token", "validator.flat")));
        assertEquals(expected, Files.getPosixFilePermissions(project.resolve("build/validators/elci-outputs.json")));
    }
}
