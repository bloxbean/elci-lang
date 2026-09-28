package org.elci.syntax;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * A source-located, stable-code compiler failure. Its first line, {@code path:line:col: CODE:
 * message}, is stable for tools; {@link #explain} adds the source excerpt and hints below it
 * (ADR-011).
 */
public final class Diagnostic extends RuntimeException {
    /** The most diagnostics one run reports (ADR-012). */
    public static final int LIMIT = 20;

    /** How many source lines an underlined span shows before it is cut (ADR-012). */
    private static final int SPAN_LINES = 5;

    private static final String RESET = "\u001b[0m", BOLD = "\u001b[1m", RED = "\u001b[31m",
            BOLD_RED = "\u001b[1;31m", CYAN = "\u001b[36m";

    private final String code;
    private final Token token;
    private final List<String> hints;
    /** The run's later diagnostics, in source order, when a phase found several (ADR-012). */
    private final List<Diagnostic> others;
    /** The last token of the reported expression, which the excerpt underlines to; or null. */
    private final Token spanEnd;
    /** A mismatch's two types, as source, for laying them out when they are long; or null. */
    private final String found, needed;

    public Diagnostic(String code, Token token, String message) {
        this(code, token, message, List.of());
    }

    public Diagnostic(String code, Token token, String message, List<String> hints) {
        this(code, token, message, hints, List.of(), null, null, null);
    }

    private Diagnostic(String code, Token token, String message, List<String> hints, List<Diagnostic> others,
                       Token spanEnd, String found, String needed) {
        super(message); this.code = code; this.token = token; this.hints = List.copyOf(hints);
        this.others = List.copyOf(others); this.spanEnd = spanEnd; this.found = found; this.needed = needed;
    }

    public String code() { return code; }
    public Token token() { return token; }
    public List<String> hints() { return hints; }

    /** This diagnostic and the run's others, in source order. */
    public List<Diagnostic> all() {
        var all = new ArrayList<Diagnostic>();
        all.add(new Diagnostic(code, token, getMessage(), hints, List.of(), spanEnd, found, needed));
        all.addAll(others);
        return all;
    }

    /**
     * The diagnostics a phase found, as one: the first in source order, carrying the others. So a
     * caller that catches a {@code Diagnostic} sees the first error; the CLI prints them all.
     * Null when there are none.
     */
    public static Diagnostic of(List<Diagnostic> found) {
        if (found.isEmpty()) return null;
        // One mistake can fail several checks alike, such as each comparison inside List.sort;
        // it is reported once.
        var seen = new HashSet<String>();
        var sorted = new ArrayList<Diagnostic>();
        found.forEach(d -> d.all().forEach(e -> {
            if (seen.add(e.token.source() + ":" + e.token.line() + ":" + e.token.column() + ":" + e.code + ":" + e.getMessage()))
                sorted.add(e);
        }));
        sorted.sort(Comparator.comparing((Diagnostic d) -> d.token.source() == null ? "" : d.token.source())
                .thenComparingInt(d -> d.token.line()).thenComparingInt(d -> d.token.column()));
        var first = sorted.getFirst();
        return new Diagnostic(first.code, first.token, first.getMessage(), first.hints, sorted.subList(1, sorted.size()),
                first.spanEnd, first.found, first.needed);
    }

    /** Every diagnostic of the run changed alike. */
    public Diagnostic map(UnaryOperator<Diagnostic> change) {
        return of(all().stream().map(change).toList());
    }

    /** This diagnostic with more hints. */
    public Diagnostic withHints(List<String> more) {
        if (more.isEmpty()) return this;
        var all = new ArrayList<>(hints);
        more.forEach(h -> { if (!all.contains(h)) all.add(h); });
        return new Diagnostic(code, token, getMessage(), all, others, spanEnd, found, needed);
    }

    /** This diagnostic underlining the expression from its token to {@code end}. */
    public Diagnostic withSpan(Token end) {
        return new Diagnostic(code, token, getMessage(), hints, others, end, found, needed);
    }

    /** This diagnostic with a mismatch's types, laid out under the excerpt when they are long. */
    public Diagnostic withTypes(String found, String needed) {
        return new Diagnostic(code, token, getMessage(), hints, others, spanEnd, found, needed);
    }

    public String render(String source) {
        return source + ":" + token.line() + ":" + token.column() + ": " + code + ": " + getMessage();
    }

    /**
     * Render at the token's own module file when {@code files} knows it. A module the project does
     * not own (a bundled core or test-library module) is named as {@code <bundled>/Module.elm};
     * tokens without a module render at {@code fallback}.
     */
    public String render(Function<String, String> files, String fallback) {
        return render(path(files, fallback));
    }

    private String path(Function<String, String> files, String fallback) {
        if (token.source() == null) return fallback;
        String file = files.apply(token.source());
        return file != null ? file : "<bundled>/" + token.source().replace('.', '/') + ".elm";
    }

    /** The first line, then the source line with a caret under the token, then the hints. */
    public String explain(Function<String, String> files, String fallback) {
        return explain(files, fallback, false);
    }

    /**
     * {@link #explain(Function, String)}, in colour for a terminal when {@code colour}: the path
     * and code, the carets and the hints. Without colour the text is the same, character for
     * character.
     */
    public String explain(Function<String, String> files, String fallback, boolean colour) {
        String path = path(files, fallback);
        var out = new StringBuilder(colour
                ? BOLD + path + ":" + token.line() + ":" + token.column() + ":" + RESET + " " + BOLD_RED + code + RESET
                        + ": " + getMessage()
                : render(path));
        String excerpt = excerpt(path, colour);
        if (excerpt != null) out.append("\n\n").append(excerpt);
        String types = types(colour);
        if (types != null) out.append(excerpt != null ? "\n\n" : "\n\n").append(types);
        if (!hints.isEmpty()) out.append(excerpt != null || types != null ? "\n" : "\n\n");
        for (int i = 0; i < hints.size(); i++)
            out.append(i == 0 ? "" : "\n").append(colour ? CYAN + "Hint:" + RESET : "Hint:").append(" ").append(hints.get(i));
        return out.toString();
    }

    /**
     * A long mismatch's two types on their own lines, with the first difference marked; null when
     * the message holds them comfortably.
     */
    private String types(boolean colour) {
        if (found == null || needed == null || found.length() + needed.length() < 60) return null;
        String first = "It is:     ", second = "It needs:  ";
        int common = 0;
        while (common < found.length() && common < needed.length() && found.charAt(common) == needed.charAt(common)) common++;
        int stop = common;
        while (stop < needed.length() && " ,()[]{}".indexOf(needed.charAt(stop)) < 0) stop++;
        if (stop == common) stop = Math.min(common + 1, needed.length());
        String marker = " ".repeat(second.length() + common) + "^".repeat(Math.max(1, stop - common));
        return first + found + "\n" + second + needed + "\n" + (colour ? RED + marker + RESET : marker);
    }

    /** {@code N| line} and a caret line under the token; null when the source can't be read. */
    public String excerpt(String path) {
        return excerpt(path, false);
    }

    private String excerpt(String path, boolean colour) {
        if (path == null || path.startsWith("<") || token.line() < 1) return null;
        List<String> lines;
        try {
            lines = Files.readAllLines(Path.of(path));
        } catch (IOException | RuntimeException e) {
            return null;
        }
        int row = token.line();
        int start = Math.max(0, token.column() - 1);
        if (token.kind() == Token.Kind.EOF) {
            // The end of the file: point just past its last code, before any comments after it.
            int[] end = lastCode(String.join("\n", lines));
            if (end == null) return null;
            row = end[0];
            start = end[1];
        }
        if (row > lines.size()) return null;
        String line = lines.get(row - 1);
        if (start > line.length()) return null;
        int width;
        if (token.kind() == Token.Kind.STRING) {
            width = literalWidth(line, start);
        } else if (token.kind() == Token.Kind.EOF || token.text().isEmpty()) {
            width = 1;
        } else if (line.startsWith(token.text(), start)) {
            width = token.text().length();
            // A field access is reported at its dot: underline the field too.
            if (token.text().equals("."))
                while (start + width < line.length() && (Character.isLetterOrDigit(line.charAt(start + width))
                        || line.charAt(start + width) == '_')) width++;
        } else {
            // A position the compiler made up (a module's first line): find the name on the line.
            int found = line.indexOf(token.text());
            if (found < 0) return null;
            start = found;
            width = token.text().length();
        }
        int endRow = row, endColumn = start + width;
        if (spanEnd != null && spanEnd.offset() > token.offset() && spanEnd.line() <= lines.size()
                && token.kind() != Token.Kind.EOF) {
            String last = lines.get(spanEnd.line() - 1);
            int lastStart = Math.max(0, spanEnd.column() - 1);
            int lastWidth = spanEnd.kind() == Token.Kind.STRING ? literalWidth(last, lastStart)
                    : last.startsWith(spanEnd.text(), lastStart) ? spanEnd.text().length() : 1;
            endRow = spanEnd.line();
            endColumn = Math.min(last.length(), lastStart + lastWidth);
            // The reported expression may close brackets after its last token: include them.
            int open = 0;
            for (int r = row; r <= endRow; r++) {
                String text = lines.get(r - 1);
                int from = r == row ? start : 0, to = r == endRow ? endColumn : text.length();
                open += balance(text.substring(Math.min(from, text.length()), Math.min(to, text.length())));
            }
            // Past spaces and line breaks, as far as the brackets it opened are closed.
            int r = endRow, c = endColumn;
            while (open > 0 && r <= lines.size()) {
                String text = lines.get(r - 1);
                while (c < text.length() && text.charAt(c) == ' ') c++;
                if (c >= text.length()) {
                    if (r - endRow >= SPAN_LINES) break;
                    r++;
                    c = 0;
                    continue;
                }
                if (")]}".indexOf(text.charAt(c)) < 0) break;
                open--;
                endRow = r;
                endColumn = ++c;
            }
        }
        int digits = String.valueOf(endRow).length();
        var out = new StringBuilder();
        int shown = 0;
        for (int r = row; r <= endRow; r++) {
            if (shown == SPAN_LINES) {
                out.append(" ".repeat(digits)).append("| …");
                break;
            }
            String text = lines.get(r - 1);
            String number = String.format("%" + digits + "d| ", r);
            // The first line from the token, later ones from their indentation; the last to the span's end.
            int from = r == row ? start : text.length() - text.stripLeading().length();
            int to = Math.min(text.length(), r == endRow ? endColumn : text.stripTrailing().length());
            int carets = Math.max(1, to - from);
            if (shown > 0) out.append("\n");
            String caretLine = " ".repeat(number.length() + from) + "^".repeat(carets);
            out.append(number).append(text).append("\n").append(colour ? RED + caretLine + RESET : caretLine);
            shown++;
        }
        return out.toString();
    }

    /** Opening minus closing brackets in a piece of source, outside string literals. */
    private static int balance(String text) {
        int open = 0;
        boolean string = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (string) {
                if (c == '\\') i++;
                else if (c == '"') string = false;
            } else if (c == '"') string = true;
            else if ("([{".indexOf(c) >= 0) open++;
            else if (")]}".indexOf(c) >= 0) open--;
        }
        return open;
    }

    /** The row (from 1) and the column (from 0) just past the last code in a source, or null. */
    private static int[] lastCode(String text) {
        int row = 1, column = 0, lastRow = 0, lastColumn = 0, depth = 0;
        boolean string = false, line = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                row++;
                column = 0;
                line = false;
                continue;
            }
            column++;
            if (line) continue;
            if (depth > 0) {
                if (text.startsWith("{-", i)) depth++;
                else if (text.startsWith("-}", i)) depth--;
                if (text.startsWith("{-", i) || text.startsWith("-}", i)) { i++; column++; }
                continue;
            }
            if (string) {
                if (c == '\\') { i++; column++; }
                else if (c == '"') string = false;
                lastRow = row;
                lastColumn = column;
                continue;
            }
            if (text.startsWith("--", i)) { line = true; continue; }
            if (text.startsWith("{-", i)) { depth = 1; i++; column++; continue; }
            if (c == '"') string = true;
            if (!Character.isWhitespace(c)) {
                lastRow = row;
                lastColumn = column;
            }
        }
        return lastRow == 0 ? null : new int[] {lastRow, lastColumn};
    }

    /** The width of the string literal starting at {@code start}, quotes and escapes included. */
    private static int literalWidth(String line, int start) {
        if (start >= line.length() || line.charAt(start) != '"') return 1;
        for (int i = start + 1; i < line.length(); i++) {
            if (line.charAt(i) == '\\') i++;
            else if (line.charAt(i) == '"') return i - start + 1;
        }
        return line.length() - start;
    }

    /** The diagnostic as a JSON object: code, path, line, column, message, hints, excerpt, context. */
    public String json(Function<String, String> files, String fallback) {
        return json(files, fallback, null);
    }

    /** {@link #json(Function, String)}, with {@code context}: where in the project it arose, or null. */
    public String json(Function<String, String> files, String fallback, String context) {
        String path = path(files, fallback);
        String excerpt = excerpt(path);
        var hintsJson = new ArrayList<String>();
        hints.forEach(h -> hintsJson.add(quote(h)));
        return "{\"code\":" + quote(code) + ",\"path\":" + quote(path) + ",\"line\":" + token.line()
                + ",\"column\":" + token.column() + ",\"message\":" + quote(getMessage())
                + ",\"hints\":[" + String.join(",", hintsJson) + "]"
                + ",\"excerpt\":" + (excerpt == null ? "null" : quote(excerpt))
                + ",\"context\":" + (context == null ? "null" : quote(context)) + "}";
    }

    public static String quote(String text) {
        var out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
