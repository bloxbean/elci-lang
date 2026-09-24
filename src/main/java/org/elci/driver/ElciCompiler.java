package org.elci.driver;

import org.elci.syntax.*;
import org.elci.types.*;
import org.elci.lowering.Lowering;
import org.julclang.compiler.*;
import org.julclang.compiler.backend.*;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.schema.ContractSchema;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.core.Program;
import java.util.*;
import static org.elci.types.Types.*;

/** Stateless compiler facade. Each request owns inference, specialization and backend state. */
public final class ElciCompiler {
    public static final String JULC_VERSION=BuildInfo.JULC_VERSION;
    public record Result(Program program, String module, String function, Ty type, CompilerTarget target,
                         org.julclang.compiler.pir.PirType pirType,Map<String,org.julclang.compiler.pir.PirType> namedTypes) {
        public Result {namedTypes=Map.copyOf(namedTypes);}
    }

    /** One configured handler of a named validator: the purpose that selects it and its source function. */
    public record HandlerSpec(ContractSchema.Purpose purpose, String module, String function) {
        public HandlerSpec {Objects.requireNonNull(purpose);Objects.requireNonNull(module);Objects.requireNonNull(function);}
        public String symbol() {return module+"."+function;}
    }

    /**
     * A named validator: one deployed script whose ledger purpose selects exactly one handler.
     *
     * @param name       the validator identity, also its artifact directory name
     * @param handlers   purpose-indexed handlers; order does not affect the script
     * @param parameters deployment parameter names, in application order
     * @param spendDatum how the spending handler receives its datum
     */
    public record ValidatorSpec(String name, List<HandlerSpec> handlers, List<String> parameters, DatumProfile spendDatum) {
        public ValidatorSpec {handlers=List.copyOf(handlers);parameters=List.copyOf(parameters);Objects.requireNonNull(spendDatum);}
    }

    /** A handler's source and boundary types, in ledger-tag order. */
    public record HandlerInfo(ContractSchema.Purpose purpose, int tag, String symbol, DatumProfile datum,
                              Ty datumSource, PirType datumType, Ty redeemerSource, PirType redeemerType) {}

    /** A deployment parameter's source and boundary types. */
    public record ParameterInfo(String name, Ty source, PirType type) {}

    /** A compiled named validator: the backend result plus the source-level view of its boundary. */
    public record ValidatorBuild(String name, ValidatorResult result, CompilerTarget target, List<ParameterInfo> parameters,
                                 List<HandlerInfo> handlers, Map<String,PirType> namedTypes) {
        public ValidatorBuild {parameters=List.copyOf(parameters);handlers=List.copyOf(handlers);namedTypes=Map.copyOf(namedTypes);}
        public Program program() {return result.program();}
    }

    /** Imported names, types and the library provider shared by both compilation paths. */
    private record Frontend(Ast.Module module, Inference inference, Map<String,Ty> importedTypes,
                            Map<String,String> importedSymbols, LibraryProvider provider) {}

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
        var frontend=frontend(module,javaModules,options,librarySources);
        var entry=module.declarations().stream().filter(d->d.name().equals(function)).findFirst().orElseThrow(()->new IllegalArgumentException("Entrypoint not found: "+function));
        if(!module.exposing().contains(function)&&!module.exposing().contains(".."))throw new Diagnostic("ELCI2103",entry.token(),"Entrypoint must be exposed by its module");
        var inference=frontend.inference();inference.check(module.declarations(),frontend.importedTypes());
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
        var lowering=new Lowering(module.declarations(),inference,frontend.importedSymbols(),frontend.provider());
        var pir=lowering.lower(function,entryType);
        var frontendProgram=new FrontendProgram(pir,lowering.pir(entryType),options.getTarget(),new FrontendProgram.Entrypoint(module.name()+"."+function,purpose,"julc-strict-v1"),lowering.namedTypes());
        var backend=new CompilerBackend().compile(frontendProgram,options);
        return new Result(backend.program(),module.name(),function,entryType,options.getTarget(),frontendProgram.type(),frontendProgram.namedTypes());
    }

    /**
     * Compile a named validator (ADR-004) as one revision-2 {@link ValidatorProgram}: handlers
     * share program-level definitions and linked library imports, and Julc's wrapper decodes
     * only the selected purpose's datum and redeemer.
     */
    public ValidatorBuild compileValidator(Map<String,String> sources,ValidatorSpec spec,Map<String,String> javaModules,CompilerOptions options,Map<String,String> librarySources) {
        if(spec.handlers().isEmpty())throw new IllegalArgumentException("Validator "+spec.name()+" has no handlers");
        var purposes=EnumSet.noneOf(ContractSchema.Purpose.class);
        for(var handler:spec.handlers())if(!purposes.add(handler.purpose()))throw new IllegalArgumentException("Validator "+spec.name()+" configures "+purpose(handler.purpose())+" more than once");
        if(spec.spendDatum()!=DatumProfile.REQUIRED&&!purposes.contains(ContractSchema.Purpose.SPEND))throw new IllegalArgumentException("Validator "+spec.name()+" sets a spending datum profile without a spend handler");
        var modules=new org.elci.modules.Modules(sources,javaModules);
        for(var handler:spec.handlers()) {
            if(!sources.containsKey(handler.module()))throw new IllegalArgumentException("Handler "+handler.symbol()+" for "+purpose(handler.purpose())+": no source module "+handler.module());
            var source=modules.module(handler.module());
            if(!modules.declares(handler.module(),handler.function()))throw new IllegalArgumentException("Handler "+handler.symbol()+" for "+purpose(handler.purpose())+": module "+handler.module()+" does not declare "+handler.function());
            if(!modules.exposes(handler.module(),handler.function())) {
                var declaration=source.declarations().stream().filter(d->d.name().equals(handler.function())).findFirst().orElseThrow();
                throw new Diagnostic("ELCI2103",declaration.token(),"Handler "+handler.symbol()+" must be exposed by its module");
            }
        }
        var module=modules.resolveAll(spec.handlers().stream().map(HandlerSpec::module).toList());
        var frontend=frontend(module,javaModules,options,librarySources);
        var inference=frontend.inference();inference.check(module.declarations(),frontend.importedTypes());
        var declarations=new HashMap<String,Ast.Decl>();module.declarations().forEach(d->declarations.put(d.name(),d));

        var ordered=spec.handlers().stream().sorted(Comparator.comparingInt(h->BackendContract.ledgerTag(h.purpose()))).toList();
        var parameterTypes=new ArrayList<Ty>();for(int i=0;i<spec.parameters().size();i++)parameterTypes.add(new Var());
        record Shape(HandlerSpec spec,Ty type,Ty datum,Ty redeemer,Ast.Decl declaration) {}
        var shapes=new ArrayList<Shape>();
        for(var handler:ordered) {
            var declaration=declarations.get(handler.symbol());
            Ty type=inference.globals.get(handler.symbol()).body();
            if(!Types.concrete(type))throw new Diagnostic("ELCI2009",declaration.token(),"Handler "+handler.symbol()+" requires a concrete type");
            boolean hasDatum=handler.purpose()==ContractSchema.Purpose.SPEND&&spec.spendDatum()!=DatumProfile.ABSENT;
            Ty datum=hasDatum?new Var():null, redeemer=new Var();
            Ty expected=fn(redeemer,fn(con("ScriptContext"),con("Bool")));
            if(hasDatum)expected=fn(datum,expected);
            for(int i=parameterTypes.size()-1;i>=0;i--)expected=fn(parameterTypes.get(i),expected);
            try {unify(copy(type,new HashMap<>()),expected,declaration.token());}
            catch(Diagnostic e){throw new Diagnostic("ELCI2014",declaration.token(),"Handler "+handler.symbol()+" does not have the "+purpose(handler.purpose())+" signature "+signature(spec,handler.purpose())+": "+e.getMessage());}
            if(hasDatum&&spec.spendDatum()==DatumProfile.OPTIONAL) {
                var maybe=prune(datum);
                if(!(maybe instanceof Con c)||!c.name().equals("Elci.Maybe"))throw new Diagnostic("ELCI2014",declaration.token(),"Handler "+handler.symbol()+" uses spend-datum = \"optional\", so its datum must have type Maybe d, not "+maybe);
                checkBoundary(c.args().getFirst(),inference,new HashSet<>(),declaration.token());
            } else if(hasDatum)checkBoundary(datum,inference,new HashSet<>(),declaration.token());
            checkBoundary(redeemer,inference,new HashSet<>(),declaration.token());
            shapes.add(new Shape(handler,type,datum==null?null:prune(datum),prune(redeemer),declaration));
        }
        var firstToken=shapes.getFirst().declaration().token();
        for(var parameter:parameterTypes)checkBoundary(parameter,inference,new HashSet<>(),firstToken);

        var lowering=new Lowering(module.declarations(),inference,frontend.importedSymbols(),frontend.provider(),Lowering.ImportMode.LINK);
        var handlers=new ArrayList<ValidatorProgram.Handler>();var infos=new ArrayList<HandlerInfo>();
        var required=new TreeSet<BackendCapability>();required.add(BackendCapability.VALIDATOR_PROGRAM);required.add(BackendCapability.STRICT_BOUNDARY_V1);
        if(!spec.parameters().isEmpty())required.add(BackendCapability.VALIDATOR_PARAMETERS);
        for(var shape:shapes) {
            var purpose=shape.spec().purpose();
            required.add(BackendContract.purposeCapability(purpose));
            PirTerm term=lowering.entry(shape.spec().symbol(),shape.type());
            PirType type=lowering.pir(shape.type());
            DatumProfile profile=purpose==ContractSchema.Purpose.SPEND?spec.spendDatum():DatumProfile.ABSENT;
            PirType datumType=null;Ty datumSource=shape.datum();
            if(purpose==ContractSchema.Purpose.SPEND)required.add(switch(profile){case REQUIRED->BackendCapability.SPEND_DATUM_REQUIRED;case OPTIONAL->BackendCapability.SPEND_DATUM_OPTIONAL;case ABSENT->BackendCapability.SPEND_DATUM_ABSENT;});
            if(profile==DatumProfile.OPTIONAL) {
                Ty payload=((Con)datumSource).args().getFirst();
                datumType=new PirType.OptionalType(lowering.pir(payload));
                var adapted=optionalDatum(term,type,spec.parameters().size(),datumType,lowering.pir(datumSource),lowering.pir(payload));
                term=adapted;type=replaceArgument(type,spec.parameters().size(),datumType);
                datumSource=payload;
            } else if(profile==DatumProfile.REQUIRED)datumType=lowering.pir(datumSource);
            handlers.add(new ValidatorProgram.Handler(purpose,shape.spec().symbol(),term,type,profile));
            infos.add(new HandlerInfo(purpose,BackendContract.ledgerTag(purpose),shape.spec().symbol(),profile,datumSource,datumType,shape.redeemer(),lowering.pir(shape.redeemer())));
        }
        var parameters=new ArrayList<Parameter>();var parameterInfos=new ArrayList<ParameterInfo>();
        for(int i=0;i<spec.parameters().size();i++) {
            var pt=lowering.pir(parameterTypes.get(i));
            parameters.add(new Parameter(spec.parameters().get(i),pt));
            parameterInfos.add(new ParameterInfo(spec.parameters().get(i),prune(parameterTypes.get(i)),pt));
        }
        var imports=lowering.imports();
        if(!imports.isEmpty()){required.add(BackendCapability.LIBRARY_IMPORTS);required.add(BackendCapability.LIBRARY_REQUEST_EXPORT);}
        // Named definitions are records and sums; newtype identities are representations only.
        // Imported named types come from their import groups (ADR-059).
        var namedTypes=new TreeMap<String,PirType>();
        lowering.namedTypes().forEach((id,type)->{if(type instanceof PirType.RecordType||type instanceof PirType.SumType)namedTypes.put(id,type);});
        for(var group:imports)namedTypes.keySet().removeAll(group.namedTypes().keySet());
        var program=new ValidatorProgram(BackendContract.REVISION_2,required,spec.name(),options.getTarget(),BackendContract.STRICT_BOUNDARY_V1,
                namedTypes,imports,lowering.definitions(),parameters,handlers);
        var backend=new CompilerBackend();
        backend.capabilities(options).require(required,"validator "+spec.name());
        var result=backend.compile(program,options);
        var allTypes=new LinkedHashMap<>(lowering.namedTypes());for(var group:imports)group.namedTypes().forEach(allTypes::putIfAbsent);
        return new ValidatorBuild(spec.name(),result,options.getTarget(),parameterInfos,infos,allTypes);
    }

    /** The source signature a handler of {@code purpose} must have, for diagnostics. */
    private static String signature(ValidatorSpec spec,ContractSchema.Purpose purpose) {
        var parts=new ArrayList<String>();
        for(String parameter:spec.parameters())parts.add(parameter);
        if(purpose==ContractSchema.Purpose.SPEND&&spec.spendDatum()!=DatumProfile.ABSENT)parts.add(spec.spendDatum()==DatumProfile.OPTIONAL?"Maybe datum":"datum");
        parts.add("redeemer");parts.add("ScriptContext");parts.add("Bool");
        return String.join(" -> ",parts);
    }

    /** The configuration key of a ledger purpose. */
    public static String purpose(ContractSchema.Purpose purpose) {
        return switch(purpose){case MINT->"mint";case SPEND->"spend";case WITHDRAW->"withdraw";case CERTIFY->"certify";case VOTE->"vote";case PROPOSE->"propose";};
    }

    /**
     * Adapt a handler whose datum is Elci's {@code Maybe d} to Julc's optional-datum profile,
     * which passes the ledger {@code Maybe} ({@code Constr 0 [d]} or {@code Constr 1 []}).
     * Elci's {@code Maybe} tags {@code Nothing} 0 and {@code Just} 1, so the adapter re-tags the
     * strictly checked value before calling the handler.
     */
    private static PirTerm optionalDatum(PirTerm handler,PirType handlerType,int parameterCount,PirType optional,PirType maybe,PirType payload) {
        var arguments=new ArrayList<PirTerm.Var>();var types=new ArrayList<PirType>();
        PirType rest=handlerType;int index=0;
        while(rest instanceof PirType.FunType f) {
            PirType argument=index==parameterCount?optional:f.paramType();
            arguments.add(new PirTerm.Var("$elci$handler$"+index,argument));types.add(argument);rest=f.returnType();index++;
        }
        var raw=arguments.get(parameterCount);
        var fields=Lowering.call(org.julclang.core.DefaultFun.SndPair,Lowering.call(org.julclang.core.DefaultFun.UnConstrData,raw));
        PirTerm just=new PirTerm.DataConstr(1,maybe,List.of(org.julclang.compiler.pir.PirHelpers.wrapDecode(Lowering.call(org.julclang.core.DefaultFun.HeadList,fields),payload)));
        PirTerm nothing=new PirTerm.DataConstr(0,maybe,List.of());
        var tag=Lowering.call(org.julclang.core.DefaultFun.FstPair,Lowering.call(org.julclang.core.DefaultFun.UnConstrData,raw));
        PirTerm converted=new PirTerm.IfThenElse(Lowering.call(org.julclang.core.DefaultFun.EqualsInteger,tag,new PirTerm.Const(org.julclang.core.Constant.integer(0))),just,nothing);
        PirTerm body=handler;
        for(int i=0;i<arguments.size();i++)body=new PirTerm.App(body,i==parameterCount?converted:arguments.get(i));
        for(int i=arguments.size()-1;i>=0;i--)body=new PirTerm.Lam(arguments.get(i).name(),types.get(i),body);
        return body;
    }

    private static PirType replaceArgument(PirType type,int index,PirType replacement) {
        var f=(PirType.FunType)type;
        return index==0?new PirType.FunType(replacement,f.returnType()):new PirType.FunType(f.paramType(),replaceArgument(f.returnType(),index-1,replacement));
    }

    /** Resolve imports, ledger types and library exports; inference is prepared but not run. */
    private Frontend frontend(Ast.Module module,Map<String,String> javaModules,CompilerOptions options,Map<String,String> librarySources) {
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
        var inference=new Inference();inference.aliases(module.aliases());inference.unions(module.name(),module.unions());inference.ledger(ledger,ledgerAliases);inference.library(provider);inference.unsupportedImports.putAll(unsupportedImports);
        return new Frontend(module,inference,importedTypes,importedSymbols,provider);
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
