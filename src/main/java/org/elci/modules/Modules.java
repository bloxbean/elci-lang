package org.elci.modules;

import static org.elci.syntax.Ast.*;

import org.elci.syntax.*;

import java.util.*;

/** Resolve a source graph to stable module-qualified symbols before inference. */
public final class Modules {
    /** The compiler-provided module of explicit Data codecs and optional adapters. */
    public static final String DATA_MODULE = "Julc.Data";
    /** Its functions, resolved to intrinsic names with this prefix. */
    public static final String DATA_PREFIX = "$julc.data.";
    public static final List<String> DATA_FUNCTIONS = List.of("decode", "encode", "fromOptional", "toOptional");

    private final Map<String, Ast.Module> available = new LinkedHashMap<>();
    private final Map<String, String> javaModules;
    /** Imported type descriptions (ledger and Java), whose approved constructors are importable. */
    private Map<String, org.julclang.compiler.backend.LibraryType> catalogue = Map.of();
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
                    if (javaModules.containsKey(name) || name.equals("Julc.Ledger") || name.equals(DATA_MODULE))
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
        return resolve(List.of(root), root);
    }

    /**
     * Supply imported type descriptions. Types approving construction or matching make their
     * constructors importable, through {@code T(..)} or a module qualifier.
     */
    public void catalogue(Map<String, org.julclang.compiler.backend.LibraryType> types) {
        catalogue = Map.copyOf(types);
    }

    /**
     * The configured Java library classes imported by the modules reachable from {@code roots},
     * in the order resolution encounters them.
     */
    public List<String> javaLibraries(Collection<String> roots) {
        List.copyOf(new TreeSet<>(roots)).forEach(this::visit);
        var result = new ArrayList<String>();
        var reachable = new HashSet<String>();
        var queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            String name = queue.removeFirst();
            if (!reachable.add(name)) continue;
            for (var imp : available.get(name).imports())
                if (!javaModules.containsKey(imp.module()) && !intrinsic(imp.module())) queue.add(imp.module());
        }
        for (var module : ordered.values())
            if (reachable.contains(module.name()))
                for (var imp : module.imports())
                    if (javaModules.containsKey(imp.module())) result.add(javaModules.get(imp.module()));
        return result;
    }

    private static boolean intrinsic(String module) {
        return module.equals("Julc.Ledger") || module.equals(DATA_MODULE);
    }

    /**
     * Register the constructors of an imported type, resolved as {@link
     * org.elci.types.Inference#constructorName}. A record's or newtype's constructor is named
     * after the type. They are qualified by the import alias and, when {@code exposed}, also
     * unqualified.
     */
    private void constructors(Map<String, String> names, String qualifier, String local,
                              org.julclang.compiler.backend.LibraryType type, boolean exposed, Token token) {
        if (!type.supports(org.julclang.compiler.backend.LibraryType.Operation.CONSTRUCT)
                && !type.supports(org.julclang.compiler.backend.LibraryType.Operation.MATCH)) {
            if (exposed)
                throw new Diagnostic("ELCI2104", token, local + " has no approved constructors or patterns; its"
                        + " values come from library functions");
            return;
        }
        var entries = new LinkedHashMap<String, String>();
        if (type.constructors().isEmpty())
            entries.put(local, org.elci.types.Inference.constructorName(type.identity(), null));
        else
            for (var c : type.constructors())
                entries.put(c.name(), org.elci.types.Inference.constructorName(type.identity(), c.name()));
        entries.forEach((name, target) -> {
            names.put(qualifier + "." + name, target);
            if (exposed) put(names, name, target, token);
        });
    }

    /** Catalogue types whose simple name is visible through a Java library module {@code owner}. */
    private Map<String, org.julclang.compiler.backend.LibraryType> libraryTypes(String owner) {
        var result = new LinkedHashMap<String, org.julclang.compiler.backend.LibraryType>();
        String packageName = owner.contains(".") ? owner.substring(0, owner.lastIndexOf('.') + 1) : "";
        for (var type : catalogue.values()) {
            String id = type.identity();
            String local = id.startsWith(owner + ".") ? id.substring(owner.length() + 1)
                    : id.startsWith(packageName) ? id.substring(packageName.length()) : null;
            if (local != null && !local.contains(".")) result.put(local, type);
        }
        return result;
    }

    /**
     * Resolve several root modules into one program in which every declaration is
     * module-qualified, for named validators whose handlers live in different modules.
     */
    public Ast.Module resolveAll(Collection<String> roots) {
        return resolve(List.copyOf(new TreeSet<>(roots)), null);
    }

    /** Whether {@code module} exposes the top-level declaration {@code name}. */
    public boolean exposes(String module, String name) {
        var m = available.get(module);
        if (m == null) throw new IllegalArgumentException("Missing source module: " + module);
        return m.declarations().stream().anyMatch(d -> d.name().equals(name)) && exposes(m, name);
    }

    /** Whether {@code module} declares the top-level name {@code name}. */
    public boolean declares(String module, String name) {
        var m = available.get(module);
        return m != null && m.declarations().stream().anyMatch(d -> d.name().equals(name));
    }

    /** The parsed source module, before resolution. */
    public Ast.Module module(String module) {
        var m = available.get(module);
        if (m == null) throw new IllegalArgumentException("Missing source module: " + module);
        return m;
    }

    private Ast.Module resolve(List<String> roots, String root) {
        roots.forEach(this::visit);
        var definitions = new ArrayList<Decl>();
        var imports = new ArrayList<Import>();
        var aliases = new LinkedHashMap<String, Alias>();
        var unions = new ArrayList<Union>();
        for (var module : ordered.values()) {
            boolean isRoot = root != null && module.name().equals(root);
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
                if (imp.module().equals(DATA_MODULE)) {
                    for (String function : DATA_FUNCTIONS) names.put(imp.alias() + "." + function, DATA_PREFIX + function);
                    for (String exposed : imp.exposing()) {
                        if (exposed.equals(".."))
                            for (String function : DATA_FUNCTIONS) put(names, function, DATA_PREFIX + function, imp.token());
                        else if (DATA_FUNCTIONS.contains(exposed)) put(names, exposed, DATA_PREFIX + exposed, imp.token());
                        else
                            throw new Diagnostic("ELCI2104", imp.token(), DATA_MODULE + " does not expose '" + exposed
                                    + "'; it provides " + String.join(", ", DATA_FUNCTIONS));
                    }
                    continue;
                }
                if (imp.module().equals("Julc.Ledger")) {
                    imports.add(imp);
                    types.put(imp.alias() + ".", "org.julclang.ledger.");
                    var exposedConstructors = new HashSet<String>();
                    for (String exposed : imp.exposing()) {
                        if (exposed.equals("..")) types.put("$ledgerAll", "true");
                        else {
                            String type = exposed.endsWith("(..)") ? exposed.substring(0, exposed.length() - 4) : exposed;
                            if (!type.equals(exposed)) exposedConstructors.add(type);
                            put(
                                    types,
                                    type,
                                    type.equals("ScriptContext")
                                            ? "ScriptContext"
                                            : "org.julclang.ledger." + type,
                                    imp.token());
                        }
                    }
                    for (var type : catalogue.values()) {
                        String id = type.identity();
                        if (!id.startsWith("org.julclang.ledger.") || id.substring(20).contains(".")) continue;
                        String local = id.substring(20);
                        constructors(names, imp.alias(), local, type, exposedConstructors.remove(local), imp.token());
                    }
                    if (!exposedConstructors.isEmpty())
                        throw new Diagnostic("ELCI2101", imp.token(), "Unknown ledger type " + exposedConstructors.iterator().next());
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
                    var libraryTypes = libraryTypes(javaModules.get(imp.module()));
                    var exposedConstructors = new HashSet<String>();
                    for (String n : imp.exposing()) {
                        if (Character.isUpperCase(n.charAt(0))) {
                            String type = n.endsWith("(..)") ? n.substring(0, n.length() - 4) : n;
                            if (!type.equals(n)) {
                                if (!libraryTypes.containsKey(type))
                                    throw new Diagnostic("ELCI2101", imp.token(), "No library type '" + type + "' in " + imp.module());
                                exposedConstructors.add(type);
                            }
                            put(types, type, alias + "." + type, imp.token());
                        } else put(names, n, alias + "." + n, imp.token());
                    }
                    libraryTypes.forEach((local, type) ->
                            constructors(names, imp.alias(), local, type, exposedConstructors.contains(local), imp.token()));
                    continue;
                }
                var dependency = available.get(imp.module());
                String dp =
                        root != null && dependency.name().equals(root)
                                ? ""
                                : dependency.name() + ".";
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
        if (root == null)
            return new Ast.Module(
                    String.join("+", roots), List.of(), imports, definitions, aliases, unions);
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
            if (!javaModules.containsKey(imp.module()) && !intrinsic(imp.module()))
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

    private static boolean resolves(String n, Map<String, String> names) {
        if (names.containsKey(n)) return true;
        for (var key : names.keySet()) if (key.endsWith(".") && n.startsWith(key)) return true;
        return false;
    }

    private static String resolve(String n, Map<String, String> names, Set<String> bound) {
        if (bound.contains(n)) return n;
        if (names.containsKey(n)) return names.get(n);
        for (var entry : names.entrySet())
            if (entry.getKey().endsWith(".") && n.startsWith(entry.getKey()))
                return entry.getValue() + n.substring(entry.getKey().length());
        return n;
    }

    /**
     * A module-qualified name must come from an import that exposes it. Without this check a
     * qualified reference would reach a private or unimported declaration of the flattened
     * program by its internal name.
     */
    private static String qualified(String n, Map<String, String> names, Token token) {
        int dot = n.lastIndexOf('.');
        if (dot <= 0 || !Character.isUpperCase(n.charAt(0))) return n;
        throw new Diagnostic(
                "ELCI2104",
                token,
                "Module "
                        + n.substring(0, dot)
                        + " is not imported or does not expose '"
                        + n.substring(dot + 1)
                        + "'");
    }

    private static Expr expr(
            Expr e, Map<String, String> names, Set<String> bound, Map<String, String> types) {
        return switch (e) {
            case Literal l -> l;
            case Name n -> {
                // Unresolved qualified names would otherwise alias internal flattened symbols.
                if (!bound.contains(n.name()) && !resolves(n.name(), names))
                    qualified(n.name(), names, n.token());
                yield new Name(resolve(n.name(), names, bound), n.token());
            }
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
        if (!variable && p.token().kind() == Token.Kind.NAME && !names.containsKey(p.name()))
            qualified(p.name(), names, p.token());
        return new Pattern(
                name,
                p.arguments().stream().map(a -> pattern(a, names, bound)).toList(),
                p.token());
    }
}
