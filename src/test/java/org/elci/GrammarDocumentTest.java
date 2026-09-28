package org.elci;

import static org.junit.jupiter.api.Assertions.*;

import org.elci.syntax.Parser;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Keeps the grammar true: every complete module that docs/GRAMMAR.md, or the documentation site's
 * copy of it, shows must parse.
 */
class GrammarDocumentTest {
    private static final List<Path> GRAMMARS =
            List.of(Path.of("docs/GRAMMAR.md"), Path.of("www/src/content/docs/reference/grammar.md"));

    static List<String> blocks(String markdown, String language) {
        var blocks = new ArrayList<String>();
        var matcher =
                Pattern.compile("(?ms)^```" + language + "[^\\n]*\\n(.*?)^```").matcher(markdown);
        while (matcher.find()) blocks.add(matcher.group(1));
        return blocks;
    }

    static List<String> elmBlocks(String markdown) {
        return blocks(markdown, "elm");
    }

    /** The site shows the grammar's productions in text blocks, since its highlighter has no EBNF. */
    @Test
    void theSiteShowsTheSameProductionsAsTheRepository() throws IOException {
        var repository = blocks(Files.readString(GRAMMARS.get(0)), "ebnf");
        assertFalse(repository.isEmpty(), GRAMMARS.get(0) + " has no ebnf blocks");
        assertEquals(repository, blocks(Files.readString(GRAMMARS.get(1)), "text"));
    }

    @TestFactory
    Stream<DynamicTest> everyCompleteModuleInTheGrammarParses() throws IOException {
        var tests = new ArrayList<DynamicTest>();
        for (var grammar : GRAMMARS) {
            var modules =
                    elmBlocks(Files.readString(grammar)).stream()
                            .filter(block -> block.startsWith("module "))
                            .toList();
            assertFalse(modules.isEmpty(), grammar + " shows no complete elm module");
            for (var source : modules)
                tests.add(
                        DynamicTest.dynamicTest(
                                grammar + ": " + source.lines().findFirst().orElseThrow(),
                                () ->
                                        assertDoesNotThrow(
                                                () -> new Parser(source).parse(),
                                                () -> "in " + grammar + ":\n" + source)));
        }
        return tests.stream();
    }
}
