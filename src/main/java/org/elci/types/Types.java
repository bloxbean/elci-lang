package org.elci.types;

import org.elci.syntax.*;

import java.util.*;

/** Hindley-Milner unification with occurs checks; mutable variables are invocation-local. */
public final class Types {
    private Types() {}

    public sealed interface Ty permits Var, Con {}

    public static final class Var implements Ty {
        private static final String PREFIX = "t";
        public Ty link;

        @Override
        public String toString() {
            return link == null ? PREFIX : prune(link).toString();
        }
    }

    public record Con(String name, List<Ty> args) implements Ty {
        public Con {
            args = List.copyOf(args);
        }

        @Override
        public String toString() {
            return name.equals("->")
                    ? "(" + args.get(0) + " -> " + args.get(1) + ")"
                    : name + (args.isEmpty() ? "" : " " + args);
        }
    }

    public static Ty con(String name, Ty... args) {
        return new Con(name, List.of(args));
    }

    public static Ty fn(Ty a, Ty b) {
        return con("->", a, b);
    }

    public static Ty prune(Ty t) {
        if (t instanceof Var v && v.link != null) {
            v.link = prune(v.link);
            return v.link;
        }
        return t;
    }

    public static void unify(Ty a, Ty b, Token at) {
        a = prune(a);
        b = prune(b);
        if (a == b) return;
        if (a instanceof Var v) {
            if (occurs(v, b)) throw new Diagnostic("ELCI2002", at, "Infinite type");
            v.link = b;
            return;
        }
        if (b instanceof Var) {
            unify(b, a, at);
            return;
        }
        var x = (Con) a;
        var y = (Con) b;
        if (!x.name.equals(y.name) || x.args.size() != y.args.size())
            throw new Diagnostic("ELCI2002", at, "Type mismatch: expected " + show(a) + ", found " + show(b));
        for (int i = 0; i < x.args.size(); i++) unify(x.args.get(i), y.args.get(i), at);
    }

    /**
     * A type as Elci source writes it, for diagnostics: {@code List Int -> Maybe a},
     * {@code { amount : Int }}, {@code ( Int, String )}. Internal names are simplified.
     */
    public static String show(Ty type) {
        return show(type, false);
    }

    /** The left side of an arrow needs parentheses only when it is itself a function. */
    private static String showParameter(Ty type) {
        return prune(type) instanceof Con c && c.name.equals("->") ? "(" + show(type, false) + ")" : show(type, false);
    }

    private static String show(Ty type, boolean argument) {
        type = prune(type);
        if (type instanceof Var) return "t";
        var c = (Con) type;
        String name = c.name.startsWith("$") ? c.name.substring(1) : c.name;
        if (name.startsWith("org.julclang.ledger.")) name = name.substring("org.julclang.ledger.".length());
        if (name.equals("Elci.Maybe")) name = "Maybe";
        if (name.equals("->")) {
            String text = showParameter(c.args.get(0)) + " -> " + show(c.args.get(1), false);
            return argument ? "(" + text + ")" : text;
        }
        if (name.equals("Unit")) return "()";
        if (name.equals("Tuple"))
            return "( " + String.join(", ", c.args.stream().map(a -> show(a, false)).toList()) + " )";
        if (name.startsWith("Record:")) {
            String fields = name.substring("Record:".length());
            if (fields.isEmpty()) return "{}";
            var names = fields.split(",");
            var shown = new ArrayList<String>();
            for (int i = 0; i < names.length; i++) shown.add(names[i] + " : " + show(c.args.get(i), false));
            return "{ " + String.join(", ", shown) + " }";
        }
        if (c.args.isEmpty()) return name;
        String text = name + " " + String.join(" ", c.args.stream().map(a -> show(a, true)).toList());
        return argument ? "(" + text + ")" : text;
    }

    private static boolean occurs(Var v, Ty t) {
        t = prune(t);
        return t == v || t instanceof Con c && c.args.stream().anyMatch(a -> occurs(v, a));
    }

    public static Ty annotation(Ast.Type type, Map<String, Ty> variables) {
        if (!type.name().contains(".") && Character.isLowerCase(type.name().charAt(0)))
            return variables.computeIfAbsent(type.name(), k -> new Var());
        if (type.name().equals("->"))
            return fn(
                    annotation(type.arguments().get(0), variables),
                    annotation(type.arguments().get(1), variables));
        return new Con(
                type.name(), type.arguments().stream().map(a -> annotation(a, variables)).toList());
    }

    public static Ty copy(Ty t, Map<Var, Ty> vars) {
        t = prune(t);
        if (t instanceof Var v) return vars.computeIfAbsent(v, k -> new Var());
        var c = (Con) t;
        return new Con(c.name, c.args.stream().map(a -> copy(a, vars)).toList());
    }

    public static boolean concrete(Ty t) {
        t = prune(t);
        return t instanceof Con c && c.args.stream().allMatch(Types::concrete);
    }

    public static Set<Var> variables(Ty t) {
        var vs = new HashSet<Var>();
        gather(t, vs);
        return vs;
    }

    private static void gather(Ty t, Set<Var> vs) {
        t = prune(t);
        if (t instanceof Var v) vs.add(v);
        else ((Con) t).args.forEach(a -> gather(a, vs));
    }

    public record Instantiation(Ty body, List<Ty> equality, List<Ty> append) {}

    public record Scheme(Set<Var> quantified, Ty body, List<Ty> equality, List<Ty> append) {
        public Scheme(Set<Var> quantified, Ty body) {
            this(quantified, body, List.of(), List.of());
        }

        public Scheme {
            quantified = Set.copyOf(quantified);
            equality = List.copyOf(equality);
            append = List.copyOf(append);
        }

        public Instantiation instantiateAll() {
            var replacements = new HashMap<Var, Ty>();
            var schemeVariables = new HashSet<Var>(variables(body));
            equality.forEach(t -> schemeVariables.addAll(variables(t)));
            append.forEach(t -> schemeVariables.addAll(variables(t)));
            for (Var variable : schemeVariables)
                replacements.put(variable, quantified.contains(variable) ? new Var() : variable);
            return new Instantiation(
                    copy(body, replacements),
                    equality.stream().map(t -> copy(t, replacements)).toList(),
                    append.stream().map(t -> copy(t, replacements)).toList());
        }

        public Ty instantiate() {
            return instantiateAll().body();
        }
    }
}
