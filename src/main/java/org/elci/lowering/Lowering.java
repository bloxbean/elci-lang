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
     * at its use site (revision-1 programs, whose bytes are kept stable); {@link #LINK}
     * references provider bindings that the backend links once as trusted imports (revision 2).
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
        if (importMode == ImportMode.LINK) return reference;
        // A revision-1 program has no import groups: link a closed copy at the use site.
        return PirLinker.link(new LinkedHashMap<>(group.definitions()), reference);
    }

    private String fresh(String hint) {
        return "$elci$" + hint + "$" + (fresh++);
    }

    private PirTerm reference(String name, Ty concrete) {
        if (!Types.concrete(concrete))
            throw new Diagnostic(
                    "ELCI3001",
                    definitions.get(name).token(),
                    "Cannot specialize an unconstrained type: " + concrete);
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
                        var localMapping = new HashMap<Var, Ty>();
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
                if (n.name().startsWith(org.elci.modules.Modules.DATA_PREFIX))
                    yield dataIntrinsic(n.name().substring(org.elci.modules.Modules.DATA_PREFIX.length()), type(n, map), n.token());
                if (imports.containsKey(n.name())) {
                    String symbol = imports.get(n.name());
                    var scheme = schemes.get(symbol);
                    if (scheme == null) yield library(new LibraryRequest.Export(symbol), n.token());
                    yield library(instantiate(scheme, type(n, map), n.token()), n.token());
                }
                if (n.name().equals("not")) {
                    String p = fresh("not");
                    yield new PirTerm.Lam(
                            p,
                            new PirType.BoolType(),
                            new PirTerm.IfThenElse(
                                    new PirTerm.Var(p, new PirType.BoolType()),
                                    bool(false),
                                    bool(true)));
                }
                if (n.name().equals("remainderBy")) {
                    String a = fresh("divisor"), b = fresh("value");
                    yield new PirTerm.Lam(
                            a,
                            new PirType.IntegerType(),
                            new PirTerm.Lam(
                                    b,
                                    new PirType.IntegerType(),
                                    call(
                                            DefaultFun.RemainderInteger,
                                            new PirTerm.Var(b, new PirType.IntegerType()),
                                            new PirTerm.Var(a, new PirType.IntegerType()))));
                }
                yield reference(n.name(), type(n, map));
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
        if (p.name().equals("Tuple")) {
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
        if (p.name().equals("True")) test = value;
        else if (p.name().equals("False"))
            test = new PirTerm.IfThenElse(value, bool(false), bool(true));
        else if (p.token().kind() == Token.Kind.STRING)
            test =
                    call(
                            DefaultFun.EqualsString,
                            value,
                            new PirTerm.Const(Constant.string(p.name())));
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
     * {@code Julc.Data}. {@code decode} and {@code encode} are Julc's strict decoder and encoder
     * for the concrete type; {@code fromOptional} and {@code toOptional} re-tag between Julc's
     * optional ({@code Some} 0, {@code None} 1) and Elci's {@code Maybe} ({@code Nothing} 0,
     * {@code Just} 1) without decoding the payload.
     */
    private PirTerm dataIntrinsic(String function, Ty use, Token token) {
        var arrow = (Con) prune(use);
        Ty input = arrow.args().get(0), output = arrow.args().get(1);
        if (!Types.concrete(use))
            throw new Diagnostic("ELCI3001", token, "Cannot determine the type converted by Julc.Data." + function
                    + "; add a type annotation");
        return switch (function) {
            case "decode" -> codec(output, LibraryRequest.Codec.Direction.DECODE_STRICT, token);
            case "encode" -> codec(input, LibraryRequest.Codec.Direction.ENCODE, token);
            case "fromOptional" -> retag(pir(input), pir(output), 0, 1);
            case "toOptional" -> retag(pir(input), pir(output), 1, 0);
            default -> throw new Diagnostic("ELCI2005", token, "Unknown name 'Julc.Data." + function + "'");
        };
    }

    private PirTerm codec(Ty type, LibraryRequest.Codec.Direction direction, Token token) {
        var representation = pir(type);
        describeLeaves(representation);
        try {
            return library(new LibraryRequest.Codec(representation, direction), token);
        } catch (Diagnostic e) {
            throw new Diagnostic("ELCI3003", token, "No " + (direction == LibraryRequest.Codec.Direction.ENCODE
                    ? "encoder" : "strict decoder") + " for " + prune(type) + ": " + e.getMessage());
        }
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
        String export = scheme.symbol().substring(scheme.symbol().lastIndexOf('.') + 1);
        for (var parameter : scheme.typeParameters()) {
            Ty argument = variables.get(parameter.name());
            if (argument == null || !Types.concrete(argument))
                throw new Diagnostic("ELCI3001", token, "Cannot determine type argument " + parameter.name() + " of " + export);
            if (parameter.constraints().contains(LibraryScheme.Constraint.DATA_ENCODABLE)
                    && !TypeReferences.dataEncodable(pir(argument)))
                throw new Diagnostic("ELCI3003", token, "Type argument " + parameter.name() + " = " + prune(argument)
                        + " of " + export + " must have a Plutus Data encoding (DATA_ENCODABLE); functions, unit and"
                        + " native values do not");
            arguments.add(reference(argument, token));
        }
        return new LibraryRequest.Instantiate(scheme.symbol(), arguments);
    }

    /** The provider-neutral reference for a concrete source type. */
    private LibraryType.Reference reference(Ty type, Token token) {
        var c = (Con) prune(type);
        return switch (c.name()) {
            case "Int", "Bool", "String", "Bytes", "Data", "Unit" -> new LibraryType.Reference(c.name(), List.of());
            case "List", "Map", "JulcOptional" ->
                    new LibraryType.Reference(c.name(), c.args().stream().map(a -> reference(a, token)).toList());
            case "->" -> new LibraryType.Reference("Function", c.args().stream().map(a -> reference(a, token)).toList());
            case "ScriptContext" -> new LibraryType.Reference("org.julclang.ledger.ScriptContext", List.of());
            default -> {
                if (inference.importedTypes.containsKey(c.name())) yield new LibraryType.Reference(c.name(), List.of());
                // An Elci record, tuple or union: described to the providers by its representation.
                yield new LibraryType.Reference(
                        libraries.describe(pir(type), namedTypes, libraries.uncheckable()), List.of());
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
            case "Data" -> new PirType.DataType();
            case "Map" -> new PirType.MapType(pir(c.args().get(0)), pir(c.args().get(1)));
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

    private static void requireData(PirType type) {
        if (type instanceof PirType.FunType)
            throw new IllegalArgumentException(
                    "Function-valued data fields require a representation not supported by this"
                            + " profile");
        if (type instanceof PirType.UnitType)
            throw new IllegalArgumentException(
                    "Unit in serialized containers is not yet supported");
    }

    private PirTerm projection(PirTerm base, Ty type, String field, Token token) {
        var record = (Con) prune(type);
        if (inference.importedTypes.containsKey(record.name())) {
            var descriptor = inference.importedTypes.get(record.name());
            if (descriptor.newType()) return base;
            if (importMode == ImportMode.LINK) {
                if (!descriptor.supports(LibraryType.Operation.PROJECT))
                    throw new Diagnostic("ELCI2010", token, "The provider of " + descriptor.identity()
                            + " does not approve field access (PROJECT)");
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
