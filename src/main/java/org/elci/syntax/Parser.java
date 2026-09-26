package org.elci.syntax;

import static org.elci.syntax.Ast.*;

import java.math.BigInteger;
import java.util.*;

/** Recursive descent with token-column layout boundaries and Pratt operators. */
public final class Parser {
    private final List<Token> tokens;
    private final String source;
    private int pos;
    private final List<Union> unions = new ArrayList<>();
    private final Map<String, Integer> typeArities = new HashMap<>(Map.of("List", 1, "Maybe", 1));
    private final Map<String, Alias> aliases = new LinkedHashMap<>();
    private final IdentityHashMap<Type, List<String>> recordOrders = new IdentityHashMap<>();
    private int synthetic;
    private static final Map<String, Integer> PRECEDENCE =
            Map.ofEntries(
                    Map.entry("|>", 0),
                    Map.entry("<|", 0),
                    Map.entry("||", 2),
                    Map.entry("&&", 3),
                    Map.entry("==", 4),
                    Map.entry("/=", 4),
                    Map.entry("<", 4),
                    Map.entry(">", 4),
                    Map.entry("<=", 4),
                    Map.entry(">=", 4),
                    Map.entry("::", 5),
                    Map.entry("++", 5),
                    Map.entry("+", 6),
                    Map.entry("-", 6),
                    Map.entry("^", 8),
                    Map.entry(">>", 9),
                    Map.entry("<<", 9),
                    Map.entry("*", 7),
                    Map.entry("//", 7),
                    Map.entry("/", 7));
    private static final Set<String> RESERVED =
            Set.of(
                    "then",
                    "else",
                    "in",
                    "of",
                    "module",
                    "import",
                    "exposing",
                    "as",
                    "type",
                    "port");

    public Parser(String source) {
        this(source, null);
    }

    /** @param module the module the source defines, recorded on tokens for diagnostics */
    public Parser(String source, String module) {
        this.source = source;
        tokens = new Lexer(source, module).lex();
        for (int i = 0; i < tokens.size() - 2; i++)
            if (tokens.get(i).kind() == Token.Kind.NAME && tokens.get(i).text().equals("type")
                    && !tokens.get(i + 1).text().equals("alias")) {
                int j = i + 2;
                while (j < tokens.size()
                        && tokens.get(j).kind() == Token.Kind.NAME
                        && Character.isLowerCase(tokens.get(j).text().charAt(0))) j++;
                typeArities.put(tokens.get(i + 1).text(), j - i - 2);
            }
    }

    public Ast.Module parse() {
        expect("module");
        String module = qualified(true);
        expect("exposing");
        var exports = exposing();
        var imports = new ArrayList<Import>();
        while (take("import")) {
            Token t = previous();
            String name = qualified(true);
            String alias = name;
            if (take("as")) alias = upper().text();
            var exposed = take("exposing") ? exposing() : List.<String>of();
            imports.add(new Import(name, alias, exposed, t));
        }
        var ds = declarations(1, "<eof>");
        expect("<eof>");
        return new Ast.Module(
                module,
                exports,
                List.copyOf(imports),
                ds,
                Map.copyOf(aliases),
                List.copyOf(unions));
    }

    private List<String> exposing() {
        expect("(");
        var names = new ArrayList<String>();
        if (take("..")) {
            names.add("..");
            expect(")");
            return names;
        }
        if (take(")")) return names;
        do {
            String n = name().text();
            if (take("(")) {
                expect("..");
                expect(")");
                n += "(..)";
            }
            names.add(n);
        } while (take(","));
        expect(")");
        return List.copyOf(names);
    }

    private List<Decl> declarations(int indent, String end) {
        var result = new ArrayList<Decl>();
        var seen = new HashSet<String>();
        while (!is(end) && peek().kind() != Token.Kind.EOF) {
            if (peek().column() != indent)
                throw error("Declarations must align at column " + indent);
            if (take("type")) {
                if (take("alias")) {
                    Token at = upper();
                    String alias = at.text();
                    var parameters = new ArrayList<String>();
                    while (!is("=")) parameters.add(lower().text());
                    expect("=");
                    Type target = type(indent);
                    if (parameters.size() != new HashSet<>(parameters).size())
                        throw new Diagnostic("ELCI1002", at, "Duplicate type alias parameter");
                    if (unions.stream().anyMatch(u -> u.name().equals(alias))
                            || aliases.putIfAbsent(
                                            alias,
                                            new Alias(
                                                    alias,
                                                    List.copyOf(parameters),
                                                    target,
                                                    recordOrders.getOrDefault(target, List.of()),
                                                    at))
                                    != null) throw error("Duplicate type alias " + alias);
                } else {
                    Token at = upper();
                    String n = at.text();
                    var parameters = new ArrayList<String>();
                    while (!is("=")) parameters.add(lower().text());
                    expect("=");
                    var variants = new ArrayList<Variant>();
                    do {
                        String constructor = upper().text();
                        var fields = new ArrayList<Type>();
                        while (!is("|") && !boundary(indent)) {
                            if (!is("(") && !is("{") && peek().kind() != Token.Kind.NAME) break;
                            fields.add(typeAtom(indent));
                        }
                        variants.add(new Variant(constructor, List.copyOf(fields)));
                    } while (take("|"));
                    if (unions.stream().anyMatch(u -> u.name().equals(n)) || aliases.containsKey(n))
                        throw error("Duplicate type " + n);
                    unions.add(new Union(n, parameters, variants, at));
                }
                continue;
            }
            if (!isNameStart()) {
                if (indent == 1) throw error("Top-level declarations require a name");
                Pattern pattern = parameterPattern();
                if (!irrefutable(pattern))
                    throw new Diagnostic(
                            "ELCI1002",
                            pattern.token(),
                            "Local destructuring requires an irrefutable tuple or record pattern");
                expect("=");
                Expr value = expression(0, indent);
                String temporary = "$elci$destructure$" + (synthetic++);
                result.add(new Decl(temporary, List.of(), null, value, pattern.token()));
                var names = new LinkedHashSet<String>();
                patternNames(pattern, names);
                for (String bound : names) {
                    if (!seen.add(bound))
                        throw new Diagnostic(
                                "ELCI1002", pattern.token(), "Duplicate declaration " + bound);
                    result.add(
                            new Decl(
                                    bound,
                                    List.of(),
                                    null,
                                    new Case(
                                            new Name(temporary, pattern.token()),
                                            List.of(
                                                    new Branch(
                                                            pattern,
                                                            new Name(bound, pattern.token()))),
                                            pattern.token()),
                                    pattern.token()));
                }
                continue;
            }
            Token t = lower();
            String n = t.text();
            Type annotation = null;
            if (take(":")) {
                annotation = type(indent);
                Token definition = lower();
                if (definition.column() != indent || !definition.text().equals(n))
                    throw error("A signature must precede its matching definition");
            }
            var params = new ArrayList<String>();
            var parameterPatterns = new ArrayList<Pattern>();
            while (!is("=")) {
                Pattern pattern = parameterPattern();
                if (!irrefutable(pattern))
                    throw new Diagnostic(
                            "ELCI1002",
                            pattern.token(),
                            "Function parameters require irrefutable patterns; use case for"
                                    + " refutable matching");
                String param =
                        simpleBinder(pattern) ? pattern.name() : "$elci$parameter$" + (synthetic++);
                params.add(param);
                parameterPatterns.add(pattern);
            }
            expect("=");
            var body = expression(0, indent);
            var boundNames = new HashSet<String>();
            for (var p : parameterPatterns) {
                for (String bound : patternNameList(p))
                    if (!boundNames.add(bound))
                        throw new Diagnostic("ELCI1002", p.token(), "Duplicate parameter " + bound);
            }
            for (int i = parameterPatterns.size() - 1; i >= 0; i--)
                if (!simpleBinder(parameterPatterns.get(i)))
                    body =
                            new Case(
                                    new Name(params.get(i), parameterPatterns.get(i).token()),
                                    List.of(new Branch(parameterPatterns.get(i), body)),
                                    parameterPatterns.get(i).token());
            if (!seen.add(n)) throw new Diagnostic("ELCI1002", t, "Duplicate declaration " + n);
            result.add(new Decl(n, List.copyOf(params), annotation, body, t));
            if (!is(end) && peek().kind() != Token.Kind.EOF && peek().column() != indent)
                throw error("Unexpected token after declaration");
        }
        return List.copyOf(result);
    }

    private Type type(int floor) {
        Type left = typeAtom(floor);
        var arguments = new ArrayList<>(left.arguments());
        while (!boundary(floor)
                && (peek().kind() == Token.Kind.NAME && !RESERVED.contains(peek().text())
                        || is("(")
                        || is("{"))) arguments.add(typeAtom(floor));
        Type original = left;
        left = new Type(left.name(), arguments);
        if (recordOrders.containsKey(original)) recordOrders.put(left, recordOrders.get(original));
        if (take("->")) return Type.fn(left, type(floor));
        return left;
    }

    private Type typeAtom(int floor) {
        if (take("(")) {
            if (take(")")) return new Type("Unit", List.of());
            var a = type(floor);
            if (take(",")) {
                var parts = new ArrayList<Type>();
                parts.add(a);
                do {
                    parts.add(type(floor));
                } while (take(","));
                expect(")");
                return new Type("Tuple", parts);
            }
            expect(")");
            return a;
        }
        if (take("{")) {
            var fields = new TreeMap<String, Type>();
            var order = new ArrayList<String>();
            if (!is("}"))
                do {
                    String field = lower().text();
                    if (is("|"))
                        throw error("Extensible records are unsupported; use a closed record");
                    expect(":");
                    if (fields.putIfAbsent(field, type(-1)) != null)
                        throw error("Duplicate record field " + field);
                    order.add(field);
                } while (take(","));
            expect("}");
            var record =
                    new Type(
                            "Record:" + String.join(",", fields.keySet()),
                            List.copyOf(fields.values()));
            recordOrders.put(record, List.copyOf(order));
            return record;
        }
        var t = name();
        String n = t.text();
        if (Character.isUpperCase(n.charAt(0))) while (take(".")) n += "." + upper().text();
        if (n.equals("Tuple") || n.equals("Unit"))
            throw new Diagnostic("ELCI2012", t, n.equals("Tuple")
                    ? "Write tuple types as ( a, b ) or ( a, b, c )" : "Write the unit type as ()");
        if (n.equals("Float"))
            throw new Diagnostic("ELCI2001", t, "Float is unsupported; use scaled Int");
        var args = new ArrayList<Type>();

        return new Type(n, List.copyOf(args));
    }

    /** Right-associative operators; the rest (other than comparisons) associate to the left. */
    private static final Set<String> RIGHT_ASSOCIATIVE = Set.of("::", "++", "<|", "&&", "||", ">>", "^");

    private Expr expression(int min, int floor) {
        return expression(min, floor, null);
    }

    /** @param rightOf the right-associative operator whose right operand this is, or null */
    private Expr expression(int min, int floor, String rightOf) {
        Expr left = prefix(floor);
        while (!boundary(floor)) {
            if (is(".") && peek().offset() == previous().offset() + previous().text().length()) {
                Token dot = next();
                left = new Field(left, lower().text(), dot);
                continue;
            }
            if (negation() && 10 >= min) {
                // Elm 0.19: `f -1` passes a negative argument; `a - 1` and `a-1` subtract.
                Token minus = next();
                left = new Apply(left, new Binary("-", new Literal(BigInteger.ZERO, minus), expression(11, floor), minus), minus);
                continue;
            }
            String op = peek().kind() == Token.Kind.SYMBOL ? peek().text() : "";
            Integer precedence = PRECEDENCE.get(op);
            if (precedence != null && precedence >= min) {
                if (rightOf != null && precedence.equals(PRECEDENCE.get(rightOf)) && !RIGHT_ASSOCIATIVE.contains(op))
                    throw mixing(rightOf, op);
                Token token = next();
                if (op.equals("/"))
                    throw new Diagnostic(
                            "ELCI2001",
                            token,
                            "Float division is unsupported; use // for integer quotient");
                boolean rightAssociative = RIGHT_ASSOCIATIVE.contains(op);
                Expr right = rightAssociative ? expression(precedence, floor, op) : expression(precedence + 1, floor);
                left = new Binary(op, left, right, token);
                String next = peek().kind() == Token.Kind.SYMBOL ? peek().text() : "";
                if (!rightAssociative && precedence != 4 && precedence.equals(PRECEDENCE.get(next))
                        && RIGHT_ASSOCIATIVE.contains(next))
                    throw mixing(op, next);
                if (precedence == 4 && peek().kind() == Token.Kind.SYMBOL
                        && PRECEDENCE.getOrDefault(peek().text(), -1) == 4)
                    throw error(
                            "Comparison operators cannot be chained; use && or explicit"
                                    + " parentheses");
            } else if (atomStart() && 10 >= min) {
                Token token = peek();
                left = new Apply(left, expression(11, floor), token);
            } else break;
        }
        return left;
    }

    /** Elm's rule: operators of one precedence that associate in opposite directions don't mix. */
    private Diagnostic mixing(String first, String second) {
        return error("Mixing (" + first + ") and (" + second + ") needs parentheses: they associate in opposite"
                + " directions, as in Elm");
    }

    private Expr prefix(int floor) {
        Token t = next();
        // A string's text is its content, never a keyword or symbol: "-", "(" and "of" are strings.
        if (t.kind() == Token.Kind.STRING) return new Literal(t.text(), t);
        if (t.text().equals("if")) {
            Expr cond = expression(0, floor);
            expect("then");
            Expr yes = expression(0, floor);
            expect("else");
            return new If(cond, yes, expression(0, floor), t);
        }
        if (t.text().equals("\\")) {
            var ps = new ArrayList<String>();
            var patterns = new ArrayList<Pattern>();
            do {
                var pattern = parameterPattern();
                if (!irrefutable(pattern))
                    throw new Diagnostic(
                            "ELCI1002",
                            pattern.token(),
                            "Lambda parameters require irrefutable patterns; use case for refutable"
                                    + " matching");
                patterns.add(pattern);
                ps.add(simpleBinder(pattern) ? pattern.name() : "$elci$parameter$" + (synthetic++));
            } while (!is("->"));
            var names = new HashSet<String>();
            for (var p : patterns)
                for (String n : patternNameList(p))
                    if (!names.add(n))
                        throw new Diagnostic(
                                "ELCI1002", p.token(), "Duplicate lambda parameter " + n);
            expect("->");
            Expr body = expression(0, floor);
            for (int i = patterns.size() - 1; i >= 0; i--)
                if (!simpleBinder(patterns.get(i)))
                    body =
                            new Case(
                                    new Name(ps.get(i), patterns.get(i).token()),
                                    List.of(new Branch(patterns.get(i), body)),
                                    patterns.get(i).token());
            return new Lambda(ps, body, t);
        }
        if (t.text().equals("let")) {
            int indent = peek().column();
            var ds = declarations(indent, "in");
            expect("in");
            return new Let(ds, expression(0, floor), t);
        }
        if (t.text().equals("case")) {
            Expr value = expression(0, floor);
            expect("of");
            int indent = peek().column();
            var branches = new ArrayList<Branch>();
            do {
                var p = pattern();
                expect("->");
                branches.add(new Branch(p, expression(0, indent)));
            } while (peek().column() == indent && !is("<eof>") && !is("in") && !is("else"));
            return new Case(value, branches, t);
        }
        if (t.text().equals("-"))
            return new Binary("-", new Literal(BigInteger.ZERO, t), expression(9, floor), t);
        if (t.kind() == Token.Kind.INT) return new Literal(new BigInteger(t.text()), t);
        if (t.text().equals("True") || t.text().equals("False"))
            return new Literal(t.text().equals("True"), t);
        if (t.text().equals("(")) {
            if (take(")")) return new Literal(null, t);
            if (peek().kind() == Token.Kind.SYMBOL && PRECEDENCE.containsKey(peek().text())
                    && tokens.get(pos + 1).kind() == Token.Kind.SYMBOL && tokens.get(pos + 1).text().equals(")")) {
                // An operator as a function, as in Elm: (+) is \a b -> a + b.
                Token op = next();
                expect(")");
                if (op.text().equals("/"))
                    throw new Diagnostic("ELCI2001", op, "Float division is unsupported; use (//) for integer quotient");
                String left = "$elci$left$" + (synthetic++), right = "$elci$right$" + (synthetic++);
                return new Lambda(List.of(left, right),
                        new Binary(op.text(), new Name(left, op), new Name(right, op), op), t);
            }
            Expr e = expression(0, -1);
            if (take(",")) {
                var es = new ArrayList<Expr>();
                es.add(e);
                do {
                    es.add(expression(0, -1));
                } while (take(","));
                expect(")");
                if (es.size() > 3) throw error("Tuples have two or three elements");
                return new Items(es, true, t);
            }
            expect(")");
            return e;
        }
        if (t.text().equals("{")) {
            Expr base = null;
            var fields = new LinkedHashMap<String, Expr>();
            if (take("}")) return new RecordValue(null, fields, t);
            Token first = lower();
            if (take("|")) {
                base = new Name(first.text(), first);
                first = lower();
            }
            while (true) {
                expect("=");
                if (fields.putIfAbsent(first.text(), expression(0, -1)) != null)
                    throw error("Duplicate record field " + first.text());
                if (!take(",")) break;
                first = lower();
            }
            expect("}");
            return new RecordValue(base, Collections.unmodifiableMap(fields), t);
        }
        if (t.text().equals("[")) {
            var es = new ArrayList<Expr>();
            if (!is("]")) {
                do {
                    es.add(expression(0, -1));
                } while (take(","));
            }
            expect("]");
            return new Items(es, false, t);
        }
        if (t.kind() == Token.Kind.NAME && !RESERVED.contains(t.text())) {
            String n = t.text();
            if (Character.isUpperCase(n.charAt(0))) while (take(".")) n += "." + name().text();
            // The token spells the whole qualified name, as written, for diagnostics.
            return new Name(n, n.equals(t.text()) ? t
                    : new Token(n, t.kind(), t.offset(), t.line(), t.column(), t.source()));
        }
        throw new Diagnostic("ELCI1002", t, "Expected expression, found '" + t.text() + "'");
    }

    private Pattern pattern() {
        Pattern p = patternApplication();
        if (take("::")) p = new Pattern("::", List.of(p, pattern()), p.token());
        if (take("as")) {
            Token name = lower();
            p =
                    new Pattern(
                            "$as",
                            List.of(p, new Pattern(name.text(), List.of(), name)),
                            p.token());
        }
        return p;
    }

    private Pattern patternApplication() {
        Pattern p = patternAtom();
        if (constructor(p)) {
            var args = new ArrayList<Pattern>();
            while (patternAtomStart() && !is("as"))
                args.add(patternAtom());
            if (!args.isEmpty()) p = new Pattern(p.name(), args, p.token());
        }
        return p;
    }

    private Pattern patternAtom() {
        Token t = next();
        // A string pattern is named by its quoted text, so it never reads as `_`, a variable or a
        // constructor.
        if (t.kind() == Token.Kind.STRING) return new Pattern("\"" + t.text() + "\"", List.of(), t);
        if (t.text().equals("[")) {
            var parts = new ArrayList<Pattern>();
            if (!is("]"))
                do {
                    parts.add(pattern());
                } while (take(","));
            expect("]");
            Pattern result = new Pattern("[]", List.of(), t);
            for (int i = parts.size() - 1; i >= 0; i--)
                result = new Pattern("::", List.of(parts.get(i), result), t);
            return result;
        }
        if (t.text().equals("(")) {
            if (take(")")) return new Pattern("$unit", List.of(), t);
            Pattern first = pattern();
            if (take(",")) {
                var parts = new ArrayList<Pattern>();
                parts.add(first);
                do {
                    parts.add(pattern());
                } while (take(","));
                expect(")");
                if (parts.size() > 3) throw error("Tuple patterns have two or three elements");
                return new Pattern("$tuple", parts, t);
            }
            expect(")");
            return first;
        }
        if (t.text().equals("{")) {
            var fields = new ArrayList<Pattern>();
            if (!is("}"))
                do {
                    Token field = lower();
                    fields.add(new Pattern(field.text(), List.of(), field));
                } while (take(","));
            expect("}");
            return new Pattern("$record", fields, t);
        }
        if (t.text().equals("-") && peek().kind() == Token.Kind.INT) {
            Token value = next();
            return new Pattern("-" + value.text(), List.of(), t);
        }
        if (t.kind() == Token.Kind.NAME
                || t.kind() == Token.Kind.INT
                || t.kind() == Token.Kind.STRING) {
            String name = t.text();
            if (t.kind() == Token.Kind.NAME && Character.isUpperCase(name.charAt(0)))
                while (take(".")) name += "." + upper().text();
            return new Pattern(name, List.of(), t);
        }
        throw new Diagnostic("ELCI1002", t, "Unsupported pattern");
    }

    private Pattern parameterPattern() {
        return patternAtom();
    }

    private boolean patternAtomStart() {
        return is("(")
                || is("[")
                || is("{")
                || is("-")
                || peek().kind() == Token.Kind.NAME
                || peek().kind() == Token.Kind.INT
                || peek().kind() == Token.Kind.STRING;
    }

    private static boolean constructor(Pattern p) {
        return !p.name().isEmpty()
                && Character.isUpperCase(p.name().charAt(0))
                && !Set.of("True", "False").contains(p.name());
    }

    private static boolean simpleBinder(Pattern p) {
        return p.arguments().isEmpty()
                && (p.name().equals("_") || Character.isLowerCase(p.name().charAt(0)));
    }

    private static boolean irrefutable(Pattern p) {
        return simpleBinder(p)
                || p.name().equals("$unit")
                || p.name().equals("$tuple") && p.arguments().stream().allMatch(Parser::irrefutable)
                || p.name().equals("$record")
                || p.name().equals("$as") && irrefutable(p.arguments().getFirst());
    }

    private static void patternNames(Pattern p, Set<String> names) {
        if (simpleBinder(p) && !p.name().equals("_")) {
            if (!names.add(p.name()))
                throw new Diagnostic(
                        "ELCI1002", p.token(), "Duplicate pattern variable " + p.name());
        }
        for (var a : p.arguments()) patternNames(a, names);
    }

    private static List<String> patternNameList(Pattern p) {
        var names = new LinkedHashSet<String>();
        patternNames(p, names);
        return List.copyOf(names);
    }

    private boolean isNameStart() {
        return peek().kind() == Token.Kind.NAME && Character.isLowerCase(peek().text().charAt(0));
    }

    private boolean boundary(int floor) {
        return peek().kind() == Token.Kind.EOF
                || (peek().column() <= floor && peek().line() > previous().line());
    }

    /**
     * Whether the next {@code -} negates the operand that follows it: as in Elm 0.19, the minus
     * follows whitespace or an opening bracket and touches its operand.
     */
    private boolean negation() {
        var t = peek();
        if (t.kind() != Token.Kind.SYMBOL || !t.text().equals("-") || t.offset() + 1 >= source.length()) return false;
        char after = source.charAt(t.offset() + 1);
        if (Character.isWhitespace(after) || after == '-') return false;
        return t.offset() == 0 || Character.isWhitespace(source.charAt(t.offset() - 1))
                || "([{,".indexOf(source.charAt(t.offset() - 1)) >= 0;
    }

    private boolean atomStart() {
        var t = peek();
        return (t.kind() == Token.Kind.NAME
                        && !RESERVED.contains(t.text())
                        && !Set.of("if", "let", "case").contains(t.text()))
                || t.kind() == Token.Kind.INT
                || t.kind() == Token.Kind.STRING
                || is("(")
                || is("[")
                || is("{");
    }

    private String qualified(boolean capital) {
        String n = (capital ? upper() : name()).text();
        while (take(".")) n += "." + (capital ? upper() : name()).text();
        return n;
    }

    private Token lower() {
        Token t = name();
        if (!Character.isLowerCase(t.text().charAt(0)) && !t.text().equals("_"))
            throw new Diagnostic("ELCI1002", t, "Expected lowercase name");
        return t;
    }

    private Token upper() {
        Token t = name();
        if (!Character.isUpperCase(t.text().charAt(0)))
            throw new Diagnostic("ELCI1002", t, "Expected capitalized module name");
        return t;
    }

    private Token name() {
        Token t = next();
        if (t.kind() != Token.Kind.NAME || RESERVED.contains(t.text()))
            throw new Diagnostic("ELCI1002", t, "Expected name");
        return t;
    }

    private Token peek() {
        return tokens.get(pos);
    }

    private Token previous() {
        return tokens.get(Math.max(0, pos - 1));
    }

    private Token next() {
        return tokens.get(pos++);
    }

    /** Whether the next token is the keyword, name or symbol {@code s}; a string literal never is. */
    private boolean is(String s) {
        return peek().kind() != Token.Kind.STRING && peek().text().equals(s);
    }

    private boolean take(String s) {
        if (is(s)) {
            pos++;
            return true;
        }
        return false;
    }

    private void expect(String s) {
        if (!take(s)) throw error("Expected '" + s + "', found '" + peek().text() + "'");
    }

    private Diagnostic error(String m) {
        return new Diagnostic("ELCI1002", peek(), m);
    }
}
