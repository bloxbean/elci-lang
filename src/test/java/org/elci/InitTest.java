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

    @Test void refusesInvalidPurposeAndExistingContent() throws Exception {
        var project = directory.resolve("existing");
        Files.createDirectories(project);
        Files.writeString(project.resolve("keep.txt"), "keep");
        assertEquals(1, Main.run("init", project.toString()));
        assertEquals("keep", Files.readString(project.resolve("keep.txt")));
        assertEquals(1, Main.run("init", directory.resolve("bad").toString(), "--purpose", "vote"));
        assertFalse(Files.exists(directory.resolve("bad")));
        assertEquals(1, Main.run("init", directory.resolve("duplicate").toString(), "--purpose", "mint", "--purpose", "spend"));
        assertFalse(Files.exists(directory.resolve("duplicate")));
    }
}
