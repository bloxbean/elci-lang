package org.elci.syntax;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A source-located, stable-code compiler failure. Its first line, {@code path:line:col: CODE:
 * message}, is stable for tools; {@link #explain} adds the source excerpt and hints below it
 * (ADR-011).
 */
public final class Diagnostic extends RuntimeException {
    private final String code;
    private final Token token;
    private final List<String> hints;

    public Diagnostic(String code, Token token, String message) {
        this(code, token, message, List.of());
    }

    public Diagnostic(String code, Token token, String message, List<String> hints) {
        super(message); this.code = code; this.token = token; this.hints = List.copyOf(hints);
    }

    public String code() { return code; }
    public Token token() { return token; }
    public List<String> hints() { return hints; }

    /** This diagnostic with more hints. */
    public Diagnostic withHints(List<String> more) {
        if (more.isEmpty()) return this;
        var all = new ArrayList<>(hints);
        more.forEach(h -> { if (!all.contains(h)) all.add(h); });
        return new Diagnostic(code, token, getMessage(), all);
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
        String path = path(files, fallback);
        var out = new StringBuilder(render(path));
        String excerpt = excerpt(path);
        if (excerpt != null) out.append("\n\n").append(excerpt);
        if (!hints.isEmpty()) out.append(excerpt != null ? "\n" : "\n\n");
        for (int i = 0; i < hints.size(); i++) out.append(i == 0 ? "" : "\n").append("Hint: ").append(hints.get(i));
        return out.toString();
    }

    /** {@code N| line} and a caret line under the token; null when the source can't be read. */
    public String excerpt(String path) {
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
        width = Math.max(1, Math.min(width, line.length() - start));
        String number = row + "| ";
        return number + line + "\n" + " ".repeat(number.length() + start) + "^".repeat(width);
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
