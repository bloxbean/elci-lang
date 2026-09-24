package org.elci;

import org.elci.cli.Main;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class InitTest {
    @TempDir Path directory;

    @Test void createsBuildableSpendingProjectByDefault() throws Exception {
        var project = directory.resolve("vesting");
        assertEquals(0, Main.run("init", project.toString()));
        assertTrue(Files.readString(project.resolve("elci.toml")).contains("purpose = \"spend\""));
        assertEquals(0, Main.run("check", project.toString()));
        assertEquals("build/\n", Files.readString(project.resolve(".gitignore")));
    }

    @Test void createsBuildableMintingAndFunctionTemplates() throws Exception {
        var mint = directory.resolve("token");
        var function = directory.resolve("calculator");
        assertEquals(0, Main.run("init", "--purpose", "mint", mint.toString()));
        assertEquals(0, Main.run("check", mint.toString()));
        assertEquals(0, Main.run("init", function.toString(), "--purpose", "function"));
        assertEquals(0, Main.run("eval", function.toString()));
    }

    @Test void createsNamedValidatorProjectsForEveryPurpose() throws Exception {
        for (String purpose : new String[] {"spend", "mint", "withdraw", "certify", "vote", "propose"}) {
            var project = directory.resolve("named-" + purpose);
            assertEquals(0, Main.run("init", project.toString(), "--validator", "main", "--purpose", purpose));
            String config = Files.readString(project.resolve("elci.toml"));
            assertTrue(config.contains("[validators.main]"), config);
            assertTrue(config.contains(purpose + " = \"Main." + purpose + "\""), config);
            assertFalse(Files.readString(project.resolve("src/Main.elm")).contains("True"), "no accept-all placeholder");
            assertEquals(0, Main.run("build", project.toString()), purpose);
            assertTrue(Files.exists(project.resolve("build/validators/main/validator.plutus.json")));
        }
        assertEquals(0, Main.run("init", directory.resolve("default-named").toString(), "--validator", "vault"));
        assertTrue(Files.readString(directory.resolve("default-named/elci.toml")).contains("spend = \"Main.spend\""));
    }

    @Test void namedOnlyPurposesRequireAValidator() throws Exception {
        assertEquals(1, Main.run("init", directory.resolve("withdraw").toString(), "--purpose", "withdraw"));
        assertFalse(Files.exists(directory.resolve("withdraw")));
        assertEquals(1, Main.run("init", directory.resolve("fn").toString(), "--validator", "v", "--purpose", "function"));
        assertEquals(1, Main.run("init", directory.resolve("bad-name").toString(), "--validator", "../x"));
        assertFalse(Files.exists(directory.resolve("bad-name")));
    }

    @Test void refusesInvalidPurposeAndExistingContent() throws Exception {
        var project = directory.resolve("existing");
        Files.createDirectories(project);
        Files.writeString(project.resolve("keep.txt"), "keep");
        assertEquals(1, Main.run("init", project.toString()));
        assertEquals("keep", Files.readString(project.resolve("keep.txt")));
        assertEquals(1, Main.run("init", directory.resolve("bad").toString(), "--purpose", "invalid"));
        assertFalse(Files.exists(directory.resolve("bad")));
        assertEquals(1, Main.run("init", directory.resolve("duplicate").toString(), "--purpose", "mint", "--purpose", "spend"));
        assertFalse(Files.exists(directory.resolve("duplicate")));
    }
}
