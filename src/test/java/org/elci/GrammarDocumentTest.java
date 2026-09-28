package org.elci;

import static org.junit.jupiter.api.Assertions.*;

import org.elci.syntax.Parser;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Keeps docs/GRAMMAR.md true: every complete module it shows must parse. */
class GrammarDocumentTest {
    private static final Path GRAMMAR = Path.of("docs/GRAMMAR.md");
    private static final Pattern ELM_BLOCK = Pattern.compile("(?ms)^```elm[^\\n]*\\n(.*?)^```");

    static List<String> elmBlocks(String markdown) {
        var blocks = new ArrayList<String>();
        var matcher = ELM_BLOCK.matcher(markdown);
        while (matcher.find()) blocks.add(matcher.group(1));
        return blocks;
    }

    @TestFactory
    Stream<DynamicTest> everyCompleteModuleInTheGrammarParses() throws IOException {
        var modules =
                elmBlocks(Files.readString(GRAMMAR)).stream()
                        .filter(block -> block.startsWith("module "))
                        .toList();
        assertFalse(modules.isEmpty(), GRAMMAR + " shows no complete elm module");
        return modules.stream()
                .map(
                        source ->
                                DynamicTest.dynamicTest(
                                        source.lines().findFirst().orElseThrow(),
                                        () ->
                                                assertDoesNotThrow(
                                                        () -> new Parser(source).parse(),
                                                        () -> "in " + GRAMMAR + ":\n" + source)));
    }
}
