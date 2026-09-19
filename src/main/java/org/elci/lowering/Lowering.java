package org.elci.lowering;

import org.elci.syntax.*;
import org.elci.types.*;
import org.julclang.compiler.backend.LibraryProvider;
import org.julclang.compiler.pir.*;
import org.julclang.core.*;
import java.math.BigInteger;
import java.util.*;
import static org.elci.syntax.Ast.*;
import static org.elci.types.Types.*;

/** Demand specialization and strict, hygienic PIR construction. */
public final class Lowering {
    private final Inference inference;
    private final Map<String,Decl> definitions = new LinkedHashMap<>();
    private final Map<String,String> imports;
    private final LibraryProvider provider;
    private final LinkedHashMap<String,PirTerm> specialized = new LinkedHashMap<>();
    private final Map<String,String> instances = new HashMap<>();
    private int fresh;
    private final Map<String,PirType> namedTypes = new LinkedHashMap<>();
    private final Set<String> planning = new HashSet<>();
    public Map<String,PirType> namedTypes(){return Map.copyOf(namedTypes);}
    public Lowering(List<Decl> declarations, Inference inference, Map<String,String> imports,LibraryProvider provider) {
        declarations.forEach(d->definitions.put(d.name(),d));this.inference=inference;this.imports=imports;this.provider=provider;
        namedTypes.putAll(inference.importedRepresentations);
    }
    public PirTerm lower(String entry,Ty type) {
        PirTerm root=reference(entry,type);
        return org.julclang.compiler.backend.PirLinker.link(specialized,root);
    }
    private String fresh(String hint){return "$elci$"+hint+"$"+(fresh++);}
    private PirTerm reference(String name,Ty concrete) {
        if(!Types.concrete(concrete))throw new Diagnostic("ELCI3001",definitions.get(name).token(),"Cannot specialize an unconstrained type: "+concrete);
        String key=name+":"+concrete;
        if(instances.containsKey(key))return new PirTerm.Var(instances.get(key),pir(concrete));
        if(instances.size()>256)throw new Diagnostic("ELCI3002",definitions.get(name).token(),"Specialization limit exceeded; polymorphic recursion is unsupported");
        String symbol=fresh(name);instances.put(key,symbol);specialized.put(symbol,null);
        var d=definitions.get(name);var mapping=new HashMap<Var,Ty>();
        Ty instance=copy(inference.declarations.get(d),mapping);unify(instance,concrete,d.token());
        PirTerm body=declaration(d,instance,new HashMap<>(),mapping);
        specialized.put(symbol,body);return new PirTerm.Var(symbol,pir(concrete));
    }
    private PirTerm declaration(Decl d,Ty type,Map<String,PirTerm> env,Map<Var,Ty> mapping) {
        var local=new HashMap<>(env);var ps=new ArrayList<String>();var ts=new ArrayList<PirType>();Ty rest=prune(type);
        for(String p:d.parameters()) {
            var fn=(Con)prune(rest);PirType pt=pir(fn.args().get(0));String id=fresh(p);ps.add(id);ts.add(pt);
            local.put(p,new PirTerm.Var(id,pt));rest=fn.args().get(1);
        }
        PirTerm body=expr(d.body(),local,mapping);
        for(int i=ps.size()-1;i>=0;i--)body=new PirTerm.Lam(ps.get(i),ts.get(i),body);
        return body;
    }
    private Ty type(Expr e,Map<Var,Ty> map){return copy(inference.expressions.get(e),map);}
    private PirTerm expr(Expr e,Map<String,PirTerm> env,Map<Var,Ty> map) {
        return switch(e) {
            case Field f -> projection(expr(f.record(),env,map),type(f.record(),map),f.name());
            case RecordValue r -> {
                var recordType=(Con)prune(type(r,map));var names=recordType.name().substring(7).isEmpty()?List.<String>of():Arrays.asList(recordType.name().substring(7).split(","));
                var temporaries=new LinkedHashMap<String,PirTerm>();var values=new LinkedHashMap<String,PirTerm>();
                PirTerm base=null;String baseName=null;
                if(r.base()!=null){baseName=fresh("base");base=new PirTerm.Var(baseName,pir(type(r.base(),map)));}
                for(var field:r.fields().entrySet()) {
                    String id=fresh(field.getKey());var value=expr(field.getValue(),env,map);temporaries.put(id,value);
                    values.put(field.getKey(),new PirTerm.Var(id,pir(type(field.getValue(),map))));
                }
                var fields=new ArrayList<PirTerm>();for(String name:names)fields.add(values.containsKey(name)?values.get(name):projection(base,type(r,map),name));
                PirTerm record=new PirTerm.DataConstr(0,pir(type(r,map)),fields);
                var bindings=new ArrayList<>(temporaries.entrySet());for(int i=bindings.size()-1;i>=0;i--)record=new PirTerm.Let(bindings.get(i).getKey(),bindings.get(i).getValue(),record);
                if(base!=null)record=new PirTerm.Let(baseName,expr(r.base(),env,map),record);yield record;
            }
            case Literal l -> new PirTerm.Const(l.value()==null?Constant.unit():l.value() instanceof Boolean b?Constant.bool(b):l.value() instanceof String s?Constant.string(s):Constant.integer((BigInteger)l.value()));
            case Name n -> {
                if(env.containsKey(n.name()))yield env.get(n.name());
                if(inference.constructors.containsKey(n.name())) {
                    var union=inference.constructors.get(n.name());Ty rest=type(n,map);var ids=new ArrayList<String>();var pts=new ArrayList<PirType>();var fields=new ArrayList<PirTerm>();
                    while(prune(rest) instanceof Con f && f.name().equals("->")) {
                        String id=fresh("field");var pt=pir(f.args().get(0));ids.add(id);pts.add(pt);fields.add(new PirTerm.Var(id,pt));rest=f.args().get(1);
                    }
                    int tag=0;while(!union.variants().get(tag).name().equals(n.name()))tag++;
                    PirTerm value=new PirTerm.DataConstr(tag,pir(rest),fields);
                    for(int i=ids.size()-1;i>=0;i--)value=new PirTerm.Lam(ids.get(i),pts.get(i),value);yield value;
                }
                if(imports.containsKey(n.name()))yield provider.materialize(imports.get(n.name()));
                if(n.name().equals("not")){String p=fresh("not");yield new PirTerm.Lam(p,new PirType.BoolType(),new PirTerm.IfThenElse(new PirTerm.Var(p,new PirType.BoolType()),bool(false),bool(true)));}
                if(n.name().equals("remainderBy")){String a=fresh("divisor"),b=fresh("value");yield new PirTerm.Lam(a,new PirType.IntegerType(),new PirTerm.Lam(b,new PirType.IntegerType(),call(DefaultFun.RemainderInteger,new PirTerm.Var(b,new PirType.IntegerType()),new PirTerm.Var(a,new PirType.IntegerType()))));}
                yield reference(n.name(),type(n,map));
            }
            case Apply a -> new PirTerm.App(expr(a.function(),env,map),expr(a.argument(),env,map));
            case Lambda l -> {
                var local=new HashMap<>(env);var ids=new ArrayList<String>();var ts=new ArrayList<PirType>();Ty rest=type(l,map);
                for(String p:l.parameters()){var ft=(Con)prune(rest);String id=fresh(p);var pt=pir(ft.args().get(0));ids.add(id);ts.add(pt);local.put(p,new PirTerm.Var(id,pt));rest=ft.args().get(1);}
                var body=expr(l.body(),local,map);for(int i=ids.size()-1;i>=0;i--)body=new PirTerm.Lam(ids.get(i),ts.get(i),body);yield body;
            }
            case If i -> new PirTerm.IfThenElse(expr(i.condition(),env,map),expr(i.yes(),env,map),expr(i.no(),env,map));
            case Binary b -> binary(b,env,map);
            case Let l -> {
                var local=new HashMap<>(env);var groups=Inference.groups(l.declarations());
                for(var d:l.declarations())local.put(d.name(),new PirTerm.Var(fresh(d.name()),pir(copy(inference.declarations.get(d),map))));
                PirTerm body=expr(l.body(),local,map);
                for(int gi=groups.size()-1;gi>=0;gi--) {
                    var group=groups.get(gi);var bindings=new ArrayList<PirTerm.Binding>();
                    for(var d:group)bindings.add(new PirTerm.Binding(((PirTerm.Var)local.get(d.name())).name(),declaration(d,copy(inference.declarations.get(d),map),local,map)));
                    if(group.size()==1&&group.getFirst().parameters().isEmpty()&&!(group.getFirst().body() instanceof Lambda))body=new PirTerm.Let(bindings.getFirst().name(),bindings.getFirst().value(),body);
                    else body=new PirTerm.LetRec(bindings,body);
                }
                yield body;
            }
            case Items items -> {
                if(items.tuple())yield new PirTerm.DataConstr(0,pir(type(items,map)),items.elements().stream().map(item->expr(item,env,map)).toList());
                Ty element=((Con)prune(type(items,map))).args().getFirst();
                PirTerm list=call(DefaultFun.MkNilData,new PirTerm.Const(Constant.unit()));
                // Each cons evaluates head before tail, preserving source element order.
                for(int i=items.elements().size()-1;i>=0;i--)list=call(DefaultFun.MkCons,PirHelpers.wrapEncode(expr(items.elements().get(i),env,map),pir(element)),list);
                yield list;
            }
            case Case c -> {
                String name=fresh("case");PirType pt=pir(type(c.scrutinee(),map));var variable=new PirTerm.Var(name,pt);
                PirTerm body=new PirTerm.Error(pir(type(c,map)));
                for(int i=c.branches().size()-1;i>=0;i--) {
                    var branch=c.branches().get(i);var p=branch.pattern();var local=new HashMap<>(env);
                    if(p.name().equals("_")||Character.isLowerCase(p.name().charAt(0))) {
                        local.put(p.name(),variable);body=expr(branch.body(),local,map);
                    } else if(p.name().equals("Tuple")) {
                        var tuple=(PirType.RecordType)pt;var bindings=new ArrayList<PirTerm.Binding>();
                        PirTerm fields=call(DefaultFun.SndPair,call(DefaultFun.UnConstrData,variable));
                        for(int j=0;j<p.arguments().size();j++) {
                            String id=fresh("tuple");var ft=tuple.fields().get(j).type();local.put(p.arguments().get(j).name(),new PirTerm.Var(id,ft));
                            bindings.add(new PirTerm.Binding(id,PirHelpers.wrapDecode(call(DefaultFun.HeadList,fields),ft)));fields=call(DefaultFun.TailList,fields);
                        }
                        body=expr(branch.body(),local,map);for(int j=bindings.size()-1;j>=0;j--)body=new PirTerm.Let(bindings.get(j).name(),bindings.get(j).value(),body);
                    } else if(p.name().equals("[]"))body=new PirTerm.IfThenElse(call(DefaultFun.NullList,variable),expr(branch.body(),local,map),body);
                    else if(p.name().equals("::")) {
                        var element=((PirType.ListType)pt).elemType();String head=fresh("head"),tail=fresh("tail");
                        String h=p.arguments().get(0).name(),t=p.arguments().get(1).name();String decoded=fresh(h);
                        local.put(h,new PirTerm.Var(decoded,element));local.put(t,new PirTerm.Var(tail,pt));
                        PirTerm cons=new PirTerm.Let(decoded,PirHelpers.wrapDecode(new PirTerm.Var(head,new PirType.DataType()),element),expr(branch.body(),local,map));
                        body=new PirTerm.ListMatch(variable,head,tail,body,cons);
                    } else if(inference.constructors.containsKey(p.name())) {
                        var sum=(PirType.SumType)pt;var ctor=sum.constructors().stream().filter(v->v.name().equals(p.name())).findFirst().orElseThrow();
                        var bindings=new ArrayList<PirTerm.Binding>();
                        PirTerm fields=call(DefaultFun.SndPair,call(DefaultFun.UnConstrData,variable));
                        for(int j=0;j<p.arguments().size();j++) {
                            String id=fresh("pattern");var ft=ctor.fields().get(j).type();String pn=p.arguments().get(j).name();local.put(pn,new PirTerm.Var(id,ft));
                            bindings.add(new PirTerm.Binding(id,PirHelpers.wrapDecode(call(DefaultFun.HeadList,fields),ft)));fields=call(DefaultFun.TailList,fields);
                        }
                        PirTerm selected=expr(branch.body(),local,map);
                        for(int j=bindings.size()-1;j>=0;j--)selected=new PirTerm.Let(bindings.get(j).name(),bindings.get(j).value(),selected);
                        var tag=call(DefaultFun.FstPair,call(DefaultFun.UnConstrData,variable));
                        body=new PirTerm.IfThenElse(call(DefaultFun.EqualsInteger,tag,new PirTerm.Const(Constant.integer(ctor.tag()))),selected,body);
                    } else {
                        PirTerm test=p.name().equals("True")?variable:p.name().equals("False")?new PirTerm.IfThenElse(variable,bool(false),bool(true)):call(DefaultFun.EqualsInteger,variable,new PirTerm.Const(Constant.integer(new BigInteger(p.name()))));
                        body=new PirTerm.IfThenElse(test,expr(branch.body(),local,map),body);
                    }
                }
                yield new PirTerm.Let(name,expr(c.scrutinee(),env,map),body);
            }
        };
    }
    private PirTerm binary(Binary b,Map<String,PirTerm> env,Map<Var,Ty> map) {
        var a=expr(b.left(),env,map);var z=expr(b.right(),env,map);
        if(b.op().equals("&&"))return new PirTerm.IfThenElse(a,z,bool(false));
        if(b.op().equals("||"))return new PirTerm.IfThenElse(a,bool(true),z);
        if(b.op().equals("<|"))return new PirTerm.App(a,z);
        if(b.op().equals("|>")) {
            // Pipe's source left operand is evaluated before the right function expression.
            String id=fresh("pipe");return new PirTerm.Let(id,a,new PirTerm.App(z,new PirTerm.Var(id,pir(type(b.left(),map)))));
        }
        if(b.op().equals("++")) {
            var type=type(b.left(),map);
            if(((Con)prune(type)).name().equals("String"))return call(DefaultFun.AppendString,a,z);
            return new PirTerm.App(new PirTerm.App(reference("List.append",fn(type,fn(type,type))),a),z);
        }
        if(b.op().equals(">>")||b.op().equals("<<")) {
            String left=fresh("composeLeft"),right=fresh("composeRight"),input=fresh("input");
            var lt=pir(type(b.left(),map));var rt=pir(type(b.right(),map));var ft=(PirType.FunType)pir(type(b,map));
            var lv=new PirTerm.Var(left,lt);var rv=new PirTerm.Var(right,rt);var iv=new PirTerm.Var(input,ft.paramType());
            PirTerm body=b.op().equals(">>")?new PirTerm.App(rv,new PirTerm.App(lv,iv)):new PirTerm.App(lv,new PirTerm.App(rv,iv));
            return new PirTerm.Let(left,a,new PirTerm.Let(right,z,new PirTerm.Lam(input,ft.paramType(),body)));
        }
        if(b.op().equals("::"))return call(DefaultFun.MkCons,PirHelpers.wrapEncode(a,pir(type(b.left(),map))),z);
        if(Set.of("==","/=").contains(b.op())) {
            var t=(Con)prune(type(b.left(),map));PirTerm eq;
            if(t.name().equals("Bool")) {
                String x=fresh("left"),y=fresh("right");var xv=new PirTerm.Var(x,new PirType.BoolType());var yv=new PirTerm.Var(y,new PirType.BoolType());
                eq=new PirTerm.Let(x,a,new PirTerm.Let(y,z,new PirTerm.IfThenElse(xv,yv,new PirTerm.IfThenElse(yv,bool(false),bool(true)))));
            }else eq=call(t.name().equals("String")?DefaultFun.EqualsString:inference.byteEquality(t.name())?DefaultFun.EqualsByteString:DefaultFun.EqualsInteger,a,z);
            return b.op().equals("/=")?new PirTerm.IfThenElse(eq,bool(false),bool(true)):eq;
        }
        DefaultFun op=switch(b.op()) {
            case "+"->DefaultFun.AddInteger;case "-"->DefaultFun.SubtractInteger;case "*"->DefaultFun.MultiplyInteger;case "//"->DefaultFun.QuotientInteger;
            case "<",">="->DefaultFun.LessThanInteger;case "<=",">"->DefaultFun.LessThanEqualsInteger;
            default->throw new Diagnostic("ELCI3001",b.token(),"Unsupported operator "+b.op());
        };
        var result=call(op,a,z);
        return b.op().equals(">")||b.op().equals(">=")?new PirTerm.IfThenElse(result,bool(false),bool(true)):result;
    }
    public PirType pir(Ty type) {
        type=prune(type);if(!(type instanceof Con c))throw new IllegalArgumentException("Unresolved source type: "+type);
        if(inference.importedRepresentations.containsKey(c.name()))return inference.importedRepresentations.get(c.name());
        for(var union:inference.unions.values())if(inference.nominal(union.name()).equals(c.name())) {
            if(planning.size()>64)throw new IllegalArgumentException("Expanding recursive type requires unsupported polymorphic recursion");
            String id="elci."+c;
            if(namedTypes.containsKey(id))return namedTypes.get(id);
            if(!planning.add(id))return new PirType.NamedTypeRef(id,id,PirType.NamedKind.SUM);
            var vars=new HashMap<String,Ty>();for(int i=0;i<union.parameters().size();i++)vars.put(union.parameters().get(i),c.args().get(i));
            var variants=new ArrayList<PirType.Constructor>();int tag=0;
            for(var variant:union.variants()) {
                var fields=new ArrayList<PirType.Field>();int index=0;
                for(var field:variant.fields()) {
                    var pt=pir(inference.annotatedPublic(field,vars));requireData(pt);fields.add(new PirType.Field("field"+(index++),pt));
                }
                variants.add(new PirType.Constructor(variant.name(),tag++,fields));
            }
            var sum=new PirType.SumType(id,variants);planning.remove(id);namedTypes.put(id,sum);return sum;
        }
        if(c.name().equals("Tuple")) {
            var fields=new ArrayList<PirType.Field>();for(int i=0;i<c.args().size();i++){var pt=pir(c.args().get(i));requireData(pt);fields.add(new PirType.Field("_"+i,pt));}
            return new PirType.RecordType("elci.Tuple"+c.args().size()+c.args(),fields);
        }
        if(c.name().startsWith("Record:")) {
            var names=c.name().substring(7).isEmpty()?List.<String>of():Arrays.asList(c.name().substring(7).split(","));
            var fields=new ArrayList<PirType.Field>();for(int i=0;i<names.size();i++) {
                var field=pir(c.args().get(i));requireData(field);fields.add(new PirType.Field(names.get(i),field));
            }
            return new PirType.RecordType("elci."+c,fields);
        }
        return switch(c.name()) {
            case "Bytes"->new PirType.ByteStringType();case "Data"->new PirType.DataType();
            case "Map"->new PirType.MapType(pir(c.args().get(0)),pir(c.args().get(1)));
            case "JulcOptional"->new PirType.OptionalType(pir(c.args().getFirst()));
            case "Int"->new PirType.IntegerType();case "Bool"->new PirType.BoolType();case "String"->new PirType.StringType();case "Unit"->new PirType.UnitType();case "ScriptContext"->new PirType.DataType();
            case "List"->{var element=pir(c.args().getFirst());requireData(element);yield new PirType.ListType(element);}case "->"->new PirType.FunType(pir(c.args().get(0)),pir(c.args().get(1)));
            default->throw new IllegalArgumentException("Unsupported source type: "+type);
        };
    }
    public static Ty source(PirType type) {
        return switch(type) {
            case PirType.IntegerType _->con("Int");case PirType.BoolType _->con("Bool");case PirType.StringType _->con("String");
            case PirType.FunType f->fn(source(f.paramType()),source(f.returnType()));
            case PirType.ListType l->con("List",source(l.elemType()));
            default->throw new IllegalArgumentException("Unmapped Java export type: "+type);
        };
    }
    private static void requireData(PirType type) {
        if(type instanceof PirType.FunType)throw new IllegalArgumentException("Function-valued data fields require a representation not supported by this profile");
        if(type instanceof PirType.UnitType)throw new IllegalArgumentException("Unit in serialized containers is not yet supported");
    }
    private PirTerm projection(PirTerm base,Ty type,String field) {
        var record=(Con)prune(type);
        if(inference.importedTypes.containsKey(record.name())) {
            var descriptor=inference.importedTypes.get(record.name());
            if(descriptor.newType())return base;
            int index=0;while(!descriptor.fields().get(index).name().equals(field))index++;
            var ft=pir(inference.libraryType(descriptor.fields().get(index).type()));
            PirTerm fields=call(DefaultFun.SndPair,call(DefaultFun.UnConstrData,base));for(int i=0;i<index;i++)fields=call(DefaultFun.TailList,fields);
            return PirHelpers.wrapDecode(call(DefaultFun.HeadList,fields),ft);
        }
        int index=Arrays.asList(record.name().substring(7).split(",")).indexOf(field);
        PirTerm fields=call(DefaultFun.SndPair,call(DefaultFun.UnConstrData,base));
        for(int i=0;i<index;i++)fields=call(DefaultFun.TailList,fields);
        return PirHelpers.wrapDecode(call(DefaultFun.HeadList,fields),pir(record.args().get(index)));
    }
    private static PirTerm bool(boolean value){return new PirTerm.Const(Constant.bool(value));}
    public static PirTerm call(DefaultFun fun,PirTerm...args){PirTerm r=new PirTerm.Builtin(fun);for(var a:args)r=new PirTerm.App(r,a);return r;}
}
