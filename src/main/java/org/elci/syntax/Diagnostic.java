package org.elci.syntax;

import java.util.function.Function;

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
    /**
     * Render at the token's own module file when {@code files} knows it, else at {@code fallback}.
     */
    public String render(Function<String, String> files, String fallback) {
        String file = token.source() == null ? null : files.apply(token.source());
        return render(file == null ? fallback : file);
    }
}
