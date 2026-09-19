package org.elci.syntax;

import java.util.ArrayList;
import java.util.List;
import static org.elci.syntax.Token.Kind.*;

/** Original Elm-profile lexer. Layout is preserved as token positions. */
public final class Lexer {
    private final String source;
    private final List<Token> tokens = new ArrayList<>();
    private int pos, line = 1, column = 1;
    public Lexer(String source) { this.source = source; }
    public List<Token> lex() {
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (c == '\t') fail("Tabs are not permitted; use spaces for Elm layout");
            if (Character.isWhitespace(c)) { advance(); continue; }
            if (at("--")) { while (pos < source.length() && source.charAt(pos) != '\n') advance(); continue; }
            if (at("{-")) { comment(); continue; }
            int start = pos, ln = line, col = column;
            if (Character.isLetter(c) || c == '_') {
                advance();
                while (pos < source.length() && (Character.isLetterOrDigit(source.charAt(pos)) || source.charAt(pos) == '_')) advance();
                tokens.add(new Token(source.substring(start,pos), NAME,start,ln,col));
            } else if (Character.isDigit(c)) {
                advance();
                while (pos < source.length() && Character.isDigit(source.charAt(pos))) advance();
                if (pos < source.length() && source.charAt(pos) == '.' && pos+1 < source.length() && Character.isDigit(source.charAt(pos+1)))
                    fail("Float is unsupported; represent fractional amounts using an explicit integer scale");
                tokens.add(new Token(source.substring(start,pos),INT,start,ln,col));
            } else if (c == '"') {
                advance(); var value = new StringBuilder();
                while (pos < source.length() && source.charAt(pos) != '"') {
                    char ch = advance();
                    if (ch == '\n' || ch == '\r') fail("Use an escaped newline in a string");
                    if (ch == '\\') {
                        if (pos >= source.length()) fail("Unterminated string escape");
                        ch = switch (advance()) {
                            case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                            case '"' -> '"'; case '\\' -> '\\';
                            default -> throw error("Unsupported string escape");
                        };
                    }
                    value.append(ch);
                }
                if (pos == source.length()) fail("Unterminated string");
                advance(); tokens.add(new Token(value.toString(),STRING,start,ln,col));
            } else {
                String op = null;
                for (String candidate : List.of("->","//","==","/=","<=",">=","&&","||","::","++","|>","<|",">>","<<",".."))
                    if (at(candidate)) { op=candidate; break; }
                if (op == null) {
                    if ("()[]{},:=|.\\+-*<>/".indexOf(c) < 0) fail("Unsupported character '" + c + "'");
                    op = String.valueOf(c);
                }
                for (int i=0;i<op.length();i++) advance();
                tokens.add(new Token(op,SYMBOL,start,ln,col));
            }
        }
        tokens.add(new Token("<eof>",EOF,pos,line,column));
        return List.copyOf(tokens);
    }
    private void comment() {
        advance(); advance(); int depth=1;
        while (depth>0) {
            if (pos==source.length()) fail("Unterminated block comment");
            if(at("{-")) { advance(); advance(); depth++; }
            else if(at("-}")) { advance(); advance(); depth--; }
            else advance();
        }
    }
    private boolean at(String s) { return source.startsWith(s,pos); }
    private char advance() { char c=source.charAt(pos++); if(c=='\n'){line++;column=1;}else column++; return c; }
    private Diagnostic error(String message) { return new Diagnostic("ELCI1001",new Token("",SYMBOL,pos,line,column),message); }
    private void fail(String message) { throw error(message); }
}
