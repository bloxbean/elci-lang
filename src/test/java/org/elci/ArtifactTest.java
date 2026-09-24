package org.elci;

import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import org.elci.cli.Main;
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

    private void fixture(String purpose, String source) throws Exception {
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/Main.elm"),source);
        Files.writeString(project.resolve("elci.toml"),"""
            [project]
            source-directories = ["src"]
            target = "plutus-v3-pv11-uplc-1.1.0"
            julc-version = "%s"
            [entrypoint]
            module = "Main"
            function = "validate"
            purpose = "%s"
            boundary = "julc-strict-v1"
            """.formatted(org.elci.driver.ElciCompiler.JULC_VERSION,purpose));
    }

    @Test void spendingArtifactRoundTripsThroughJulcAndCardanoClientLib() throws Exception {
        fixture("spend","""
            module Main exposing (validate)
            import Julc.Ledger exposing (ScriptContext)
            validate : Int -> Int -> ScriptContext -> Bool
            validate minimum offered _ = offered >= minimum
            """);
        assertEquals(0,Main.run("build",project.toString()));
        var path=project.resolve("build/validator.plutus.json");
        var json=Files.readString(path);
        var output=ValidatorOutput.fromJson(json);
        assertEquals("PlutusScriptV3",output.type());
        assertEquals("spending",output.purpose());
        assertEquals("Main.validate",output.description());
        assertEquals("",output.params());
        assertFalse(output.isParameterized());
        var script=PlutusV3Script.builder().cborHex(output.cborHex()).build();
        assertEquals(output.hash(),HexFormat.of().formatHex(script.getScriptHash()));
        assertEquals(56,output.hash().length());
        byte[] flat=Files.readAllBytes(project.resolve("build/validator.flat"));
        assertEquals(flat.length,output.sizeBytes());
        assertArrayEquals(flat,UplcFlatEncoder.encodeProgram(JulcScriptAdapter.toProgram(output.cborHex())));
        assertTrue(Files.readString(project.resolve("build/validator.plutus")).contains(output.cborHex()));
        assertTrue(Files.readString(project.resolve("build/abi.json")).contains("\"schema\""));
        assertEquals(0,Main.run("build",project.toString()));
        assertEquals(json,Files.readString(path));
    }

    @Test void mintingUsesJulcPurposeAndFunctionDoesNotAdvertiseValidator() throws Exception {
        fixture("mint","""
            module Main exposing (validate)
            import Julc.Ledger exposing (ScriptContext)
            validate : Int -> ScriptContext -> Bool
            validate amount _ = amount > 0
            """);
        assertEquals(0,Main.run("build",project.toString()));
        var path=project.resolve("build/validator.plutus.json");
        assertEquals("minting",ValidatorOutput.fromJson(Files.readString(path)).purpose());
        fixture("function","""
            module Main exposing (validate)
            validate : Int
            validate = 42
            """);
        assertEquals(0,Main.run("build",project.toString()));
        assertFalse(Files.exists(path));
        assertTrue(Files.exists(project.resolve("build/abi.json")));
    }
}
