package org.elci.modules;

import static org.elci.syntax.Ast.*;

import org.elci.syntax.*;

import java.util.*;

/** Resolve a source graph to stable module-qualified symbols before inference. */
public final class Modules {
    /** Core modules written in Elci and bundled with the compiler. */
    public static final List<String> CORE_MODULES =
            List.of("Basics", "List", "Maybe", "Result", "String", "Tuple", "Map", "Dict", "Array", "Value", "Interval", "Tx");
    /** Core modules whose type is built in, and may still be named as in Elm: {@code Map.Map}, {@code exposing (Map)}. */
    private static final Map<String, String> BUILTIN_TYPES = Map.of("Map", "Map", "Dict", "Dict", "Array", "Array",
            "List", "List", "Maybe", "Maybe", "String", "String", "Value", "Value", "Interval", "Interval");
    /** The identity a built-in type name resolves to: the core `Value` and `Interval` are the ledger's. */
    private static String builtinTarget(String type) {
        return type.equals("Value") || type.equals("Interval") ? "org.julclang.ledger." + type : type;
    }
    /**
     * Elm's default imports (ADR-008), added to every project and test module. Their names have
     * the lowest priority: a module's own declarations and its explicit imports take precedence,
     * without an ambiguity error. Core modules import what they use explicitly, as in Elm.
     */
    private static final Map<String, List<String>> DEFAULT_IMPORTS = defaultImports();

    private static Map<String, List<String>> defaultImports() {
        var imports = new LinkedHashMap<String, List<String>>();
        imports.put("Basics", List.of(".."));
        imports.put("List", List.of());
        imports.put("Maybe", List.of("Maybe(..)"));
        imports.put("Result", List.of("Result(..)"));
        imports.put("String", List.of());
        imports.put("Tuple", List.of());
        imports.put("Debug", List.of());
        return Collections.unmodifiableMap(imports);
    }

    /** Functions of {@code Basics} the compiler provides, resolved to intrinsic names with this prefix. */
    public static final String BASICS_PREFIX = "$basics.";
    public static final List<String> BASICS_FUNCTIONS = List.of("not", "modBy", "remainderBy");
    /** Module names reserved for later phases of the core library (ADR-008): {@code Dict}, {@code Set}, {@code Value}. */
    private static final List<String> RESERVED_MODULES = List.of("Set", "Elci");
    /** Built-in type names, which no module may declare again: core modules refer to them. */
    private static final Set<String> BUILT_IN_TYPES =
            Set.of("Int", "Bool", "String", "Bytes", "Data", "List", "Maybe", "Map", "Dict", "Array", "Unit", "Tuple", "Float");
    /** Elm functions Elci does not provide, with what to use instead. */
    private static final Map<String, String> ELM_ALTERNATIVES = Map.of(
            "Debug.log", "use Debug.trace message value, which returns value",
            "Debug.toString", "use String.fromInt for an Int",
            "Debug.todo", "return a value of the right type until the code is written; a validator can return False",
            "String.fromFloat", "there is no Float; use String.fromInt",
            "String.length", "a String is UTF-8 text; Bytes.length (Bytes.fromString s) is its byte length",
            "Bytes.lessThan", "bytes are comparable: write a < b",
            "Bytes.lessThanOrEqual", "bytes are comparable: write a <= b");
    /** Constructors of built-in types, which no module may declare again. */
    private static final Map<String, String> BUILT_IN_CONSTRUCTORS =
            Map.of("Just", "Maybe", "Nothing", "Maybe", "True", "Bool", "False", "Bool");
    /** Built-in constructors of a core module's built-in type: {@code Maybe.Just}. */
    private static final Map<String, List<String>> BUILTIN_CONSTRUCTORS = Map.of("Maybe", List.of("Nothing", "Just"));
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
            "slice", "take", "drop", "at", "cons", "replicate",
            "fromIntBigEndian", "fromIntLittleEndian", "toIntBigEndian", "toIntLittleEndian",
            "and", "or", "xor", "complement", "shift", "rotate", "testBit", "countSetBits", "findFirstSetBit",
            "blake2b224", "blake2b256", "sha256", "sha3_256", "keccak256", "ripemd160");
    /**
     * The core library's internal association-list operations (ADR-009): only core modules may
     * import them, because some trust their callers (sorted or distinct keys).
     */
    public static final String ASSOC_MODULE = "Elci.Assoc";
    public static final String ASSOC_PREFIX = "$assoc.";
    public static final List<String> ASSOC_FUNCTIONS = List.of("toList", "fromDistinct", "cons", "reverse", "find",
            "has", "delete", "get", "member", "insert", "remove", "ascending", "runs", "merge", "dedupe", "toMap", "fromMap");
    /** The core `Value` module's internal CIP-153 operations (ADR-009); core modules only. */
    public static final String VALUE_MODULE = "Elci.Value";
    public static final String VALUE_PREFIX = "$value.";
    public static final List<String> VALUE_FUNCTIONS = List.of("fromLedger", "toLedger", "empty", "insertCoin",
            "lookupCoin", "union", "contains", "scale", "lovelaceOf", "isZero", "policies", "tokens", "toList",
            "nonNegative", "filter", "adaPolicy", "adaToken");
    /** Intrinsic modules without source: their functions and resolved-name prefixes. */
    private static final Map<String, Map.Entry<String, List<String>>> INTRINSICS = Map.of(
            DATA_MODULE, Map.entry(DATA_PREFIX, DATA_FUNCTIONS),
            DEBUG_MODULE, Map.entry(DEBUG_PREFIX, DEBUG_FUNCTIONS),
            BYTES_MODULE, Map.entry(BYTES_PREFIX, BYTES_FUNCTIONS),
            ASSOC_MODULE, Map.entry(ASSOC_PREFIX, ASSOC_FUNCTIONS),
            VALUE_MODULE, Map.entry(VALUE_PREFIX, VALUE_FUNCTIONS));

    private final Map<String, Ast.Module> available = new LinkedHashMap<>();
    /** The default imports added to modules, by identity: they bind names only where nothing else does. */
    private final Set<Import> defaults = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String, String> javaModules;
    /** Imported type descriptions (ledger and Java), whose approved constructors are importable. */
    private Map<String, org.julclang.compiler.backend.LibraryType> catalogue = Map.of();
    private final Set<String> visiting = new LinkedHashSet<>();
    private final LinkedHashMap<String, Ast.Module> ordered = new LinkedHashMap<>();

    public Modules(Map<String, String> sources, Map<String, String> javaModules) {
        this.javaModules = javaModules;
        for (String name : javaModules.keySet())
            if (CORE_MODULES.contains(name) || RESERVED_MODULES.contains(name) || DEFAULT_IMPORTS.containsKey(name)
                    || name.startsWith("Elci."))
                throw new IllegalArgumentException("ELCI4001: [java-modules] may not map " + name
                        + ", a module name of Elci's core library");
        for (String name : sources.keySet())
            if (RESERVED_MODULES.contains(name) || name.startsWith("Elci."))
                throw new Diagnostic("ELCI2104", new Token(name, Token.Kind.NAME, 0, 1, 1, name),
                        "The module name " + name + " is reserved for Elci's core library; rename this module");
        var allSources = new LinkedHashMap<>(sources);
        // Core modules written in Elci: the default imports everywhere, the others on request.
        for (String core : CORE_MODULES)
            try (var input = Modules.class.getResourceAsStream("/elci/core/" + core + ".elm")) {
                if (input == null) throw new IllegalStateException("Bundled " + core + " module is missing");
                if (allSources.putIfAbsent(
                                core,
                                new String(
                                        input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                        != null)
                    throw new Diagnostic("ELCI2104", new Token(core, Token.Kind.NAME, 0, 1, 1, core),
                            "The module name " + core + " is taken by Elci's core library; rename this module");
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        // Every module is parsed, and all their syntax errors are reported together (ADR-012).
        var parseErrors = new ArrayList<Diagnostic>();
        allSources.forEach(
                (name, source) -> {
                  try {
                    var module = new Parser(source, name).parse();
                    if (!module.name().equals(name))
                        throw new Diagnostic(
                                "ELCI2104",
                                new Token(module.name(), Token.Kind.NAME, 0, 1, 1, name),
                                "Module header "
                                        + module.name()
                                        + " does not match its source path, which names module "
                                        + name);
                    if (intrinsic(name))
                        throw new Diagnostic("ELCI2104", new Token(name, Token.Kind.NAME, 0, 1, 1, name),
                                "The module name " + name + " is taken by a module the compiler provides; rename this module");
                    if (javaModules.containsKey(name))
                        throw new Diagnostic("ELCI2104", new Token(name, Token.Kind.NAME, 0, 1, 1, name),
                                "The module name " + name + " is also mapped to a Java library under [java-modules];"
                                        + " rename the module or the mapping");
                    for (var alias : module.aliases().values())
                        if (BUILT_IN_TYPES.contains(alias.name()))
                            throw new Diagnostic("ELCI2011", alias.token(), alias.name()
                                    + " is a built-in type; choose another name");
                    for (var union : module.unions())
                        if (BUILT_IN_TYPES.contains(union.name()))
                            throw new Diagnostic("ELCI2011", union.token(), union.name()
                                    + " is a built-in type; choose another name");
                    for (var union : module.unions())
                        for (var variant : union.variants())
                            if (BUILT_IN_CONSTRUCTORS.containsKey(variant.name()))
                                throw new Diagnostic("ELCI2011", union.token(), variant.name() + " is a constructor of the"
                                        + " built-in " + BUILT_IN_CONSTRUCTORS.get(variant.name()) + " type; choose another name");
                    for (var imp : module.imports())
                        if (imp.module().startsWith("Elci.") && !CORE_MODULES.contains(name))
                            throw new Diagnostic("ELCI2104", imp.token(), imp.module()
                                    + " is internal to Elci's core library; use " + (imp.module().equals(VALUE_MODULE)
                                            ? "Value" : "Map or Dict"));
                    // A module's names are qualified by its alias, and a Java library's exports are named
                    // by it; an alias that names another module would merge the two.
                    for (var imp : module.imports())
                        if (!imp.alias().equals(imp.module())) {
                            if (DEFAULT_IMPORTS.containsKey(imp.alias()))
                                throw new Diagnostic("ELCI2104", imp.token(), "The alias " + imp.alias()
                                        + " names a core module that every module imports; choose another alias for "
                                        + imp.module());
                            if (CORE_MODULES.contains(imp.alias()) || sources.containsKey(imp.alias()))
                                throw new Diagnostic("ELCI2104", imp.token(), "The alias " + imp.alias() + " names "
                                        + (CORE_MODULES.contains(imp.alias()) ? "Elci's core module " : "this project's module ")
                                        + imp.alias() + "; choose another alias for " + imp.module());
                        }
                    if (!CORE_MODULES.contains(name)) {
                        var imports = new ArrayList<>(module.imports());
                        DEFAULT_IMPORTS.forEach((core, exposing) -> {
                            var imp = new Import(core, core, exposing, new Token(core, Token.Kind.NAME, 0, 1, 1, name));
                            defaults.add(imp);
                            imports.add(imp);
                        });
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
                  } catch (Diagnostic e) {
                    parseErrors.add(e);
                  }
                });
        var found = Diagnostic.of(parseErrors);
        if (found != null) throw found;
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
    /** @param seen this import's qualified constructor names, to tell two of its types apart */
    private void constructors(Scope scope, String module, String qualifier, Set<String> seen, String local,
                              org.julclang.compiler.backend.LibraryType type, boolean exposed, Token token) {
        if (!type.supports(org.julclang.compiler.backend.LibraryType.Operation.CONSTRUCT)
                && !type.supports(org.julclang.compiler.backend.LibraryType.Operation.MATCH)) {
            if (exposed)
                throw new Diagnostic("ELCI2104", token, local + " has no approved constructors or patterns; its"
                        + (type.identity().equals("org.julclang.ledger.Value") ? " values come from the Value module"
                                : " values come from library functions"));
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
            String qualified = qualifier + "." + name;
            if (seen.add(qualified)) scope.name(qualified, target, Rank.NAMED, module + "." + name);
            else if (!target.equals(scope.names.get(qualified)))
                scope.names.put(qualified, AMBIGUOUS + qualified);
            if (exposed) scope.name(name, target, Rank.NAMED, qualifier + "." + name);
        });
    }

    /**
     * Marks a name that more than one import provides: {@code $ambiguous:name|Candidate.name|...}.
     * A qualified constructor that two types of one library define has no candidates.
     */
    private static final String AMBIGUOUS = "$ambiguous:";

    private static String unambiguous(String resolved, Token token) {
        if (!resolved.startsWith(AMBIGUOUS)) return resolved;
        throw new Diagnostic("ELCI2102", token, ambiguity(resolved));
    }

    private static String ambiguity(String marker) {
        String rest = marker.substring(AMBIGUOUS.length());
        int bar = rest.indexOf('|');
        if (bar < 0)
            return "Ambiguous constructor " + rest
                    + ": more than one imported type defines it; expose the intended type's constructors with (..)";
        String name = rest.substring(0, bar);
        var candidates = List.of(rest.substring(bar + 1).split("\\|"));
        String choices = String.join(" or ", candidates);
        if (name.contains("."))
            return name + " is ambiguous: it could be " + choices + ". Give the imports different aliases";
        return name + " is ambiguous: it could be " + choices + ". Write the one you mean, like " + candidates.getFirst()
                + ", or stop exposing the other";
    }

    /** How a name entered a module's scope; a higher rank takes precedence (ADR-008). */
    private enum Rank { DEFAULT, OPEN, NAMED, OWN }

    /**
     * A module's names and type names, with Elm's precedence: the module's own declarations
     * shadow every import. Two imports exposing different things under one name make it
     * ambiguous where it is used, except that a default import yields to a named exposure
     * ({@code import Utils exposing (clamp)}), an Elci addition.
     */
    private static final class Scope {
        final Map<String, String> names = new LinkedHashMap<>();
        final Map<String, String> types = new LinkedHashMap<>();
        private final Map<String, Rank> ranks = new HashMap<>();
        private final Map<String, Set<String>> candidates = new HashMap<>();

        void name(String n, String target, Rank rank, String spelling) {
            bind(names, "value " + n, n, target, rank, spelling);
        }

        void type(String n, String target, Rank rank, String spelling) {
            bind(types, "type " + n, n, target, rank, spelling);
        }

        private void bind(Map<String, String> map, String key, String n, String target, Rank rank, String spelling) {
            String previous = map.get(n);
            Rank held = ranks.getOrDefault(key, Rank.NAMED);
            candidates.computeIfAbsent(key, k -> new TreeSet<>());
            if (previous == null || rank == Rank.OWN || held == Rank.DEFAULT && rank == Rank.NAMED) {
                map.put(n, target);
                ranks.put(key, rank);
                candidates.put(key, new TreeSet<>());
                if (spelling != null) candidates.get(key).add(spelling);
                return;
            }
            if (previous.equals(target) || held == Rank.OWN || held == Rank.NAMED && rank == Rank.DEFAULT) {
                if (rank.compareTo(held) > 0) ranks.put(key, rank);
                return;
            }
            if (spelling != null) candidates.get(key).add(spelling);
            map.put(n, AMBIGUOUS + n + "|" + String.join("|", candidates.get(key)));
            if (rank.compareTo(held) > 0) ranks.put(key, rank);
        }
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

    /** The module whose names are being resolved. */
    private String current;

    /** "Did you mean" hints for names left unresolved here, used if inference rejects them (ADR-011). */
    private final Map<Token, java.util.function.Supplier<List<String>>> nameHints = new HashMap<>();

    /** {@code d}, with a "did you mean" hint when it rejects a name this resolution left unresolved. */
    public Diagnostic withSuggestions(Diagnostic d) {
        return d.map(one -> {
            var hint = Set.of("ELCI2005", "ELCI2001").contains(one.code()) && one.getMessage().startsWith("Unknown")
                    ? nameHints.get(one.token()) : null;
            return hint == null ? one : one.withHints(hint.get());
        });
    }

    /**
     * Names close to {@code name}: first those in scope as written (locals, and exposed or own
     * names), then qualified ones.
     */
    private static List<String> suggest(String name, Map<String, String> names, Set<String> bound) {
        var plain = new TreeSet<>(bound);
        plain.addAll(List.of("True", "False", "Just", "Nothing"));
        var qualified = new TreeSet<String>();
        for (String key : names.keySet())
            if (key.startsWith("$") || key.endsWith(".")) continue;
            else if (key.contains(".")) qualified.add(key);
            else plain.add(key);
        if (!name.contains(".")) {
            if (plain.contains(name)) return List.of();
            // The name is right but not exposed: say how to reach it.
            var reach = qualified.stream().filter(key -> key.substring(key.lastIndexOf('.') + 1).equals(name))
                    .limit(3).map(key -> "`" + key + "`").toList();
            if (!reach.isEmpty())
                return List.of("write " + (reach.size() == 1 ? reach.getFirst()
                        : String.join(", ", reach.subList(0, reach.size() - 1)) + " or " + reach.getLast())
                        + ", or expose `" + name + "` in its import");
            var hint = Suggestions.hint(name, plain);
            if (!hint.isEmpty()) return hint;
        }
        // Imported without an alias: `Pricing.priceOf` for `Shop.Pricing.priceOf`.
        for (String key : qualified)
            if (name.contains(".") && key.endsWith("." + name)) return List.of("did you mean `" + key + "`?");
        return Suggestions.hint(name, qualified);
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
        var resolveErrors = new ArrayList<Diagnostic>();
        var definitions = new ArrayList<Decl>();
        var imports = new ArrayList<Import>();
        var aliases = new LinkedHashMap<String, Alias>();
        var unions = new ArrayList<Union>();
        for (var module : ordered.values()) {
            current = module.name();
            boolean isRoot = root != null && module.name().equals(root);
            String prefix = isRoot ? "" : module.name() + ".";
            var scope = new Scope();
            var names = scope.names;
            var types = scope.types;
            for (var d : module.declarations()) scope.name(d.name(), prefix + d.name(), Rank.OWN, null);
            for (var alias : module.aliases().entrySet()) {
                scope.type(alias.getKey(), prefix + alias.getKey(), Rank.OWN, null);
                if (alias.getValue().target().name().startsWith("Record:"))
                    scope.name(alias.getKey(), prefix + alias.getKey(), Rank.OWN, null);
            }
            for (var u : module.unions()) {
                scope.type(u.name(), prefix + u.name(), Rank.OWN, null);
                for (var v : u.variants()) scope.name(v.name(), prefix + v.name(), Rank.OWN, null);
            }
            boolean importsFailed = false;
            for (var imp : module.imports()) {
                // An import that fails is recorded; the module's declarations are then not
                // resolved, since the names it would bring are missing (ADR-012).
                try {
                    // Default imports rank lowest; an explicit exposing (..) ranks below a named exposure.
                    boolean fallback = defaults.contains(imp);
                    Rank named = fallback ? Rank.DEFAULT : Rank.NAMED;
                    Rank open = fallback ? Rank.DEFAULT : Rank.OPEN;
                    if (INTRINSICS.containsKey(imp.module())) {
                        String intrinsicPrefix = INTRINSICS.get(imp.module()).getKey();
                        var functions = INTRINSICS.get(imp.module()).getValue();
                        for (String function : functions)
                            scope.name(imp.alias() + "." + function, intrinsicPrefix + function, named, imp.module() + "." + function);
                        // Their types can be named qualified too, like `Map.Map`: `Data.Data`, `Bytes.Bytes`.
                        if (imp.module().equals(DATA_MODULE)) scope.type(imp.alias() + ".Data", "Data", named, null);
                        if (imp.module().equals(BYTES_MODULE)) scope.type(imp.alias() + ".Bytes", "Bytes", named, null);
                        for (String exposed : imp.exposing()) {
                            if (exposed.equals(".."))
                                for (String function : functions)
                                    scope.name(function, intrinsicPrefix + function, open, imp.alias() + "." + function);
                            else if (functions.contains(exposed))
                                scope.name(exposed, intrinsicPrefix + exposed, named, imp.alias() + "." + exposed);
                            else
                                throw new Diagnostic("ELCI2104", imp.token(), imp.module() + " does not expose '" + exposed
                                        + "'; it provides " + String.join(", ", functions), Suggestions.hint(exposed, functions));
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
                                scope.type(type, type.equals("ScriptContext") ? "ScriptContext" : "org.julclang.ledger." + type,
                                        named, imp.alias() + "." + type);
                            }
                        }
                        var seen = new HashSet<String>();
                        for (var type : catalogue.values()) {
                            String id = type.identity();
                            if (!id.startsWith("org.julclang.ledger.") || id.substring(20).contains(".")) continue;
                            String local = id.substring(20);
                            constructors(scope, imp.module(), imp.alias(), seen, local, type, exposedConstructors.remove(local),
                                    imp.token());
                        }
                        if (!exposedConstructors.isEmpty()) {
                            String unknown = exposedConstructors.iterator().next();
                            throw new Diagnostic("ELCI2101", imp.token(), "Unknown ledger type " + unknown,
                                    Suggestions.hint(unknown, catalogue.keySet().stream().filter(id -> id.startsWith("org.julclang.ledger."))
                                            .map(id -> id.substring(20)).toList()));
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
                                scope.type(type, alias + "." + type, Rank.NAMED, imp.alias() + "." + type);
                            } else scope.name(n, alias + "." + n, Rank.NAMED, imp.alias() + "." + n);
                        }
                        var seen = new HashSet<String>();
                        libraryTypes.forEach((local, type) -> constructors(scope, imp.module(), imp.alias(), seen, local, type,
                                exposedConstructors.contains(local), imp.token()));
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
                    if (dependency.name().equals("Basics"))
                        for (String n : BASICS_FUNCTIONS) exported.put(n, BASICS_PREFIX + n);
                    exported.forEach((n, s) -> scope.name(imp.alias() + "." + n, s, named, dependency.name() + "." + n));
                    for (String n : BUILTIN_CONSTRUCTORS.getOrDefault(dependency.name(), List.of()))
                        scope.name(imp.alias() + "." + n, n, named, dependency.name() + "." + n);
                    for (String n : imp.exposing()) {
                        if (n.equals(".."))
                            exported.forEach((k, v) -> scope.name(k, v, open, imp.alias() + "." + k));
                        else if (n.equals(BUILTIN_TYPES.get(dependency.name()) + "(..)")
                                && BUILTIN_CONSTRUCTORS.containsKey(dependency.name())) {
                            // `import Maybe exposing (Maybe(..))`: the constructors are built in.
                        } else if (n.endsWith("(..)")) {
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
                                                                    "Unknown exposed type " + n,
                                                                    Suggestions.hint(typeName, dependency.unions().stream()
                                                                            .map(u -> u.name()).toList())));
                            if (!dependency.exposing().contains(n)
                                    && !dependency.exposing().contains(".."))
                                throw new Diagnostic(
                                        "ELCI2104", imp.token(), "Constructors are private: " + n);
                            for (var variant : union.variants())
                                scope.name(variant.name(), dp + variant.name(), named, imp.alias() + "." + variant.name());
                        } else if (exported.containsKey(n)) scope.name(n, exported.get(n), named, imp.alias() + "." + n);
                        else if (BUILTIN_TYPES.getOrDefault(dependency.name(), "").equals(n)) {
                            // `import Map exposing (Map)`, as with Elm's Dict: the type is always in scope.
                            if (!builtinTarget(n).equals(n)) scope.type(n, builtinTarget(n), named, imp.alias() + "." + n);
                        } else if (!dependency.aliases().containsKey(n)
                                && dependency.unions().stream().noneMatch(u -> u.name().equals(n)))
                            throw new Diagnostic(
                                    "ELCI2104",
                                    imp.token(),
                                    dependency.name() + " does not expose '" + n + "'",
                                    Suggestions.hint(n, exposedNames(dependency)));
                    }
                    if (BUILTIN_TYPES.containsKey(dependency.name()))
                        scope.type(imp.alias() + "." + BUILTIN_TYPES.get(dependency.name()),
                                builtinTarget(BUILTIN_TYPES.get(dependency.name())), named, null);
                    for (String n : dependency.aliases().keySet())
                        if (exposes(dependency, n)) {
                            scope.type(imp.alias() + "." + n, dp + n, named, dependency.name() + "." + n);
                            if (imp.exposing().contains(n)) scope.type(n, dp + n, named, imp.alias() + "." + n);
                            else if (imp.exposing().contains("..")) scope.type(n, dp + n, open, imp.alias() + "." + n);
                        }
                    // Cross-module custom-type constructors and type names remain nominal.
                    for (var u : dependency.unions())
                        if (exposes(dependency, u.name())
                                || dependency.exposing().contains(u.name() + "(..)")) {
                            scope.type(imp.alias() + "." + u.name(), dp + u.name(), named, dependency.name() + "." + u.name());
                            if (imp.exposing().contains(u.name()) || imp.exposing().contains(u.name() + "(..)"))
                                scope.type(u.name(), dp + u.name(), named, imp.alias() + "." + u.name());
                            else if (imp.exposing().contains(".."))
                                scope.type(u.name(), dp + u.name(), open, imp.alias() + "." + u.name());
                        }
                } catch (Diagnostic e) {
                    resolveErrors.add(e);
                    importsFailed = true;
                }
            }
            if (importsFailed) continue;
            // Each declaration, alias and custom type is resolved on its own: an error is recorded,
            // and the rest are still resolved (ADR-012).
            for (var d : module.declarations())
                try {
                    definitions.add(
                            new Decl(
                                    prefix + d.name(),
                                    d.parameters(),
                                    type(d.annotation(), types, d.token()),
                                    expr(d.body(), names, new HashSet<>(d.parameters()), types,
                                            isRoot ? Set.of() : rootNames),
                                    d.token()));
                } catch (Diagnostic e) {
                    resolveErrors.add(e);
                }
            for (var entry : module.aliases().entrySet())
                try {
                    var a = entry.getValue();
                    aliases.put(prefix + entry.getKey(), new Alias(prefix + entry.getKey(), a.parameters(),
                            type(a.target(), types, a.token()), a.constructorFields(), a.token()));
                } catch (Diagnostic e) {
                    resolveErrors.add(e);
                }
            for (var u : module.unions())
                try {
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
                                                                            .map(t -> type(t, types, u.token()))
                                                                            .toList()))
                                            .toList(),
                                    u.token()));
                } catch (Diagnostic e) {
                    resolveErrors.add(e);
                }
        }
        var found = Diagnostic.of(resolveErrors);
        if (found != null) throw found;
        if (root == null)
            return new Ast.Module(
                    String.join("+", roots), List.of(), imports, definitions, aliases, unions);
        var original = available.get(root);
        return new Ast.Module(root, original.exposing(), imports, definitions, aliases, unions);
    }

    /** The names a module exposes: values, type aliases and custom types. */
    private static List<String> exposedNames(Ast.Module m) {
        var result = new ArrayList<String>();
        m.declarations().forEach(d -> { if (exposes(m, d.name())) result.add(d.name()); });
        m.aliases().keySet().forEach(a -> { if (exposes(m, a)) result.add(a); });
        m.unions().forEach(u -> { if (exposes(m, u.name()) || m.exposing().contains(u.name() + "(..)")) result.add(u.name()); });
        return result;
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
            if (via != null) {
                var known = new ArrayList<>(available.keySet());
                known.addAll(javaModules.keySet());
                known.addAll(INTRINSICS.keySet());
                known.add("Julc.Ledger");
                throw new Diagnostic("ELCI2101", via, missing, Suggestions.hint(name, known));
            }
            throw new IllegalArgumentException("ELCI2101: " + missing);
        }
        for (var imp : module.imports())
            if (!javaModules.containsKey(imp.module()) && !intrinsic(imp.module()))
                visit(imp.module(), imp.token());
        visiting.remove(name);
        ordered.put(name, module);
    }


    /** {@link #type(Type, Map)}, with a failure located at the declaration that wrote the type. */
    private static Type type(Type t, Map<String, String> names, Token at) {
        try {
            return type(t, names);
        } catch (IllegalArgumentException e) {
            String message = e.getMessage();
            if (message != null && message.matches("ELCI\\d{4}: .*"))
                throw new Diagnostic(message.substring(0, 8), at, message.substring(10),
                        e instanceof UnknownType unknown ? unknown.hints : List.of());
            throw e;
        }
    }

    /** A type name no scope declares, with "did you mean" hints. */
    private static final class UnknownType extends IllegalArgumentException {
        final List<String> hints;

        UnknownType(String message, List<String> hints) {
            super(message);
            this.hints = hints;
        }
    }

    /** The type names a module can write: its scope's and the built-in ones. */
    private static List<String> typeHint(String name, Map<String, String> names) {
        var plain = new TreeSet<>(List.of("Int", "Bool", "String", "Bytes", "Data", "List", "Maybe", "Map", "Dict", "Array"));
        var qualified = new TreeSet<String>();
        for (String key : names.keySet())
            if (key.startsWith("$") || key.endsWith(".")) continue;
            else if (key.contains(".")) qualified.add(key);
            else plain.add(key);
        var hint = name.contains(".") ? List.<String>of() : Suggestions.hint(name, plain);
        return hint.isEmpty() ? Suggestions.hint(name, qualified) : hint;
    }

    private static Type type(Type t, Map<String, String> names) {
        if (t == null) return null;
        String name = names.get(t.name());
        if (name != null && name.startsWith(AMBIGUOUS))
            throw new IllegalArgumentException("ELCI2102: The type " + ambiguity(name));
        if (name == null)
            for (var entry : names.entrySet())
                if (entry.getKey().endsWith(".") && t.name().startsWith(entry.getKey()))
                    name = entry.getValue() + t.name().substring(entry.getKey().length());
        if (name == null) {
            if (t.name().startsWith("Record:")
                    || Set.of("Int", "Bool", "String", "Unit", "Bytes", "Data", "Map", "Dict", "Array", "JulcOptional", "List", "Maybe", "Tuple", "->")
                            .contains(t.name())
                    || !t.name().contains(".") && Character.isLowerCase(t.name().charAt(0)))
                name = t.name();
            else if (names.containsKey("$ledgerAll")) name = "org.julclang.ledger." + t.name();
            else {
                // A core module's type: say which import brings it in.
                String module = BUILTIN_TYPES.entrySet().stream().filter(e -> e.getValue().equals(t.name())
                        && !builtinTarget(t.name()).equals(t.name())).map(Map.Entry::getKey).findFirst().orElse(null);
                throw new UnknownType("ELCI2012: Type is not declared or imported in this module: " + t.name()
                        + (module == null ? "" : "; add `import " + module + " exposing (" + t.name() + ")`"),
                        module != null ? List.of() : typeHint(t.name(), names));
            }
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
    private String qualified(String n, Map<String, String> names, Token token) {
        int dot = n.lastIndexOf('.');
        if (dot <= 0 || !Character.isUpperCase(n.charAt(0))) return n;
        String module = n.substring(0, dot), name = n.substring(dot + 1);
        if (module.equals(current))
            throw new Diagnostic("ELCI2104", token, "A module names its own declarations without a qualifier; write " + name
                    + (names.containsKey(name) ? "" : ", once " + module + " declares it"));
        if (ELM_ALTERNATIVES.containsKey(n))
            throw new Diagnostic("ELCI2104", token, "Elci has no " + n + "; " + ELM_ALTERNATIVES.get(n));
        // The module is imported under another alias: `import Map as M`, then `Map.get`.
        // `Data` is the usual alias of Julc.Data.
        String real = module.equals("Data") ? DATA_MODULE : module;
        String target = INTRINSICS.containsKey(real) ? INTRINSICS.get(real).getKey() + name : real + "." + name;
        for (var entry : names.entrySet())
            if (entry.getValue().equals(target) && entry.getKey().endsWith("." + name)
                    && !entry.getKey().equals(n)) {
                String alias = entry.getKey().substring(0, entry.getKey().length() - name.length() - 1);
                throw new Diagnostic("ELCI2104", token, real + " is imported as " + alias + "; write " + alias + "." + name);
            }
        boolean imported = names.keySet().stream().anyMatch(k -> k.startsWith(module + "."));
        if (!imported && (INTRINSICS.containsKey(module) || CORE_MODULES.contains(module)))
            throw new Diagnostic("ELCI2104", token, n + " needs the " + module + " module; add `import " + module + "`");
        if (!imported && module.equals("Data"))
            throw new Diagnostic("ELCI2104", token, n + " needs the Julc.Data module; add `import Julc.Data as Data`");
        // A core module is always known: say what it has.
        var exports = new TreeSet<String>();
        if (INTRINSICS.containsKey(module)) exports.addAll(INTRINSICS.get(module).getValue());
        else if (CORE_MODULES.contains(module) && available.containsKey(module)) {
            var core = available.get(module);
            core.declarations().stream().map(Decl::name).filter(d -> exposes(core, d)).forEach(exports::add);
            if (module.equals("Basics")) exports.addAll(BASICS_FUNCTIONS);
        }
        if (!exports.isEmpty())
            throw new Diagnostic("ELCI2104", token, "Elci's " + module + " module has no " + name + ". It has "
                    + String.join(", ", exports), Suggestions.hint(n, exports.stream().map(e -> module + "." + e).toList()));
        throw new Diagnostic(
                "ELCI2104",
                token,
                imported ? "Module " + module + " does not expose '" + name + "'"
                        : available.containsKey(module) || javaModules.containsKey(module) || intrinsic(module)
                                ? "Module " + module + " is not imported; add `import " + module + "`"
                                : "No module " + module + " is imported, and none has that name",
                suggest(n, names, Set.of()));
    }

    /** @param hidden the root module's names, invisible to the other modules */
    private Expr expr(
            Expr e, Map<String, String> names, Set<String> bound, Map<String, String> types, Set<String> hidden) {
        return switch (e) {
            case Literal l -> l;
            case Name n -> {
                // Unresolved qualified names would otherwise alias internal flattened symbols.
                if (!bound.contains(n.name()) && !resolves(n.name(), names)) {
                    qualified(n.name(), names, n.token());
                    if (hidden.contains(n.name()))
                        throw new Diagnostic("ELCI2005", n.token(), "Unknown name '" + n.name() + "'", suggest(n.name(), names, bound));
                    var visible = Set.copyOf(bound);
                    nameHints.put(n.token(), () -> suggest(n.name(), names, visible));
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
                                    type(d.annotation(), types, d.token()),
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

    private Pattern pattern(Pattern p, Map<String, String> names, Set<String> bound, Set<String> hidden) {
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
                throw new Diagnostic("ELCI2001", p.token(), "Unknown constructor " + p.name() + " in this pattern",
                        suggest(p.name(), names, Set.of()));
            nameHints.put(p.token(), () -> suggest(p.name(), names, Set.of()));
        }
        name = unambiguous(name, p.token());
        return new Pattern(
                name,
                p.arguments().stream().map(a -> pattern(a, names, bound, hidden)).toList(),
                p.token());
    }
}
