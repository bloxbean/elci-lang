package org.elci.driver;

import org.elci.syntax.*;
import org.elci.types.*;
import org.elci.lowering.Lowering;
import org.julclang.compiler.*;
import org.julclang.compiler.backend.*;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.core.Program;
import java.util.*;
import static org.elci.types.Types.*;

/** Stateless compiler facade. Each request owns inference, specialization and backend state. */
public final class ElciCompiler {
    public static final String JULC_VERSION="0.1.0-elci-dev";
    public record Result(Program program, String module, String function, Ty type, CompilerTarget target,
                         org.julclang.compiler.pir.PirType pirType,Map<String,org.julclang.compiler.pir.PirType> namedTypes) {
        public Result {namedTypes=Map.copyOf(namedTypes);}
    }
    public Result compile(String source,String function,FrontendProgram.Purpose purpose) {
        return compile(source,function,purpose,Map.of("Julc.Math","org.julclang.stdlib.lib.MathLib"),new CompilerOptions());
    }
    public Result compile(String source,String function,FrontendProgram.Purpose purpose,Map<String,String> javaModules,CompilerOptions options) {
        var module=new Parser(source).parse();
        return compileProject(Map.of(module.name(),source),module.name(),function,purpose,javaModules,options);
    }
    public Result compileProject(Map<String,String> sources,String module,String function,FrontendProgram.Purpose purpose,Map<String,String> javaModules,CompilerOptions options) {
        return compileProject(sources,module,function,purpose,javaModules,options,Map.of());
    }
    public Result compileProject(Map<String,String> sources,String module,String function,FrontendProgram.Purpose purpose,Map<String,String> javaModules,CompilerOptions options,Map<String,String> librarySources) {
        return compileModule(new org.elci.modules.Modules(sources,javaModules).resolve(module),function,purpose,javaModules,options,librarySources);
    }
    private Result compileModule(Ast.Module module,String function,FrontendProgram.Purpose purpose,Map<String,String> javaModules,CompilerOptions options,Map<String,String> librarySources) {
        var importedTypes=new LinkedHashMap<String,Ty>();var importedSymbols=new LinkedHashMap<String,String>();
        importedTypes.put("not",fn(con("Bool"),con("Bool")));
        importedTypes.put("remainderBy",fn(con("Int"),fn(con("Int"),con("Int"))));
        var ledger=new LedgerTypeProvider();var ledgerAliases=new LinkedHashMap<String,String>();
        var requestedLibraries=new ArrayList<String>();
        for(var imp:module.imports()) {
            if(imp.module().equals("Julc.Ledger"))continue;
            String java=javaModules.get(imp.module());
            if(java==null)throw new Diagnostic("ELCI2101",imp.token(),"Unknown module '"+imp.module()+"'");
            requestedLibraries.add(java);
        }
        var sources=LibrarySources.resolve(requestedLibraries,librarySources,ElciCompiler.class.getClassLoader());
        var provider=new JavaLibraryProvider(sources,StdlibRegistry.defaultRegistry(),options);
        var unsupportedImports=new LinkedHashMap<String,String>();
        for(var imp:module.imports()) {
            if(imp.module().equals("Julc.Ledger")) {
                for(String exposed:imp.exposing()) {
                    if(exposed.equals("..")) {
                        ledger.types().keySet().forEach(n->ledgerAliases.put(n.substring(n.lastIndexOf('.')+1),n));
                    } else {
                        String identity="org.julclang.ledger."+exposed;
                        if(!ledger.types().containsKey(identity))throw new Diagnostic("ELCI2101",imp.token(),"Unknown ledger record type "+exposed);
                        ledgerAliases.put(exposed,identity);
                    }
                }
                ledgerAliases.put("ScriptContext","ScriptContext");continue;
            }
            var exports=provider.describe(javaModules.get(imp.module()));var found=new HashSet<String>();
            String owner=javaModules.get(imp.module());
            for(var descriptor:provider.types().values()) {
                String id=descriptor.identity();
                String packageName=owner.contains(".")?owner.substring(0,owner.lastIndexOf('.')+1):"";
                String local=id.startsWith(owner+".")?id.substring(owner.length()+1):id.startsWith(packageName)?id.substring(packageName.length()):null;
                if(local!=null&&!local.contains(".")) {
                    String alias=imp.alias()+"."+local;
                    String previous=ledgerAliases.putIfAbsent(alias,id);
                    if(previous!=null&&!previous.equals(id))throw new Diagnostic("ELCI2102",imp.token(),"Ambiguous imported type "+alias);
                }
            }
            provider.unsupportedExports().forEach((symbol,reason)->{
                if(symbol.startsWith(owner+"."))unsupportedImports.put(imp.alias()+symbol.substring(owner.length()),symbol+": "+reason);
            });
            for(var export:exports) {
                String n=export.symbol().substring(export.symbol().lastIndexOf('.')+1);Ty type;
                type=sourceType(export);
                found.add(n);addImport(imp.alias()+"."+n,type,export.symbol(),imp.token(),importedTypes,importedSymbols);
                if(imp.exposing().contains(n)||imp.exposing().contains(".."))addImport(n,type,export.symbol(),imp.token(),importedTypes,importedSymbols);
            }
            for(String exposed:imp.exposing())if(!exposed.equals("..")&&!found.contains(exposed))throw new Diagnostic("ELCI2101",imp.token(),"No supported export '"+exposed+"' in "+imp.module());
        }
        for(var d:module.declarations())if(importedTypes.containsKey(d.name()))throw new Diagnostic("ELCI2102",d.token(),"Declaration conflicts with imported name "+d.name());
        var entry=module.declarations().stream().filter(d->d.name().equals(function)).findFirst().orElseThrow(()->new IllegalArgumentException("Entrypoint not found: "+function));
        if(!module.exposing().contains(function)&&!module.exposing().contains(".."))throw new Diagnostic("ELCI2103",entry.token(),"Entrypoint must be exposed by its module");
        var inference=new Inference();inference.aliases(module.aliases());inference.unions(module.name(),module.unions());inference.ledger(ledger,ledgerAliases);inference.library(provider);inference.unsupportedImports.putAll(unsupportedImports);inference.check(module.declarations(),importedTypes);
        Ty entryType=inference.globals.get(function).body();
        if(!Types.concrete(entryType))throw new Diagnostic("ELCI2009",entry.token(),"Deployed entrypoints require concrete types");
        if(purpose!=FrontendProgram.Purpose.FUNCTION) {
            var expected=purpose==FrontendProgram.Purpose.SPEND?fn(new Var(),fn(new Var(),fn(con("ScriptContext"),con("Bool")))):fn(new Var(),fn(con("ScriptContext"),con("Bool")));
            unify(entryType,expected,entry.token());
            Ty boundaryType=entryType;
            int boundaryCount=purpose==FrontendProgram.Purpose.SPEND?2:1;
            for(int i=0;i<boundaryCount;i++) {
                var arrow=(Con)prune(boundaryType);checkBoundary(arrow.args().get(0),inference,new HashSet<>(),entry.token());boundaryType=arrow.args().get(1);
            }
            if(module.imports().stream().noneMatch(i->i.module().equals("Julc.Ledger")&&(i.exposing().contains("ScriptContext")||i.exposing().contains(".."))))throw new Diagnostic("ELCI2101",entry.token(),"Import Julc.Ledger exposing (ScriptContext)");
        }
        var lowering=new Lowering(module.declarations(),inference,importedSymbols,provider);
        var pir=lowering.lower(function,entryType);
        var frontend=new FrontendProgram(pir,lowering.pir(entryType),options.getTarget(),new FrontendProgram.Entrypoint(module.name()+"."+function,purpose,"julc-strict-v1"),lowering.namedTypes());
        var backend=new CompilerBackend().compile(frontend,options);
        return new Result(backend.program(),module.name(),function,entryType,options.getTarget(),frontend.type(),frontend.namedTypes());
    }
    private static void checkBoundary(Ty type,Inference inference,Set<String> visiting,Token token) {
        if(visiting.size()>64)throw new Diagnostic("ELCI2013",token,"Expanding recursive boundary type is unsupported");
        var t=(Con)prune(type);
        if(t.name().equals("ScriptContext"))throw new Diagnostic("ELCI2013",token,"ScriptContext is only valid in the ledger context argument, not serialized user data");
        if(inference.importedRepresentations.containsKey(t.name())&&!inference.byteEquality(t.name()))throw new Diagnostic("ELCI2013",token,"Imported ledger records are currently context views; use an Elci data type for datum/redeemer");
        if(t.name().equals("->"))throw new Diagnostic("ELCI2013",token,"Functions cannot be serialized as datum or redeemer");
        t.args().forEach(a->checkBoundary(a,inference,visiting,token));
        for(var union:inference.unions.values())if(inference.nominal(union.name()).equals(t.name())&&visiting.add(t.toString())) {
            var vars=new HashMap<String,Ty>();for(int i=0;i<union.parameters().size();i++)vars.put(union.parameters().get(i),t.args().get(i));
            for(var variant:union.variants())for(var field:variant.fields())checkBoundary(inference.annotatedPublic(field,vars),inference,visiting,token);
            visiting.remove(t.toString());
        }
    }
    private static void addImport(String name,Ty type,String symbol,Token token,Map<String,Ty> types,Map<String,String> symbols) {
        if(types.putIfAbsent(name,type)!=null)throw new Diagnostic("ELCI2102",token,"Ambiguous imported name "+name);
        symbols.put(name,symbol);
    }
    private static Ty sourceType(org.julclang.compiler.backend.LibraryExport export) {
        if(export.result()==null)return Lowering.source(export.type());
        Ty result=sourceType(export.result());
        for(int i=export.parameters().size()-1;i>=0;i--)result=fn(sourceType(export.parameters().get(i)),result);
        return result;
    }
    private static Ty sourceType(LibraryType.Reference reference) {
        return new Con(reference.name().equals("org.julclang.ledger.ScriptContext")?"ScriptContext":reference.name(),reference.arguments().stream().map(ElciCompiler::sourceType).toList());
    }
}
