package org.elci.abi;

import org.elci.driver.ElciCompiler;
import org.julclang.compiler.pir.PirType;
import java.util.*;

/** Canonical, deterministic encoding metadata. This is not a transaction-construction blueprint. */
public final class Abi {
    private Abi() {}
    /** Format revision 1: an [entrypoint] function's arguments, result and type definitions. */
    public static String manifest(ElciCompiler.Result result) {
        var root=new TreeMap<String,Object>();root.put("formatVersion",1);root.put("compiler","elci-"+org.elci.driver.BuildInfo.VERSION);root.put("julcVersion",ElciCompiler.JULC_VERSION);
        root.put("target",result.target().profileId());root.put("entrypoint",result.module()+"."+result.function());root.put("purpose","function");root.put("boundary","julc-strict-v1");root.put("parameters",List.of());
        root.put("ledgerArguments",0);
        var arguments=new ArrayList<Object>();PirType type=result.pirType();org.elci.types.Types.Ty sourceType=result.type();int index=0;
        var references=new LinkedHashSet<String>();
        while(type instanceof PirType.FunType fn) {
            var sourceFunction=(org.elci.types.Types.Con)org.elci.types.Types.prune(sourceType);
            arguments.add(Map.of("role","argument"+(index+1),"sourceType",sourceFunction.args().get(0).toString(),"schema",schema(fn.paramType())));
            references(fn.paramType(),references);
            sourceType=sourceFunction.args().get(1);type=fn.returnType();index++;
        }
        root.put("arguments",arguments);root.put("result",schema(type));
        references(type,references);
        root.put("definitions",definitions(references,result.namedTypes()));
        return json(root)+"\n";
    }

    /**
     * Format revision 2: one named validator (ADR-004). Parameters are listed in application
     * order and handlers in ledger-tag order, each with its datum profile and boundary schemas.
     */
    public static String validatorManifest(ElciCompiler.ValidatorBuild build) {
        var root=new TreeMap<String,Object>();root.put("formatVersion",2);root.put("compiler","elci-"+org.elci.driver.BuildInfo.VERSION);root.put("julcVersion",ElciCompiler.JULC_VERSION);
        root.put("target",build.target().profileId());root.put("validator",build.name());root.put("boundary","julc-strict-v1");
        root.put("purpose",build.handlers().size()==1?ElciCompiler.purpose(build.handlers().getFirst().purpose()):"multi");
        root.put("ledgerArguments",1);
        var references=new LinkedHashSet<String>();
        var parameters=new ArrayList<Object>();
        for(var parameter:build.parameters()) {
            parameters.add(Map.of("name",parameter.name(),"sourceType",parameter.source().toString(),"schema",schema(parameter.type())));
            references(parameter.type(),references);
        }
        root.put("parameters",parameters);
        var handlers=new ArrayList<Object>();
        for(var handler:build.handlers()) {
            var entry=new TreeMap<String,Object>();
            entry.put("purpose",ElciCompiler.purpose(handler.purpose()));entry.put("tag",handler.tag());entry.put("handler",handler.symbol());
            var datum=new TreeMap<String,Object>();datum.put("profile",handler.datum().name().toLowerCase(Locale.ROOT));
            if(handler.datum()!=org.julclang.compiler.backend.DatumProfile.ABSENT) {
                // An optional datum is the ledger datum when present; its schema is the payload's.
                PirType datumType=handler.datumType() instanceof PirType.OptionalType o?o.elemType():handler.datumType();
                datum.put("sourceType",handler.datumSource().toString());datum.put("schema",schema(datumType));references(datumType,references);
            }
            if(handler.purpose()==org.julclang.compiler.schema.ContractSchema.Purpose.SPEND)entry.put("datum",datum);
            entry.put("redeemer",Map.of("sourceType",handler.redeemerSource().toString(),"schema",schema(handler.redeemerType())));
            references(handler.redeemerType(),references);
            handlers.add(entry);
        }
        root.put("handlers",handlers);
        root.put("definitions",definitions(references,build.namedTypes()));
        return json(root)+"\n";
    }

    private static Map<String,Object> definitions(Set<String> references,Map<String,PirType> namedTypes) {
        var definitions=new TreeMap<String,Object>();var queue=new ArrayDeque<>(references);
        while(!queue.isEmpty()) {
            String name=queue.removeFirst();if(definitions.containsKey(name))continue;
            var definition=namedTypes.get(name);if(definition==null)throw new IllegalArgumentException("Missing ABI type definition: "+name);
            definitions.put(name,schema(definition));var next=new LinkedHashSet<String>();references(definition,next);queue.addAll(next);
        }
        return definitions;
    }

    private static void references(PirType type,Set<String> references) {
        switch(type) {
            case PirType.NamedTypeRef n->references.add(n.stableId());
            case PirType.RecordType r->r.fields().forEach(f->references(f.type(),references));
            case PirType.SumType s->s.constructors().forEach(c->c.fields().forEach(f->references(f.type(),references)));
            case PirType.ListType l->references(l.elemType(),references);
            case PirType.MapType m->{references(m.keyType(),references);references(m.valueType(),references);}
            case PirType.OptionalType o->references(o.elemType(),references);
            case PirType.FunType f->{references(f.paramType(),references);references(f.returnType(),references);}
            default->{}
        }
    }
    private static Object schema(PirType type) {
        return switch(type) {
            case PirType.IntegerType _->Map.of("kind","integer","encoding","I");
            case PirType.BoolType _->Map.of("kind","boolean","falseTag",0,"trueTag",1,"arity",0);
            case PirType.StringType _->Map.of("kind","string","encoding","B(UTF-8)");
            case PirType.ByteStringType _->Map.of("kind","bytes","encoding","B");
            case PirType.UnitType _->Map.of("kind","unit","tag",0,"arity",0);
            case PirType.DataType _->Map.of("kind","data","encoding","opaque");
            case PirType.ListType l->Map.of("kind","list","encoding","ListData","element",schema(l.elemType()));
            case PirType.MapType m->Map.of("kind","map","key",schema(m.keyType()),"value",schema(m.valueType()));
            case PirType.OptionalType o->Map.of("kind","julc-optional","someTag",0,"noneTag",1,"element",schema(o.elemType()));
            case PirType.RecordType r->Map.of("kind","record","identity",r.name(),"tag",0,"fields",r.fields().stream().map(f->Map.of("name",f.name(),"schema",schema(f.type()))).toList());
            case PirType.SumType s->Map.of("kind","union","identity",s.name(),"constructors",s.constructors().stream().map(c->Map.of("name",c.name(),"tag",c.tag(),"fields",c.fields().stream().map(f->schema(f.type())).toList())).toList());
            case PirType.NamedTypeRef n->Map.of("ref",n.stableId());
            case PirType.FunType f->Map.of("kind","function","argument",schema(f.paramType()),"result",schema(f.returnType()));
            default->throw new IllegalArgumentException("No Elci ABI codec for "+type);
        };
    }
    public static String json(Object value) {
        if(value instanceof String s)return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\"";
        if(value instanceof Number||value instanceof Boolean)return value.toString();
        if(value instanceof Map<?,?> map) {
            var sorted=new TreeMap<String,Object>();map.forEach((k,v)->sorted.put(k.toString(),v));
            return "{"+String.join(",",sorted.entrySet().stream().map(e->json(e.getKey())+":"+json(e.getValue())).toList())+"}";
        }
        if(value instanceof Collection<?> list)return "["+String.join(",",list.stream().map(Abi::json).toList())+"]";
        throw new IllegalArgumentException("Unsupported JSON value "+value);
    }
}
