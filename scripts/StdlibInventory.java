import java.util.*;
import org.elci.driver.ElciCompiler;
import org.julclang.compiler.*;
import org.julclang.compiler.backend.*;
import org.julclang.stdlib.StdlibRegistry;

/** Run after installDist: java -cp 'build/install/elci/lib/*' scripts/StdlibInventory.java */
class StdlibInventory {
    public static void main(String[] args) {
        var loader = StdlibInventory.class.getClassLoader();
        var bundled = LibrarySourceResolver.scanClasspathSources(loader);
        for (String owner : new TreeSet<>(bundled.keySet())) {
            if (!owner.startsWith("org.julclang.stdlib.lib.")) continue;
            String library = owner.substring(owner.lastIndexOf('.') + 1);
            try {
                var provider = new JavaLibraryProvider(LibrarySources.resolve(List.of(owner), Map.of(), loader),
                        StdlibRegistry.defaultRegistry(), new CompilerOptions());
                for (var export : provider.describe(owner).stream().sorted(Comparator.comparing(LibraryExport::symbol)).toList()) {
                    String method = export.symbol().substring(export.symbol().lastIndexOf('.') + 1);
                    String parameters = String.join(" ", java.util.stream.IntStream.range(0, export.arity()).mapToObj(i -> "p" + i).toList());
                    String source = "module Main exposing (main)\nimport Library as Lib\nimport Julc.Ledger as Ledger\nmain : " + signature(export) + "\nmain " + parameters
                            + " = Lib." + method + " " + parameters + "\n";
                    try {
                        new ElciCompiler().compile(source, "main", FrontendProgram.Purpose.FUNCTION,
                                Map.of("Library", owner), new CompilerOptions());
                        row(library, method, "COMPILES", signature(export));
                    } catch (Exception error) { row(library, method, "BLOCKED", error.getMessage()); }
                }
                provider.unsupportedExports().entrySet().stream().filter(e -> e.getKey().startsWith(owner + "."))
                        .sorted(Map.Entry.comparingByKey()).forEach(e -> row(library,
                                e.getKey().substring(owner.length() + 1), "UNSUPPORTED", e.getValue()));
            } catch (Exception error) { row(library, "*", "BLOCKED", error.getMessage()); }
        }
    }
    static String signature(LibraryExport e) {
        var parts = new ArrayList<String>();
        for (var parameter : e.parameters()) parts.add(type(parameter));
        parts.add(type(e.result()));
        return String.join(" -> ", parts);
    }
    static String type(LibraryType.Reference ref) {
        if(ref == null)return "legacy PIR signature";
        if(ref.arguments().isEmpty())return ref.name().replace("org.julclang.ledger.", "Ledger.");
        return "(" + ref.name() + " " + String.join(" ",ref.arguments().stream().map(StdlibInventory::type).toList()) + ")";
    }
    static void row(String library, String method, String status, String detail) {
        System.out.println(String.join("\t",library,method,status,detail.replace('\n',' ').replace('\t',' ')));
    }
}
