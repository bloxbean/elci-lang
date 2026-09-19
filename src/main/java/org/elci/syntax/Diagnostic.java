package org.elci.syntax;

/** A source-located, stable-code compiler failure. */
public final class Diagnostic extends RuntimeException {
    private final String code;
    private final Token token;
    public Diagnostic(String code, Token token, String message) {
        super(message); this.code = code; this.token = token;
    }
    public String code() { return code; }
    public Token token() { return token; }
    public String render(String source) {
        return source + ":" + token.line() + ":" + token.column() + ": " + code + ": " + getMessage();
    }
}
