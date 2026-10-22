package org.elci.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code elci explain} (ADR-012): every code has an entry, and every entry's example gives
 * exactly the output it shows, and its fix compiles.
 */
class ExplainTest {
    /** Codes whose entries have no example: they need a compiler or Julc fault, or a program too large to show. */
    private static final Set<String> NO_EXAMPLE = Set.of("ELCI3002", "ELCI4002", "ELCI4003");

    private static final String FUNCTION_PROJECT = """
            [project]
            source-directories = ["src"]
            target = "plutus-v3-pv11-uplc-1.1.0"
            julc-version = "0.1.0-pre20-ebaf028-SNAPSHOT"

            [entrypoint]
            module = "Main"
            function = "main"
            purpose = "function"
            boundary = "julc-strict-v1"
            """;

    @TempDir Path root;

    record Run(int status, String out, String err) {}

    static synchronized Run run(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        PrintStream oldOut = System.out, oldErr = System.err;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int status = Main.run(args);
            return new Run(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    @Test
    void everyCodeTheCompilerEmitsHasAnEntry() throws Exception {
        var emitted = new TreeSet<String>();
        var code = Pattern.compile("ELCI\\d{4}");
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = code.matcher(Files.readString(file));
                while (m.find()) emitted.add(m.group());
            }
        }
        assertEquals(emitted, new TreeSet<>(Explain.CODES), "Explain.CODES lists exactly the codes in the compiler");
        for (String c : Explain.CODES) {
            String entry = Explain.entry(c);
            assertTrue(entry.startsWith("# " + c + ": "), c + " starts with its heading");
            assertTrue(entry.endsWith("\n") && !entry.endsWith("\n\n"), c + " ends with one newline");
            assertEquals(NO_EXAMPLE.contains(c), !entry.contains("\n## Example\n"), c + ": an example, unless exempt");
        }
    }

    @TestFactory
    List<DynamicTest> everyExampleGivesItsOutputAndEveryFixCompiles() {
        var tests = new ArrayList<DynamicTest>();
        for (String code : Explain.CODES) {
            if (NO_EXAMPLE.contains(code)) continue;
            tests.add(DynamicTest.dynamicTest(code, () -> checkEntry(code)));
        }
        return tests;
    }

    private void checkEntry(String code) throws Exception {
        var sections = sections(Explain.entry(code));
        var example = sections.get("Example");
        Path project = root.resolve(code);
        Files.createDirectories(project.resolve("src"));
        write(project, example);
        var expected = example.stream().filter(b -> b.lang().equals("text")).toList();
        assertEquals(1, expected.size(), code + ": one output in the example");
        String output = expected.getFirst().body();
        assertTrue(output.startsWith("elci: " + code + ": ") || output.lines().findFirst().orElseThrow().contains(": " + code + ": "),
                code + ": the output is " + code);
        var failed = run("check", project.toString());
        assertEquals(1, failed.status(), code + " fails");
        String err = failed.err().replace(project + "/", "").replaceFirst("\n\nFor more on [^\n]*\n$", "\n");
        assertEquals(output + "\n", err, code + ": the output shown");

        var fix = sections.get("Fix");
        assertNotNull(fix, code + " has a fix");
        if (fix.stream().noneMatch(b -> b.lang().equals("elm") || b.lang().equals("toml"))) return;
        write(project, fix);
        var fixed = run("check", project.toString());
        assertEquals(0, fixed.status(), code + ": the fix compiles\n" + fixed.err());
    }

    record Block(String lang, String body) {}

    /** Each {@code ## } section's fenced blocks, by heading. */
    private static Map<String, List<Block>> sections(String markdown) {
        var sections = new LinkedHashMap<String, List<Block>>();
        List<Block> current = null;
        String lang = null;
        var body = new StringBuilder();
        for (String line : markdown.split("\n")) {
            if (lang != null) {
                if (line.equals("```")) {
                    current.add(new Block(lang, body.toString().stripTrailing()));
                    lang = null;
                    body.setLength(0);
                } else body.append(line).append('\n');
            } else if (line.startsWith("## ")) {
                current = new ArrayList<>();
                sections.put(line.substring(3), current);
            } else if (line.startsWith("```") && current != null) lang = line.substring(3);
        }
        return sections;
    }

    /**
     * Write a section's modules and configuration. A module without its own header is Main,
     * exposing main, with its code starting on line 4, or line 3 when it starts with an import.
     */
    private static void write(Path project, List<Block> blocks) throws Exception {
        if (!Files.exists(project.resolve("elci.toml"))) Files.writeString(project.resolve("elci.toml"), FUNCTION_PROJECT);
        for (Block b : blocks) {
            switch (b.lang()) {
                case "toml" -> Files.writeString(project.resolve("elci.toml"), b.body() + "\n");
                case "elm" -> {
                    String source = b.body() + "\n";
                    String module = "Main";
                    var header = Pattern.compile("^module ([A-Z][A-Za-z0-9.]*)").matcher(source);
                    if (header.find()) module = header.group(1);
                    else source = "module Main exposing (main)\n\n" + (source.startsWith("import ") ? "" : "\n") + source;
                    Path file = project.resolve("src/" + module.replace('.', '/') + ".elm");
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, source);
                }
                default -> {}
            }
        }
    }

    @Test
    void theCommandListsTheCodesAndPrintsAnEntry() {
        var list = run("explain");
        assertEquals(0, list.status());
        assertTrue(list.out().contains("\n  ELCI2002: "), list.out());
        assertEquals(Explain.CODES.size(), list.out().lines().filter(l -> l.startsWith("  ELCI")).count());
        var one = run("explain", "ELCI2002");
        assertEquals(0, one.status());
        assertTrue(one.out().startsWith("ELCI2002: "), one.out());
        assertFalse(one.out().contains("```") || one.out().contains("\n#"), "Markdown marks are removed");
        assertTrue(one.out().contains("\n    main : String\n    main =\n        String.fromInt 42\n"), "Code is indented");
        assertEquals(one.out(), run("explain", "2002").out());
        assertEquals(one.out(), run("explain", "elci2002").out());
        assertEquals(one.out(), run("explain", "ELCI2002", "--color=never").out());
        assertTrue(run("explain", "ELCI2002", "--color=always").out().startsWith("\u001b[1mELCI2002: "));
        var unknown = run("explain", "ELCI9999");
        assertEquals(1, unknown.status());
        assertEquals("elci: ELCI4001: ELCI9999 is not an Elci error code; run elci explain to list them\n", unknown.err());
    }

    @Test
    void configurationErrorsHaveACode() throws Exception {
        Path empty = root.resolve("empty");
        Files.createDirectories(empty);
        assertEquals("elci: ELCI4001: " + empty.resolve("elci.toml") + " not found; a project needs an elci.toml, which elci init creates\n",
                run("check", empty.toString()).err());
        Files.writeString(empty.resolve("elci.toml"), FUNCTION_PROJECT);
        assertTrue(run("check", empty.toString()).err().startsWith("elci: ELCI4001: No source file for module Main; add Main.elm"));
        assertTrue(run("check", empty.toString(), "--frobnicate").err().startsWith("elci: ELCI4001: Unknown option"));
    }

    @Test
    void aFailedRunEndsWithWhereToReadMore() throws Exception {
        Files.writeString(root.resolve("elci.toml"), FUNCTION_PROJECT);
        Files.createDirectories(root.resolve("src"));
        Path main = root.resolve("src/Main.elm");
        Files.writeString(main, "module Main exposing (main)\n\n\nmain : Int\nmain =\n    Jsut 1\n");
        var one = run("check", root.toString());
        assertTrue(one.err().endsWith("Hint: did you mean `Just`?\n\nFor more on ELCI2005, run `elci explain ELCI2005`.\n"), one.err());
        Files.writeString(main, "module Main exposing (main)\n\n\na : Int\na =\n    \"x\"\n\n\nb : Int\nb =\n    Jsut 1\n\n\n"
                + "c : Int\nc =\n    \"y\"\n\n\nmain : Int\nmain =\n    a + b + c\n");
        var several = run("check", root.toString());
        assertTrue(several.err().endsWith("\n3 errors\n\nFor more on ELCI2002 and ELCI2005, run `elci explain <code>`.\n"), several.err());
        assertFalse(run("check", root.toString(), "--report=json").err().contains("For more"), "JSON has one object per line only");
    }
}
