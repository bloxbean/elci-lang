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
     * Render at the token's own module file when {@code files} knows it. A module the project does
     * not own (a bundled core or test-library module) is named as {@code <bundled>/Module.elm};
     * tokens without a module render at {@code fallback}.
     */
    public String render(Function<String, String> files, String fallback) {
        if (token.source() == null) return render(fallback);
        String file = files.apply(token.source());
        return render(file != null ? file : "<bundled>/" + token.source().replace('.', '/') + ".elm");
    }
}
