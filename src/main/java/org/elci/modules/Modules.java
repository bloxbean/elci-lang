package org.elci.modules;

import static org.elci.syntax.Ast.*;

import org.elci.syntax.*;

import java.util.*;

/** Resolve a source graph to stable module-qualified symbols before inference. */
public final class Modules {
    /** Core modules written in Elci and bundled with the compiler. */
    public static final List<String> CORE_MODULES = List.of("List", "Map", "Array");
    /** Core modules whose type is built in, and may still be named as in Elm: {@code Map.Map}, {@code exposing (Map)}. */
    private static final Map<String, String> BUILTIN_TYPES = Map.of("Map", "Map", "Array", "Array", "List", "List");
    /**
     * Library modules the core modules import, mapped without project configuration: Julc's
     * typed map templates back {@code Map}.
     */
    public static final Map<String, String> CORE_LIBRARIES = Map.of("Julc.TypedMap", "org.julclang.stdlib.lib.TypedMapLib",
            "Julc.TypedArray", "org.julclang.stdlib.lib.TypedArrayLib");

    /** Project library mappings plus the core ones; a project may not remap a core alias. */
    public static Map<String, String> withCoreLibraries(Map<String, String> javaModules) {
        var result = new LinkedHashMap<>(javaModules);
        CORE_LIBRARIES.forEach((alias, owner) -> {
            var previous = result.putIfAbsent(alias, owner);
            if (previous != null && !previous.equals(owner))
                throw new IllegalArgumentException("ELCI4001: [java-modules] may not remap " + alias
                        + ", which the Elci core library uses for " + owner);
        });
        return result;
    }

    /** The compiler-provided module of explicit Data codecs and optional adapters. */
    public static final String DATA_MODULE = "Julc.Data";
    /** Its functions, resolved to intrinsic names with this prefix. */
    public static final String DATA_PREFIX = "$julc.data.";
    public static final List<String> DATA_FUNCTIONS = List.of("decode", "encode", "fromOptional", "toOptional");
    /** The compiler-provided debugging module: {@code Debug.trace}. */
    public static final String DEBUG_MODULE = "Debug";
    public static final String DEBUG_PREFIX = "$debug.";
    public static final List<String> DEBUG_FUNCTIONS = List.of("trace");
    /** The compiler-provided byte-string module: single Plutus builtins. */
    public static final String BYTES_MODULE = "Bytes";
    public static final String BYTES_PREFIX = "$bytes.";
    public static final List<String> BYTES_FUNCTIONS = List.of("empty", "fromString", "toString", "length", "append",
            "slice", "take", "drop", "at", "cons", "replicate", "lessThan", "lessThanOrEqual",
            "fromIntBigEndian", "fromIntLittleEndian", "toIntBigEndian", "toIntLittleEndian",
            "and", "or", "xor", "complement", "shift", "rotate", "testBit", "countSetBits", "findFirstSetBit",
            "blake2b224", "blake2b256", "sha256", "sha3_256", "keccak256", "ripemd160");
    /** Intrinsic modules without source: their functions and resolved-name prefixes. */
    private static final Map<String, Map.Entry<String, List<String>>> INTRINSICS = Map.of(
            DATA_MODULE, Map.entry(DATA_PREFIX, DATA_FUNCTIONS),
            DEBUG_MODULE, Map.entry(DEBUG_PREFIX, DEBUG_FUNCTIONS),
            BYTES_MODULE, Map.entry(BYTES_PREFIX, BYTES_FUNCTIONS));

    private final Map<String, Ast.Module> available = new LinkedHashMap<>();
    private final Map<String, String> javaModules;
    /** Imported type descriptions (ledger and Java), whose approved constructors are importable. */
    private Map<String, org.julclang.compiler.backend.LibraryType> catalogue = Map.of();
    private final Set<String> visiting = new LinkedHashSet<>();
    private final LinkedHashMap<String, Ast.Module> ordered = new LinkedHashMap<>();

    public Modules(Map<String, String> sources, Map<String, String> javaModules) {
        this.javaModules = javaModules;
        var allSources = new LinkedHashMap<>(sources);
        // Core modules written in Elci: List is imported everywhere, Map on request.
        for (String core : CORE_MODULES)
            try (var input = Modules.class.getResourceAsStream("/elci/core/" + core + ".elm")) {
                if (input == null) throw new IllegalStateException("Bundled " + core + " module is missing");
                if (allSources.putIfAbsent(
                                core,
                                new String(
                                        input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                        != null)
                    throw new IllegalArgumentException(core + " is owned by the Elci core library");
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        allSources.forEach(
                (name, source) -> {
                    var module = new Parser(source, name).parse();
                    if (!module.name().equals(name))
                        throw new Diagnostic(
                                "ELCI2104",
                                new Token(module.name(), Token.Kind.NAME, 0, 1, 1, name),
                                "Module header "
                                        + module.name()
                                        + " does not match its source path, which names module "
                                        + name);
                    if (javaModules.containsKey(name) || intrinsic(name))
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
        // Sorted, so resolution never depends on hash iteration order.
        catalogue = Collections.unmodifiableMap(new TreeMap<>(types));
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
        return module.equals("Julc.Ledger") || INTRINSICS.containsKey(module);
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
            // Two types of one library may share a constructor name: the qualified name is then
            // ambiguous, and using it is an error rather than a silent choice.
            var previous = names.putIfAbsent(qualifier + "." + name, target);
            if (previous != null && !previous.equals(target))
                names.put(qualifier + "." + name, AMBIGUOUS + qualifier + "." + name);
            if (exposed) put(names, name, target, token);
        });
    }

    /** Marks a qualified constructor name that more than one imported type defines. */
    private static final String AMBIGUOUS = "$ambiguous:";

    private static String unambiguous(String resolved, Token token) {
        if (resolved.startsWith(AMBIGUOUS))
            throw new Diagnostic("ELCI2102", token, "Ambiguous constructor " + resolved.substring(AMBIGUOUS.length())
                    + ": more than one imported type defines it; expose the intended type's constructors with (..)");
        return resolved;
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
        // The root module's own names are unprefixed. Another module never imports the root
        // (that would be a cycle), so an unresolved name there must not reach them.
        var rootNames = new HashSet<String>();
        if (root != null) {
            var rootModule = available.get(root);
            rootModule.declarations().forEach(d -> rootNames.add(d.name()));
            rootModule.aliases().keySet().forEach(rootNames::add);
            rootModule.unions().forEach(u -> u.variants().forEach(v -> rootNames.add(v.name())));
        }
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
                if (INTRINSICS.containsKey(imp.module())) {
                    String intrinsicPrefix = INTRINSICS.get(imp.module()).getKey();
                    var functions = INTRINSICS.get(imp.module()).getValue();
                    for (String function : functions) names.put(imp.alias() + "." + function, intrinsicPrefix + function);
                    for (String exposed : imp.exposing()) {
                        if (exposed.equals(".."))
                            for (String function : functions) put(names, function, intrinsicPrefix + function, imp.token());
                        else if (functions.contains(exposed)) put(names, exposed, intrinsicPrefix + exposed, imp.token());
                        else
                            throw new Diagnostic("ELCI2104", imp.token(), imp.module() + " does not expose '" + exposed
                                    + "'; it provides " + String.join(", ", functions));
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
                                "Java modules cannot be imported with exposing (..); list the exported"
                                        + " names you use");
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
                    else if (BUILTIN_TYPES.getOrDefault(dependency.name(), "").equals(n)) {
                        // `import Map exposing (Map)`, as with Elm's Dict: the type is always in scope.
                    } else if (!dependency.aliases().containsKey(n)
                            && dependency.unions().stream().noneMatch(u -> u.name().equals(n)))
                        throw new Diagnostic(
                                "ELCI2104",
                                imp.token(),
                                "Module does not expose '" + n + "': " + dependency.name());
                }
                if (BUILTIN_TYPES.containsKey(dependency.name()))
                    types.put(imp.alias() + "." + BUILTIN_TYPES.get(dependency.name()), BUILTIN_TYPES.get(dependency.name()));
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
                                expr(d.body(), names, new HashSet<>(d.parameters()), types,
                                        isRoot ? Set.of() : rootNames),
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
        visit(name, null);
    }

    /** {@code via} is the import that reached {@code name}, for locating cycles and missing modules. */
    private void visit(String name, Token via) {
        if (ordered.containsKey(name)) return;
        if (!visiting.add(name)) {
            String cycle = "Import cycle: " + String.join(" -> ", visiting) + " -> " + name;
            if (via != null) throw new Diagnostic("ELCI2104", via, cycle);
            throw new IllegalArgumentException("ELCI2104: " + cycle);
        }
        var module = available.get(name);
        if (module == null) {
            String missing = Set.of("Expect", "Scenario", "Property").contains(name)
                    ? name + " is part of the test library; only test modules can import it"
                    : "Missing source module: " + name + "; add " + name.replace('.', '/')
                            + ".elm under a source directory, or map a Java library to it under [java-modules]";
            if (via != null) throw new Diagnostic("ELCI2101", via, missing);
            throw new IllegalArgumentException("ELCI2101: " + missing);
        }
        for (var imp : module.imports())
            if (!javaModules.containsKey(imp.module()) && !intrinsic(imp.module()))
                visit(imp.module(), imp.token());
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
                    || Set.of("Int", "Bool", "String", "Unit", "Bytes", "Data", "Map", "Array", "JulcOptional", "List", "Maybe", "Tuple", "->")
                            .contains(t.name())
                    || !t.name().contains(".") && Character.isLowerCase(t.name().charAt(0)))
                name = t.name();
            else if (names.containsKey("$ledgerAll")) name = "org.julclang.ledger." + t.name();
            else
                throw new IllegalArgumentException(
                        "ELCI2012: Type is not declared or imported in this module: " + t.name());
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

    /** @param hidden the root module's names, invisible to the other modules */
    private static Expr expr(
            Expr e, Map<String, String> names, Set<String> bound, Map<String, String> types, Set<String> hidden) {
        return switch (e) {
            case Literal l -> l;
            case Name n -> {
                // Unresolved qualified names would otherwise alias internal flattened symbols.
                if (!bound.contains(n.name()) && !resolves(n.name(), names)) {
                    qualified(n.name(), names, n.token());
                    if (hidden.contains(n.name()))
                        throw new Diagnostic("ELCI2005", n.token(), "Unknown name '" + n.name() + "'");
                }
                yield new Name(unambiguous(resolve(n.name(), names, bound), n.token()), n.token());
            }
            case Apply a ->
                    new Apply(
                            expr(a.function(), names, bound, types, hidden),
                            expr(a.argument(), names, bound, types, hidden),
                            a.token());
            case Binary b ->
                    new Binary(
                            b.op(),
                            expr(b.left(), names, bound, types, hidden),
                            expr(b.right(), names, bound, types, hidden),
                            b.token());
            case If i ->
                    new If(
                            expr(i.condition(), names, bound, types, hidden),
                            expr(i.yes(), names, bound, types, hidden),
                            expr(i.no(), names, bound, types, hidden),
                            i.token());
            case Field f -> new Field(expr(f.record(), names, bound, types, hidden), f.name(), f.token());
            case RecordValue r -> {
                var fields = new LinkedHashMap<String, Expr>();
                r.fields().forEach((n, v) -> fields.put(n, expr(v, names, bound, types, hidden)));
                yield new RecordValue(
                        r.base() == null ? null : expr(r.base(), names, bound, types, hidden),
                        fields,
                        r.token());
            }
            case Items i ->
                    new Items(
                            i.elements().stream().map(x -> expr(x, names, bound, types, hidden)).toList(),
                            i.tuple(),
                            i.token());
            case Lambda l -> {
                var b = new HashSet<>(bound);
                b.addAll(l.parameters());
                yield new Lambda(l.parameters(), expr(l.body(), names, b, types, hidden), l.token());
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
                                    expr(d.body(), names, scope, types, hidden),
                                    d.token()));
                }
                yield new Let(ds, expr(l.body(), names, b, types, hidden), l.token());
            }
            case Case c -> {
                var branches = new ArrayList<Branch>();
                for (var branch : c.branches()) {
                    var b = new HashSet<>(bound);
                    var p = pattern(branch.pattern(), names, b, hidden);
                    branches.add(new Branch(p, expr(branch.body(), names, b, types, hidden)));
                }
                yield new Case(expr(c.scrutinee(), names, bound, types, hidden), branches, c.token());
            }
        };
    }

    private static Pattern pattern(Pattern p, Map<String, String> names, Set<String> bound, Set<String> hidden) {
        boolean variable =
                p.token().kind() == Token.Kind.NAME
                        && !p.name().isEmpty()
                        && Character.isLowerCase(p.name().charAt(0));
        if (variable) bound.add(p.name());
        String name =
                variable || p.name().startsWith("$") || p.token().kind() != Token.Kind.NAME
                        ? p.name()
                        : names.getOrDefault(p.name(), p.name());
        if (!variable && p.token().kind() == Token.Kind.NAME && !names.containsKey(p.name())) {
            qualified(p.name(), names, p.token());
            if (hidden.contains(p.name()))
                throw new Diagnostic("ELCI2001", p.token(), "Unsupported constructor pattern " + p.name());
        }
        name = unambiguous(name, p.token());
        return new Pattern(
                name,
                p.arguments().stream().map(a -> pattern(a, names, bound, hidden)).toList(),
                p.token());
    }
}
