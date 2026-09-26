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
    private final java.util.function.Function<CompilerOptions,BackendCapabilities> capabilities;

    public ElciCompiler() {this(options->new CompilerBackend().capabilities(options));}

    /**
     * A compiler that negotiates against the given backend capabilities, for example to check a
     * project against an older backend's declared capability set.
     */
    public ElciCompiler(java.util.function.Function<CompilerOptions,BackendCapabilities> capabilities) {this.capabilities=capabilities;}
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
    /** @param canonical per parameter, the rewrite that sorts its Dicts' keys: a value it changes is rejected (ADR-009) */
    public record ValidatorBuild(String name, ValidatorResult result, CompilerTarget target, List<ParameterInfo> parameters,
                                 List<HandlerInfo> handlers, Map<String,PirType> namedTypes,
                                 List<java.util.function.UnaryOperator<org.julclang.core.PlutusData>> canonical) {
        public ValidatorBuild {parameters=List.copyOf(parameters);handlers=List.copyOf(handlers);namedTypes=Map.copyOf(namedTypes);canonical=List.copyOf(canonical);}
        public Program program() {return result.program();}
    }

    /** Imported names, types and the library provider shared by both compilation paths. */
    private record Frontend(Ast.Module module, Inference inference, Map<String,Ty> importedTypes,
                            Map<String,String> importedSymbols, Map<String,LibraryScheme> schemes, Libraries libraries) {}

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
        javaModules=org.elci.modules.Modules.withCoreLibraries(javaModules);
        var modules=new org.elci.modules.Modules(sources,javaModules);
        var java=javaProvider(modules,List.of(module),options,librarySources);
        try {return compileModule(modules.resolve(module),function,purpose,javaModules,options,java);}
        catch(Diagnostic d){throw modules.withSuggestions(d);}
    }
    /**
     * The Java-source provider for the libraries reachable from {@code roots}. It is built before
     * name resolution because its type descriptions decide which imported constructors exist.
     */
    private static JavaLibraryProvider javaProvider(org.elci.modules.Modules modules,List<String> roots,CompilerOptions options,Map<String,String> librarySources) {
        var sources=LibrarySources.resolve(modules.javaLibraries(roots),librarySources,ElciCompiler.class.getClassLoader());
        var java=new JavaLibraryProvider(sources,StdlibRegistry.defaultRegistry(),options);
        modules.catalogue(java.types());
        return java;
    }
    private Result compileModule(Ast.Module module,String function,FrontendProgram.Purpose purpose,Map<String,String> javaModules,CompilerOptions options,JavaLibraryProvider java) {
        // Validators are named and compiled with compileValidator (ADR-007).
        if(purpose!=FrontendProgram.Purpose.FUNCTION)throw new IllegalArgumentException("Only function entrypoints compile here; validators are [validators.<name>] groups compiled with compileValidator");
        var frontend=frontend(module,javaModules,options,java);
        var entry=module.declarations().stream().filter(d->d.name().equals(function)).findFirst().orElseThrow(()->new IllegalArgumentException("Entrypoint not found: "+function));
        if(!module.exposing().contains(function)&&!module.exposing().contains(".."))throw new Diagnostic("ELCI2103",entry.token(),"Entrypoint must be exposed by its module");
        var inference=frontend.inference();inference.check(module.declarations(),frontend.importedTypes());
        Ty entryType=inference.globals.get(function).body();
        if(!Types.concrete(entryType))throw new Diagnostic("ELCI2009",entry.token(),"Entrypoints require concrete types");
        var lowering=new Lowering(module.declarations(),inference,frontend.importedSymbols(),frontend.schemes(),frontend.libraries(),Lowering.ImportMode.INLINE);
        var pir=lowering.lower(function,entryType);
        var frontendProgram=new FrontendProgram(pir,lowering.pir(entryType),options.getTarget(),new FrontendProgram.Entrypoint(module.name()+"."+function,purpose,"julc-strict-v1"),lowering.namedTypes());
        PirBackend.Result backend;
        try {backend=new CompilerBackend().compile(frontendProgram,options);}
        catch(BackendException e){var byName=new HashMap<String,Ast.Decl>();module.declarations().forEach(d->byName.put(d.name(),d));throw located(e,byName,entry.token());}
        return new Result(backend.program(),module.name(),function,entryType,options.getTarget(),frontendProgram.type(),frontendProgram.namedTypes());
    }

    /**
     * An exposed test declaration and its inferred type: a value, or a property over
     * {@code parameters} whose generated arguments have the Data representations {@code arguments}.
     */
    /**
     * @param canonical per parameter, a rewrite of generated Data into a value the program decodes:
     *                  every Dict with strictly ascending keys (ADR-009)
     */
    public record Value(String name,Ty type,Token token,List<Ty> parameters,List<PirType> arguments,
                        List<java.util.function.UnaryOperator<org.julclang.core.PlutusData>> canonical) {
        public Value {parameters=List.copyOf(parameters);arguments=List.copyOf(arguments);canonical=List.copyOf(canonical);}
        public Value(String name,Ty type,Token token) {this(name,type,token,List.of(),List.of(),List.of());}
        public boolean property() {return !parameters.isEmpty();}
    }

    /**
     * Type-check {@code module} once and compile each exposed, zero-argument declaration that
     * {@code select} accepts into its own function program, as an {@code [entrypoint]} with
     * {@code purpose = "function"} would be compiled.
     */
    public Map<Value,Result> compileValues(Map<String,String> sources,String module,java.util.function.Predicate<Ty> select,Map<String,String> javaModules,CompilerOptions options,Map<String,String> librarySources) {
        return compileExposed(sources,module,select,null,javaModules,options,librarySources);
    }

    /**
     * {@link #compileValues}, plus properties (ADR-006): exposed functions whose result
     * {@code properties} accepts and whose parameters are Data-encodable. Each compiles to a
     * program taking every argument as Data, decoded strictly; its {@link Result} describes the
     * result. Used by {@code elci test}, where every test must run in isolation.
     */
    public Map<Value,Result> compileTests(Map<String,String> sources,String module,java.util.function.Predicate<Ty> values,java.util.function.Predicate<Ty> properties,Map<String,String> javaModules,CompilerOptions options,Map<String,String> librarySources) {
        return compileExposed(sources,module,values,Objects.requireNonNull(properties),javaModules,options,librarySources);
    }

    private Map<Value,Result> compileExposed(Map<String,String> sources,String module,java.util.function.Predicate<Ty> select,java.util.function.Predicate<Ty> properties,Map<String,String> javaModules,CompilerOptions options,Map<String,String> librarySources) {
        javaModules=org.elci.modules.Modules.withCoreLibraries(javaModules);
        var modules=new org.elci.modules.Modules(sources,javaModules);
        var java=javaProvider(modules,List.of(module),options,librarySources);
        var resolved=modules.resolve(module);
        var frontend=frontend(resolved,javaModules,options,java);
        var inference=frontend.inference();
        try {inference.check(resolved.declarations(),frontend.importedTypes());}
        catch(Diagnostic d){throw modules.withSuggestions(d);}
        var results=new LinkedHashMap<Value,Result>();
        for(var declaration:resolved.declarations()) {
            // Only the root module's own declarations; imported ones are module-qualified.
            if(declaration.name().contains(".")||properties==null&&!declaration.parameters().isEmpty())continue;
            if(!resolved.exposing().contains(declaration.name())&&!resolved.exposing().contains(".."))continue;
            Ty type=prune(inference.globals.get(declaration.name()).body());
            var parameters=new ArrayList<Ty>();
            Ty result=type;
            if(properties!=null)
                while(result instanceof Con arrow&&arrow.name().equals("->")) {parameters.add(prune(arrow.args().get(0)));result=prune(arrow.args().get(1));}
            if(parameters.isEmpty()?!Types.concrete(type)||!select.test(result):!properties.test(result))continue;
            if(!Types.concrete(type))
                throw new Diagnostic("ELCI2009",declaration.token(),"Property "+declaration.name()+" needs concrete parameter types; add a type annotation");
            if(!parameters.stream().allMatch(ElciCompiler::generatable))
                throw new Diagnostic("ELCI2009",declaration.token(),"Property "+declaration.name()+" needs Data-encodable parameters to generate; functions, arrays and ScriptContext cannot be generated (take a List and use Array.fromList)");
            var lowering=new Lowering(resolved.declarations(),inference,frontend.importedSymbols(),frontend.schemes(),frontend.libraries(),Lowering.ImportMode.INLINE);
            PirTerm pir;
            PirType programType=lowering.pir(result);
            var arguments=new ArrayList<PirType>();
            if(parameters.isEmpty())pir=lowering.lower(declaration.name(),type);
            else {
                pir=lowering.lowerProperty(declaration.name(),type,parameters,declaration.token());
                for(var parameter:parameters) {arguments.add(lowering.pir(parameter));programType=new PirType.FunType(new PirType.DataType(),programType);}
            }
            var program=new FrontendProgram(pir,programType,options.getTarget(),new FrontendProgram.Entrypoint(module+"."+declaration.name(),FrontendProgram.Purpose.FUNCTION,"julc-strict-v1"),lowering.namedTypes());
            PirBackend.Result backend;
            try {backend=new CompilerBackend().compile(program,options);}
            catch(BackendException e){var byName=new HashMap<String,Ast.Decl>();resolved.declarations().forEach(d->byName.put(d.name(),d));throw located(e,byName,declaration.token());}
            var canonical=new DictCanonical(inference);
            results.put(new Value(declaration.name(),type,declaration.token(),parameters,arguments,parameters.stream().map(canonical::of).toList()),new Result(backend.program(),module,declaration.name(),result,options.getTarget(),lowering.pir(result),program.namedTypes()));
        }
        return results;
    }

    /** Whether values of {@code type} can be generated as Data: no functions and no ledger context. */
    private static boolean generatable(Ty type) {
        var c=(Con)prune(type);
        if(Set.of("->","Array","ScriptContext","org.julclang.ledger.ScriptContext").contains(c.name()))return false;
        return c.args().stream().allMatch(ElciCompiler::generatable);
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
        var available=capabilities.apply(options);
        requireRoles(available,spec);
        javaModules=org.elci.modules.Modules.withCoreLibraries(javaModules);
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
        var roots=spec.handlers().stream().map(HandlerSpec::module).distinct().toList();
        var java=javaProvider(modules,roots,options,librarySources);
        var module=modules.resolveAll(roots);
        var frontend=frontend(module,javaModules,options,java);
        var inference=frontend.inference();
        try {inference.check(module.declarations(),frontend.importedTypes());}
        catch(Diagnostic d){throw modules.withSuggestions(d);}
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
            catch(Diagnostic e){throw new Diagnostic("ELCI2014",declaration.token(),"Handler "+handler.symbol()+" does not have the "+purpose(handler.purpose())+" signature "+signature(spec,handler.purpose())
                    +" that [validators."+spec.name()+"] needs ("+spec.parameters().size()+" parameter"+(spec.parameters().size()==1?"":"s")+"); its type is "+Types.show(type)+" ("+e.getMessage()+")");}
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

        var lowering=new Lowering(module.declarations(),inference,frontend.importedSymbols(),frontend.schemes(),frontend.libraries(),Lowering.ImportMode.LINK);
        var handlers=new ArrayList<ValidatorProgram.Handler>();var infos=new ArrayList<HandlerInfo>();
        var required=new TreeSet<BackendCapability>();required.add(BackendCapability.VALIDATOR_PROGRAM);required.add(BackendCapability.STRICT_BOUNDARY_V1);
        if(!spec.parameters().isEmpty())required.add(BackendCapability.VALIDATOR_PARAMETERS);
        for(var shape:shapes) {
            var purpose=shape.spec().purpose();
            required.add(BackendContract.purposeCapability(purpose));
            PirTerm term=lowering.guardDicts(lowering.entry(shape.spec().symbol(),shape.type()),shape.type(),shape.declaration().token());
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
        required.addAll(frontend.libraries().used());
        // Named definitions are records and sums; newtype identities are representations only.
        // Imported named types come from their import groups (ADR-059).
        var namedTypes=new TreeMap<String,PirType>();
        lowering.namedTypes().forEach((id,type)->{if(type instanceof PirType.RecordType||type instanceof PirType.SumType)namedTypes.put(id,type);});
        for(var group:imports)namedTypes.keySet().removeAll(group.namedTypes().keySet());
        var program=new ValidatorProgram(BackendContract.REVISION_2,required,spec.name(),options.getTarget(),BackendContract.STRICT_BOUNDARY_V1,
                namedTypes,imports,lowering.definitions(),parameters,handlers);
        var backend=new CompilerBackend();
        available.require(required,"validator "+spec.name());
        ValidatorResult result;
        try {result=backend.compile(program,options);}
        catch(BackendException e){throw located(e,declarations,shapes.getFirst().declaration().token());}
        var allTypes=new LinkedHashMap<>(lowering.namedTypes());for(var group:imports)group.namedTypes().forEach(allTypes::putIfAbsent);
        var canonical=new DictCanonical(inference);
        return new ValidatorBuild(spec.name(),result,options.getTarget(),parameterInfos,infos,allTypes,
                parameterTypes.stream().map(canonical::of).toList());
    }

    /**
     * Check the backend's contract revision and the role capabilities a validator needs before
     * any lowering or materialization.
     */
    private static void requireRoles(BackendCapabilities available,ValidatorSpec spec) {
        String subject="validator "+spec.name();
        if(available.revision()<BackendContract.REVISION_2)
            throw new IllegalArgumentException("ELCI4003: "+subject+" needs Julc backend contract revision 2; the backend implements "+available.revision());
        var needed=new TreeSet<BackendCapability>();
        needed.add(BackendCapability.VALIDATOR_PROGRAM);needed.add(BackendCapability.STRICT_BOUNDARY_V1);
        if(!spec.parameters().isEmpty())needed.add(BackendCapability.VALIDATOR_PARAMETERS);
        for(var handler:spec.handlers()) {
            needed.add(BackendContract.purposeCapability(handler.purpose()));
            if(handler.purpose()==ContractSchema.Purpose.SPEND)needed.add(switch(spec.spendDatum()){case REQUIRED->BackendCapability.SPEND_DATUM_REQUIRED;case OPTIONAL->BackendCapability.SPEND_DATUM_OPTIONAL;case ABSENT->BackendCapability.SPEND_DATUM_ABSENT;});
        }
        for(var capability:needed)if(!available.provides(capability))
            throw new IllegalArgumentException("ELCI4003: the Julc backend (contract revision "+available.revision()+", target "+available.target().profileId()+") does not provide "+capability+", required by "+subject);
    }

    private static final java.util.regex.Pattern DEFINITION=java.util.regex.Pattern.compile("\\$elci\\$([A-Za-z_][A-Za-z0-9_.]*)\\$[0-9]+");

    /**
     * Map a backend rejection to the Elci declaration it names. Elci's definitions are named
     * {@code $elci$<declaration>$<n>} and handlers by their source symbol; the backend's
     * catalogued code stays in the message.
     */
    static Diagnostic located(BackendException e,Map<String,Ast.Decl> declarations,Token fallback) {
        String message=e.getMessage();
        var matcher=DEFINITION.matcher(message);
        while(matcher.find()) {
            var declaration=declarations.get(matcher.group(1));
            if(declaration!=null)return new Diagnostic("ELCI4002",declaration.token(),"Julc rejected the PIR generated for "+matcher.group(1)+": "+message);
        }
        for(var entry:new TreeMap<>(declarations).entrySet())
            if(message.contains(entry.getKey()+" ")||message.endsWith(entry.getKey()))
                return new Diagnostic("ELCI4002",entry.getValue().token(),"Julc rejected "+entry.getKey()+": "+message);
        return new Diagnostic("ELCI4002",fallback,"Julc rejected the program: "+message);
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
    private Frontend frontend(Ast.Module module,Map<String,String> javaModules,CompilerOptions options,JavaLibraryProvider java) {
        var importedTypes=new LinkedHashMap<String,Ty>();var importedSymbols=new LinkedHashMap<String,String>();
        String basics=org.elci.modules.Modules.BASICS_PREFIX;
        importedTypes.put(basics+"not",fn(con("Bool"),con("Bool")));
        importedTypes.put(basics+"remainderBy",fn(con("Int"),fn(con("Int"),con("Int"))));
        importedTypes.put(basics+"modBy",fn(con("Int"),fn(con("Int"),con("Int"))));
        intrinsics(importedTypes);
        var ledger=new LedgerTypeProvider();var ledgerAliases=new LinkedHashMap<String,String>();
        for(var imp:module.imports()) {
            if(imp.module().equals("Julc.Ledger"))continue;
            if(javaModules.get(imp.module())==null)throw new Diagnostic("ELCI2101",imp.token(),"Unknown module '"+imp.module()+"'");
        }
        var libraries=new Libraries(java,options,capabilities.apply(options));var provider=libraries.provider();
        var unsupportedImports=new LinkedHashMap<String,String>();var schemes=new LinkedHashMap<String,LibraryScheme>();
        var catalogue=new HashMap<String,LibraryType>(ledger.types());catalogue.putAll(java.types());
        for(var imp:module.imports()) {
            if(imp.module().equals("Julc.Ledger")) {
                // Module resolution already names exposed ledger types by their identity, per
                // module; a program-wide bare alias would capture a root type of the same name.
                for(String exposed:imp.exposing()) {
                    if(exposed.equals(".."))continue;
                    String type=exposed.endsWith("(..)")?exposed.substring(0,exposed.length()-4):exposed;
                    if(!type.equals("ScriptContext")&&!ledger.types().containsKey("org.julclang.ledger."+type))
                        throw new Diagnostic("ELCI2101",imp.token(),"Unknown ledger record type "+type);
                }
                ledgerAliases.put("ScriptContext","ScriptContext");continue;
            }
            var exports=provider.describe(javaModules.get(imp.module()));var found=new HashSet<String>();
            String owner=javaModules.get(imp.module());
            var generic=provider.schemes(owner);
            for(var descriptor:java.types().values()) {
                String id=descriptor.identity();
                String packageName=owner.contains(".")?owner.substring(0,owner.lastIndexOf('.')+1):"";
                String local=id.startsWith(owner+".")?id.substring(owner.length()+1):id.startsWith(packageName)?id.substring(packageName.length()):null;
                if(local!=null&&!local.contains(".")) {
                    String alias=imp.alias()+"."+local;
                    String previous=ledgerAliases.putIfAbsent(alias,id);
                    if(previous!=null&&!previous.equals(id))throw new Diagnostic("ELCI2102",imp.token(),"Ambiguous imported type "+alias);
                }
            }
            var instantiable=new HashSet<String>();generic.forEach(g->instantiable.add(g.symbol()));
            provider.unsupportedExports().forEach((symbol,reason)->{
                // Generic templates are described by schemes and instantiated at each use.
                if(symbol.startsWith(owner+".")&&!instantiable.contains(symbol))unsupportedImports.put(imp.alias()+symbol.substring(owner.length()),symbol+": "+reason);
            });
            for(var scheme:generic) {
                String n=scheme.symbol().substring(scheme.symbol().lastIndexOf('.')+1);
                Ty type=schemeType(scheme,new HashMap<>());
                if(!CONTEXT_LIBRARIES.contains(owner)&&mentionsValue(resultOf(type),catalogue)) {
                    unsupportedImports.put(imp.alias()+"."+n,scheme.symbol()+": returns a Value, which Java libraries do not keep in the"
                            +" ledger's canonical order; use the core Value module");
                    if(imp.exposing().contains(n))unsupportedImports.put(n,unsupportedImports.get(imp.alias()+"."+n));
                    found.add(n);continue;
                }
                schemes.put(scheme.symbol(),scheme);
                found.add(n);addImport(imp.alias()+"."+n,type,scheme.symbol(),imp.token(),importedTypes,importedSymbols);
                if(imp.exposing().contains(n)||imp.exposing().contains(".."))addImport(n,type,scheme.symbol(),imp.token(),importedTypes,importedSymbols);
            }
            for(var export:exports) {
                String n=export.symbol().substring(export.symbol().lastIndexOf('.')+1);Ty type;
                type=sourceType(export);
                // A Java library builds values in its own order; Elci's Value is always canonical (ADR-009).
                if(!CONTEXT_LIBRARIES.contains(owner)&&mentionsValue(resultOf(type),catalogue)) {
                    unsupportedImports.put(imp.alias()+"."+n,export.symbol()+": returns a Value, which Java libraries do not keep in the"
                            +" ledger's canonical order; use the core Value module");
                    if(imp.exposing().contains(n))unsupportedImports.put(n,unsupportedImports.get(imp.alias()+"."+n));
                    found.add(n);continue;
                }
                found.add(n);addImport(imp.alias()+"."+n,type,export.symbol(),imp.token(),importedTypes,importedSymbols);
                if(imp.exposing().contains(n)||imp.exposing().contains(".."))addImport(n,type,export.symbol(),imp.token(),importedTypes,importedSymbols);
            }
            for(String exposed:imp.exposing())if(!exposed.equals("..")&&!found.contains(exposed))throw new Diagnostic("ELCI2101",imp.token(),"No supported export '"+exposed+"' in "+imp.module());
        }
        for(var d:module.declarations())if(importedTypes.containsKey(d.name()))throw new Diagnostic("ELCI2102",d.token(),"Declaration conflicts with imported name "+d.name());
        var inference=new Inference();inference.aliases(module.aliases());inference.unions(module.name(),module.unions());inference.ledger(ledger,ledgerAliases);inference.library(java);inference.unsupportedImports.putAll(unsupportedImports);
        constructors(java.types(),importedTypes,inference);
        return new Frontend(module,inference,importedTypes,importedSymbols,schemes,libraries);
    }
    /**
     * Reject types without a strict Data boundary. Imported types are accepted when their provider
     * approves BOUNDARY; Julc's validator compiler checks them again.
     */
    private static void checkBoundary(Ty type,Inference inference,Set<String> visiting,Token token) {
        if(visiting.size()>64)throw new Diagnostic("ELCI2013",token,"Expanding recursive boundary type is unsupported");
        var t=(Con)prune(type);
        if(t.name().equals("ScriptContext"))throw new Diagnostic("ELCI2013",token,"ScriptContext is only valid in the ledger context argument, not serialized user data");
        if(inference.importedRepresentations.containsKey(t.name())&&!inference.byteEquality(t.name())) {
            if(inference.approves(t.name(),LibraryType.Operation.BOUNDARY))return;
            if(t.name().equals("org.julclang.ledger.Value"))
                throw new Diagnostic("ELCI2013",token,"A Value cannot be part of a datum, redeemer or parameter yet: the ledger's strict"
                        +" boundary cannot check its form. Store a List ( PolicyId, TokenName, Int ) and rebuild it with Value.fromList");
            throw new Diagnostic("ELCI2013",token,Types.show(t)+" has no approved strict Data boundary (BOUNDARY); for example records containing"
                    +" a ledger Value (such as TxOut) cannot be checked");
        }
        if(t.name().equals("->"))throw new Diagnostic("ELCI2013",token,"Functions cannot be serialized as datum or redeemer");
        if(t.name().equals("Array"))throw new Diagnostic("ELCI2013",token,"Arrays have no Data encoding and cannot be a datum, redeemer or parameter; take a List and convert it with Array.fromList");
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
    /**
     * Approved constructors of imported types, named by {@link Inference#constructorName} as
     * module resolution produces them. Constructors usable in expressions need CONSTRUCT;
     * patterns need MATCH, which inference checks.
     */
    private static void constructors(Map<String,LibraryType> types,Map<String,Ty> importedTypes,Inference inference) {
        for(var type:types.values()) {
            boolean construct=type.supports(LibraryType.Operation.CONSTRUCT);
            if(!construct&&!type.supports(LibraryType.Operation.MATCH))continue;
            Ty result=con(type.identity().equals("org.julclang.ledger.ScriptContext")?"ScriptContext":type.identity());
            if(type.constructors().isEmpty()) {
                var entry=new Inference.ImportedConstructor(type,null);
                inference.importedConstructors.put(entry.name(),entry);
                if(construct)importedTypes.put(entry.name(),constructorType(type.fields(),result,inference));
            } else for(var constructor:type.constructors()) {
                var entry=new Inference.ImportedConstructor(type,constructor);
                inference.importedConstructors.put(entry.name(),entry);
                if(construct)importedTypes.put(entry.name(),constructorType(constructor.fields(),result,inference));
            }
        }
    }
    private static Ty constructorType(List<LibraryType.Field> fields,Ty result,Inference inference) {
        for(int i=fields.size()-1;i>=0;i--)result=fn(inference.libraryType(fields.get(i).type()),result);
        return result;
    }
    /** {@code Julc.Data}: explicit codecs and the Julc optional / Elci Maybe adapters. */
    private static void intrinsics(Map<String,Ty> importedTypes) {
        String prefix=org.elci.modules.Modules.DATA_PREFIX;
        var a=new Var();importedTypes.put(prefix+"decode",fn(con("Data"),a));
        var b=new Var();importedTypes.put(prefix+"encode",fn(b,con("Data")));
        var c=new Var();importedTypes.put(prefix+"fromOptional",fn(con("JulcOptional",c),con("Elci.Maybe",c)));
        var d=new Var();importedTypes.put(prefix+"toOptional",fn(con("Elci.Maybe",d),con("JulcOptional",d)));
        var e=new Var();importedTypes.put(org.elci.modules.Modules.DEBUG_PREFIX+"trace",fn(con("String"),fn(e,e)));
        assocIntrinsics(importedTypes);
        valueIntrinsics(importedTypes);
        String bytes=org.elci.modules.Modules.BYTES_PREFIX;
        importedTypes.put(bytes+"empty",con("Bytes"));
        importedTypes.put(bytes+"fromString",fn(con("String"),con("Bytes")));
        importedTypes.put(bytes+"toString",fn(con("Bytes"),con("String")));
        importedTypes.put(bytes+"length",fn(con("Bytes"),con("Int")));
        importedTypes.put(bytes+"append",fn(con("Bytes"),fn(con("Bytes"),con("Bytes"))));
        importedTypes.put(bytes+"slice",fn(con("Int"),fn(con("Int"),fn(con("Bytes"),con("Bytes")))));
        importedTypes.put(bytes+"take",fn(con("Int"),fn(con("Bytes"),con("Bytes"))));
        importedTypes.put(bytes+"drop",fn(con("Int"),fn(con("Bytes"),con("Bytes"))));
        importedTypes.put(bytes+"at",fn(con("Int"),fn(con("Bytes"),con("Int"))));
        importedTypes.put(bytes+"cons",fn(con("Int"),fn(con("Bytes"),con("Bytes"))));
        importedTypes.put(bytes+"replicate",fn(con("Int"),fn(con("Int"),con("Bytes"))));
        for(String from:List.of("fromIntBigEndian","fromIntLittleEndian"))importedTypes.put(bytes+from,fn(con("Int"),fn(con("Int"),con("Bytes"))));
        for(String to:List.of("toIntBigEndian","toIntLittleEndian"))importedTypes.put(bytes+to,fn(con("Bytes"),con("Int")));
        for(String logic:List.of("and","or","xor"))importedTypes.put(bytes+logic,fn(con("Bytes"),fn(con("Bytes"),con("Bytes"))));
        for(String move:List.of("shift","rotate"))importedTypes.put(bytes+move,fn(con("Int"),fn(con("Bytes"),con("Bytes"))));
        importedTypes.put(bytes+"testBit",fn(con("Int"),fn(con("Bytes"),con("Bool"))));
        for(String count:List.of("countSetBits","findFirstSetBit"))importedTypes.put(bytes+count,fn(con("Bytes"),con("Int")));
        importedTypes.put(bytes+"complement",fn(con("Bytes"),con("Bytes")));
        for(String hash:List.of("blake2b224","blake2b256","sha256","sha3_256","keccak256","ripemd160"))importedTypes.put(bytes+hash,fn(con("Bytes"),con("Bytes")));
    }

    /** {@code Elci.Value}, the core Value module's CIP-153 operations (ADR-009); NativeValue is internal. */
    private static void valueIntrinsics(Map<String,Ty> importedTypes) {
        String value=org.elci.modules.Modules.VALUE_PREFIX;
        Ty ledger=con("org.julclang.ledger.Value"),nativeValue=con("NativeValue"),policy=con("org.julclang.ledger.PolicyId"),
                token=con("org.julclang.ledger.TokenName"),integer=con("Int");
        importedTypes.put(value+"fromLedger",fn(ledger,nativeValue));
        importedTypes.put(value+"toLedger",fn(nativeValue,ledger));
        importedTypes.put(value+"empty",nativeValue);
        importedTypes.put(value+"insertCoin",fn(policy,fn(token,fn(integer,fn(nativeValue,nativeValue)))));
        importedTypes.put(value+"lookupCoin",fn(policy,fn(token,fn(nativeValue,integer))));
        importedTypes.put(value+"union",fn(nativeValue,fn(nativeValue,nativeValue)));
        importedTypes.put(value+"contains",fn(nativeValue,fn(nativeValue,con("Bool"))));
        importedTypes.put(value+"scale",fn(integer,fn(nativeValue,nativeValue)));
        importedTypes.put(value+"lovelaceOf",fn(ledger,integer));
        importedTypes.put(value+"isZero",fn(ledger,con("Bool")));
        importedTypes.put(value+"nonNegative",fn(ledger,con("Bool")));
        importedTypes.put(value+"policies",fn(ledger,con("List",policy)));
        importedTypes.put(value+"tokens",fn(policy,fn(ledger,con("Dict",token,integer))));
        importedTypes.put(value+"toList",fn(ledger,con("List",con("Tuple",policy,token,integer))));
        importedTypes.put(value+"filter",fn(fn(policy,fn(token,fn(integer,con("Bool")))),fn(ledger,ledger)));
        importedTypes.put(value+"adaPolicy",policy);
        importedTypes.put(value+"adaToken",token);
    }

    /**
     * Java libraries whose exports only pass along ledger data from the context: their Values are
     * the ledger's, and canonical (ADR-009).
     */
    public static final Set<String> CONTEXT_LIBRARIES = Set.of("org.julclang.stdlib.lib.ContextsLib",
            "org.julclang.stdlib.lib.OutputLib");

    /** Whether values of a type can hold a ledger Value, through type arguments and imported types' fields. */
    private static boolean mentionsValue(Ty type,Map<String,LibraryType> catalogue) {
        // Source types name the context "ScriptContext"; the catalogue knows it by its Java name.
        return prune(type) instanceof Con c && (c.name().equals("org.julclang.ledger.Value")
                || c.args().stream().anyMatch(a->mentionsValue(a,catalogue))
                || referencesValue(new LibraryType.Reference(c.name().equals("ScriptContext")?"org.julclang.ledger.ScriptContext":c.name(),List.of()),catalogue,new HashSet<>()));
    }

    private static boolean referencesValue(LibraryType.Reference reference,Map<String,LibraryType> catalogue,Set<String> visiting) {
        if(reference.name().equals("org.julclang.ledger.Value"))return true;
        if(reference.arguments().stream().anyMatch(a->referencesValue(a,catalogue,visiting)))return true;
        var type=catalogue.get(reference.name());
        if(type==null||!visiting.add(reference.name()))return false;
        for(var field:type.fields())if(referencesValue(field.type(),catalogue,visiting))return true;
        for(var constructor:type.constructors())for(var field:constructor.fields())if(referencesValue(field.type(),catalogue,visiting))return true;
        return false;
    }

    /** The result of a function type, after all its arguments. */
    private static Ty resultOf(Ty type) {
        Ty t=prune(type);
        while(t instanceof Con c&&c.name().equals("->"))t=prune(c.args().get(1));
        return t;
    }

    /** {@code Elci.Assoc}, the core library's association-list operations (ADR-009). */
    private static void assocIntrinsics(Map<String,Ty> importedTypes) {
        String assoc=org.elci.modules.Modules.ASSOC_PREFIX;
        record Pair(Ty k,Ty v,Ty map){}
        java.util.function.Supplier<Pair> fresh=()->{var k=new Var();var v=new Var();return new Pair(k,v,con("Map",k,v));};
        var p=fresh.get();importedTypes.put(assoc+"toList",fn(p.map(),con("List",con("Tuple",p.k(),p.v()))));
        p=fresh.get();importedTypes.put(assoc+"fromDistinct",fn(con("List",con("Tuple",p.k(),p.v())),p.map()));
        p=fresh.get();importedTypes.put(assoc+"cons",fn(p.k(),fn(p.v(),fn(p.map(),p.map()))));
        p=fresh.get();importedTypes.put(assoc+"reverse",fn(p.map(),p.map()));
        p=fresh.get();importedTypes.put(assoc+"find",fn(p.k(),fn(p.map(),con("Elci.Maybe",p.v()))));
        p=fresh.get();importedTypes.put(assoc+"has",fn(p.k(),fn(p.map(),con("Bool"))));
        p=fresh.get();importedTypes.put(assoc+"delete",fn(p.k(),fn(p.map(),p.map())));
        p=fresh.get();importedTypes.put(assoc+"runs",fn(p.map(),con("List",p.map())));
        p=fresh.get();importedTypes.put(assoc+"merge",fn(p.map(),fn(p.map(),p.map())));
        p=fresh.get();importedTypes.put(assoc+"dedupe",fn(p.map(),p.map()));
        p=fresh.get();importedTypes.put(assoc+"get",fn(p.k(),fn(p.map(),con("Elci.Maybe",p.v()))));
        p=fresh.get();importedTypes.put(assoc+"member",fn(p.k(),fn(p.map(),con("Bool"))));
        p=fresh.get();importedTypes.put(assoc+"insert",fn(p.k(),fn(p.v(),fn(p.map(),p.map()))));
        p=fresh.get();importedTypes.put(assoc+"remove",fn(p.k(),fn(p.map(),p.map())));
        p=fresh.get();importedTypes.put(assoc+"ascending",fn(p.map(),con("Bool")));
        p=fresh.get();importedTypes.put(assoc+"toMap",fn(con("Dict",p.k(),p.v()),p.map()));
        p=fresh.get();importedTypes.put(assoc+"fromMap",fn(p.map(),con("Dict",p.k(),p.v())));
    }

    /**
     * The source type of a generic export: each scheme type parameter becomes one type variable,
     * quantified when the import enters the environment, so every use is instantiated freshly.
     */
    public static Ty schemeType(LibraryScheme scheme,Map<String,Ty> variables) {
        Ty result=schemeType(scheme.result(),variables);
        for(int i=scheme.parameters().size()-1;i>=0;i--)result=fn(schemeType(scheme.parameters().get(i),variables),result);
        return result;
    }
    private static Ty schemeType(LibraryType.Reference reference,Map<String,Ty> variables) {
        if(reference.variable())return variables.computeIfAbsent(reference.name(),n->new Var());
        if(reference.name().equals("Function"))return fn(schemeType(reference.arguments().get(0),variables),schemeType(reference.arguments().get(1),variables));
        return new Con(reference.name().equals("org.julclang.ledger.ScriptContext")?"ScriptContext":reference.name(),reference.arguments().stream().map(a->schemeType(a,variables)).toList());
    }
    private static Ty sourceType(LibraryType.Reference reference) {
        return new Con(reference.name().equals("org.julclang.ledger.ScriptContext")?"ScriptContext":reference.name(),reference.arguments().stream().map(ElciCompiler::sourceType).toList());
    }
}
