package org.elci.syntax;

import java.util.ArrayList;
import java.util.List;
import static org.elci.syntax.Token.Kind.*;

/** Original Elm-profile lexer. Layout is preserved as token positions. */
public final class Lexer {
    private final String source;
    private final String module;
    private final List<Token> tokens = new ArrayList<>();
    private final List<Diagnostic> errors = new ArrayList<>();
    private int pos, line = 1, column = 1;
    public Lexer(String source) { this(source, null); }
    /** @param module the module this source defines, recorded on every token for diagnostics */
    public Lexer(String source, String module) { this.source = source; this.module = module; }
    /**
     * The tokens of the source. A lexical error is recorded and lexing continues after it, so a
     * source reports all its lexical errors at once (ADR-012); a source with any is not parsed.
     */
    public List<Token> lex() {
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (c == '\t') { record("Tabs are not permitted; use spaces for Elm layout"); advance(); continue; }
            if (Character.isWhitespace(c)) { advance(); continue; }
            if (at("--")) { while (pos < source.length() && source.charAt(pos) != '\n') advance(); continue; }
            if (at("{-")) { comment(); continue; }
            int start = pos, ln = line, col = column;
            if (Character.isLetter(c) || c == '_') {
                advance();
                while (pos < source.length() && (Character.isLetterOrDigit(source.charAt(pos)) || source.charAt(pos) == '_')) advance();
                tokens.add(new Token(source.substring(start,pos), NAME,start,ln,col,module));
            } else if (Character.isDigit(c)) {
                advance();
                while (pos < source.length() && Character.isDigit(source.charAt(pos))) advance();
                String digits = source.substring(start,pos);
                if (pos < source.length() && source.charAt(pos) == '.' && pos+1 < source.length() && Character.isDigit(source.charAt(pos+1))) {
                    record("Float is unsupported; represent fractional amounts using an explicit integer scale");
                    advance();
                    while (pos < source.length() && Character.isDigit(source.charAt(pos))) advance();
                }
                tokens.add(new Token(digits,INT,start,ln,col,module));
            } else if (c == '"') {
                advance(); var value = new StringBuilder();
                boolean closed = false;
                while (pos < source.length()) {
                    if (source.charAt(pos) == '"') { advance(); closed = true; break; }
                    char ch = advance();
                    if (ch == '\n' || ch == '\r') {
                        record("Use an escaped newline in a string");
                        closed = true;
                        break;
                    }
                    if (ch == '\\') {
                        if (pos >= source.length()) { record("Unterminated string escape"); break; }
                        char escape = advance();
                        switch (escape) {
                            case 'n' -> value.append('\n');
                            case 'r' -> value.append('\r');
                            case 't' -> value.append('\t');
                            case '"' -> value.append('"');
                            case '\\' -> value.append('\\');
                            default -> record("Unsupported string escape");
                        }
                        continue;
                    }
                    value.append(ch);
                }
                if (!closed && pos == source.length() && errors.stream().noneMatch(e -> e.getMessage().equals("Unterminated string escape")))
                    record("Unterminated string");
                tokens.add(new Token(value.toString(),STRING,start,ln,col,module));
            } else {
                String op = null;
                for (String candidate : List.of("->","//","==","/=","<=",">=","&&","||","::","++","|>","<|",">>","<<",".."))
                    if (at(candidate)) { op=candidate; break; }
                if (op == null) {
                    if ("()[]{},:=|.\\+-*<>/^".indexOf(c) < 0) { record("Unsupported character '" + c + "'"); advance(); continue; }
                    op = String.valueOf(c);
                }
                for (int i=0;i<op.length();i++) advance();
                tokens.add(new Token(op,SYMBOL,start,ln,col,module));
            }
        }
        tokens.add(new Token("<eof>",EOF,pos,line,column,module));
        var found = Diagnostic.of(errors);
        if (found != null) throw found;
        return List.copyOf(tokens);
    }
    private void comment() {
        advance(); advance(); int depth=1;
        while (depth>0) {
            if (pos==source.length()) { record("Unterminated block comment"); return; }
            if(at("{-")) { advance(); advance(); depth++; }
            else if(at("-}")) { advance(); advance(); depth--; }
            else advance();
        }
    }
    private boolean at(String s) { return source.startsWith(s,pos); }
    private char advance() { char c=source.charAt(pos++); if(c=='\n'){line++;column=1;}else column++; return c; }
    private Diagnostic error(String message) { return new Diagnostic("ELCI1001",new Token("",SYMBOL,pos,line,column,module),message); }
    private void record(String message) { errors.add(error(message)); }
}
