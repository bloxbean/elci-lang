package org.elci.syntax;

/**
 * A lexical token. {@code source} is the module the token was read from, or null for tokens
 * the compiler synthesizes or single-source compilations.
 */
public record Token(String text, Kind kind, int offset, int line, int column, String source) {
    public enum Kind { NAME, INT, STRING, SYMBOL, EOF }

    public Token(String text, Kind kind, int offset, int line, int column) {
        this(text, kind, offset, line, column, null);
    }
}
