package org.elci.types;

import static org.elci.syntax.Ast.*;
import static org.elci.types.Types.*;

import org.elci.syntax.*;

import java.util.*;

/** Type checking is separate from concrete PIR lowering. */
public final class Inference {
    public final IdentityHashMap<Expr, Ty> expressions = new IdentityHashMap<>();
    public final IdentityHashMap<Decl, Ty> declarations = new IdentityHashMap<>();
    public final IdentityHashMap<Decl, Scheme> declarationSchemes = new IdentityHashMap<>();
    public final Map<String, Scheme> globals = new LinkedHashMap<>();
    public final Map<String, Union> unions = new LinkedHashMap<>();
    public final Map<String, Union> constructors = new LinkedHashMap<>();
    public final Map<String, Alias> aliasConstructors = new LinkedHashMap<>();
    private String moduleName;
    public final Map<String, org.julclang.compiler.backend.LibraryType> importedTypes =
            new LinkedHashMap<>();
    public final Map<String, org.julclang.compiler.pir.PirType> importedRepresentations =
            new LinkedHashMap<>();
    private Map<String, String> importedAliases = Map.of();
    public final Map<String, String> unsupportedImports = new LinkedHashMap<>();

    public void library(org.julclang.compiler.backend.LibraryProvider provider) {
        importedTypes.putAll(provider.types());
        importedRepresentations.putAll(provider.namedDefinitions());
        provider.types().forEach((n,t)->importedRepresentations.put(n,t.representation()));
    }

    public void ledger(
            org.julclang.compiler.LedgerTypeProvider provider, Map<String, String> aliases) {
        importedTypes.putAll(provider.types());
        importedRepresentations.putAll(provider.namedDefinitions());
        provider.types().forEach((n, t) -> importedRepresentations.put(n, t.representation()));
        importedAliases = Map.copyOf(aliases);
        importedTypes.put(
                "ScriptContext", provider.types().get("org.julclang.ledger.ScriptContext"));
    }

    public Ty libraryType(org.julclang.compiler.backend.LibraryType.Reference ref) {
        return new Con(ref.name(), ref.arguments().stream().map(this::libraryType).toList());
    }

    public boolean byteEquality(String name) {
        return name.equals("Bytes")
                || importedRepresentations.get(name)
                        instanceof org.julclang.compiler.pir.PirType.ByteStringType;
    }

    public void unions(String module, List<Union> definitions) {
        moduleName = module;
        var all = new ArrayList<Union>();
        all.add(
                new Union(
                        "Maybe",
                        List.of("a"),
                        List.of(
                                new Variant("Nothing", List.of()),
                                new Variant("Just", List.of(new Type("a", List.of())))),
                        new Token("Maybe", Token.Kind.NAME, 0, 1, 1)));
        all.addAll(definitions);
        for (var u : all) {
            if (unions.putIfAbsent(u.name(), u) != null)
                throw new Diagnostic("ELCI2011", u.token(), "Duplicate type " + u.name());
            for (var v : u.variants())
                if (constructors.putIfAbsent(v.name(), u) != null)
                    throw new Diagnostic(
                            "ELCI2011", u.token(), "Duplicate constructor " + v.name());
        }
    }

    public String nominal(String name) {
        return name.contains(".")
                ? name
                : (name.equals("Maybe") ? "Elci" : moduleName) + "." + name;
    }

    public Ty annotatedPublic(Type type, Map<String, Ty> variables) {
        return annotated(type, variables);
    }

    private Map<String, Alias> aliases = Map.of();

    private record FieldConstraint(Ty record, String name, Ty value, Token token) {}

    private final List<FieldConstraint> recordConstraints = new ArrayList<>();

    public void aliases(Map<String, Alias> aliases) {
        this.aliases = Map.copyOf(aliases);
    }

    private Type expand(Type type, Set<String> seen) {
        if (aliases.containsKey(type.name())) {
            var alias = aliases.get(type.name());
            if (!seen.add(type.name()))
                throw new Diagnostic(
                        "ELCI2012",
                        alias.token(),
                        "Cyclic type alias: " + String.join(" -> ", seen) + " -> " + type.name());
            if (seen.size() > 128)
                throw new Diagnostic(
                        "ELCI2012", alias.token(), "Type alias expansion limit exceeded");
            if (type.arguments().size() != alias.parameters().size())
                throw new Diagnostic(
                        "ELCI2012",
                        alias.token(),
                        "Type "
                                + type.name()
                                + " expects "
                                + alias.parameters().size()
                                + " arguments, got "
                                + type.arguments().size());
            var replacements = new HashMap<String, Type>();
            for (int i = 0; i < alias.parameters().size(); i++)
                replacements.put(alias.parameters().get(i), type.arguments().get(i));
            var expanded = expand(substitute(alias.target(), replacements), seen);
            seen.remove(type.name());
            return expanded;
        }
        return new Type(
                importedAliases.containsKey(type.name())
                        ? importedAliases.get(type.name())
                        : unions.containsKey(type.name()) ? nominal(type.name()) : type.name(),
                type.arguments().stream().map(t -> expand(t, new HashSet<>(seen))).toList());
    }

    private static Type substitute(Type type, Map<String, Type> replacements) {
        if (replacements.containsKey(type.name()) && type.arguments().isEmpty())
            return replacements.get(type.name());
        return new Type(
                type.name(),
                type.arguments().stream().map(t -> substitute(t, replacements)).toList());
    }

    private Ty annotated(Type type, Map<String, Ty> variables) {
        return annotation(expand(type, new HashSet<>()), variables);
    }

    private final List<Map.Entry<Ty, Token>> append = new ArrayList<>();
    private final List<Map.Entry<Ty, Token>> equality = new ArrayList<>();

    private Ty instantiate(Scheme scheme, Token token) {
        var instance = scheme.instantiateAll();
        instance.equality().forEach(type -> equality.add(Map.entry(type, token)));
        instance.append().forEach(type -> append.add(Map.entry(type, token)));
        return instance.body();
    }

    public void check(List<Decl> ds, Map<String, Ty> imports) {
        for (var alias : aliases.values()) {
            var free = new HashSet<String>();
            sourceVariables(alias.target(), free);
            if (!alias.parameters().containsAll(free))
                throw new Diagnostic(
                        "ELCI2012",
                        alias.token(),
                        "Type alias uses undeclared type variables: " + free);
            expand(
                    new Type(
                            alias.name(),
                            alias.parameters().stream().map(n -> new Type(n, List.of())).toList()),
                    new HashSet<>());
            if (alias.target().name().startsWith("Record:"))
                aliasConstructors.put(alias.name(), alias);
        }
        for (var d : ds)
            if (d.annotation() != null)
                validateType(expand(d.annotation(), new HashSet<>()), d.token());
        for (var u : unions.values())
            for (var v : u.variants())
                for (var field : v.fields()) {
                    validateType(expand(field, new HashSet<>()), u.token());
                    var vars = new HashSet<String>();
                    sourceVariables(field, vars);
                    if (!u.parameters().containsAll(vars))
                        throw new Diagnostic(
                                "ELCI2012",
                                u.token(),
                                "Constructor uses undeclared type variables: " + vars);
                }
        imports.forEach((name, type) -> globals.put(name, new Scheme(variables(type), type)));
        for (var alias : aliasConstructors.values()) {
            var vars = new HashMap<String, Ty>();
            alias.parameters().forEach(n -> vars.put(n, new Var()));
            Ty result =
                    annotated(
                            new Type(
                                    alias.name(),
                                    alias.parameters().stream()
                                            .map(n -> new Type(n, List.of()))
                                            .toList()),
                            vars);
            var record = (Con) result;
            var names = Arrays.asList(record.name().substring(7).split(","));
            Ty constructor = result;
            for (int i = alias.constructorFields().size() - 1; i >= 0; i--)
                constructor =
                        fn(
                                record.args().get(names.indexOf(alias.constructorFields().get(i))),
                                constructor);
            if (globals.putIfAbsent(alias.name(), new Scheme(variables(constructor), constructor))
                    != null)
                throw new Diagnostic(
                        "ELCI2011",
                        alias.token(),
                        "Type alias constructor conflicts with an existing name: " + alias.name());
        }
        for (var u : unions.values())
            for (var v : u.variants()) {
                var vars = new HashMap<String, Ty>();
                u.parameters().forEach(n -> vars.put(n, new Var()));
                Ty t = new Con(nominal(u.name()), u.parameters().stream().map(vars::get).toList());
                for (int i = v.fields().size() - 1; i >= 0; i--)
                    t = fn(annotated(v.fields().get(i), vars), t);
                if (globals.putIfAbsent(v.name(), new Scheme(variables(t), t)) != null)
                    throw new Diagnostic(
                            "ELCI2011",
                            u.token(),
                            "Constructor name conflicts with import " + v.name());
            }
        inferDeclarations(ds, globals, true);
        solveFields();
        if (!recordConstraints.isEmpty())
            throw new Diagnostic(
                    "ELCI2010",
                    recordConstraints.getFirst().token(),
                    "Field access requires a known closed record; add a concrete record"
                            + " annotation");
        for (var constraint : append) {
            Ty t = prune(constraint.getKey());
            if (!(t instanceof Con c) || !Set.of("String", "List").contains(c.name()))
                throw new Diagnostic(
                        "ELCI2003",
                        constraint.getValue(),
                        "Append requires a concrete String or List type");
        }
        for (var constraint : equality) {
            Ty t = prune(constraint.getKey());
            if (t instanceof Var)
                throw new Diagnostic(
                        "ELCI2003",
                        constraint.getValue(),
                        "Equality requires a concrete supported type");
            if (!equalityCapable(t, new HashSet<>()))
                throw new Diagnostic(
                        "ELCI2003",
                        constraint.getValue(),
                        "Equality is unsupported for "
                                + t
                                + " because it contains a function, opaque Data, or an imported"
                                + " type without verified equality");
        }
    }

    private boolean equalityCapable(Ty type, Set<String> visiting) {
        type = prune(type);
        if (!(type instanceof Con c)) return false;
        if (Set.of("Int", "Bool", "String", "Unit").contains(c.name()) || byteEquality(c.name()))
            return true;
        if (c.name().equals("->") || c.name().equals("Data") || c.name().equals("ScriptContext"))
            return false;
        if (c.name().equals("List") || c.name().equals("Tuple") || c.name().startsWith("Record:"))
            return c.args().stream().allMatch(a -> equalityCapable(a, visiting));
        if (importedRepresentations.containsKey(c.name())) return false;
        if (!visiting.add(c.toString())) return true;
        try {
            for (var union : unions.values())
                if (nominal(union.name()).equals(c.name())) {
                    var variables = new HashMap<String, Ty>();
                    for (int i = 0; i < union.parameters().size(); i++)
                        variables.put(union.parameters().get(i), c.args().get(i));
                    for (var variant : union.variants())
                        for (var field : variant.fields())
                            if (!equalityCapable(annotated(field, variables), visiting))
                                return false;
                    return true;
                }
            return false;
        } finally {
            visiting.remove(c.toString());
        }
    }

    private void validateType(Type type, Token token) {
        String name = type.name();
        int expected;
        if (name.startsWith("Record:"))
            expected = name.substring(7).isEmpty() ? 0 : name.substring(7).split(",").length;
        else if (name.equals("Tuple")) {
            expected = type.arguments().size();
            if (expected < 2 || expected > 3)
                throw new Diagnostic("ELCI2012", token, "Tuples require two or three elements");
        } else if (name.equals("->")) expected = 2;
        else if (name.equals("List") || name.equals("JulcOptional")) expected = 1;
        else if (name.equals("Map")) expected = 2;
        else if (!name.contains(".") && Character.isLowerCase(name.charAt(0))) expected = 0;
        else if (Set.of("Int", "Bool", "String", "Unit", "Bytes", "Data", "ScriptContext")
                        .contains(name)
                || importedRepresentations.containsKey(name)) expected = 0;
        else {
            var union =
                    unions.values().stream()
                            .filter(u -> nominal(u.name()).equals(name))
                            .findFirst();
            if (union.isEmpty())
                throw new Diagnostic("ELCI2012", token, "Unknown type '" + name + "'");
            expected = union.get().parameters().size();
        }
        if (type.arguments().size() != expected)
            throw new Diagnostic(
                    "ELCI2012",
                    token,
                    "Type "
                            + name
                            + " expects "
                            + expected
                            + " arguments, got "
                            + type.arguments().size());
        type.arguments().forEach(a -> validateType(a, token));
    }

    private static void sourceVariables(Type type, Set<String> vars) {
        if (!type.name().contains(".") && Character.isLowerCase(type.name().charAt(0)))
            vars.add(type.name());
        type.arguments().forEach(a -> sourceVariables(a, vars));
    }

    private void inferDeclarations(List<Decl> ds, Map<String, Scheme> env, boolean top) {
        var pending = new LinkedHashMap<String, Decl>();
        ds.forEach(d -> pending.put(d.name(), d));
        // Dependency SCCs preserve monomorphic recursion and generalize only completed groups.
        for (var group : groups(ds)) {
            var monotypes = new LinkedHashMap<String, Ty>();
            for (var d : group) {
                Ty t = new Var();
                monotypes.put(d.name(), t);
                env.put(d.name(), new Scheme(Set.of(), t));
            }
            var declarationEquality = new IdentityHashMap<Decl, List<Map.Entry<Ty, Token>>>();
            var declarationAppend = new IdentityHashMap<Decl, List<Map.Entry<Ty, Token>>>();
            for (var d : group) {
                int equalityStart = equality.size();
                int appendStart = append.size();
                var local = new LinkedHashMap<>(env);
                var params = new ArrayList<Ty>();
                if (d.annotation() != null)
                    unify(
                            monotypes.get(d.name()),
                            annotated(d.annotation(), new HashMap<>()),
                            d.token());
                Ty parameterShape = monotypes.get(d.name());
                for (String p : d.parameters()) {
                    Ty t = new Var(), next = new Var();
                    unify(parameterShape, fn(t, next), d.token());
                    parameterShape = next;
                    params.add(t);
                    if (!p.equals("_")) local.put(p, new Scheme(Set.of(), t));
                }
                Ty body = infer(d.body(), local);
                for (int i = params.size() - 1; i >= 0; i--) body = fn(params.get(i), body);
                unify(monotypes.get(d.name()), body, d.token());
                declarations.put(d, body);
                declarationEquality.put(
                        d, new ArrayList<>(equality.subList(equalityStart, equality.size())));
                declarationAppend.put(
                        d, new ArrayList<>(append.subList(appendStart, append.size())));
            }
            solveFields();
            if (top && !recordConstraints.isEmpty())
                throw new Diagnostic(
                        "ELCI2010",
                        recordConstraints.getFirst().token(),
                        "Field access needs a concrete closed record type; an unconstrained generic"
                                + " signature is insufficient");
            for (var d : group) {
                var t = monotypes.get(d.name());
                if (top && d.annotation() == null)
                    throw new Diagnostic(
                            "ELCI2004",
                            d.token(),
                            "Top-level declarations require a type signature");
                if (d.annotation() != null) {
                    // Skolems prevent accepting 'a -> a' for an Int-only implementation.
                    var rigid = new HashMap<String, Ty>();
                    collectRigid(d.annotation(), rigid);
                    unify(copy(t, new HashMap<>()), annotated(d.annotation(), rigid), d.token());
                    unify(t, annotated(d.annotation(), new HashMap<>()), d.token());
                }
            }
            solveFields();
            var environmentVars = new HashSet<Var>();
            for (var constraint : recordConstraints) {
                environmentVars.addAll(variables(constraint.record()));
                environmentVars.addAll(variables(constraint.value()));
            }
            for (var e : env.entrySet())
                if (!monotypes.containsKey(e.getKey())) {
                    var vs = variables(e.getValue().body());
                    vs.removeAll(e.getValue().quantified());
                    environmentVars.addAll(vs);
                }
            var generalizedEquality =
                    Collections.newSetFromMap(new IdentityHashMap<Map.Entry<Ty, Token>, Boolean>());
            var generalizedAppend =
                    Collections.newSetFromMap(new IdentityHashMap<Map.Entry<Ty, Token>, Boolean>());
            for (var d : group) {
                var t = monotypes.get(d.name());
                var vs = variables(t);
                vs.removeAll(environmentVars);
                boolean eligible =
                        top
                                || group.size() == 1
                                        && nonRecursive(d)
                                        && independent(d, ds)
                                        && nonExpansive(d);
                var quantified = eligible ? vs : Set.<Var>of();
                var equalityRequirements = new ArrayList<Ty>();
                for (var requirement : declarationEquality.getOrDefault(d, List.of()))
                    if (!Collections.disjoint(variables(requirement.getKey()), quantified)) {
                        equalityRequirements.add(requirement.getKey());
                        generalizedEquality.add(requirement);
                    }
                var appendRequirements = new ArrayList<Ty>();
                for (var requirement : declarationAppend.getOrDefault(d, List.of()))
                    if (!Collections.disjoint(variables(requirement.getKey()), quantified)) {
                        appendRequirements.add(requirement.getKey());
                        generalizedAppend.add(requirement);
                    }
                var scheme = new Scheme(quantified, t, equalityRequirements, appendRequirements);
                env.put(d.name(), scheme);
                declarationSchemes.put(d, scheme);
            }
            equality.removeIf(generalizedEquality::contains);
            append.removeIf(generalizedAppend::contains);
        }
    }

    private boolean nonExpansive(Decl d) {
        return !d.parameters().isEmpty() || nonExpansive(d.body());
    }

    private boolean nonExpansive(Expr expression) {
        return switch (expression) {
            case Lambda _, Name _, Literal _ -> true;
            case Items i -> i.elements().stream().allMatch(this::nonExpansive);
            case RecordValue r ->
                    r.base() == null && r.fields().values().stream().allMatch(this::nonExpansive);
            case Apply a ->
                    constructorApplication(a)
                            && nonExpansive(a.function())
                            && nonExpansive(a.argument());
            default -> false;
        };
    }

    private boolean constructorApplication(Expr expression) {
        while (expression instanceof Apply a) expression = a.function();
        return expression instanceof Name n
                && (constructors.containsKey(n.name()) || aliasConstructors.containsKey(n.name()));
    }

    private static boolean nonRecursive(Decl d) {
        var refs = new HashSet<String>();
        references(d.body(), new HashSet<>(d.parameters()), refs);
        return !refs.contains(d.name());
    }

    private static boolean independent(Decl d, List<Decl> declarations) {
        var refs = new HashSet<String>();
        references(d.body(), new HashSet<>(d.parameters()), refs);
        var siblings = new HashSet<String>();
        declarations.forEach(x -> siblings.add(x.name()));
        siblings.remove(d.name());
        refs.retainAll(siblings);
        return refs.isEmpty();
    }

    private void collectRigid(Type t, Map<String, Ty> rigid) {
        if (Character.isLowerCase(t.name().charAt(0)))
            rigid.computeIfAbsent(t.name(), n -> con("$" + n));
        t.arguments().forEach(a -> collectRigid(a, rigid));
    }

    private Ty infer(Expr e, Map<String, Scheme> env) {
        Ty result =
                switch (e) {
                    case Field f -> {
                        Ty record = infer(f.record(), env), value = new Var();
                        recordConstraints.add(
                                new FieldConstraint(record, f.name(), value, f.token()));
                        yield value;
                    }
                    case RecordValue r -> {
                        var fields = new TreeMap<String, Ty>();
                        r.fields().forEach((n, e1) -> fields.put(n, infer(e1, env)));
                        if (r.base() == null)
                            yield new Con(
                                    "Record:" + String.join(",", fields.keySet()),
                                    List.copyOf(fields.values()));
                        Ty base = infer(r.base(), env);
                        fields.forEach(
                                (n, t) ->
                                        recordConstraints.add(
                                                new FieldConstraint(base, n, t, r.token())));
                        yield base;
                    }
                    case Literal l ->
                            con(
                                    l.value() == null
                                            ? "Unit"
                                            : l.value() instanceof Boolean
                                                    ? "Bool"
                                                    : l.value() instanceof String
                                                            ? "String"
                                                            : "Int");
                    case Name n -> {
                        if(unsupportedImports.containsKey(n.name()))
                            throw new Diagnostic("ELCI2105",n.token(),unsupportedImports.get(n.name()));
                        var scheme = env.get(n.name());
                        if (scheme == null)
                            throw new Diagnostic(
                                    "ELCI2005", n.token(), "Unknown name '" + n.name() + "'");
                        yield instantiate(scheme, n.token());
                    }
                    case Apply a -> {
                        var f = infer(a.function(), env);
                        var arg = infer(a.argument(), env);
                        Ty out = new Var();
                        unify(f, fn(arg, out), a.token());
                        yield out;
                    }
                    case Lambda l -> {
                        var local = new LinkedHashMap<>(env);
                        var ps = new ArrayList<Ty>();
                        for (String p : l.parameters()) {
                            Ty t = new Var();
                            ps.add(t);
                            if (!p.equals("_")) local.put(p, new Scheme(Set.of(), t));
                        }
                        Ty body = infer(l.body(), local);
                        for (int i = ps.size() - 1; i >= 0; i--) body = fn(ps.get(i), body);
                        yield body;
                    }
                    case If i -> {
                        unify(infer(i.condition(), env), con("Bool"), i.token());
                        var y = infer(i.yes(), env);
                        unify(y, infer(i.no(), env), i.token());
                        yield y;
                    }
                    case Binary b -> {
                        Ty a = infer(b.left(), env), z = infer(b.right(), env);
                        String op = b.op();
                        if (op.equals("|>") || op.equals("<|")) {
                            Ty out = new Var();
                            unify(
                                    op.equals("|>") ? z : a,
                                    fn(op.equals("|>") ? a : z, out),
                                    b.token());
                            yield out;
                        }
                        if (op.equals("::")) {
                            unify(z, con("List", a), b.token());
                            yield z;
                        }
                        if (op.equals("++")) {
                            unify(a, z, b.token());
                            append.add(Map.entry(a, b.token()));
                            yield a;
                        }
                        if (op.equals(">>") || op.equals("<<")) {
                            Ty input = new Var(), middle = new Var(), output = new Var();
                            unify(op.equals(">>") ? a : z, fn(input, middle), b.token());
                            unify(op.equals(">>") ? z : a, fn(middle, output), b.token());
                            yield fn(input, output);
                        }
                        if (op.equals("&&") || op.equals("||")) {
                            unify(a, con("Bool"), b.token());
                            unify(z, con("Bool"), b.token());
                            yield con("Bool");
                        }
                        if (op.equals("==") || op.equals("/=")) {
                            unify(a, z, b.token());
                            equality.add(Map.entry(a, b.token()));
                            yield con("Bool");
                        }
                        unify(a, con("Int"), b.token());
                        unify(z, con("Int"), b.token());
                        yield Set.of("<", ">", "<=", ">=").contains(op) ? con("Bool") : con("Int");
                    }
                    case Let l -> {
                        var local = new LinkedHashMap<>(env);
                        inferDeclarations(l.declarations(), local, false);
                        yield infer(l.body(), local);
                    }
                    case Items items -> {
                        if (items.tuple())
                            yield new Con(
                                    "Tuple",
                                    items.elements().stream()
                                            .map(item -> infer(item, env))
                                            .toList());
                        Ty element = new Var();
                        for (var item : items.elements())
                            unify(element, infer(item, env), item.token());
                        yield con("List", element);
                    }
                    case Case c -> {
                        Ty scrutinee = infer(c.scrutinee(), env), out = new Var();
                        var previous = new ArrayList<Pattern>();
                        for (var branch : c.branches()) {
                            var local = new LinkedHashMap<>(env);
                            var p = branch.pattern();
                            uniquePatternNames(p, new HashSet<>());
                            // Pattern typing can reveal a product or union shape that surrounding
                            // context has not established yet (notably in an unannotated lambda).
                            // Usefulness must inspect that resolved shape rather than a fresh type
                            // variable.
                            bindPattern(p, scrutinee, local);
                            if (!useful(
                                    previous.stream().map(List::of).toList(),
                                    List.of(p),
                                    List.of(scrutinee)))
                                throw new Diagnostic("ELCI2006", p.token(), "Unreachable pattern");
                            if (previous.contains(p))
                                throw new Diagnostic("ELCI2006", p.token(), "Duplicate pattern");
                            previous.add(p);
                            unify(out, infer(branch.body(), local), branch.body().token());
                        }
                        if (!matrixTotal(
                                previous.stream().map(List::of).toList(), List.of(scrutinee)))
                            throw new Diagnostic(
                                    "ELCI2007",
                                    c.token(),
                                    "Non-exhaustive case; add the missing constructor or a wildcard"
                                            + " branch");
                        yield out;
                    }
                };
        expressions.put(e, result);
        return result;
    }

    private void solveFields() {
        boolean progress;
        do {
            progress = false;
            var iterator = recordConstraints.iterator();
            while (iterator.hasNext()) {
                var constraint = iterator.next();
                if (prune(constraint.record()) instanceof Con) {
                    unify(
                            constraint.value(),
                            field(constraint.record(), constraint.name(), constraint.token()),
                            constraint.token());
                    iterator.remove();
                    progress = true;
                }
            }
        } while (progress);
    }

    private Ty field(Ty record, String name, Token token) {
        var type = prune(record);
        if (type instanceof Con c && importedTypes.containsKey(c.name())) {
            var recordType = importedTypes.get(c.name());
            return recordType.fields().stream()
                    .filter(f -> f.name().equals(name))
                    .findFirst()
                    .map(f -> libraryType(f.type()))
                    .orElseThrow(
                            () ->
                                    new Diagnostic(
                                            "ELCI2010",
                                            token,
                                            "Unknown field '" + name + "' of " + c.name()));
        }
        if (!(type instanceof Con c) || !c.name().startsWith("Record:"))
            throw new Diagnostic(
                    "ELCI2010", token, "Field access requires a known closed record type");
        var names = Arrays.asList(c.name().substring(7).split(","));
        int index = names.indexOf(name);
        if (index < 0)
            throw new Diagnostic(
                    "ELCI2010", token, "Missing field '" + name + "'; available fields: " + names);
        return c.args().get(index);
    }

    private void uniquePatternNames(Pattern p, Set<String> seen) {
        if (variable(p) && !p.name().equals("_") && !seen.add(p.name()))
            throw new Diagnostic("ELCI2006", p.token(), "Duplicate pattern variable " + p.name());
        p.arguments().forEach(a -> uniquePatternNames(a, seen));
    }

    private void bindPattern(Pattern p, Ty t, Map<String, Scheme> env) {
        if (p.name().equals("_")) return;
        if (variable(p)) {
            env.put(p.name(), new Scheme(Set.of(), t));
            return;
        }
        if (p.name().equals("$as")) {
            bindPattern(p.arguments().getFirst(), t, env);
            bindPattern(p.arguments().get(1), t, env);
            return;
        }
        if (p.name().equals("$unit")) {
            unify(t, con("Unit"), p.token());
            return;
        }
        if (p.name().equals("$record")) {
            for (var part : p.arguments()) {
                Ty value = new Var();
                recordConstraints.add(new FieldConstraint(t, part.name(), value, part.token()));
                bindPattern(part, value, env);
            }
            return;
        }
        if (p.name().equals("Tuple")) {
            var fields = new ArrayList<Ty>();
            for (var part : p.arguments()) {
                Ty field = new Var();
                fields.add(field);
                bindPattern(part, field, env);
            }
            unify(t, new Con("Tuple", fields), p.token());
            return;
        }
        if (p.name().equals("[]")) {
            unify(t, con("List", new Var()), p.token());
            return;
        }
        if (p.name().equals("::")) {
            Ty el = new Var();
            unify(t, con("List", el), p.token());
            bindPattern(p.arguments().get(0), el, env);
            bindPattern(p.arguments().get(1), t, env);
            return;
        }
        if (constructors.containsKey(p.name())) {
            Ty ct = instantiate(globals.get(p.name()), p.token());
            for (var argument : p.arguments()) {
                Ty field = new Var(), rest = new Var();
                unify(ct, fn(field, rest), p.token());
                bindPattern(argument, field, env);
                ct = rest;
            }
            unify(t, ct, p.token());
            return;
        }
        if (Set.of("True", "False").contains(p.name())) unify(t, con("Bool"), p.token());
        else if (p.token().kind() == Token.Kind.INT
                || p.name().startsWith("-")
                        && p.name().length() > 1
                        && Character.isDigit(p.name().charAt(1))) unify(t, con("Int"), p.token());
        else if (p.token().kind() == Token.Kind.STRING) unify(t, con("String"), p.token());
        else
            throw new Diagnostic(
                    "ELCI2001", p.token(), "Unsupported constructor pattern " + p.name());
    }

    public static List<List<Decl>> groups(List<Decl> ds) {
        var byName = new LinkedHashMap<String, Decl>();
        ds.forEach(d -> byName.put(d.name(), d));
        var edges = new LinkedHashMap<String, Set<String>>();
        for (var d : ds) {
            var refs = new HashSet<String>();
            references(d.body(), new HashSet<>(d.parameters()), refs);
            refs.retainAll(byName.keySet());
            edges.put(d.name(), refs);
        }
        var result = new ArrayList<List<Decl>>();
        var index = new HashMap<String, Integer>();
        var low = new HashMap<String, Integer>();
        var stack = new ArrayDeque<String>();
        var active = new HashSet<String>();
        for (String n : byName.keySet())
            if (!index.containsKey(n)) scc(n, edges, index, low, stack, active, result, byName);
        return result;
    }

    private static void scc(
            String n,
            Map<String, Set<String>> es,
            Map<String, Integer> ix,
            Map<String, Integer> low,
            Deque<String> stack,
            Set<String> active,
            List<List<Decl>> out,
            Map<String, Decl> ds) {
        ix.put(n, ix.size());
        low.put(n, ix.get(n));
        stack.push(n);
        active.add(n);
        for (String v : ds.keySet())
            if (es.get(n).contains(v)) {
                if (!ix.containsKey(v)) {
                    scc(v, es, ix, low, stack, active, out, ds);
                    low.put(n, Math.min(low.get(n), low.get(v)));
                } else if (active.contains(v)) low.put(n, Math.min(low.get(n), ix.get(v)));
            }
        if (low.get(n).equals(ix.get(n))) {
            var names = new HashSet<String>();
            String v;
            do {
                v = stack.pop();
                active.remove(v);
                names.add(v);
            } while (!v.equals(n));
            var group = ds.values().stream().filter(d -> names.contains(d.name())).toList();
            if (group.stream()
                            .anyMatch(
                                    d -> d.parameters().isEmpty() && !(d.body() instanceof Lambda))
                    && (group.size() > 1 || es.get(n).contains(n)))
                throw new Diagnostic(
                        "ELCI2008",
                        group.getFirst().token(),
                        "Cyclic eager values are unsupported; recursion requires functions");
            out.add(group);
        }
    }

    private static void references(Expr e, Set<String> bound, Set<String> out) {
        switch (e) {
            case Field f -> references(f.record(), bound, out);
            case RecordValue r -> {
                if (r.base() != null) references(r.base(), bound, out);
                r.fields().values().forEach(v -> references(v, bound, out));
            }
            case Name n -> {
                if (!bound.contains(n.name())) out.add(n.name());
            }
            case Apply a -> {
                references(a.function(), bound, out);
                references(a.argument(), bound, out);
            }
            case Binary b -> {
                references(b.left(), bound, out);
                references(b.right(), bound, out);
            }
            case If i -> {
                references(i.condition(), bound, out);
                references(i.yes(), bound, out);
                references(i.no(), bound, out);
            }
            case Lambda l -> {
                var b = new HashSet<>(bound);
                b.addAll(l.parameters());
                references(l.body(), b, out);
            }
            case Let l -> {
                var b = new HashSet<>(bound);
                l.declarations().forEach(d -> b.add(d.name()));
                for (var d : l.declarations()) {
                    var p = new HashSet<>(b);
                    p.addAll(d.parameters());
                    references(d.body(), p, out);
                }
                references(l.body(), b, out);
            }
            case Items i -> i.elements().forEach(x -> references(x, bound, out));
            case Case c -> {
                references(c.scrutinee(), bound, out);
                for (var branch : c.branches()) {
                    var b = new HashSet<>(bound);
                    patternNames(branch.pattern(), b);
                    references(branch.body(), b, out);
                }
            }
            case Literal _ -> {}
        }
    }

    private static boolean variable(Pattern p) {
        return p.token().kind() == Token.Kind.NAME
                && !p.name().isEmpty()
                && Character.isLowerCase(p.name().charAt(0));
    }

    private static void patternNames(Pattern p, Set<String> names) {
        if (variable(p) && !p.name().equals("_")) names.add(p.name());
        p.arguments().forEach(a -> patternNames(a, names));
    }

    private record PatternCtor(String name, List<Ty> fields) {}

    private boolean matrixTotal(List<List<Pattern>> rows, List<Ty> types) {
        if (rows.isEmpty()) return false;
        if (types.isEmpty()) return !rows.isEmpty();
        Ty first = prune(types.getFirst());
        var restTypes = types.subList(1, types.size());
        var defaults = new ArrayList<List<Pattern>>();
        for (var row : rows)
            if (irrefutable(row.getFirst())) defaults.add(row.subList(1, row.size()));
        if (!defaults.isEmpty() && matrixTotal(defaults, restTypes)) return true;
        var constructors = patternConstructors(first);
        if (constructors == null) {
            return false;
        }
        for (var constructor : constructors) {
            var specialized = new ArrayList<List<Pattern>>();
            for (var row : rows) {
                Pattern head = unwrap(row.getFirst());
                var tail = row.subList(1, row.size());
                if (irrefutable(head)) {
                    var expanded = new ArrayList<Pattern>();
                    for (int i = 0; i < constructor.fields().size(); i++)
                        expanded.add(new Pattern("_", List.of(), head.token()));
                    expanded.addAll(tail);
                    specialized.add(expanded);
                } else if (head.name().equals(constructor.name())) {
                    var expanded = new ArrayList<>(head.arguments());
                    expanded.addAll(tail);
                    specialized.add(expanded);
                }
            }
            var nextTypes = new ArrayList<>(constructor.fields());
            nextTypes.addAll(restTypes);
            if (!matrixTotal(specialized, nextTypes)) return false;
        }
        return true;
    }

    private List<PatternCtor> patternConstructors(Ty type) {
        if (!(type instanceof Con c)) return null;
        if (c.name().equals("Bool"))
            return List.of(new PatternCtor("True", List.of()), new PatternCtor("False", List.of()));
        if (c.name().equals("List"))
            return List.of(
                    new PatternCtor("[]", List.of()),
                    new PatternCtor("::", List.of(c.args().getFirst(), type)));
        if (c.name().equals("Tuple")) return List.of(new PatternCtor("Tuple", c.args()));
        if (c.name().equals("Unit")) return List.of(new PatternCtor("$unit", List.of()));
        for (var union : unions.values())
            if (nominal(union.name()).equals(c.name())) {
                var variables = new HashMap<String, Ty>();
                for (int i = 0; i < union.parameters().size(); i++)
                    variables.put(union.parameters().get(i), c.args().get(i));
                return union.variants().stream()
                        .map(
                                v ->
                                        new PatternCtor(
                                                v.name(),
                                                v.fields().stream()
                                                        .map(t -> annotated(t, variables))
                                                        .toList()))
                        .toList();
            }
        return null;
    }

    private static Pattern unwrap(Pattern p) {
        return p.name().equals("$as") ? unwrap(p.arguments().getFirst()) : p;
    }

    private static boolean irrefutable(Pattern p) {
        p = unwrap(p);
        return p.name().equals("_") || variable(p) || p.name().equals("$record");
    }

    private boolean useful(List<List<Pattern>> matrix, List<Pattern> candidate, List<Ty> types) {
        if (candidate.isEmpty()) return matrix.isEmpty();
        Pattern head = unwrap(candidate.getFirst());
        var tail = candidate.subList(1, candidate.size());
        Ty type = prune(types.getFirst());
        var restTypes = types.subList(1, types.size());
        var universe = patternConstructors(type);
        if (irrefutable(head)) {
            if (universe == null) return useful(defaultRows(matrix), tail, restTypes);
            for (var constructor : universe) {
                var expanded = new ArrayList<Pattern>();
                for (int i = 0; i < constructor.fields().size(); i++)
                    expanded.add(new Pattern("_", List.of(), head.token()));
                expanded.addAll(tail);
                var nextTypes = new ArrayList<>(constructor.fields());
                nextTypes.addAll(restTypes);
                if (useful(
                        specialize(matrix, constructor.name(), constructor.fields().size()),
                        expanded,
                        nextTypes)) return true;
            }
            return false;
        }
        PatternCtor constructor = null;
        if (universe != null)
            constructor =
                    universe.stream()
                            .filter(c -> c.name().equals(head.name()))
                            .findFirst()
                            .orElse(null);
        int arity = constructor == null ? head.arguments().size() : constructor.fields().size();
        var fields = constructor == null ? List.<Ty>of() : constructor.fields();
        var expanded = new ArrayList<>(head.arguments());
        expanded.addAll(tail);
        var nextTypes = new ArrayList<>(fields);
        nextTypes.addAll(restTypes);
        return useful(specialize(matrix, head.name(), arity), expanded, nextTypes);
    }

    private static List<List<Pattern>> defaultRows(List<List<Pattern>> matrix) {
        var rows = new ArrayList<List<Pattern>>();
        for (var row : matrix)
            if (irrefutable(row.getFirst())) rows.add(row.subList(1, row.size()));
        return rows;
    }

    private static List<List<Pattern>> specialize(
            List<List<Pattern>> matrix, String constructor, int arity) {
        var result = new ArrayList<List<Pattern>>();
        for (var row : matrix) {
            Pattern head = unwrap(row.getFirst());
            var tail = row.subList(1, row.size());
            if (irrefutable(head)) {
                var expanded = new ArrayList<Pattern>();
                for (int i = 0; i < arity; i++)
                    expanded.add(new Pattern("_", List.of(), head.token()));
                expanded.addAll(tail);
                result.add(expanded);
            } else if (head.name().equals(constructor)) {
                var expanded = new ArrayList<>(head.arguments());
                expanded.addAll(tail);
                result.add(expanded);
            }
        }
        return result;
    }
}
