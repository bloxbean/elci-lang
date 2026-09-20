package org.elci.modules;

import static org.elci.syntax.Ast.*;

import org.elci.syntax.*;

import java.util.*;

/** Resolve a source graph to stable module-qualified symbols before inference. */
public final class Modules {
    private final Map<String, Ast.Module> available = new LinkedHashMap<>();
    private final Map<String, String> javaModules;
    private final Set<String> visiting = new LinkedHashSet<>();
    private final LinkedHashMap<String, Ast.Module> ordered = new LinkedHashMap<>();

    public Modules(Map<String, String> sources, Map<String, String> javaModules) {
        this.javaModules = javaModules;
        var allSources = new LinkedHashMap<>(sources);
        try (var input = Modules.class.getResourceAsStream("/elci/core/List.elm")) {
            if (input == null) throw new IllegalStateException("Bundled List module is missing");
            if (allSources.putIfAbsent(
                            "List",
                            new String(
                                    input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    != null)
                throw new IllegalArgumentException("List is owned by the Elci core library");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        allSources.forEach(
                (name, source) -> {
                    var module = new Parser(source).parse();
                    if (!module.name().equals(name))
                        throw new IllegalArgumentException(
                                "Module header "
                                        + module.name()
                                        + " does not match source path "
                                        + name);
                    if (javaModules.containsKey(name) || name.equals("Julc.Ledger"))
                        throw new IllegalArgumentException(
                                "Duplicate source/library module ownership: " + name);
                    if (!name.equals("List")
                            && module.imports().stream()
                                    .noneMatch(i -> i.module().equals("List"))) {
                        var imports = new ArrayList<>(module.imports());
                        imports.add(
                                new Import(
                                        "List",
                                        "List",
                                        List.of(),
                                        new Token("List", Token.Kind.NAME, 0, 1, 1)));
                        module =
                                new Ast.Module(
                                        module.name(),
                                        module.exposing(),
                                        imports,
                                        module.declarations(),
                                        module.aliases(),
                                        module.unions());
                    }
                    available.put(name, module);
                });
    }

    public Ast.Module resolve(String root) {
        visit(root);
        var definitions = new ArrayList<Decl>();
        var imports = new ArrayList<Import>();
        var aliases = new LinkedHashMap<String, Alias>();
        var unions = new ArrayList<Union>();
        for (var module : ordered.values()) {
            boolean isRoot = module.name().equals(root);
            String prefix = isRoot ? "" : module.name() + ".";
            var names = new LinkedHashMap<String, String>();
            var types = new LinkedHashMap<String, String>();
            for (var d : module.declarations()) names.put(d.name(), prefix + d.name());
            for (var alias : module.aliases().entrySet()) {
                types.put(alias.getKey(), prefix + alias.getKey());
                if (alias.getValue().target().name().startsWith("Record:"))
                    names.put(alias.getKey(), prefix + alias.getKey());
            }
            for (var u : module.unions()) {
                types.put(u.name(), prefix + u.name());
                for (var v : u.variants()) names.put(v.name(), prefix + v.name());
            }
            for (var imp : module.imports()) {
                if (imp.module().equals("Julc.Ledger")) {
                    imports.add(imp);
                    types.put(imp.alias() + ".", "org.julclang.ledger.");
                    for (String exposed : imp.exposing()) {
                        if (exposed.equals("..")) types.put("$ledgerAll", "true");
                        else
                            put(
                                    types,
                                    exposed,
                                    exposed.equals("ScriptContext")
                                            ? "ScriptContext"
                                            : "org.julclang.ledger." + exposed,
                                    imp.token());
                    }
                    continue;
                }
                if (javaModules.containsKey(imp.module())) {
                    // Library aliases are private to the importing module; exposing is resolved
                    // below.
                    String alias = module.name() + "$" + imp.alias();
                    imports.add(new Import(imp.module(), alias, List.of(), imp.token()));
                    names.put(imp.alias() + ".", alias + ".");
                    types.put(imp.alias() + ".", alias + ".");
                    if (imp.exposing().contains(".."))
                        throw new Diagnostic(
                                "ELCI2104",
                                imp.token(),
                                "Use explicit names when exposing Java exports in multi-module"
                                        + " projects");
                    for (String n : imp.exposing()) {
                        if (Character.isUpperCase(n.charAt(0))) {
                            if(n.endsWith("(..)"))throw new Diagnostic("ELCI2104",imp.token(),"Imported Java constructors must be called through library factory functions");
                            put(types,n,alias+"."+n,imp.token());
                        } else put(names, n, alias + "." + n, imp.token());
                    }
                    continue;
                }
                var dependency = available.get(imp.module());
                String dp = dependency.name().equals(root) ? "" : dependency.name() + ".";
                var exported = new LinkedHashMap<String, String>();
                for (var d : dependency.declarations())
                    if (exposes(dependency, d.name())) exported.put(d.name(), dp + d.name());
                for (var a : dependency.aliases().values())
                    if (exposes(dependency, a.name()) && a.target().name().startsWith("Record:"))
                        exported.put(a.name(), dp + a.name());
                for (var u : dependency.unions())
                    if (dependency.exposing().contains(u.name() + "(..)")
                            || dependency.exposing().contains(".."))
                        for (var v : u.variants()) exported.put(v.name(), dp + v.name());
                exported.forEach((n, s) -> put(names, imp.alias() + "." + n, s, imp.token()));
                for (String n : imp.exposing()) {
                    if (n.equals("..")) exported.forEach((k, v) -> put(names, k, v, imp.token()));
                    else if (n.endsWith("(..)")) {
                        String typeName = n.substring(0, n.length() - 4);
                        var union =
                                dependency.unions().stream()
                                        .filter(u -> u.name().equals(typeName))
                                        .findFirst()
                                        .orElseThrow(
                                                () ->
                                                        new Diagnostic(
                                                                "ELCI2104",
                                                                imp.token(),
                                                                "Unknown exposed type " + n));
                        if (!dependency.exposing().contains(n)
                                && !dependency.exposing().contains(".."))
                            throw new Diagnostic(
                                    "ELCI2104", imp.token(), "Constructors are private: " + n);
                        for (var variant : union.variants())
                            put(names, variant.name(), dp + variant.name(), imp.token());
                    } else if (exported.containsKey(n)) put(names, n, exported.get(n), imp.token());
                    else if (!dependency.aliases().containsKey(n)
                            && dependency.unions().stream().noneMatch(u -> u.name().equals(n)))
                        throw new Diagnostic(
                                "ELCI2104",
                                imp.token(),
                                "Module does not expose '" + n + "': " + dependency.name());
                }
                for (String n : dependency.aliases().keySet())
                    if (exposes(dependency, n)) {
                        types.put(imp.alias() + "." + n, dp + n);
                        if (imp.exposing().contains(n) || imp.exposing().contains(".."))
                            put(types, n, dp + n, imp.token());
                    }
                // Cross-module custom-type constructors and type names remain nominal.
                for (var u : dependency.unions())
                    if (exposes(dependency, u.name())
                            || dependency.exposing().contains(u.name() + "(..)")) {
                        types.put(imp.alias() + "." + u.name(), dp + u.name());
                        if (imp.exposing().contains(u.name())
                                || imp.exposing().contains(u.name() + "(..)")
                                || imp.exposing().contains(".."))
                            put(types, u.name(), dp + u.name(), imp.token());
                    }
            }
            for (var d : module.declarations())
                definitions.add(
                        new Decl(
                                prefix + d.name(),
                                d.parameters(),
                                type(d.annotation(), types),
                                expr(d.body(), names, new HashSet<>(d.parameters()), types),
                                d.token()));
            module.aliases()
                    .forEach(
                            (n, a) ->
                                    aliases.put(
                                            prefix + n,
                                            new Alias(
                                                    prefix + n,
                                                    a.parameters(),
                                                    type(a.target(), types),
                                                    a.constructorFields(),
                                                    a.token())));
            for (var u : module.unions())
                unions.add(
                        new Union(
                                prefix + u.name(),
                                u.parameters(),
                                u.variants().stream()
                                        .map(
                                                v ->
                                                        new Variant(
                                                                prefix + v.name(),
                                                                v.fields().stream()
                                                                        .map(t -> type(t, types))
                                                                        .toList()))
                                        .toList(),
                                u.token()));
        }
        var original = available.get(root);
        return new Ast.Module(root, original.exposing(), imports, definitions, aliases, unions);
    }

    private static boolean exposes(Ast.Module m, String n) {
        return m.exposing().contains(n) || m.exposing().contains("..");
    }

    private void visit(String name) {
        if (ordered.containsKey(name)) return;
        if (!visiting.add(name))
            throw new IllegalArgumentException("Import cycle: " + visiting + " -> " + name);
        var module = available.get(name);
        if (module == null) throw new IllegalArgumentException("Missing source module: " + name);
        for (var imp : module.imports())
            if (!javaModules.containsKey(imp.module()) && !imp.module().equals("Julc.Ledger"))
                visit(imp.module());
        visiting.remove(name);
        ordered.put(name, module);
    }

    private static void put(Map<String, String> map, String n, String value, Token token) {
        if (map.putIfAbsent(n, value) != null)
            throw new Diagnostic("ELCI2102", token, "Ambiguous name " + n);
    }

    private static Type type(Type t, Map<String, String> names) {
        if (t == null) return null;
        String name = names.get(t.name());
        if (name == null)
            for (var entry : names.entrySet())
                if (entry.getKey().endsWith(".") && t.name().startsWith(entry.getKey()))
                    name = entry.getValue() + t.name().substring(entry.getKey().length());
        if (name == null) {
            if (t.name().startsWith("Record:")
                    || Set.of("Int", "Bool", "String", "Unit", "Bytes", "Data", "Map", "JulcOptional", "List", "Maybe", "Tuple", "->")
                            .contains(t.name())
                    || !t.name().contains(".") && Character.isLowerCase(t.name().charAt(0)))
                name = t.name();
            else if (names.containsKey("$ledgerAll")) name = "org.julclang.ledger." + t.name();
            else
                throw new IllegalArgumentException(
                        "Type is not declared or imported in this module: " + t.name());
        }
        if (name.equals("org.julclang.ledger.ScriptContext")) name = "ScriptContext";
        return new Type(name, t.arguments().stream().map(a -> type(a, names)).toList());
    }

    private static String resolve(String n, Map<String, String> names, Set<String> bound) {
        if (bound.contains(n)) return n;
        if (names.containsKey(n)) return names.get(n);
        for (var entry : names.entrySet())
            if (entry.getKey().endsWith(".") && n.startsWith(entry.getKey()))
                return entry.getValue() + n.substring(entry.getKey().length());
        return n;
    }

    private static Expr expr(
            Expr e, Map<String, String> names, Set<String> bound, Map<String, String> types) {
        return switch (e) {
            case Literal l -> l;
            case Name n -> new Name(resolve(n.name(), names, bound), n.token());
            case Apply a ->
                    new Apply(
                            expr(a.function(), names, bound, types),
                            expr(a.argument(), names, bound, types),
                            a.token());
            case Binary b ->
                    new Binary(
                            b.op(),
                            expr(b.left(), names, bound, types),
                            expr(b.right(), names, bound, types),
                            b.token());
            case If i ->
                    new If(
                            expr(i.condition(), names, bound, types),
                            expr(i.yes(), names, bound, types),
                            expr(i.no(), names, bound, types),
                            i.token());
            case Field f -> new Field(expr(f.record(), names, bound, types), f.name(), f.token());
            case RecordValue r -> {
                var fields = new LinkedHashMap<String, Expr>();
                r.fields().forEach((n, v) -> fields.put(n, expr(v, names, bound, types)));
                yield new RecordValue(
                        r.base() == null ? null : expr(r.base(), names, bound, types),
                        fields,
                        r.token());
            }
            case Items i ->
                    new Items(
                            i.elements().stream().map(x -> expr(x, names, bound, types)).toList(),
                            i.tuple(),
                            i.token());
            case Lambda l -> {
                var b = new HashSet<>(bound);
                b.addAll(l.parameters());
                yield new Lambda(l.parameters(), expr(l.body(), names, b, types), l.token());
            }
            case Let l -> {
                var b = new HashSet<>(bound);
                l.declarations().forEach(d -> b.add(d.name()));
                var ds = new ArrayList<Decl>();
                for (var d : l.declarations()) {
                    var scope = new HashSet<>(b);
                    scope.addAll(d.parameters());
                    ds.add(
                            new Decl(
                                    d.name(),
                                    d.parameters(),
                                    type(d.annotation(), types),
                                    expr(d.body(), names, scope, types),
                                    d.token()));
                }
                yield new Let(ds, expr(l.body(), names, b, types), l.token());
            }
            case Case c -> {
                var branches = new ArrayList<Branch>();
                for (var branch : c.branches()) {
                    var b = new HashSet<>(bound);
                    var p = pattern(branch.pattern(), names, b);
                    branches.add(new Branch(p, expr(branch.body(), names, b, types)));
                }
                yield new Case(expr(c.scrutinee(), names, bound, types), branches, c.token());
            }
        };
    }

    private static Pattern pattern(Pattern p, Map<String, String> names, Set<String> bound) {
        boolean variable =
                p.token().kind() == Token.Kind.NAME
                        && !p.name().isEmpty()
                        && Character.isLowerCase(p.name().charAt(0));
        if (variable) bound.add(p.name());
        String name =
                variable || p.name().startsWith("$") || p.token().kind() != Token.Kind.NAME
                        ? p.name()
                        : names.getOrDefault(p.name(), p.name());
        return new Pattern(
                name,
                p.arguments().stream().map(a -> pattern(a, names, bound)).toList(),
                p.token());
    }
}
