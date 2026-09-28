package org.elci;

import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import org.elci.cli.Main;
import org.elci.driver.ElciCompiler;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.clientlib.ValidatorOutput;
import org.julclang.core.flat.UplcFlatEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class ArtifactTest {
    @TempDir Path project;

    private void source(String source) throws Exception {
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/Main.elm"), source);
    }

    private void config(String table) throws Exception {
        Files.writeString(project.resolve("elci.toml"), """
            [project]
            source-directories = ["src"]
            target = "plutus-v3-pv11-uplc-1.1.0"
            julc-version = "%s"
            """.formatted(ElciCompiler.JULC_VERSION) + table);
    }

    @Test void spendingArtifactRoundTripsThroughJulcAndCardanoClientLib() throws Exception {
        source("""
            module Main exposing (validate)
            import Julc.Ledger exposing (ScriptContext)
            validate : Int -> Int -> ScriptContext -> Bool
            validate minimum offered _ = offered >= minimum
            """);
        config("[validators.main]\nboundary = \"julc-strict-v1\"\nspend = \"Main.validate\"\n");
        assertEquals(0, Main.run("build", project.toString()));
        var path = project.resolve("build/validators/main/validator.plutus.json");
        var json = Files.readString(path);
        var output = ValidatorOutput.fromJson(json);
        assertEquals("PlutusScriptV3", output.type());
        assertEquals("spending", output.purpose());
        assertEquals("main", output.description());
        assertEquals("", output.params());
        assertFalse(output.isParameterized());
        var script = PlutusV3Script.builder().cborHex(output.cborHex()).build();
        assertEquals(output.hash(), HexFormat.of().formatHex(script.getScriptHash()));
        assertEquals(56, output.hash().length());
        byte[] flat = Files.readAllBytes(project.resolve("build/validators/main/validator.flat"));
        assertEquals(flat.length, output.sizeBytes());
        assertArrayEquals(flat, UplcFlatEncoder.encodeProgram(JulcScriptAdapter.toProgram(output.cborHex())));
        assertTrue(Files.readString(project.resolve("build/validators/main/validator.plutus")).contains(output.cborHex()));
        assertTrue(Files.readString(project.resolve("build/validators/main/abi.json")).contains("\"schema\""));
        assertEquals(0, Main.run("build", project.toString()));
        assertEquals(json, Files.readString(path));
    }

    @Test void mintingUsesJulcPurposeAndFunctionsWriteNoValidatorArtifact() throws Exception {
        source("""
            module Main exposing (validate)
            import Julc.Ledger exposing (ScriptContext)
            validate : Int -> ScriptContext -> Bool
            validate amount _ = amount > 0
            """);
        config("[validators.main]\nboundary = \"julc-strict-v1\"\nmint = \"Main.validate\"\n");
        assertEquals(0, Main.run("build", project.toString()));
        assertEquals("minting", ValidatorOutput.fromJson(
                Files.readString(project.resolve("build/validators/main/validator.plutus.json"))).purpose());
        source("""
            module Main exposing (main)
            main : Int
            main = 42
            """);
        config("[entrypoint]\nmodule = \"Main\"\nfunction = \"main\"\npurpose = \"function\"\n");
        assertEquals(0, Main.run("build", project.toString()));
        assertFalse(Files.exists(project.resolve("build/validator.plutus.json")));
        assertTrue(Files.exists(project.resolve("build/abi.json")));
        assertTrue(Files.readString(project.resolve("build/abi.json")).contains("\"purpose\":\"function\""));
    }

    @Test void entrypointValidatorsPointToNamedValidators() throws Exception {
        source("""
            module Main exposing (validate)
            import Julc.Ledger exposing (ScriptContext)
            validate : Int -> ScriptContext -> Bool
            validate amount _ = amount > 0
            """);
        config("[entrypoint]\nmodule = \"Main\"\nfunction = \"validate\"\npurpose = \"mint\"\nboundary = \"julc-strict-v1\"\n");
        var result = NamedValidatorCliTest.run("build", project.toString());
        assertEquals(1, result.status());
        assertTrue(result.err().contains("configure validators as [validators.<name>] with mint = \"Main.validate\""), result.err());
    }
}
