package org.elci.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * {@code elci explain}: the long-form entry for each diagnostic code (ADR-012). Entries are
 * Markdown resources under {@code elci/explain/}, one per code, printed for the terminal.
 */
final class Explain {
    /** Every code the compiler emits, each with an entry. */
    static final List<String> CODES = List.of(
            "ELCI1001", "ELCI1002",
            "ELCI2001", "ELCI2002", "ELCI2003", "ELCI2004", "ELCI2005", "ELCI2006", "ELCI2007",
            "ELCI2008", "ELCI2009", "ELCI2010", "ELCI2011", "ELCI2012", "ELCI2013", "ELCI2014",
            "ELCI2101", "ELCI2102", "ELCI2103", "ELCI2104", "ELCI2105",
            "ELCI3001", "ELCI3002", "ELCI3003",
            "ELCI4001", "ELCI4002", "ELCI4003");

    private Explain() {}

    /** Run {@code elci explain [code]}: the list of codes, or one entry. */
    static int run(String[] args, boolean colour) {
        if (args.length == 0) {
            System.out.println("Run elci explain <code> for one of these:\n");
            for (String code : CODES) System.out.println("  " + entry(code).lines().findFirst().orElseThrow().substring(2));
            return 0;
        }
        if (args.length > 1) throw new IllegalArgumentException("ELCI4001: explain takes one code, such as ELCI2002");
        String code = normalize(args[0]);
        if (!CODES.contains(code))
            throw new IllegalArgumentException("ELCI4001: " + args[0] + " is not an Elci error code; run elci explain to list them");
        System.out.print(render(entry(code), colour));
        return 0;
    }

    /** {@code ELCI2002}, {@code elci2002} and {@code 2002} all name ELCI2002. */
    static String normalize(String code) {
        String upper = code.toUpperCase(Locale.ROOT);
        return upper.matches("\\d{4}") ? "ELCI" + upper : upper;
    }

    /** The Markdown entry for a code in {@link #CODES}. */
    static String entry(String code) {
        try (InputStream in = Explain.class.getResourceAsStream("/elci/explain/" + code + ".md")) {
            if (in == null) throw new IllegalStateException("No explain entry for " + code);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the explain entry for " + code, e);
        }
    }

    /**
     * The line that ends a failed run's report, pointing to the entries of its codes, or null
     * when none of them has one.
     */
    static String footer(Collection<String> codes) {
        var named = new ArrayList<String>();
        for (String code : codes) if (code != null && CODES.contains(code) && !named.contains(code)) named.add(code);
        if (named.isEmpty()) return null;
        if (named.size() == 1) return "For more on " + named.get(0) + ", run `elci explain " + named.get(0) + "`.";
        return "For more on " + String.join(", ", named.subList(0, named.size() - 1)) + " and " + named.getLast()
                + ", run `elci explain <code>`.";
    }

    /**
     * An entry as terminal text: headings without their {@code #} marks, and code blocks
     * indented by four spaces without their fences.
     */
    static String render(String markdown, boolean colour) {
        var out = new StringBuilder();
        boolean code = false;
        for (String line : markdown.split("\n", -1)) {
            if (line.startsWith("```")) {
                code = !code;
                continue;
            }
            if (code) out.append(line.isEmpty() ? "" : "    " + line);
            else if (line.startsWith("#")) {
                String heading = line.replaceFirst("^#+ ", "");
                out.append(colour ? "\u001b[1m" + heading + "\u001b[0m" : heading);
            } else out.append(line);
            out.append('\n');
        }
        // The Markdown ends with a newline, so the split leaves one empty line too many.
        out.setLength(out.length() - 1);
        return out.toString();
    }
}
