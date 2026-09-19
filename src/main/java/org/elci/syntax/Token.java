package org.elci.syntax;

public record Token(String text, Kind kind, int offset, int line, int column) {
    public enum Kind { NAME, INT, STRING, SYMBOL, EOF }
}
