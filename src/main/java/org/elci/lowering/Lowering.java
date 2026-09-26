package org.elci.lowering;

import static org.elci.syntax.Ast.*;
import static org.elci.types.Types.*;

import org.elci.syntax.*;
import org.elci.types.*;
import org.julclang.compiler.backend.Definition;
import org.julclang.compiler.backend.LibraryImports;
import org.julclang.compiler.backend.LibraryRequest;
import org.julclang.compiler.backend.LibraryScheme;
import org.julclang.compiler.backend.LibraryType;
import org.julclang.compiler.backend.PirLinker;
import org.julclang.compiler.backend.TypeReferences;
import org.elci.driver.Libraries;
import org.julclang.compiler.pir.*;
import org.julclang.core.*;

import java.math.BigInteger;
import java.util.*;

/** Demand specialization and strict, hygienic PIR construction. */
public final class Lowering {
    /**
     * How library implementations reach the program. {@link #INLINE} copies each closed export
     * at its use site (revision-1 function programs); {@link #LINK} references provider bindings
     * that the backend links once as trusted imports (revision-2 validators).
     */
    public enum ImportMode {
        INLINE,
        LINK
    }

    private final Inference inference;
    private final Map<String, Decl> definitions = new LinkedHashMap<>();
    private final Map<String, String> imports;
    private final Map<String, LibraryScheme> schemes;
    private final Libraries libraries;
    private final ImportMode importMode;
    private final LinkedHashMap<String, PirTerm> specialized = new LinkedHashMap<>();
    private final Map<String, PirType> specializedTypes = new HashMap<>();
    private final LinkedHashMap<LibraryRequest, LibraryImports.Binding> requested =
            new LinkedHashMap<>();
    private final Map<String, String> instances = new HashMap<>();
    private int fresh;
    private final Map<String, PirType> namedTypes = new LinkedHashMap<>();
    private final Set<String> planning = new HashSet<>();
    private final Deque<Map<String, LocalPoly>> localPolyScopes = new ArrayDeque<>();

    private record LocalPoly(
            Decl declaration,
            Map<String, PirTerm> captures,
            LinkedHashMap<String, PirTerm.Binding> instances) {}

    public Map<String, PirType> namedTypes() {
        return Map.copyOf(namedTypes);
    }

    /**
     * @param imports  imported source names and their provider symbols
     * @param schemes  generic provider exports by symbol, instantiated at each concrete use
     */
    public Lowering(
            List<Decl> declarations,
            Inference inference,
            Map<String, String> imports,
            Map<String, LibraryScheme> schemes,
            Libraries libraries,
            ImportMode importMode) {
        declarations.forEach(d -> definitions.put(d.name(), d));
        this.inference = inference;
        this.imports = imports;
        this.schemes = schemes;
        this.libraries = libraries;
        this.importMode = importMode;
        namedTypes.putAll(inference.importedRepresentations);
    }

    /** A closed revision-1 term: every specialized definition is linked around the entry. */
    public PirTerm lower(String entry, Ty type) {
        PirTerm root = reference(entry, type);
        return PirLinker.link(specialized, root);
    }

    /**
     * A closed property entry (ADR-006): {@code \d1 .. dn -> entry (decode d1) .. (decode dn)}.
     * Each argument arrives as Data and is decoded by Julc's strict decoder for its parameter
     * type, so generated values reach the property exactly as the ledger's would.
     */
    public PirTerm lowerProperty(String entry, Ty type, List<Ty> parameters, Token token) {
        PirTerm body = reference(entry, type);
        var names = new ArrayList<String>();
        for (var parameter : parameters) {
            String name = fresh("argument");
            names.add(name);
            body = new PirTerm.App(body, new PirTerm.App(strictDecoder(parameter, token), new PirTerm.Var(name, new PirType.DataType())));
        }
        for (int i = names.size() - 1; i >= 0; i--) body = new PirTerm.Lam(names.get(i), new PirType.DataType(), body);
        return PirLinker.link(specialized, body);
    }

    /**
     * Specialize {@code entry} at {@code type} as a program-level definition and return a
     * reference to it. The definition and its dependencies are available from
     * {@link #definitions()}.
     */
    public PirTerm.Var entry(String entry, Ty type) {
        return (PirTerm.Var) reference(entry, type);
    }

    /** Program-level definitions in encounter order, with their declared types. */
    public SequencedMap<String, Definition> definitions() {
        var result = new LinkedHashMap<String, Definition>();
        specialized.forEach(
                (name, term) -> result.put(name, new Definition(specializedTypes.get(name), term)));
        return result;
    }

    /**
     * The trusted import group for every library request made while lowering, materialized
     * together so shared private dependencies are linked once. Empty in {@link
     * ImportMode#INLINE}.
     */
    public List<LibraryImports> imports() {
        if (requested.isEmpty()) return List.of();
        var group = libraries.provider().materialize(List.copyOf(requested.keySet()));
        requested.forEach(
                (request, binding) -> {
                    if (!group.binding(request).equals(binding))
                        throw new IllegalStateException(
                                "Provider changed the binding of " + request.describe());
                });
        return List.of(group);
    }

    /**
     * Request a provider implementation and return a term that refers to it. A provider
     * rejection becomes a source diagnostic at the use.
     */
    private PirTerm library(LibraryRequest request, Token token) {
        if (importMode == ImportMode.INLINE && request instanceof LibraryRequest.Export export)
            return libraries.java().materialize(export.symbol());
        var binding = requested.get(request);
        LibraryImports group = null;
        if (binding == null) {
            libraries.require(request, token);
            try {
                group = libraries.provider().materialize(List.of(request));
            } catch (org.julclang.compiler.backend.BackendException e) {
                throw new Diagnostic("ELCI2105", token, "The library provider cannot supply " + request.describe()
                        + ": " + e.getMessage());
            }
            binding = group.binding(request);
            if (importMode == ImportMode.LINK) requested.put(request, binding);
        }
        var reference = new PirTerm.Var(binding.name(), binding.type());
        // Validators link every import once, evaluated when the script starts, exactly like
        // their top-level definitions (ADR-059). A closed copy cannot be placed inside
        // verified producer PIR: its provider binding names are reserved there.
        if (importMode == ImportMode.LINK) return reference;
        // A revision-1 program has no import groups: link a closed copy at the use site.
        return PirLinker.link(new LinkedHashMap<>(group.definitions()), reference);
    }

    private String fresh(String hint) {
        return "$elci$" + hint + "$" + (fresh++);
    }

    private PirTerm reference(String name, Ty concrete) {
        return reference(name, concrete, null);
    }

    /** {@code use} locates a failure at the reference rather than at the definition. */
    private PirTerm reference(String name, Ty concrete, Token use) {
        if (!Types.variables(concrete).isEmpty())
            throw new Diagnostic(
                    "ELCI3001",
                    use != null ? use : definitions.get(name).token(),
                    "Cannot specialize " + name + " at an unconstrained type " + Types.show(concrete)
                            + "; add a type annotation so the type is known here");
        try {
            return specialize(name, concrete);
        } catch (IllegalArgumentException e) {
            // A type Elci cannot represent, located at this reference (or the definition).
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            boolean data = message.contains("Data encoding") || message.contains("data fields")
                    || message.contains("serialized containers");
            throw new Diagnostic(data ? "ELCI2013" : "ELCI3001", use != null ? use : definitions.get(name).token(), message);
        } catch (Diagnostic d) {
            // A failure inside a bundled module is the caller's to fix, and so is one caused by a
            // type the call leaves unknown: report it at the call (the outermost such call wins).
            boolean unknown = d.code().equals("ELCI3001") && mentions(concrete, Types.AMBIGUOUS);
            if (use == null || bundled(use) || !unknown && !bundled(d.token())) throw d;
            throw new Diagnostic(d.code(), use, d.code().equals("ELCI3001")
                    ? "`" + use.text() + "` is used here at a type that is not known (" + Types.show(concrete)
                            + "); add a type annotation, for example to a let binding"
                    : "`" + use.text() + "`: " + d.getMessage());
        }
    }

    private static boolean mentions(Ty type, String name) {
        return prune(type) instanceof Con c && (c.name().equals(name) || c.args().stream().anyMatch(a -> mentions(a, name)));
    }

    /** Whether a token belongs to a module bundled with the compiler: the core or the test library. */
    private static boolean bundled(Token token) {
        return token.source() != null && (org.elci.modules.Modules.CORE_MODULES.contains(token.source())
                || Set.of("Expect", "Scenario", "Property").contains(token.source()));
    }

    private PirTerm specialize(String name, Ty concrete) {
        String key = name + ":" + concrete;
        if (instances.containsKey(key)) return new PirTerm.Var(instances.get(key), pir(concrete));
        if (instances.size() > 256)
            throw new Diagnostic(
                    "ELCI3002",
                    definitions.get(name).token(),
                    "Specialization limit exceeded; polymorphic recursion is unsupported");
        String symbol = fresh(name);
        instances.put(key, symbol);
        specialized.put(symbol, null);
        specializedTypes.put(symbol, pir(concrete));
        var d = definitions.get(name);
        var mapping = new HashMap<Var, Ty>();
        for (var v : inference.foreignVariables.getOrDefault(d, Set.of()))
            mapping.put(v, new Con(Types.AMBIGUOUS, List.of()));
        Ty instance = copy(inference.declarations.get(d), mapping);
        unify(instance, concrete, d.token());
        PirTerm body = declaration(d, instance, new HashMap<>(), mapping);
        specialized.put(symbol, body);
        return new PirTerm.Var(symbol, pir(concrete));
    }

    private PirTerm declaration(Decl d, Ty type, Map<String, PirTerm> env, Map<Var, Ty> mapping) {
        var local = new HashMap<>(env);
        var ps = new ArrayList<String>();
        var ts = new ArrayList<PirType>();
        Ty rest = prune(type);
        for (String p : d.parameters()) {
            var fn = (Con) prune(rest);
            PirType pt = pir(fn.args().get(0));
            String id = fresh(p);
            ps.add(id);
            ts.add(pt);
            local.put(p, new PirTerm.Var(id, pt));
            rest = fn.args().get(1);
        }
        PirTerm body = expr(d.body(), local, mapping);
        for (int i = ps.size() - 1; i >= 0; i--) body = new PirTerm.Lam(ps.get(i), ts.get(i), body);
        return body;
    }

    private Ty type(Expr e, Map<Var, Ty> map) {
        return copy(inference.expressions.get(e), map);
    }

    private PirTerm expr(Expr e, Map<String, PirTerm> env, Map<Var, Ty> map) {
        return switch (e) {
            case Field f -> projection(expr(f.record(), env, map), type(f.record(), map), f.name(), f.token());
            case RecordValue r -> {
                var recordType = (Con) prune(type(r, map));
                var names =
                        recordType.name().substring(7).isEmpty()
                                ? List.<String>of()
                                : Arrays.asList(recordType.name().substring(7).split(","));
                var temporaries = new LinkedHashMap<String, PirTerm>();
                var values = new LinkedHashMap<String, PirTerm>();
                PirTerm base = null;
                String baseName = null;
                if (r.base() != null) {
                    baseName = fresh("base");
                    base = new PirTerm.Var(baseName, pir(type(r.base(), map)));
                }
                for (var field : r.fields().entrySet()) {
                    String id = fresh(field.getKey());
                    var value = expr(field.getValue(), env, map);
                    temporaries.put(id, value);
                    values.put(
                            field.getKey(), new PirTerm.Var(id, pir(type(field.getValue(), map))));
                }
                var fields = new ArrayList<PirTerm>();
                for (String name : names)
                    fields.add(
                            values.containsKey(name)
                                    ? values.get(name)
                                    : projection(base, type(r, map), name, r.token()));
                PirTerm record = new PirTerm.DataConstr(0, pir(type(r, map)), fields);
                var bindings = new ArrayList<>(temporaries.entrySet());
                for (int i = bindings.size() - 1; i >= 0; i--)
                    record =
                            new PirTerm.Let(
                                    bindings.get(i).getKey(), bindings.get(i).getValue(), record);
                if (base != null)
                    record = new PirTerm.Let(baseName, expr(r.base(), env, map), record);
                yield record;
            }
            case Literal l ->
                    new PirTerm.Const(
                            l.value() == null
                                    ? Constant.unit()
                                    : l.value() instanceof Boolean b
                                            ? Constant.bool(b)
                                            : l.value() instanceof String s
                                                    ? Constant.string(s)
                                                    : Constant.integer((BigInteger) l.value()));
            case Name n -> {
                if (env.containsKey(n.name())) yield env.get(n.name());
                LocalPoly localPoly = localPoly(n.name());
                if (localPoly != null) {
                    Ty concrete = type(n, map);
                    String key = concrete.toString();
                    var existing = localPoly.instances().get(key);
                    if (existing == null) {
                        String symbol = fresh(localPoly.declaration().name());
                        var placeholder = new PirTerm.Binding(symbol, null);
                        localPoly.instances().put(key, placeholder);
                        // The enclosing specialization's variables stay bound in the local body.
                        var localMapping = new HashMap<Var, Ty>(map);
                        Ty instance =
                                copy(
                                        inference.declarations.get(localPoly.declaration()),
                                        localMapping);
                        unify(instance, concrete, localPoly.declaration().token());
                        var declaration =
                                declaration(
                                        localPoly.declaration(),
                                        instance,
                                        localPoly.captures(),
                                        localMapping);
                        existing = new PirTerm.Binding(symbol, declaration);
                        localPoly.instances().put(key, existing);
                    }
                    yield new PirTerm.Var(existing.name(), pir(concrete));
                }
                if (inference.constructors.containsKey(n.name())) {
                    var union = inference.constructors.get(n.name());
                    Ty rest = type(n, map);
                    var ids = new ArrayList<String>();
                    var pts = new ArrayList<PirType>();
                    var fields = new ArrayList<PirTerm>();
                    while (prune(rest) instanceof Con f && f.name().equals("->")) {
                        String id = fresh("field");
                        var pt = pir(f.args().get(0));
                        ids.add(id);
                        pts.add(pt);
                        fields.add(new PirTerm.Var(id, pt));
                        rest = f.args().get(1);
                    }
                    int tag = 0;
                    while (!union.variants().get(tag).name().equals(n.name())) tag++;
                    PirTerm value = new PirTerm.DataConstr(tag, pir(rest), fields);
                    for (int i = ids.size() - 1; i >= 0; i--)
                        value = new PirTerm.Lam(ids.get(i), pts.get(i), value);
                    yield value;
                }
                if (inference.aliasConstructors.containsKey(n.name())) {
                    var alias = inference.aliasConstructors.get(n.name());
                    Ty rest = type(n, map);
                    var ids = new ArrayList<String>();
                    var pts = new ArrayList<PirType>();
                    var supplied = new LinkedHashMap<String, PirTerm>();
                    int fieldIndex = 0;
                    while (prune(rest) instanceof Con f && f.name().equals("->")) {
                        String id = fresh("field");
                        var pt = pir(f.args().getFirst());
                        ids.add(id);
                        pts.add(pt);
                        supplied.put(
                                alias.constructorFields().get(fieldIndex++),
                                new PirTerm.Var(id, pt));
                        rest = f.args().get(1);
                    }
                    var record = (Con) prune(rest);
                    var fields = new ArrayList<PirTerm>();
                    for (String field : record.name().substring(7).split(","))
                        fields.add(supplied.get(field));
                    PirTerm value = new PirTerm.DataConstr(0, pir(rest), fields);
                    for (int i = ids.size() - 1; i >= 0; i--)
                        value = new PirTerm.Lam(ids.get(i), pts.get(i), value);
                    yield value;
                }
                if (inference.importedConstructors.containsKey(n.name())) {
                    var imported = inference.importedConstructors.get(n.name());
                    yield library(new LibraryRequest.Operation(imported.type().identity(),
                            LibraryType.Operation.CONSTRUCT,
                            imported.constructor() == null ? null : imported.constructor().name()), n.token());
                }
                if (n.name().equals(org.elci.modules.Modules.DEBUG_PREFIX + "trace")) {
                    // \message -> \value -> trace message value: evaluated strictly, like any call.
                    var fn = (Con) prune(type(n, map));
                    PirType valueType = pir(((Con) prune(fn.args().get(1))).args().get(0));
                    String message = fresh("message"), value = fresh("traced");
                    yield new PirTerm.Lam(message, new PirType.StringType(), new PirTerm.Lam(value, valueType,
                            new PirTerm.Trace(new PirTerm.Var(message, new PirType.StringType()),
                                    new PirTerm.Var(value, valueType))));
                }
                if (n.name().startsWith(org.elci.modules.Modules.ASSOC_PREFIX))
                    yield assocIntrinsic(n.name().substring(org.elci.modules.Modules.ASSOC_PREFIX.length()), type(n, map));
                if (n.name().startsWith(org.elci.modules.Modules.BYTES_PREFIX))
                    yield bytesIntrinsic(n.name().substring(org.elci.modules.Modules.BYTES_PREFIX.length()));
                if (n.name().startsWith(org.elci.modules.Modules.DATA_PREFIX))
                    yield dataIntrinsic(n.name().substring(org.elci.modules.Modules.DATA_PREFIX.length()), type(n, map), n.token());
                if (imports.containsKey(n.name())) {
                    String symbol = imports.get(n.name());
                    var scheme = schemes.get(symbol);
                    if (scheme == null) yield library(new LibraryRequest.Export(symbol), n.token());
                    yield library(instantiate(scheme, type(n, map), n.token()), n.token());
                }
                if (n.name().equals(org.elci.modules.Modules.BASICS_PREFIX + "not")) {
                    String p = fresh("not");
                    yield new PirTerm.Lam(
                            p,
                            new PirType.BoolType(),
                            new PirTerm.IfThenElse(
                                    new PirTerm.Var(p, new PirType.BoolType()),
                                    bool(false),
                                    bool(true)));
                }
                if (n.name().equals(org.elci.modules.Modules.BASICS_PREFIX + "remainderBy")
                        || n.name().equals(org.elci.modules.Modules.BASICS_PREFIX + "modBy")) {
                    // remainderBy takes the dividend's sign, modBy the divisor's (as in Elm).
                    var fun = n.name().endsWith("modBy") ? DefaultFun.ModInteger : DefaultFun.RemainderInteger;
                    String a = fresh("divisor"), b = fresh("value");
                    yield new PirTerm.Lam(
                            a,
                            new PirType.IntegerType(),
                            new PirTerm.Lam(
                                    b,
                                    new PirType.IntegerType(),
                                    call(
                                            fun,
                                            new PirTerm.Var(b, new PirType.IntegerType()),
                                            new PirTerm.Var(a, new PirType.IntegerType()))));
                }
                yield reference(n.name(), type(n, map), n.token());
            }
            case Apply a ->
                    new PirTerm.App(expr(a.function(), env, map), expr(a.argument(), env, map));
            case Lambda l -> {
                var local = new HashMap<>(env);
                var ids = new ArrayList<String>();
                var ts = new ArrayList<PirType>();
                Ty rest = type(l, map);
                for (String p : l.parameters()) {
                    var ft = (Con) prune(rest);
                    String id = fresh(p);
                    var pt = pir(ft.args().get(0));
                    ids.add(id);
                    ts.add(pt);
                    local.put(p, new PirTerm.Var(id, pt));
                    rest = ft.args().get(1);
                }
                var body = expr(l.body(), local, map);
                for (int i = ids.size() - 1; i >= 0; i--)
                    body = new PirTerm.Lam(ids.get(i), ts.get(i), body);
                yield body;
            }
            case If i ->
                    new PirTerm.IfThenElse(
                            expr(i.condition(), env, map),
                            expr(i.yes(), env, map),
                            expr(i.no(), env, map));
            case Binary b -> binary(b, env, map);
            case Let l -> {
                var local = new HashMap<>(env);
                var groups = Inference.groups(l.declarations());
                var polyScope = new LinkedHashMap<String, LocalPoly>();
                for (var d : l.declarations()) {
                    var scheme = inference.declarationSchemes.get(d);
                    if (scheme != null && !scheme.quantified().isEmpty())
                        polyScope.put(
                                d.name(),
                                new LocalPoly(d, new HashMap<>(local), new LinkedHashMap<>()));
                    else
                        local.put(
                                d.name(),
                                new PirTerm.Var(
                                        fresh(d.name()),
                                        pir(copy(inference.declarations.get(d), map))));
                }
                polyScope
                        .values()
                        .forEach(
                                p -> {
                                    p.captures().clear();
                                    p.captures().putAll(local);
                                });
                localPolyScopes.push(polyScope);
                PirTerm body;
                try {
                    body = expr(l.body(), local, map);
                    for (int gi = groups.size() - 1; gi >= 0; gi--) {
                        var group = groups.get(gi);
                        if (group.stream().anyMatch(d -> polyScope.containsKey(d.name()))) continue;
                        var bindings = new ArrayList<PirTerm.Binding>();
                        for (var d : group)
                            bindings.add(
                                    new PirTerm.Binding(
                                            ((PirTerm.Var) local.get(d.name())).name(),
                                            declaration(
                                                    d,
                                                    copy(inference.declarations.get(d), map),
                                                    local,
                                                    map)));
                        if (group.size() == 1
                                && group.getFirst().parameters().isEmpty()
                                && !(group.getFirst().body() instanceof Lambda))
                            body =
                                    new PirTerm.Let(
                                            bindings.getFirst().name(),
                                            bindings.getFirst().value(),
                                            body);
                        else body = new PirTerm.LetRec(bindings, body);
                    }
                    var polyBindings =
                            polyScope.values().stream()
                                    .flatMap(p -> p.instances().values().stream())
                                    .filter(b -> b.value() != null)
                                    .toList();
                    for (var binding : polyBindings)
                        body = new PirTerm.Let(binding.name(), binding.value(), body);
                } finally {
                    localPolyScopes.pop();
                }
                yield body;
            }
            case Items items -> {
                if (items.tuple())
                    yield new PirTerm.DataConstr(
                            0,
                            pir(type(items, map)),
                            items.elements().stream().map(item -> expr(item, env, map)).toList());
                Ty element = ((Con) prune(type(items, map))).args().getFirst();
                PirTerm list = call(DefaultFun.MkNilData, new PirTerm.Const(Constant.unit()));
                // Each cons evaluates head before tail, preserving source element order.
                for (int i = items.elements().size() - 1; i >= 0; i--)
                    list =
                            call(
                                    DefaultFun.MkCons,
                                    PirHelpers.wrapEncode(
                                            expr(items.elements().get(i), env, map), pir(element)),
                                    list);
                yield list;
            }
            case Case c -> {
                String name = fresh("case");
                PirType pt = pir(type(c.scrutinee(), map));
                var variable = new PirTerm.Var(name, pt);
                PirTerm body = new PirTerm.Error(pir(type(c, map)));
                for (int i = c.branches().size() - 1; i >= 0; i--) {
                    var branch = c.branches().get(i);
                    PirTerm fallback = body;
                    body =
                            match(
                                    branch.pattern(),
                                    variable,
                                    type(c.scrutinee(), map),
                                    new HashMap<>(env),
                                    local -> expr(branch.body(), local, map),
                                    fallback,
                                    map);
                }
                yield new PirTerm.Let(name, expr(c.scrutinee(), env, map), body);
            }
        };
    }

    private PirTerm match(
            Pattern p,
            PirTerm value,
            Ty type,
            Map<String, PirTerm> env,
            java.util.function.Function<Map<String, PirTerm>, PirTerm> success,
            PirTerm failure,
            Map<Var, Ty> mapping) {
        type = prune(type);
        if (p.name().equals("_")) return success.apply(env);
        if (patternVariable(p)) {
            var local = new HashMap<>(env);
            local.put(p.name(), value);
            return success.apply(local);
        }
        if (p.name().equals("$as")) {
            var local = new HashMap<>(env);
            local.put(p.arguments().get(1).name(), value);
            return match(p.arguments().getFirst(), value, type, local, success, failure, mapping);
        }
        if (p.name().equals("$unit")) return success.apply(env);
        if (p.name().equals("$tuple")) {
            var tuple = (Con) type;
            return matchDataFields(
                    p.arguments(), tuple.args(), value, env, success, failure, mapping);
        }
        if (p.name().equals("$record")) {
            var record = (Con) type;
            var names = Arrays.asList(record.name().substring(7).split(","));
            var fieldTypes = new ArrayList<Ty>();
            var indexes = new ArrayList<Integer>();
            for (var field : p.arguments()) {
                int index = names.indexOf(field.name());
                indexes.add(index);
                fieldTypes.add(record.args().get(index));
            }
            return matchDataFields(
                    p.arguments(), fieldTypes, value, indexes, env, success, failure, mapping);
        }
        if (p.name().equals("[]"))
            return new PirTerm.IfThenElse(
                    call(DefaultFun.NullList, value), success.apply(env), failure);
        if (p.name().equals("::")) {
            Ty listType = type;
            var list = (Con) listType;
            Ty element = list.args().getFirst();
            String head = fresh("head"), tail = fresh("tail"), decoded = fresh("decodedHead");
            var headValue = new PirTerm.Var(decoded, pir(element));
            var tailValue = new PirTerm.Var(tail, pir(listType));
            PirTerm cons =
                    match(
                            p.arguments().getFirst(),
                            headValue,
                            element,
                            env,
                            local ->
                                    match(
                                            p.arguments().get(1),
                                            tailValue,
                                            listType,
                                            local,
                                            success,
                                            failure,
                                            mapping),
                            failure,
                            mapping);
            cons =
                    new PirTerm.Let(
                            decoded,
                            PirHelpers.wrapDecode(
                                    new PirTerm.Var(head, new PirType.DataType()), pir(element)),
                            cons);
            return new PirTerm.ListMatch(value, head, tail, failure, cons);
        }
        if (inference.importedConstructors.containsKey(p.name())) {
            var imported = inference.importedConstructors.get(p.name());
            var fields = inference.constructorFields(imported);
            PirTerm selected = matchDataFields(p.arguments(), fields, value, env, success, failure, mapping);
            var actualTag = call(DefaultFun.FstPair, call(DefaultFun.UnConstrData, value));
            return new PirTerm.IfThenElse(
                    call(DefaultFun.EqualsInteger, actualTag,
                            new PirTerm.Const(Constant.integer(imported.constructor().tag()))),
                    selected,
                    failure);
        }
        if (inference.constructors.containsKey(p.name())) {
            var source = (Con) type;
            var union = inference.constructors.get(p.name());
            int tag = 0;
            while (!union.variants().get(tag).name().equals(p.name())) tag++;
            var variant = union.variants().get(tag);
            var variables = new HashMap<String, Ty>();
            for (int i = 0; i < union.parameters().size(); i++)
                variables.put(union.parameters().get(i), source.args().get(i));
            var fields =
                    variant.fields().stream()
                            .map(t -> inference.annotatedPublic(t, variables))
                            .toList();
            PirTerm selected =
                    matchDataFields(p.arguments(), fields, value, env, success, failure, mapping);
            var actualTag = call(DefaultFun.FstPair, call(DefaultFun.UnConstrData, value));
            return new PirTerm.IfThenElse(
                    call(
                            DefaultFun.EqualsInteger,
                            actualTag,
                            new PirTerm.Const(Constant.integer(tag))),
                    selected,
                    failure);
        }
        PirTerm test;
        if (p.token().kind() == Token.Kind.STRING)
            test =
                    call(
                            DefaultFun.EqualsString,
                            value,
                            new PirTerm.Const(Constant.string(p.name().substring(1, p.name().length() - 1))));
        else if (p.name().equals("True")) test = value;
        else if (p.name().equals("False"))
            test = new PirTerm.IfThenElse(value, bool(false), bool(true));
        else
            test =
                    call(
                            DefaultFun.EqualsInteger,
                            value,
                            new PirTerm.Const(Constant.integer(new BigInteger(p.name()))));
        return new PirTerm.IfThenElse(test, success.apply(env), failure);
    }

    private PirTerm matchDataFields(
            List<Pattern> patterns,
            List<Ty> types,
            PirTerm value,
            Map<String, PirTerm> env,
            java.util.function.Function<Map<String, PirTerm>, PirTerm> success,
            PirTerm failure,
            Map<Var, Ty> mapping) {
        var indexes = new ArrayList<Integer>();
        for (int i = 0; i < patterns.size(); i++) indexes.add(i);
        return matchDataFields(patterns, types, value, indexes, env, success, failure, mapping);
    }

    private PirTerm matchDataFields(
            List<Pattern> patterns,
            List<Ty> types,
            PirTerm value,
            List<Integer> indexes,
            Map<String, PirTerm> env,
            java.util.function.Function<Map<String, PirTerm>, PirTerm> success,
            PirTerm failure,
            Map<Var, Ty> mapping) {
        return matchDataField(0, patterns, types, value, indexes, env, success, failure, mapping);
    }

    private PirTerm matchDataField(
            int at,
            List<Pattern> patterns,
            List<Ty> types,
            PirTerm value,
            List<Integer> indexes,
            Map<String, PirTerm> env,
            java.util.function.Function<Map<String, PirTerm>, PirTerm> success,
            PirTerm failure,
            Map<Var, Ty> mapping) {
        if (at == patterns.size()) return success.apply(env);
        PirTerm fields = call(DefaultFun.SndPair, call(DefaultFun.UnConstrData, value));
        for (int i = 0; i < indexes.get(at); i++) fields = call(DefaultFun.TailList, fields);
        Ty fieldType = types.get(at);
        String id = fresh("patternField");
        var fieldValue = new PirTerm.Var(id, pir(fieldType));
        PirTerm rest =
                match(
                        patterns.get(at),
                        fieldValue,
                        fieldType,
                        env,
                        local ->
                                matchDataField(
                                        at + 1, patterns, types, value, indexes, local, success,
                                        failure, mapping),
                        failure,
                        mapping);
        return new PirTerm.Let(
                id, PirHelpers.wrapDecode(call(DefaultFun.HeadList, fields), pir(fieldType)), rest);
    }

    private static boolean patternVariable(Pattern p) {
        return p.token().kind() == Token.Kind.NAME
                && !p.name().isEmpty()
                && Character.isLowerCase(p.name().charAt(0));
    }

    private LocalPoly localPoly(String name) {
        for (var scope : localPolyScopes) if (scope.containsKey(name)) return scope.get(name);
        return null;
    }

    private PirTerm binary(Binary b, Map<String, PirTerm> env, Map<Var, Ty> map) {
        var a = expr(b.left(), env, map);
        var z = expr(b.right(), env, map);
        if (b.op().equals("&&")) return new PirTerm.IfThenElse(a, z, bool(false));
        if (b.op().equals("||")) return new PirTerm.IfThenElse(a, bool(true), z);
        if (b.op().equals("<|")) return new PirTerm.App(a, z);
        if (b.op().equals("|>")) {
            // Pipe's source left operand is evaluated before the right function expression.
            String id = fresh("pipe");
            return new PirTerm.Let(
                    id, a, new PirTerm.App(z, new PirTerm.Var(id, pir(type(b.left(), map)))));
        }
        if (b.op().equals("++")) {
            var type = type(b.left(), map);
            if (((Con) prune(type)).name().equals("String"))
                return call(DefaultFun.AppendString, a, z);
            return new PirTerm.App(
                    new PirTerm.App(reference("List.append", fn(type, fn(type, type))), a), z);
        }
        if (b.op().equals(">>") || b.op().equals("<<")) {
            String left = fresh("composeLeft"),
                    right = fresh("composeRight"),
                    input = fresh("input");
            var lt = pir(type(b.left(), map));
            var rt = pir(type(b.right(), map));
            var ft = (PirType.FunType) pir(type(b, map));
            var lv = new PirTerm.Var(left, lt);
            var rv = new PirTerm.Var(right, rt);
            var iv = new PirTerm.Var(input, ft.paramType());
            PirTerm body =
                    b.op().equals(">>")
                            ? new PirTerm.App(rv, new PirTerm.App(lv, iv))
                            : new PirTerm.App(lv, new PirTerm.App(rv, iv));
            return new PirTerm.Let(
                    left,
                    a,
                    new PirTerm.Let(right, z, new PirTerm.Lam(input, ft.paramType(), body)));
        }
        if (b.op().equals("::"))
            return call(DefaultFun.MkCons, PirHelpers.wrapEncode(a, pir(type(b.left(), map))), z);
        if (Set.of("==", "/=").contains(b.op())) {
            var t = (Con) prune(type(b.left(), map));
            String x = fresh("equalityLeft"), y = fresh("equalityRight");
            var pt = pir(t);
            var xv = new PirTerm.Var(x, pt);
            var yv = new PirTerm.Var(y, pt);
            PirTerm comparison;
            if (t.name().equals("Bool"))
                comparison =
                        new PirTerm.IfThenElse(
                                xv, yv, new PirTerm.IfThenElse(yv, bool(false), bool(true)));
            else if (t.name().equals("Unit")) comparison = bool(true);
            else if (t.name().equals("String")) comparison = call(DefaultFun.EqualsString, xv, yv);
            else if (inference.byteEquality(t.name()))
                comparison = call(DefaultFun.EqualsByteString, xv, yv);
            else if (t.name().equals("Int")) comparison = call(DefaultFun.EqualsInteger, xv, yv);
            else if (inference.importedRepresentations.containsKey(t.name()))
                // The provider's approved equality: Java equals semantics for the representation.
                comparison = new PirTerm.App(new PirTerm.App(library(new LibraryRequest.Operation(
                        inference.importedTypes.get(t.name()).identity(), LibraryType.Operation.EQUALS), b.token()), xv), yv);
            else
                comparison =
                        call(
                                DefaultFun.EqualsData,
                                PirHelpers.wrapEncode(xv, pt),
                                PirHelpers.wrapEncode(yv, pt));
            PirTerm eq = new PirTerm.Let(x, a, new PirTerm.Let(y, z, comparison));
            return b.op().equals("/=") ? new PirTerm.IfThenElse(eq, bool(false), bool(true)) : eq;
        }
        if (b.op().equals("^")) return new PirTerm.App(new PirTerm.App(power(), a), z);
        if (Set.of("<", "<=", ">", ">=").contains(b.op())) {
            var t = (Con) prune(type(b.left(), map));
            // Int keeps its builtins below, so existing scripts are unchanged.
            if (!t.name().equals("Int")) return ordering(b.op(), t, a, z);
        }
        DefaultFun op =
                switch (b.op()) {
                    case "+" -> DefaultFun.AddInteger;
                    case "-" -> DefaultFun.SubtractInteger;
                    case "*" -> DefaultFun.MultiplyInteger;
                    case "//" -> DefaultFun.QuotientInteger;
                    case "<", ">=" -> DefaultFun.LessThanInteger;
                    case "<=", ">" -> DefaultFun.LessThanEqualsInteger;
                    default ->
                            throw new Diagnostic(
                                    "ELCI3001", b.token(), "Unsupported operator " + b.op());
                };
        var result = call(op, a, z);
        return b.op().equals(">") || b.op().equals(">=")
                ? new PirTerm.IfThenElse(result, bool(false), bool(true))
                : result;
    }

    /**
     * An ordering operator on a comparable type other than Int (ADR-008): the operands are
     * evaluated left to right once, then compared. {@code a > b} is {@code b < a}.
     */
    private PirTerm ordering(String op, Ty type, PirTerm left, PirTerm right) {
        String x = fresh("orderLeft"), y = fresh("orderRight");
        var pt = pir(type);
        var xv = new PirTerm.Var(x, pt);
        var yv = new PirTerm.Var(y, pt);
        PirTerm result = switch (op) {
            case "<" -> less(type, xv, yv, true);
            case "<=" -> less(type, xv, yv, false);
            case ">" -> less(type, yv, xv, true);
            default -> less(type, yv, xv, false);
        };
        return new PirTerm.Let(x, left, new PirTerm.Let(y, right, result));
    }

    /** {@code x < y} (strict) or {@code x <= y} for comparable {@code type}; x and y are variables. */
    private PirTerm less(Ty type, PirTerm x, PirTerm y, boolean strict) {
        var c = (Con) prune(type);
        if (c.name().equals("Int"))
            return call(strict ? DefaultFun.LessThanInteger : DefaultFun.LessThanEqualsInteger, x, y);
        if (inference.orderedBytes(c.name()))
            return call(strict ? DefaultFun.LessThanByteString : DefaultFun.LessThanEqualsByteString, x, y);
        // No value of an ambiguous type exists, so this comparison is never evaluated.
        if (c.name().equals(Types.AMBIGUOUS)) return new PirTerm.Error(new PirType.BoolType());
        // Strings order by their UTF-8 bytes, which is Unicode code point order.
        if (c.name().equals("String"))
            return call(strict ? DefaultFun.LessThanByteString : DefaultFun.LessThanEqualsByteString,
                    call(DefaultFun.EncodeUtf8, x), call(DefaultFun.EncodeUtf8, y));
        if (c.name().equals("List") || c.name().equals("Tuple"))
            return new PirTerm.App(new PirTerm.App(orderingHelper(c, strict), x), y);
        throw new IllegalArgumentException("Not a comparable type: " + Types.show(type));
    }

    /**
     * A shared definition {@code t -> t -> Bool} ordering lists (lexicographically, a proper prefix
     * first) or tuples (by component), generated once per concrete type and strictness.
     */
    private PirTerm orderingHelper(Con type, boolean strict) {
        String key = (strict ? "$less:" : "$lessOrEqual:") + type;
        var pt = pir(type);
        var fnType = new PirType.FunType(pt, new PirType.FunType(pt, new PirType.BoolType()));
        if (instances.containsKey(key)) return new PirTerm.Var(instances.get(key), fnType);
        String symbol = fresh(strict ? "less" : "lessOrEqual");
        instances.put(key, symbol);
        specialized.put(symbol, null);
        specializedTypes.put(symbol, fnType);
        String xs = fresh("left"), ys = fresh("right");
        var xv = new PirTerm.Var(xs, pt);
        var yv = new PirTerm.Var(ys, pt);
        PirTerm body;
        if (type.name().equals("List")) {
            var element = type.args().getFirst();
            var et = pir(element);
            String hx = fresh("leftHead"), hy = fresh("rightHead");
            var hxv = new PirTerm.Var(hx, et);
            var hyv = new PirTerm.Var(hy, et);
            PirTerm rest = new PirTerm.App(new PirTerm.App(new PirTerm.Var(symbol, fnType),
                    call(DefaultFun.TailList, xv)), call(DefaultFun.TailList, yv));
            // Equal heads (neither is less) continue with the tails.
            PirTerm heads = new PirTerm.Let(hx, PirHelpers.wrapDecode(call(DefaultFun.HeadList, xv), et),
                    new PirTerm.Let(hy, PirHelpers.wrapDecode(call(DefaultFun.HeadList, yv), et),
                            new PirTerm.IfThenElse(less(element, hxv, hyv, true), bool(true),
                                    new PirTerm.IfThenElse(less(element, hyv, hxv, true), bool(false), rest))));
            body = strict
                    ? new PirTerm.IfThenElse(call(DefaultFun.NullList, yv), bool(false),
                            new PirTerm.IfThenElse(call(DefaultFun.NullList, xv), bool(true), heads))
                    : new PirTerm.IfThenElse(call(DefaultFun.NullList, xv), bool(true),
                            new PirTerm.IfThenElse(call(DefaultFun.NullList, yv), bool(false), heads));
        } else {
            var components = type.args();
            var lefts = new ArrayList<PirTerm.Var>();
            var rights = new ArrayList<PirTerm.Var>();
            var bindings = new ArrayList<Map.Entry<String, PirTerm>>();
            for (int i = 0; i < components.size(); i++) {
                var ct = pir(components.get(i));
                String l = fresh("leftItem"), r = fresh("rightItem");
                lefts.add(new PirTerm.Var(l, ct));
                rights.add(new PirTerm.Var(r, ct));
                bindings.add(Map.entry(l, component(xv, i, ct)));
                bindings.add(Map.entry(r, component(yv, i, ct)));
            }
            // Lexicographic: the last component decides with the requested strictness.
            int last = components.size() - 1;
            body = less(components.get(last), lefts.get(last), rights.get(last), strict);
            for (int i = last - 1; i >= 0; i--)
                body = new PirTerm.IfThenElse(less(components.get(i), lefts.get(i), rights.get(i), true), bool(true),
                        new PirTerm.IfThenElse(less(components.get(i), rights.get(i), lefts.get(i), true), bool(false), body));
            for (int i = bindings.size() - 1; i >= 0; i--)
                body = new PirTerm.Let(bindings.get(i).getKey(), bindings.get(i).getValue(), body);
        }
        var definition = new PirTerm.Lam(xs, pt, new PirTerm.Lam(ys, pt, body));
        specialized.put(symbol, definition);
        return new PirTerm.Var(symbol, fnType);
    }

    /**
     * The shared definition of {@code base ^ exponent} (ADR-008): exponentiation by squaring, in
     * O(log exponent) multiplications. A negative exponent fails evaluation, where Elm would
     * produce a fraction.
     */
    private PirTerm power() {
        var integer = new PirType.IntegerType();
        var fnType = new PirType.FunType(integer, new PirType.FunType(integer, integer));
        if (instances.containsKey("$power")) return new PirTerm.Var(instances.get("$power"), fnType);
        String symbol = fresh("power");
        instances.put("$power", symbol);
        specialized.put(symbol, null);
        specializedTypes.put(symbol, fnType);
        String base = fresh("base"), exponent = fresh("exponent"), half = fresh("half"), square = fresh("square");
        var bv = new PirTerm.Var(base, integer);
        var ev = new PirTerm.Var(exponent, integer);
        var sv = new PirTerm.Var(square, integer);
        var hv = new PirTerm.Var(half, integer);
        PirTerm zero = new PirTerm.Const(Constant.integer(BigInteger.ZERO));
        PirTerm two = new PirTerm.Const(Constant.integer(BigInteger.TWO));
        PirTerm squared = new PirTerm.Let(half,
                new PirTerm.App(new PirTerm.App(new PirTerm.Var(symbol, fnType), bv), call(DefaultFun.QuotientInteger, ev, two)),
                new PirTerm.Let(square, call(DefaultFun.MultiplyInteger, hv, hv),
                        new PirTerm.IfThenElse(call(DefaultFun.EqualsInteger, call(DefaultFun.RemainderInteger, ev, two), zero),
                                sv, call(DefaultFun.MultiplyInteger, sv, bv))));
        PirTerm body = new PirTerm.IfThenElse(call(DefaultFun.LessThanInteger, ev, zero), new PirTerm.Error(integer),
                new PirTerm.IfThenElse(call(DefaultFun.EqualsInteger, ev, zero),
                        new PirTerm.Const(Constant.integer(BigInteger.ONE)), squared));
        specialized.put(symbol, new PirTerm.Lam(base, integer, new PirTerm.Lam(exponent, integer, body)));
        return new PirTerm.Var(symbol, fnType);
    }

    // ----- Elci.Assoc (ADR-009): association lists as Plutus maps, walked pair by pair. -----

    /**
     * An {@code Elci.Assoc} function at its concrete use type.
     * <ul>
     *   <li>{@code find}, {@code has}, {@code delete} compare keys by their Data encoding, as
     *       {@code Map} does;</li>
     *   <li>{@code get}, {@code member}, {@code insert}, {@code remove} assume ascending keys and
     *       stop at the first key that is not smaller, comparing with the {@code comparable}
     *       ordering;</li>
     *   <li>{@code runs}, {@code merge} and {@code dedupe} sort: ascending runs, a stable merge,
     *       and the last of equal adjacent keys.</li>
     * </ul>
     */
    private PirTerm assocIntrinsic(String function, Ty use) {
        var parts = new ArrayList<Ty>();
        Ty rest = prune(use);
        while (rest instanceof Con c && c.name().equals("->")) {
            parts.add(c.args().get(0));
            rest = prune(c.args().get(1));
        }
        parts.add(rest);
        // The key and value types come from the map at the function's map position: keys and
        // values may themselves be maps. `fromDistinct` has its map as its result.
        int position = switch (function) {
            case "find", "has", "delete", "get", "member", "remove" -> 1;
            case "cons", "insert" -> 2;
            case "fromDistinct" -> parts.size() - 1;
            default -> 0;
        };
        var map = (Con) prune(parts.get(position));
        Ty k = map.args().get(0), v = map.args().get(1);
        var pairs = pir(con("Map", k, v));
        return switch (function) {
            case "toMap", "fromMap" -> {
                String x = fresh("map");
                yield new PirTerm.Lam(x, pairs, new PirTerm.Var(x, pairs));
            }
            case "cons" -> {
                String key = fresh("key"), value = fresh("value"), tail = fresh("pairs");
                yield new PirTerm.Lam(key, pir(k), new PirTerm.Lam(value, pir(v), new PirTerm.Lam(tail, pairs,
                        call(DefaultFun.MkCons, pair(new PirTerm.Var(key, pir(k)), k, new PirTerm.Var(value, pir(v)), v),
                                new PirTerm.Var(tail, pairs)))));
            }
            case "toList" -> assocToList(k, v);
            case "fromDistinct" -> assocFromDistinct(k, v);
            case "find", "has" -> assocFind(k, v, function.equals("find"));
            case "delete" -> assocDelete(k, v);
            case "get", "member" -> assocLookup(k, v, function.equals("get"));
            case "insert" -> assocInsert(k, v);
            case "remove" -> assocRemove(k, v);
            case "ascending" -> assocAscending(k, v);
            case "runs" -> assocRuns(k, v);
            case "reverse" -> assocReverse(k, v);
            case "merge" -> assocMerge(k, v);
            case "dedupe" -> assocDedupe(k, v);
            default -> throw new IllegalArgumentException("Unknown Elci.Assoc function " + function);
        };
    }

    /** {@code (key, value)} as a Plutus map entry. */
    private PirTerm pair(PirTerm key, Ty keyType, PirTerm value, Ty valueType) {
        return call(DefaultFun.MkPairData, PirHelpers.wrapEncode(key, pir(keyType)), PirHelpers.wrapEncode(value, pir(valueType)));
    }

    /**
     * A shared definition, generated once per key: {@code build} receives the definition's own
     * variable, for recursion, and returns its body.
     */
    private PirTerm shared(String key, String hint, PirType type, java.util.function.Function<PirTerm.Var, PirTerm> build) {
        if (instances.containsKey(key)) return new PirTerm.Var(instances.get(key), type);
        String symbol = fresh(hint);
        instances.put(key, symbol);
        specialized.put(symbol, null);
        specializedTypes.put(symbol, type);
        specialized.put(symbol, build.apply(new PirTerm.Var(symbol, type)));
        return new PirTerm.Var(symbol, type);
    }

    /**
     * {@code letrec go = \\p1 .. pn -> body in go a1 .. an}: a local loop, so arguments that stay
     * the same, such as the key searched for, are captured instead of passed at every step.
     */
    private PirTerm loop(String hint, List<String> parameters, List<PirType> types, PirType result,
                         java.util.function.Function<PirTerm.Var, PirTerm> body, List<PirTerm> arguments) {
        String go = fresh(hint);
        var goType = arrow(java.util.stream.Stream.concat(types.stream(), java.util.stream.Stream.of(result))
                .toArray(PirType[]::new));
        var self = new PirTerm.Var(go, goType);
        PirTerm lambda = body.apply(self);
        for (int i = parameters.size() - 1; i >= 0; i--) lambda = new PirTerm.Lam(parameters.get(i), types.get(i), lambda);
        PirTerm call = self;
        for (var argument : arguments) call = new PirTerm.App(call, argument);
        return new PirTerm.LetRec(List.of(new PirTerm.Binding(go, lambda)), call);
    }

    private static PirType arrow(PirType... types) {
        PirType result = types[types.length - 1];
        for (int i = types.length - 2; i >= 0; i--) result = new PirType.FunType(types[i], result);
        return result;
    }

    private static final PirType ENTRY = new PirType.PairType(new PirType.DataType(), new PirType.DataType());

    // Keys compare at their cheapest faithful form. EqualsData has a large fixed cost, so Int and
    // byte-string keys compare with their own builtins, and String keys by their UTF-8 bytes,
    // which is both their order and their encoding: a key is never decoded to text.

    /** The type keys are ordered at: a String key orders as its UTF-8 bytes. */
    private Ty orderType(Ty k) {
        return ((Con) prune(k)).name().equals("String") ? con("Bytes") : k;
    }

    /** A map entry's Data key, ready to order. */
    private PirTerm orderKey(Ty k, PirTerm data) {
        return ((Con) prune(k)).name().equals("String") ? call(DefaultFun.UnBData, data) : PirHelpers.wrapDecode(data, pir(k));
    }

    /** A searched key, ready to order. */
    private PirTerm orderSearch(Ty k, PirTerm search) {
        return ((Con) prune(k)).name().equals("String") ? call(DefaultFun.EncodeUtf8, search) : search;
    }

    private enum KeyForm { INTEGER, BYTES, UTF8, DATA }

    private KeyForm keyForm(Ty k) {
        String name = ((Con) prune(k)).name();
        if (name.equals("Int")) return KeyForm.INTEGER;
        if (name.equals("String")) return KeyForm.UTF8;
        if (inference.byteEquality(name)) return KeyForm.BYTES;
        return KeyForm.DATA;
    }

    /** A searched key in the form {@link #keyMatches} compares against. */
    private PirTerm matchTarget(Ty k, PirTerm search) {
        return switch (keyForm(k)) {
            case INTEGER, BYTES -> search;
            case UTF8 -> call(DefaultFun.EncodeUtf8, search);
            case DATA -> PirHelpers.wrapEncode(search, pir(k));
        };
    }

    private PirType matchTargetType(Ty k) {
        return switch (keyForm(k)) {
            case INTEGER -> new PirType.IntegerType();
            case BYTES, UTF8 -> new PirType.ByteStringType();
            case DATA -> new PirType.DataType();
        };
    }

    /** Whether a map entry's Data key equals a target from {@link #matchTarget}. */
    private PirTerm keyMatches(Ty k, PirTerm data, PirTerm target) {
        return switch (keyForm(k)) {
            case INTEGER -> call(DefaultFun.EqualsInteger, call(DefaultFun.UnIData, data), target);
            case BYTES, UTF8 -> call(DefaultFun.EqualsByteString, call(DefaultFun.UnBData, data), target);
            case DATA -> call(DefaultFun.EqualsData, data, target);
        };
    }

    /** Whether two entries' Data keys are equal. */
    private PirTerm keysEqual(Ty k, PirTerm left, PirTerm right) {
        return switch (keyForm(k)) {
            case INTEGER -> call(DefaultFun.EqualsInteger, call(DefaultFun.UnIData, left), call(DefaultFun.UnIData, right));
            case BYTES, UTF8 -> call(DefaultFun.EqualsByteString, call(DefaultFun.UnBData, left), call(DefaultFun.UnBData, right));
            case DATA -> call(DefaultFun.EqualsData, left, right);
        };
    }

    private static PirTerm unit() {
        return new PirTerm.Const(Constant.unit());
    }

    /** The pairs as a list of tuples, in order. */
    private PirTerm assocToList(Ty k, Ty v) {
        var pairs = pir(con("Map", k, v));
        var list = pir(con("List", con("Tuple", k, v)));
        return shared("$assoc.toList:" + k + "," + v, "assocToList", arrow(pairs, list), self -> {
            String xs = fresh("pairs"), head = fresh("pair");
            var xv = new PirTerm.Var(xs, pairs);
            var hv = new PirTerm.Var(head, ENTRY);
            PirTerm nil = call(DefaultFun.MkNilData, unit());
            PirTerm fields = call(DefaultFun.MkCons, call(DefaultFun.FstPair, hv),
                    call(DefaultFun.MkCons, call(DefaultFun.SndPair, hv), nil));
            PirTerm tuple = call(DefaultFun.ConstrData, new PirTerm.Const(Constant.integer(BigInteger.ZERO)), fields);
            PirTerm body = new PirTerm.IfThenElse(call(DefaultFun.NullList, xv), nil,
                    new PirTerm.Let(head, call(DefaultFun.HeadList, xv),
                            call(DefaultFun.MkCons, tuple, new PirTerm.App(self, call(DefaultFun.TailList, xv)))));
            return new PirTerm.Lam(xs, pairs, body);
        });
    }

    /** Tuples as pairs, in the same order; the caller vouches for the keys. */
    private PirTerm assocFromDistinct(Ty k, Ty v) {
        var pairs = pir(con("Map", k, v));
        var list = pir(con("List", con("Tuple", k, v)));
        return shared("$assoc.fromDistinct:" + k + "," + v, "assocFromDistinct", arrow(list, pairs), self -> {
            String xs = fresh("tuples"), fields = fresh("fields");
            var xv = new PirTerm.Var(xs, list);
            var fv = new PirTerm.Var(fields, new PirType.ListType(new PirType.DataType()));
            PirTerm entry = call(DefaultFun.MkPairData, call(DefaultFun.HeadList, fv),
                    call(DefaultFun.HeadList, call(DefaultFun.TailList, fv)));
            PirTerm body = new PirTerm.IfThenElse(call(DefaultFun.NullList, xv), call(DefaultFun.MkNilPairData, unit()),
                    new PirTerm.Let(fields, call(DefaultFun.SndPair, call(DefaultFun.UnConstrData, call(DefaultFun.HeadList, xv))),
                            call(DefaultFun.MkCons, entry, new PirTerm.App(self, call(DefaultFun.TailList, xv)))));
            return new PirTerm.Lam(xs, list, body);
        });
    }

    /** {@code find} (Just the value) or {@code has}: the first pair whose key's Data equals the key's. */
    private PirTerm assocFind(Ty k, Ty v, boolean value) {
        var pairs = pir(con("Map", k, v));
        var result = value ? pir(con("Elci.Maybe", v)) : new PirType.BoolType();
        return shared("$assoc." + (value ? "find:" : "has:") + k + "," + v, value ? "assocFind" : "assocHas",
                arrow(pir(k), pairs, result), self -> {
            String search = fresh("search"), xs = fresh("pairs"), target = fresh("target"), rest = fresh("rest"),
                    head = fresh("pair");
            var tv = new PirTerm.Var(target, matchTargetType(k));
            var rv = new PirTerm.Var(rest, pairs);
            var hv = new PirTerm.Var(head, ENTRY);
            PirTerm absent = value ? new PirTerm.DataConstr(0, result, List.of()) : bool(false);
            PirTerm present = value
                    ? new PirTerm.DataConstr(1, result, List.of(PirHelpers.wrapDecode(call(DefaultFun.SndPair, hv), pir(v))))
                    : bool(true);
            PirTerm scan = loop("scan", List.of(rest), List.of(pairs), result, go ->
                    new PirTerm.IfThenElse(call(DefaultFun.NullList, rv), absent,
                            new PirTerm.Let(head, call(DefaultFun.HeadList, rv),
                                    new PirTerm.IfThenElse(keyMatches(k, call(DefaultFun.FstPair, hv), tv), present,
                                            new PirTerm.App(go, call(DefaultFun.TailList, rv))))),
                    List.of(new PirTerm.Var(xs, pairs)));
            return new PirTerm.Lam(search, pir(k), new PirTerm.Lam(xs, pairs,
                    new PirTerm.Let(target, matchTarget(k, new PirTerm.Var(search, pir(k))), scan)));
        });
    }

    /**
     * The pairs without any whose key's Data equals the key's: a map decoded from Data may repeat
     * a key, and after {@code remove} it must be absent.
     */
    private PirTerm assocDelete(Ty k, Ty v) {
        var pairs = pir(con("Map", k, v));
        return shared("$assoc.delete:" + k + "," + v, "assocDelete", arrow(pir(k), pairs, pairs), self -> {
            String search = fresh("search"), xs = fresh("pairs"), target = fresh("target"), rest = fresh("rest"),
                    head = fresh("pair");
            var tv = new PirTerm.Var(target, matchTargetType(k));
            var rv = new PirTerm.Var(rest, pairs);
            var hv = new PirTerm.Var(head, ENTRY);
            PirTerm scan = loop("scan", List.of(rest), List.of(pairs), pairs, go ->
                    new PirTerm.IfThenElse(call(DefaultFun.NullList, rv), rv,
                            new PirTerm.Let(head, call(DefaultFun.HeadList, rv),
                                    new PirTerm.IfThenElse(keyMatches(k, call(DefaultFun.FstPair, hv), tv),
                                            new PirTerm.App(go, call(DefaultFun.TailList, rv)),
                                            call(DefaultFun.MkCons, hv, new PirTerm.App(go, call(DefaultFun.TailList, rv)))))),
                    List.of(new PirTerm.Var(xs, pairs)));
            return new PirTerm.Lam(search, pir(k), new PirTerm.Lam(xs, pairs,
                    new PirTerm.Let(target, matchTarget(k, new PirTerm.Var(search, pir(k))), scan)));
        });
    }

    /** {@code get} (Just the value) or {@code member} on ascending keys. */
    private PirTerm assocLookup(Ty k, Ty v, boolean value) {
        var pairs = pir(con("Map", k, v));
        var result = value ? pir(con("Elci.Maybe", v)) : new PirType.BoolType();
        return shared("$assoc." + (value ? "get:" : "member:") + k + "," + v, value ? "assocGet" : "assocMember",
                arrow(pir(k), pairs, result), self -> {
            String search = fresh("search"), xs = fresh("pairs"), rest = fresh("rest"), head = fresh("pair"), key = fresh("key"),
                    ordered = fresh("ordered");
            var sv = new PirTerm.Var(search, pir(k));
            var ov = new PirTerm.Var(ordered, pir(orderType(k)));
            var rv = new PirTerm.Var(rest, pairs);
            var hv = new PirTerm.Var(head, ENTRY);
            var kv = new PirTerm.Var(key, pir(orderType(k)));
            PirTerm absent = value ? new PirTerm.DataConstr(0, result, List.of()) : bool(false);
            PirTerm present = value
                    ? new PirTerm.DataConstr(1, result, List.of(PirHelpers.wrapDecode(call(DefaultFun.SndPair, hv), pir(v))))
                    : bool(true);
            PirTerm scan = loop("scan", List.of(rest), List.of(pairs), result, go ->
                    new PirTerm.IfThenElse(call(DefaultFun.NullList, rv), absent,
                            new PirTerm.Let(head, call(DefaultFun.HeadList, rv),
                                    new PirTerm.Let(key, orderKey(k, call(DefaultFun.FstPair, hv)),
                                            new PirTerm.IfThenElse(less(orderType(k), kv, ov, true),
                                                    new PirTerm.App(go, call(DefaultFun.TailList, rv)),
                                                    new PirTerm.IfThenElse(less(orderType(k), ov, kv, true), absent, present))))),
                    List.of(new PirTerm.Var(xs, pairs)));
            return new PirTerm.Lam(search, pir(k), new PirTerm.Lam(xs, pairs,
                    new PirTerm.Let(ordered, orderSearch(k, sv), scan)));
        });
    }

    /** Bind a key among ascending keys; rebuilds only up to its position. */
    private PirTerm assocInsert(Ty k, Ty v) {
        var pairs = pir(con("Map", k, v));
        return shared("$assoc.insert:" + k + "," + v, "assocInsert", arrow(pir(k), pir(v), pairs, pairs), self -> {
            String search = fresh("search"), value = fresh("value"), xs = fresh("pairs"), entry = fresh("entry"),
                    rest = fresh("rest"), head = fresh("pair"), key = fresh("key"), ordered = fresh("ordered");
            var sv = new PirTerm.Var(search, pir(k));
            var ov = new PirTerm.Var(ordered, pir(orderType(k)));
            var ev = new PirTerm.Var(entry, ENTRY);
            var rv = new PirTerm.Var(rest, pairs);
            var hv = new PirTerm.Var(head, ENTRY);
            var kv = new PirTerm.Var(key, pir(orderType(k)));
            PirTerm scan = loop("scan", List.of(rest), List.of(pairs), pairs, go ->
                    new PirTerm.IfThenElse(call(DefaultFun.NullList, rv), call(DefaultFun.MkCons, ev, rv),
                            new PirTerm.Let(head, call(DefaultFun.HeadList, rv),
                                    new PirTerm.Let(key, orderKey(k, call(DefaultFun.FstPair, hv)),
                                            new PirTerm.IfThenElse(less(orderType(k), kv, ov, true),
                                                    call(DefaultFun.MkCons, hv, new PirTerm.App(go, call(DefaultFun.TailList, rv))),
                                                    new PirTerm.IfThenElse(less(orderType(k), ov, kv, true), call(DefaultFun.MkCons, ev, rv),
                                                            call(DefaultFun.MkCons, ev, call(DefaultFun.TailList, rv))))))),
                    List.of(new PirTerm.Var(xs, pairs)));
            return new PirTerm.Lam(search, pir(k), new PirTerm.Lam(value, pir(v), new PirTerm.Lam(xs, pairs,
                    new PirTerm.Let(entry, pair(sv, k, new PirTerm.Var(value, pir(v)), v),
                            new PirTerm.Let(ordered, orderSearch(k, sv), scan)))));
        });
    }

    /** Remove a key among ascending keys; rebuilds only up to its position. */
    private PirTerm assocRemove(Ty k, Ty v) {
        var pairs = pir(con("Map", k, v));
        return shared("$assoc.remove:" + k + "," + v, "assocRemove", arrow(pir(k), pairs, pairs), self -> {
            String search = fresh("search"), xs = fresh("pairs"), rest = fresh("rest"), head = fresh("pair"), key = fresh("key"),
                    ordered = fresh("ordered");
            var sv = new PirTerm.Var(search, pir(k));
            var ov = new PirTerm.Var(ordered, pir(orderType(k)));
            var rv = new PirTerm.Var(rest, pairs);
            var hv = new PirTerm.Var(head, ENTRY);
            var kv = new PirTerm.Var(key, pir(orderType(k)));
            PirTerm scan = loop("scan", List.of(rest), List.of(pairs), pairs, go ->
                    new PirTerm.IfThenElse(call(DefaultFun.NullList, rv), rv,
                            new PirTerm.Let(head, call(DefaultFun.HeadList, rv),
                                    new PirTerm.Let(key, orderKey(k, call(DefaultFun.FstPair, hv)),
                                            new PirTerm.IfThenElse(less(orderType(k), kv, ov, true),
                                                    call(DefaultFun.MkCons, hv, new PirTerm.App(go, call(DefaultFun.TailList, rv))),
                                                    new PirTerm.IfThenElse(less(orderType(k), ov, kv, true), rv, call(DefaultFun.TailList, rv)))))),
                    List.of(new PirTerm.Var(xs, pairs)));
            return new PirTerm.Lam(search, pir(k), new PirTerm.Lam(xs, pairs,
                    new PirTerm.Let(ordered, orderSearch(k, sv), scan)));
        });
    }

    /** Whether the keys are strictly ascending, and so distinct. */
    private PirTerm assocAscending(Ty k, Ty v) {
        var pairs = pir(con("Map", k, v));
        return shared("$assoc.ascending:" + k + "," + v, "assocAscending", arrow(pairs, new PirType.BoolType()), self -> {
            String xs = fresh("pairs"), previous = fresh("previous"), rest = fresh("rest"), key = fresh("key");
            var xv = new PirTerm.Var(xs, pairs);
            var pv = new PirTerm.Var(previous, pir(orderType(k)));
            var rv = new PirTerm.Var(rest, pairs);
            var kv = new PirTerm.Var(key, pir(orderType(k)));
            PirTerm after = loop("after", List.of(previous, rest), List.of(pir(orderType(k)), pairs), new PirType.BoolType(), go ->
                    new PirTerm.IfThenElse(call(DefaultFun.NullList, rv), bool(true),
                            new PirTerm.Let(key, orderKey(k, call(DefaultFun.FstPair, call(DefaultFun.HeadList, rv))),
                                    new PirTerm.IfThenElse(less(orderType(k), pv, kv, true),
                                            new PirTerm.App(new PirTerm.App(go, kv), call(DefaultFun.TailList, rv)), bool(false)))),
                    List.of(orderKey(k, call(DefaultFun.FstPair, call(DefaultFun.HeadList, xv))),
                            call(DefaultFun.TailList, xv)));
            return new PirTerm.Lam(xs, pairs, new PirTerm.IfThenElse(call(DefaultFun.NullList, xv), bool(true), after));
        });
    }

    /** The pairs split into maximal runs of strictly ascending keys, in order. */
    private PirTerm assocRuns(Ty k, Ty v) {
        var pairs = pir(con("Map", k, v));
        var runs = pir(con("List", con("Map", k, v)));
        return shared("$assoc.runs:" + k + "," + v, "assocRuns", arrow(pairs, runs), self -> {
            String xs = fresh("pairs"), previous = fresh("previous"), current = fresh("current"), rest = fresh("rest"),
                    head = fresh("pair"), key = fresh("key"), acc = fresh("acc"), items = fresh("items");
            var xv = new PirTerm.Var(xs, pairs);
            var pv = new PirTerm.Var(previous, pir(orderType(k)));
            var cv = new PirTerm.Var(current, pairs);
            var rv = new PirTerm.Var(rest, pairs);
            var hv = new PirTerm.Var(head, ENTRY);
            var kv = new PirTerm.Var(key, pir(orderType(k)));
            // A run is collected in reverse, then turned around once it ends.
            var accv = new PirTerm.Var(acc, pairs);
            var iv = new PirTerm.Var(items, pairs);
            java.util.function.Function<PirTerm, PirTerm> finish = reversed -> call(DefaultFun.MapData,
                    loop("reverse", List.of(items, acc), List.of(pairs, pairs), pairs, go ->
                            new PirTerm.IfThenElse(call(DefaultFun.NullList, iv), accv,
                                    new PirTerm.App(new PirTerm.App(go, call(DefaultFun.TailList, iv)),
                                            call(DefaultFun.MkCons, call(DefaultFun.HeadList, iv), accv))),
                            List.of(reversed, call(DefaultFun.MkNilPairData, unit()))));
            PirTerm collect = loop("collect", List.of(previous, current, rest), List.of(pir(orderType(k)), pairs, pairs), runs, go ->
                    new PirTerm.IfThenElse(call(DefaultFun.NullList, rv),
                            call(DefaultFun.MkCons, finish.apply(cv), call(DefaultFun.MkNilData, unit())),
                            new PirTerm.Let(head, call(DefaultFun.HeadList, rv),
                                    new PirTerm.Let(key, orderKey(k, call(DefaultFun.FstPair, hv)),
                                            new PirTerm.IfThenElse(less(orderType(k), pv, kv, true),
                                                    new PirTerm.App(new PirTerm.App(new PirTerm.App(go, kv),
                                                            call(DefaultFun.MkCons, hv, cv)), call(DefaultFun.TailList, rv)),
                                                    call(DefaultFun.MkCons, finish.apply(cv),
                                                            new PirTerm.App(new PirTerm.App(new PirTerm.App(go, kv),
                                                                    call(DefaultFun.MkCons, hv, call(DefaultFun.MkNilPairData, unit()))),
                                                                    call(DefaultFun.TailList, rv))))))),
                    List.of(orderKey(k, call(DefaultFun.FstPair, call(DefaultFun.HeadList, xv))),
                            call(DefaultFun.MkCons, call(DefaultFun.HeadList, xv), call(DefaultFun.MkNilPairData, unit())),
                            call(DefaultFun.TailList, xv)));
            return new PirTerm.Lam(xs, pairs,
                    new PirTerm.IfThenElse(call(DefaultFun.NullList, xv), call(DefaultFun.MkNilData, unit()), collect));
        });
    }

    /** The pairs in reverse order, one pass. */
    private PirTerm assocReverse(Ty k, Ty v) {
        var pairs = pir(con("Map", k, v));
        return shared("$assoc.reverse:" + k + "," + v, "assocReverse", arrow(pairs, pairs), self -> {
            String xs = fresh("pairs"), rest = fresh("rest"), acc = fresh("acc");
            var rv = new PirTerm.Var(rest, pairs);
            var av = new PirTerm.Var(acc, pairs);
            return new PirTerm.Lam(xs, pairs, loop("reverse", List.of(rest, acc), List.of(pairs, pairs), pairs, go ->
                    new PirTerm.IfThenElse(call(DefaultFun.NullList, rv), av,
                            new PirTerm.App(new PirTerm.App(go, call(DefaultFun.TailList, rv)),
                                    call(DefaultFun.MkCons, call(DefaultFun.HeadList, rv), av))),
                    List.of(new PirTerm.Var(xs, pairs), call(DefaultFun.MkNilPairData, unit()))));
        });
    }

    /** A stable merge of two runs with ascending keys: on equal keys the left pair comes first. */
    private PirTerm assocMerge(Ty k, Ty v) {
        var pairs = pir(con("Map", k, v));
        return shared("$assoc.merge:" + k + "," + v, "assocMerge", arrow(pairs, pairs, pairs), self -> {
            String left = fresh("left"), right = fresh("right"), lk = fresh("leftKey"), rk = fresh("rightKey");
            var lv = new PirTerm.Var(left, pairs);
            var rv = new PirTerm.Var(right, pairs);
            var lkv = new PirTerm.Var(lk, pir(orderType(k)));
            var rkv = new PirTerm.Var(rk, pir(orderType(k)));
            PirTerm body = new PirTerm.IfThenElse(call(DefaultFun.NullList, lv), rv,
                    new PirTerm.IfThenElse(call(DefaultFun.NullList, rv), lv,
                            new PirTerm.Let(lk, orderKey(k, call(DefaultFun.FstPair, call(DefaultFun.HeadList, lv))),
                                    new PirTerm.Let(rk, orderKey(k, call(DefaultFun.FstPair, call(DefaultFun.HeadList, rv))),
                                            new PirTerm.IfThenElse(less(orderType(k), rkv, lkv, true),
                                                    call(DefaultFun.MkCons, call(DefaultFun.HeadList, rv),
                                                            new PirTerm.App(new PirTerm.App(self, lv), call(DefaultFun.TailList, rv))),
                                                    call(DefaultFun.MkCons, call(DefaultFun.HeadList, lv),
                                                            new PirTerm.App(new PirTerm.App(self, call(DefaultFun.TailList, lv)), rv)))))));
            return new PirTerm.Lam(left, pairs, new PirTerm.Lam(right, pairs, body));
        });
    }

    /** Of each run of pairs with equal keys, the last. Comparable keys have one Data encoding each. */
    private PirTerm assocDedupe(Ty k, Ty v) {
        var pairs = pir(con("Map", k, v));
        return shared("$assoc.dedupe:" + k + "," + v, "assocDedupe", arrow(pairs, pairs), self -> {
            String xs = fresh("pairs"), head = fresh("pair"), tail = fresh("tail");
            var xv = new PirTerm.Var(xs, pairs);
            var hv = new PirTerm.Var(head, ENTRY);
            var tv = new PirTerm.Var(tail, pairs);
            PirTerm body = new PirTerm.IfThenElse(call(DefaultFun.NullList, xv), xv,
                    new PirTerm.Let(head, call(DefaultFun.HeadList, xv),
                            new PirTerm.Let(tail, call(DefaultFun.TailList, xv),
                                    new PirTerm.IfThenElse(call(DefaultFun.NullList, tv), xv,
                                            new PirTerm.IfThenElse(keysEqual(k, call(DefaultFun.FstPair, hv),
                                                            call(DefaultFun.FstPair, call(DefaultFun.HeadList, tv))),
                                                    new PirTerm.App(self, tv),
                                                    call(DefaultFun.MkCons, hv, new PirTerm.App(self, tv)))))));
            return new PirTerm.Lam(xs, pairs, body);
        });
    }

    // ----- Dict at the Data boundary (ADR-009): decoding requires strictly ascending keys. -----

    /** Julc's strict decoder, and for a type containing a Dict, the check that its keys ascend (ADR-009). */
    private PirTerm strictDecoder(Ty type, Token token) {
        PirTerm decoder = codec(type, LibraryRequest.Codec.Direction.DECODE_STRICT, token);
        if (!containsDict(type)) return decoder;
        requireOrderedKeys(type, token, new HashSet<>());
        String d = fresh("data");
        var dv = new PirTerm.Var(d, new PirType.DataType());
        return new PirTerm.Lam(d, new PirType.DataType(), new PirTerm.IfThenElse(new PirTerm.App(dictCheck(type), dv),
                new PirTerm.App(decoder, dv), unsortedDict(pir(type))));
    }

    /** Checking a Dict's order at the boundary needs its keys to be comparable. */
    private void requireOrderedKeys(Ty type, Token token, Set<String> visiting) {
        if (!(prune(type) instanceof Con c) || c.name().equals("->") || !visiting.add(c.toString())) return;
        if (c.name().equals("Dict") && !inference.comparableCapable(c.args().getFirst()))
            throw new Diagnostic("ELCI2003", token, "A Dict decoded from Data needs comparable keys, to check that they are"
                    + " in order; " + Types.show(c.args().getFirst()) + " is not comparable. Use a Map instead");
        c.args().forEach(a -> requireOrderedKeys(a, token, visiting));
        unionFields(c).values().forEach(fields -> fields.forEach(f -> requireOrderedKeys(f, token, visiting)));
    }

    /** Whether values of {@code type} contain a Dict, through lists, tuples, records, maps and custom types. */
    public boolean containsDict(Ty type) {
        return containsDict(type, new HashSet<>());
    }

    private boolean containsDict(Ty type, Set<String> visiting) {
        if (!(prune(type) instanceof Con c)) return false;
        if (c.name().equals("Dict")) return true;
        if (c.name().equals("->") || !visiting.add(c.toString())) return false;
        try {
            for (Ty argument : c.args()) if (containsDict(argument, visiting)) return true;
            for (var field : unionFields(c).values())
                for (Ty t : field) if (containsDict(t, visiting)) return true;
            return false;
        } finally {
            visiting.remove(c.toString());
        }
    }

    /** The field types of each constructor tag of a custom type (including Maybe and Result), or none. */
    private Map<Integer, List<Ty>> unionFields(Con c) {
        var result = new LinkedHashMap<Integer, List<Ty>>();
        for (var union : inference.unions.values())
            if (inference.nominal(union.name()).equals(c.name())) {
                var variables = new HashMap<String, Ty>();
                for (int i = 0; i < union.parameters().size(); i++) variables.put(union.parameters().get(i), c.args().get(i));
                int tag = 0;
                for (var variant : union.variants())
                    result.put(tag++, variant.fields().stream().map(f -> inference.annotatedPublic(f, variables)).toList());
            }
        return result;
    }

    /**
     * {@code \data -> Bool} for a Data value of {@code type}: whether every Dict in it has strictly
     * ascending keys. Shared per type, so recursive types check recursively.
     */
    private PirTerm dictCheck(Ty type) {
        var c = (Con) prune(type);
        var data = new PirType.DataType();
        return shared("$validDicts:" + c, "validDicts", arrow(data, new PirType.BoolType()), self -> {
            String d = fresh("data");
            var dv = new PirTerm.Var(d, data);
            return new PirTerm.Lam(d, data, dictCheckBody(c, dv));
        });
    }

    private PirTerm dictCheckBody(Con c, PirTerm.Var dv) {
        var data = new PirType.DataType();
        switch (c.name()) {
            case "Dict", "Map" -> {
                Ty k = c.args().get(0), v = c.args().get(1);
                var pairs = pir(con("Map", k, v));
                String ps = fresh("pairs"), entry = fresh("entry");
                var pv = new PirTerm.Var(ps, pairs);
                var ev = new PirTerm.Var(entry, ENTRY);
                PirTerm entries = bool(true);
                if (containsDict(v)) entries = new PirTerm.App(dictCheck(v), call(DefaultFun.SndPair, ev));
                if (containsDict(k)) entries = and(new PirTerm.App(dictCheck(k), call(DefaultFun.FstPair, ev)), entries);
                PirTerm each = entries instanceof PirTerm.Const ? bool(true) : every(pv, ENTRY, entry, entries);
                PirTerm ordered = c.name().equals("Dict") ? new PirTerm.App(assocAscending(k, v), pv) : bool(true);
                return new PirTerm.Let(ps, call(DefaultFun.UnMapData, dv), and(ordered, each));
            }
            case "List" -> {
                String xs = fresh("items"), item = fresh("item");
                return new PirTerm.Let(xs, call(DefaultFun.UnListData, dv),
                        every(new PirTerm.Var(xs, new PirType.ListType(data)), data, item,
                                new PirTerm.App(dictCheck(c.args().getFirst()), new PirTerm.Var(item, data))));
            }
            default -> {
                // Tuples and records are constructor 0 with positional fields; custom types dispatch on
                // the tag; a Julc optional is Some (0) or None (1).
                Map<Integer, List<Ty>> variants = c.name().equals("Tuple") || c.name().startsWith("Record:")
                        ? Map.of(0, c.args())
                        : c.name().equals("JulcOptional") ? Map.of(0, c.args(), 1, List.of()) : unionFields(c);
                if (variants.isEmpty())
                    throw new IllegalArgumentException("A Dict inside " + Types.show(c)
                            + " cannot be checked at the Data boundary");
                String p = fresh("constr"), tag = fresh("tag"), fields = fresh("fields");
                var tv = new PirTerm.Var(tag, new PirType.IntegerType());
                var fv = new PirTerm.Var(fields, new PirType.ListType(data));
                PirTerm body = bool(true);
                for (var variant : new java.util.TreeMap<>(variants).descendingMap().entrySet()) {
                    PirTerm checks = bool(true);
                    var types = variant.getValue();
                    for (int i = types.size() - 1; i >= 0; i--)
                        if (containsDict(types.get(i))) {
                            PirTerm field = fv;
                            for (int j = 0; j < i; j++) field = call(DefaultFun.TailList, field);
                            checks = and(new PirTerm.App(dictCheck(types.get(i)), call(DefaultFun.HeadList, field)), checks);
                        }
                    if (!(checks instanceof PirTerm.Const))
                        body = new PirTerm.IfThenElse(call(DefaultFun.EqualsInteger, tv,
                                new PirTerm.Const(Constant.integer(variant.getKey()))), checks, body);
                }
                var pairType = new PirType.PairType(new PirType.IntegerType(), new PirType.ListType(data));
                return new PirTerm.Let(p, call(DefaultFun.UnConstrData, dv),
                        new PirTerm.Let(tag, call(DefaultFun.FstPair, new PirTerm.Var(p, pairType)),
                                new PirTerm.Let(fields, call(DefaultFun.SndPair, new PirTerm.Var(p, pairType)), body)));
            }
        }
    }

    private static PirTerm and(PirTerm left, PirTerm right) {
        if (left instanceof PirTerm.Const) return right;
        if (right instanceof PirTerm.Const) return left;
        return new PirTerm.IfThenElse(left, right, bool(false));
    }

    /** Whether {@code check} (over the variable {@code name}) holds for every element of {@code list}. */
    private PirTerm every(PirTerm list, PirType element, String name, PirTerm check) {
        String rest = fresh("rest");
        var listType = element instanceof PirType.PairType ? new PirType.ListType(element) : new PirType.ListType(element);
        var rv = new PirTerm.Var(rest, listType);
        return loop("every", List.of(rest), List.of(listType), new PirType.BoolType(), go ->
                new PirTerm.IfThenElse(call(DefaultFun.NullList, rv), bool(true),
                        new PirTerm.Let(name, call(DefaultFun.HeadList, rv),
                                new PirTerm.IfThenElse(check, new PirTerm.App(go, call(DefaultFun.TailList, rv)), bool(false)))),
                List.of(list));
    }

    /**
     * A handler that first checks the Dicts in its arguments (ADR-009): the ledger's strict
     * boundary decodes a Dict as a map, so its key order is checked here. Returns the handler
     * unchanged when no argument contains a Dict.
     */
    public PirTerm guardDicts(PirTerm handler, Ty type, Token token) {
        var arguments = new ArrayList<Ty>();
        Ty rest = prune(type);
        while (rest instanceof Con c && c.name().equals("->")) {
            arguments.add(c.args().get(0));
            rest = prune(c.args().get(1));
        }
        if (arguments.stream().noneMatch(this::containsDict)) return handler;
        arguments.forEach(argument -> requireOrderedKeys(argument, token, new HashSet<>()));
        var names = new ArrayList<String>();
        PirTerm call = handler, condition = bool(true);
        for (Ty argument : arguments) {
            String name = fresh("argument");
            names.add(name);
            var variable = new PirTerm.Var(name, pir(argument));
            call = new PirTerm.App(call, variable);
            if (containsDict(argument))
                condition = and(condition, new PirTerm.App(dictCheck(argument), PirHelpers.wrapEncode(variable, pir(argument))));
        }
        PirTerm body = new PirTerm.IfThenElse(condition, call, unsortedDict(pir(rest)));
        for (int i = arguments.size() - 1; i >= 0; i--) body = new PirTerm.Lam(names.get(i), pir(arguments.get(i)), body);
        return body;
    }

    /** The failure when a decoded Dict's keys are out of order, with a trace saying so. */
    private static PirTerm unsortedDict(PirType type) {
        return new PirTerm.Trace(new PirTerm.Const(Constant.string("Dict keys are not strictly ascending")),
                new PirTerm.Error(type));
    }

    /** Component {@code index} of a tuple value (a tag-zero constructor with positional fields). */
    private static PirTerm component(PirTerm tuple, int index, PirType type) {
        PirTerm fields = call(DefaultFun.SndPair, call(DefaultFun.UnConstrData, tuple));
        for (int i = 0; i < index; i++) fields = call(DefaultFun.TailList, fields);
        return PirHelpers.wrapDecode(call(DefaultFun.HeadList, fields), type);
    }

    /**
     * {@code Julc.Data}. {@code decode} and {@code encode} are Julc's strict decoder and encoder
     * for the concrete type; {@code fromOptional} and {@code toOptional} re-tag between Julc's
     * optional ({@code Some} 0, {@code None} 1) and Elci's {@code Maybe} ({@code Nothing} 0,
     * {@code Just} 1) without decoding the payload.
     */
    private PirTerm dataIntrinsic(String function, Ty use, Token token) {
        var arrow = (Con) prune(use);
        Ty input = arrow.args().get(0), output = arrow.args().get(1);
        // Only decoding creates values, so only it needs the type known; an ambiguous type has no
        // values to encode or re-tag (ADR-008).
        if (function.equals("decode") ? !Types.concrete(use) : !Types.variables(use).isEmpty())
            throw new Diagnostic("ELCI3001", token, "Cannot determine the type converted by Julc.Data." + function
                    + "; add a type annotation");
        return switch (function) {
            case "decode" -> strictDecoder(output, token);
            case "encode" -> codec(input, LibraryRequest.Codec.Direction.ENCODE, token);
            case "fromOptional" -> retag(pir(input), pir(output), 0, 1);
            case "toOptional" -> retag(pir(input), pir(output), 1, 0);
            default -> throw new Diagnostic("ELCI2005", token, "Unknown name 'Julc.Data." + function + "'");
        };
    }

    /**
     * {@code Bytes}: each function is one Plutus builtin, curried with the byte string last so
     * that it composes in pipelines. Arguments are evaluated once, strictly, in source order.
     */
    private PirTerm bytesIntrinsic(String function) {
        PirType bytes = new PirType.ByteStringType(), integer = new PirType.IntegerType(), string = new PirType.StringType();
        return switch (function) {
            // take n = slice 0 n; drop n = slice n (length) — slicing clamps to the string.
            case "take" -> positional(List.of(integer, bytes), a -> call(DefaultFun.SliceByteString,
                    new PirTerm.Const(Constant.integer(0)), a.get(0), a.get(1)));
            case "drop" -> positional(List.of(integer, bytes), a -> call(DefaultFun.SliceByteString, a.get(0),
                    call(DefaultFun.LengthOfByteString, a.get(1)), a.get(1)));
            case "at" -> positional(List.of(integer, bytes), a -> call(DefaultFun.IndexByteString, a.get(1), a.get(0)));
            case "cons" -> builtin(DefaultFun.ConsByteString, List.of(integer, bytes));
            // replicate count byte
            case "replicate" -> builtin(DefaultFun.ReplicateByte, List.of(integer, integer));
            // fromInt… width value: width 0 means as short as possible.
            case "fromIntBigEndian", "fromIntLittleEndian" -> positional(List.of(integer, integer), a ->
                    call(DefaultFun.IntegerToByteString, bool(function.equals("fromIntBigEndian")), a.get(0), a.get(1)));
            case "toIntBigEndian", "toIntLittleEndian" -> positional(List.of(bytes), a ->
                    call(DefaultFun.ByteStringToInteger, bool(function.equals("toIntBigEndian")), a.get(0)));
            // Bitwise operations truncate to the shorter operand (no padding).
            case "and", "or", "xor" -> positional(List.of(bytes, bytes), a -> call(switch (function) {
                case "and" -> DefaultFun.AndByteString;
                case "or" -> DefaultFun.OrByteString;
                default -> DefaultFun.XorByteString;
            }, bool(false), a.get(0), a.get(1)));
            case "complement" -> builtin(DefaultFun.ComplementByteString, List.of(bytes));
            case "shift", "rotate" -> positional(List.of(integer, bytes), a -> call(
                    function.equals("shift") ? DefaultFun.ShiftByteString : DefaultFun.RotateByteString, a.get(1), a.get(0)));
            case "testBit" -> positional(List.of(integer, bytes), a -> call(DefaultFun.ReadBit, a.get(1), a.get(0)));
            case "countSetBits" -> builtin(DefaultFun.CountSetBits, List.of(bytes));
            case "findFirstSetBit" -> builtin(DefaultFun.FindFirstSetBit, List.of(bytes));
            case "sha3_256" -> builtin(DefaultFun.Sha3_256, List.of(bytes));
            case "keccak256" -> builtin(DefaultFun.Keccak_256, List.of(bytes));
            case "ripemd160" -> builtin(DefaultFun.Ripemd_160, List.of(bytes));
            case "empty" -> new PirTerm.Const(Constant.byteString(new byte[0]));
            case "fromString" -> builtin(DefaultFun.EncodeUtf8, List.of(string));
            case "toString" -> builtin(DefaultFun.DecodeUtf8, List.of(bytes));
            case "length" -> builtin(DefaultFun.LengthOfByteString, List.of(bytes));
            case "append" -> builtin(DefaultFun.AppendByteString, List.of(bytes, bytes));
            // slice start length bytes, as SliceByteString.
            case "slice" -> builtin(DefaultFun.SliceByteString, List.of(integer, integer, bytes));
            case "blake2b224" -> builtin(DefaultFun.Blake2b_224, List.of(bytes));
            case "blake2b256" -> builtin(DefaultFun.Blake2b_256, List.of(bytes));
            case "sha256" -> builtin(DefaultFun.Sha2_256, List.of(bytes));
            default -> throw new IllegalArgumentException("Unknown Bytes function " + function);
        };
    }

    /** A curried lambda over {@code parameters} whose body the builder makes from the variables. */
    private PirTerm positional(List<PirType> parameters, java.util.function.Function<List<PirTerm>, PirTerm> body) {
        var names = new ArrayList<String>();
        var variables = new ArrayList<PirTerm>();
        for (var parameter : parameters) {
            String name = fresh("argument");
            names.add(name);
            variables.add(new PirTerm.Var(name, parameter));
        }
        PirTerm term = body.apply(variables);
        for (int i = parameters.size() - 1; i >= 0; i--) term = new PirTerm.Lam(names.get(i), parameters.get(i), term);
        return term;
    }

    /** {@code \a -> \b -> ... -> fun a b ...}: a strict, curried builtin application. */
    private PirTerm builtin(DefaultFun fun, List<PirType> parameters) {
        var names = new ArrayList<String>();
        var arguments = new ArrayList<PirTerm>();
        for (var parameter : parameters) {
            String name = fresh("argument");
            names.add(name);
            arguments.add(new PirTerm.Var(name, parameter));
        }
        PirTerm body = call(fun, arguments.toArray(PirTerm[]::new));
        for (int i = parameters.size() - 1; i >= 0; i--) body = new PirTerm.Lam(names.get(i), parameters.get(i), body);
        return body;
    }

    private PirTerm codec(Ty type, LibraryRequest.Codec.Direction direction, Token token) {
        // ScriptContext is represented as Data, so a codec would silently erase it.
        if (containsContext(type, new HashSet<>()))
            throw new Diagnostic("ELCI3003", token, "No " + (direction == LibraryRequest.Codec.Direction.ENCODE
                    ? "encoder" : "strict decoder") + " for " + prune(type) + ": ScriptContext has no approved Data"
                    + " codec; only the ledger supplies a context");
        var representation = pir(type);
        describeLeaves(representation);
        try {
            return library(new LibraryRequest.Codec(representation, direction), token);
        } catch (Diagnostic e) {
            throw new Diagnostic("ELCI3003", token, "No " + (direction == LibraryRequest.Codec.Direction.ENCODE
                    ? "encoder" : "strict decoder") + " for " + prune(type) + ": " + e.getMessage());
        }
    }

    /** Whether {@code type} is or contains the ledger context, through Elci records and unions. */
    private boolean containsContext(Ty type, Set<String> visiting) {
        var c = (Con) prune(type);
        if (c.name().equals("ScriptContext") || c.name().equals("org.julclang.ledger.ScriptContext")) return true;
        if (!visiting.add(c.toString())) return false;
        for (var argument : c.args()) if (containsContext(argument, visiting)) return true;
        for (var union : inference.unions.values())
            if (inference.nominal(union.name()).equals(c.name())) {
                var variables = new HashMap<String, Ty>();
                for (int i = 0; i < union.parameters().size(); i++)
                    variables.put(union.parameters().get(i), c.args().get(i));
                for (var variant : union.variants())
                    for (var field : variant.fields())
                        if (containsContext(inference.annotatedPublic(field, variables), visiting)) return true;
            }
        return false;
    }

    /** Describe the Elci records and unions a codec's nominal leaves name. */
    private void describeLeaves(PirType type) {
        switch (type) {
            case PirType.RecordType _, PirType.SumType _ -> {
                if (!libraries.java().types().values().stream().anyMatch(t -> t.representation().equals(type)))
                    libraries.describe(type, namedTypes, libraries.uncheckable());
            }
            case PirType.ListType list -> describeLeaves(list.elemType());
            case PirType.MapType map -> {
                describeLeaves(map.keyType());
                describeLeaves(map.valueType());
            }
            case PirType.OptionalType optional -> describeLeaves(optional.elemType());
            default -> {}
        }
    }

    /**
     * {@code \value -> if tag == present then Constr presentOut fields else Constr absentOut []},
     * reusing the Data-encoded payload. The result is typed by applying a typed identity.
     */
    private PirTerm retag(PirType input, PirType output, int present, int presentOut) {
        String value = fresh("optional"), cast = fresh("retagged");
        var raw = new PirTerm.Var(value, input);
        var pair = call(DefaultFun.UnConstrData, raw);
        PirTerm some = call(DefaultFun.ConstrData, new PirTerm.Const(Constant.integer(presentOut)), call(DefaultFun.SndPair, pair));
        PirTerm none = call(DefaultFun.ConstrData, new PirTerm.Const(Constant.integer(1 - presentOut)),
                call(DefaultFun.MkNilData, new PirTerm.Const(Constant.unit())));
        PirTerm body = new PirTerm.IfThenElse(call(DefaultFun.EqualsInteger, call(DefaultFun.FstPair,
                call(DefaultFun.UnConstrData, raw)), new PirTerm.Const(Constant.integer(present))), some, none);
        body = new PirTerm.App(new PirTerm.Lam(cast, output, new PirTerm.Var(cast, output)), body);
        return new PirTerm.Lam(value, input, body);
    }

    /**
     * The request for one concrete use of a generic export: its type arguments are read off the
     * use's type in the order of the scheme's type parameters, and must satisfy the scheme's
     * constraints before anything is materialized.
     */
    private LibraryRequest.Instantiate instantiate(LibraryScheme scheme, Ty use, Token token) {
        var variables = new HashMap<String, Ty>();
        unify(org.elci.driver.ElciCompiler.schemeType(scheme, variables), use, token);
        var arguments = new ArrayList<LibraryType.Reference>();
        var producerTypes = new TreeMap<String, PirType>();
        String export = scheme.symbol().substring(scheme.symbol().lastIndexOf('.') + 1);
        for (var parameter : scheme.typeParameters()) {
            Ty argument = variables.get(parameter.name());
            if (argument == null || !Types.concrete(argument))
                throw new Diagnostic("ELCI3001", token, "Cannot determine type argument " + parameter.name() + " of " + export);
            // A Java library cannot keep a Dict's keys in order: it could return an unchecked one.
            if (containsDict(argument) && org.elci.modules.Modules.CORE_LIBRARIES.values().stream()
                    .noneMatch(owner -> scheme.symbol().startsWith(owner + ".")))
                throw new Diagnostic("ELCI3003", token, "Type argument " + parameter.name() + " = " + Types.show(argument)
                        + " of " + export + " contains a Dict, which a Java library cannot keep sorted; pass a Map"
                        + " or a List and build the Dict with Dict.fromList");
            if (parameter.constraints().contains(LibraryScheme.Constraint.DATA_ENCODABLE)
                    && !TypeReferences.dataEncodable(pir(argument)))
                throw new Diagnostic("ELCI3003", token, "Type argument " + parameter.name() + " = " + prune(argument)
                        + " of " + export + " must have a Plutus Data encoding (DATA_ENCODABLE); functions, unit and"
                        + " native values do not");
            arguments.add(reference(argument, producerTypes));
        }
        return new LibraryRequest.Instantiate(scheme.symbol(), arguments, producerTypes);
    }

    /**
     * The provider-neutral reference for a concrete source type. Elci's own records, tuples and
     * unions are producer-owned types: they are named by their stable identity and their
     * representations travel with the request.
     */
    private LibraryType.Reference reference(Ty type, Map<String, PirType> producerTypes) {
        var c = (Con) prune(type);
        return switch (c.name()) {
            case "Int", "Bool", "String", "Bytes", "Data", "Unit" -> new LibraryType.Reference(c.name(), List.of());
            case "List", "Map", "JulcOptional", "Array" ->
                    new LibraryType.Reference(c.name(), c.args().stream().map(a -> reference(a, producerTypes)).toList());
            // A Dict is a Map to libraries; only the core library's may carry one (instantiate).
            case "Dict" -> new LibraryType.Reference("Map", c.args().stream().map(a -> reference(a, producerTypes)).toList());
            case "->" -> new LibraryType.Reference("Function",
                    c.args().stream().map(a -> reference(a, producerTypes)).toList());
            case "ScriptContext" -> new LibraryType.Reference("org.julclang.ledger.ScriptContext", List.of());
            default -> {
                if (inference.importedTypes.containsKey(c.name())) yield new LibraryType.Reference(c.name(), List.of());
                var representation = pir(type);
                String identity = switch (representation) {
                    case PirType.SumType sum -> sum.name();
                    case PirType.RecordType record -> record.name();
                    default -> throw new IllegalArgumentException("Unsupported type argument " + type);
                };
                producerTypes.put(identity, representation);
                yield new LibraryType.Reference(identity, List.of());
            }
        };
    }

    public PirType pir(Ty type) {
        type = prune(type);
        if (!(type instanceof Con c))
            throw new IllegalArgumentException("Unresolved source type: " + type);
        if (inference.importedRepresentations.containsKey(c.name()))
            return inference.importedRepresentations.get(c.name());
        for (var union : inference.unions.values())
            if (inference.nominal(union.name()).equals(c.name())) {
                if (planning.size() > 64)
                    throw new IllegalArgumentException(
                            "Expanding recursive type requires unsupported polymorphic recursion");
                String id = "elci." + c;
                if (namedTypes.containsKey(id)) return namedTypes.get(id);
                if (!planning.add(id))
                    return new PirType.NamedTypeRef(id, id, PirType.NamedKind.SUM);
                var vars = new HashMap<String, Ty>();
                for (int i = 0; i < union.parameters().size(); i++)
                    vars.put(union.parameters().get(i), c.args().get(i));
                var variants = new ArrayList<PirType.Constructor>();
                int tag = 0;
                for (var variant : union.variants()) {
                    var fields = new ArrayList<PirType.Field>();
                    int index = 0;
                    for (var field : variant.fields()) {
                        var pt = pir(inference.annotatedPublic(field, vars));
                        requireData(pt);
                        fields.add(new PirType.Field("field" + (index++), pt));
                    }
                    variants.add(new PirType.Constructor(variant.name(), tag++, fields));
                }
                var sum = new PirType.SumType(id, variants);
                planning.remove(id);
                namedTypes.put(id, sum);
                return sum;
            }
        if (c.name().equals("Tuple")) {
            var fields = new ArrayList<PirType.Field>();
            for (int i = 0; i < c.args().size(); i++) {
                var pt = pir(c.args().get(i));
                requireData(pt);
                fields.add(new PirType.Field("_" + i, pt));
            }
            return new PirType.RecordType("elci.Tuple" + c.args().size() + c.args(), fields);
        }
        if (c.name().startsWith("Record:")) {
            var names =
                    c.name().substring(7).isEmpty()
                            ? List.<String>of()
                            : Arrays.asList(c.name().substring(7).split(","));
            var fields = new ArrayList<PirType.Field>();
            for (int i = 0; i < names.size(); i++) {
                var field = pir(c.args().get(i));
                requireData(field);
                fields.add(new PirType.Field(names.get(i), field));
            }
            return new PirType.RecordType("elci." + c, fields);
        }
        return switch (c.name()) {
            case "Bytes" -> new PirType.ByteStringType();
            case "Data", Types.AMBIGUOUS -> new PirType.DataType();
            // A Dict is a Map whose keys are strictly ascending (ADR-009).
            case "Map", "Dict" -> {
                var key = pir(c.args().get(0));
                var value = pir(c.args().get(1));
                requireEncodable(key);
                requireEncodable(value);
                yield new PirType.MapType(key, value);
            }
            // A PV11 array of Data-encoded elements (Julc's JulcArray); not Data itself.
            case "Array" -> {
                var element = pir(c.args().getFirst());
                requireData(element);
                yield new PirType.ArrayType(element);
            }
            case "JulcOptional" -> new PirType.OptionalType(pir(c.args().getFirst()));
            case "Int" -> new PirType.IntegerType();
            case "Bool" -> new PirType.BoolType();
            case "String" -> new PirType.StringType();
            case "Unit" -> new PirType.UnitType();
            case "ScriptContext" -> new PirType.DataType();
            case "List" -> {
                var element = pir(c.args().getFirst());
                requireData(element);
                yield new PirType.ListType(element);
            }
            case "->" -> new PirType.FunType(pir(c.args().get(0)), pir(c.args().get(1)));
            default -> throw new IllegalArgumentException("Unsupported source type: " + type);
        };
    }

    public static Ty source(PirType type) {
        return switch (type) {
            case PirType.IntegerType _ -> con("Int");
            case PirType.BoolType _ -> con("Bool");
            case PirType.StringType _ -> con("String");
            case PirType.FunType f -> fn(source(f.paramType()), source(f.returnType()));
            case PirType.ListType l -> con("List", source(l.elemType()));
            default -> throw new IllegalArgumentException("Unmapped Java export type: " + type);
        };
    }

    /** Functions and arrays have no Data encoding, so no Data-encoded container can hold one. */
    private static void requireEncodable(PirType type) {
        if (type instanceof PirType.FunType)
            throw new IllegalArgumentException(
                    "Function-valued data fields require a representation not supported by this"
                            + " profile");
        if (type instanceof PirType.ArrayType)
            throw new IllegalArgumentException(
                    "Arrays have no Data encoding and cannot be stored in data; keep a List and convert it"
                            + " with Array.fromList");
    }

    private static void requireData(PirType type) {
        requireEncodable(type);
        if (type instanceof PirType.UnitType)
            throw new IllegalArgumentException(
                    "Unit in serialized containers is not yet supported");
    }

    private PirTerm projection(PirTerm base, Ty type, String field, Token token) {
        var record = (Con) prune(type);
        if (inference.importedTypes.containsKey(record.name())) {
            var descriptor = inference.importedTypes.get(record.name());
            if (!descriptor.supports(LibraryType.Operation.PROJECT))
                throw new Diagnostic("ELCI2010", token, "The provider of " + descriptor.identity()
                        + " does not approve field access (PROJECT)");
            if (descriptor.newType()) return base;
            if (importMode == ImportMode.LINK) {
                return new PirTerm.App(library(new LibraryRequest.Operation(descriptor.identity(),
                        LibraryType.Operation.PROJECT, field), token), base);
            }
            int index = 0;
            while (!descriptor.fields().get(index).name().equals(field)) index++;
            var ft = pir(inference.libraryType(descriptor.fields().get(index).type()));
            PirTerm fields = call(DefaultFun.SndPair, call(DefaultFun.UnConstrData, base));
            for (int i = 0; i < index; i++) fields = call(DefaultFun.TailList, fields);
            return PirHelpers.wrapDecode(call(DefaultFun.HeadList, fields), ft);
        }
        int index = Arrays.asList(record.name().substring(7).split(",")).indexOf(field);
        PirTerm fields = call(DefaultFun.SndPair, call(DefaultFun.UnConstrData, base));
        for (int i = 0; i < index; i++) fields = call(DefaultFun.TailList, fields);
        return PirHelpers.wrapDecode(
                call(DefaultFun.HeadList, fields), pir(record.args().get(index)));
    }

    private static PirTerm bool(boolean value) {
        return new PirTerm.Const(Constant.bool(value));
    }

    public static PirTerm call(DefaultFun fun, PirTerm... args) {
        PirTerm r = new PirTerm.Builtin(fun);
        for (var a : args) r = new PirTerm.App(r, a);
        return r;
    }
}
