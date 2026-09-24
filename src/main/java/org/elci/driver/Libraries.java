package org.elci.driver;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JavaLibraryProvider;
import org.julclang.compiler.backend.*;
import org.julclang.compiler.pir.PirType;
import org.julclang.stdlib.StdlibLibraryProvider;

import java.util.*;

/**
 * The library providers of one compilation (ADR-059 #182), composed with Julc's deterministic
 * ownership: the Java-source provider (concrete exports and generic templates), Julc's
 * programmatic list exports, and a provider describing Elci's own types so that Julc can build
 * codecs for them and accept them as type arguments. Elci's type descriptions grow while
 * lowering specializes, so the composition is rebuilt from the current descriptions.
 */
public final class Libraries {
    private final JavaLibraryProvider java;
    private final CompilerOptions options;
    private final ProducerTypes producer = new ProducerTypes();
    private LibraryProvider composed;
    private int composedTypes = -1;

    Libraries(JavaLibraryProvider java, CompilerOptions options) {
        this.java = java;
        this.options = options;
    }

    /** The Java-source provider, whose descriptions define imported types. */
    public JavaLibraryProvider java() {
        return java;
    }

    /** The composed provider for the Elci type descriptions registered so far. */
    public LibraryProvider provider() {
        if (composed == null || composedTypes != producer.types.size()) {
            var arguments = new LinkedHashMap<>(java.types());
            arguments.putAll(producer.types);
            composed = LibraryProviders.compose(List.of(java, new StdlibLibraryProvider(options, arguments), producer));
            composedTypes = producer.types.size();
        }
        return composed;
    }

    /**
     * Describe an Elci-owned record or union representation so that providers can resolve it as
     * a type argument and build its codecs. Elci owns these representations: they are
     * constructor-encoded by construction. Strict decoding is approved only when no nested type
     * is one whose Data layout the strict checker would misjudge.
     *
     * @return the identity to reference the type by
     */
    public String describe(PirType representation, Map<String, PirType> namedTypes, List<PirType> uncheckable) {
        String identity = switch (representation) {
            case PirType.SumType sum -> sum.name();
            case PirType.RecordType record -> record.name();
            default -> throw new IllegalArgumentException("Only Elci records and unions are described: " + representation);
        };
        register(identity, representation, namedTypes, uncheckable);
        // Every Elci named type a codec may reference is defined and described together, so
        // the verifier never sees an Elci type as an undescribed, opaque import.
        for (var entry : namedTypes.entrySet())
            if (entry.getKey().startsWith("elci.")) {
                producer.named.put(entry.getKey(), entry.getValue());
                register(entry.getKey(), entry.getValue(), namedTypes, uncheckable);
            }
        return identity;
    }

    private void register(String identity, PirType representation, Map<String, PirType> namedTypes,
                          List<PirType> uncheckable) {
        var operations = EnumSet.of(LibraryType.Operation.CONSTRUCT, LibraryType.Operation.MATCH,
                LibraryType.Operation.ENCODE);
        if (!contains(representation, uncheckable, namedTypes, new HashSet<>()))
            operations.addAll(EnumSet.of(LibraryType.Operation.DECODE_STRICT, LibraryType.Operation.BOUNDARY));
        var constructors = representation instanceof PirType.SumType sum
                ? sum.constructors().stream().map(c -> new LibraryType.Constructor(c.name(), c.tag(), List.of())).toList()
                : List.<LibraryType.Constructor>of();
        var description = new LibraryType(identity, representation, false, List.of(), constructors, operations);
        var previous = producer.types.putIfAbsent(identity, description);
        if (previous != null && !previous.equals(description))
            throw new IllegalStateException("Conflicting descriptions of " + identity);
    }

    private static boolean contains(PirType type, List<PirType> targets, Map<String, PirType> named, Set<String> visiting) {
        if (targets.contains(type)) return true;
        return switch (type) {
            case PirType.NamedTypeRef ref -> visiting.add(ref.stableId()) && named.containsKey(ref.stableId())
                    && contains(named.get(ref.stableId()), targets, named, visiting);
            case PirType.RecordType r -> r.fields().stream().anyMatch(f -> contains(f.type(), targets, named, visiting));
            case PirType.SumType s -> s.constructors().stream().flatMap(c -> c.fields().stream())
                    .anyMatch(f -> contains(f.type(), targets, named, visiting));
            case PirType.ListType l -> contains(l.elemType(), targets, named, visiting);
            case PirType.MapType m -> contains(m.keyType(), targets, named, visiting)
                    || contains(m.valueType(), targets, named, visiting);
            case PirType.OptionalType o -> contains(o.elemType(), targets, named, visiting);
            default -> false;
        };
    }

    /** Imported representations whose providers do not approve strict decoding. */
    public List<PirType> uncheckable() {
        var result = new ArrayList<PirType>();
        for (var type : java.types().values())
            if ((type.representation() instanceof PirType.RecordType || type.representation() instanceof PirType.SumType)
                    && !type.supports(LibraryType.Operation.DECODE_STRICT))
                result.add(type.representation());
        return result;
    }

    /**
     * Elci's own types as a revision-2 provider without exports: Julc's {@link TypeOperations}
     * materializes codec and operation requests from these descriptions.
     */
    private static final class ProducerTypes implements LibraryProvider {
        final Map<String, LibraryType> types = new LinkedHashMap<>();
        final Map<String, PirType> named = new LinkedHashMap<>();

        @Override public List<LibraryExport> describe(String module) { return List.of(); }
        @Override public org.julclang.compiler.pir.PirTerm materialize(String symbol) {
            throw new IllegalArgumentException("Elci types export no functions: " + symbol);
        }
        @Override public Map<String, LibraryType> types() { return Collections.unmodifiableMap(types); }
        @Override public Map<String, PirType> namedDefinitions() { return Collections.unmodifiableMap(named); }
        @Override public int revision() { return BackendContract.REVISION_2; }
    }
}
