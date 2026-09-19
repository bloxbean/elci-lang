package org.elci.types;

import org.elci.syntax.*;
import java.util.*;

/** Hindley-Milner unification with occurs checks; mutable variables are invocation-local. */
public final class Types {
    private Types() {}
    public sealed interface Ty permits Var, Con {}
    public static final class Var implements Ty {
        private static final String PREFIX = "t";
        public Ty link;
        @Override public String toString() { return link == null ? PREFIX : prune(link).toString(); }
    }
    public record Con(String name, List<Ty> args) implements Ty {
        public Con { args=List.copyOf(args); }
        @Override public String toString() {
            return name.equals("->") ? "("+args.get(0)+" -> "+args.get(1)+")" : name+(args.isEmpty()?"":" "+args);
        }
    }
    public static Ty con(String name, Ty...args) { return new Con(name,List.of(args)); }
    public static Ty fn(Ty a, Ty b) { return con("->",a,b); }
    public static Ty prune(Ty t) { if(t instanceof Var v && v.link!=null) { v.link=prune(v.link);return v.link; } return t; }
    public static void unify(Ty a, Ty b, Token at) {
        a=prune(a);b=prune(b);if(a==b)return;
        if(a instanceof Var v) { if(occurs(v,b))throw new Diagnostic("ELCI2002",at,"Infinite type");v.link=b;return; }
        if(b instanceof Var){unify(b,a,at);return;}
        var x=(Con)a;var y=(Con)b;
        if(!x.name.equals(y.name)||x.args.size()!=y.args.size())throw new Diagnostic("ELCI2002",at,"Type mismatch: expected "+a+", found "+b);
        for(int i=0;i<x.args.size();i++)unify(x.args.get(i),y.args.get(i),at);
    }
    private static boolean occurs(Var v,Ty t) {t=prune(t);return t==v || t instanceof Con c && c.args.stream().anyMatch(a->occurs(v,a));}
    public static Ty annotation(Ast.Type type,Map<String,Ty> variables) {
        if(!type.name().contains(".") && Character.isLowerCase(type.name().charAt(0)))return variables.computeIfAbsent(type.name(),k->new Var());
        if(type.name().equals("->"))return fn(annotation(type.arguments().get(0),variables),annotation(type.arguments().get(1),variables));
        return new Con(type.name(),type.arguments().stream().map(a->annotation(a,variables)).toList());
    }
    public static Ty copy(Ty t,Map<Var,Ty> vars) {
        t=prune(t); if(t instanceof Var v)return vars.computeIfAbsent(v,k->new Var());
        var c=(Con)t;return new Con(c.name,c.args.stream().map(a->copy(a,vars)).toList());
    }
    public static boolean concrete(Ty t) {t=prune(t);return t instanceof Con c && c.args.stream().allMatch(Types::concrete);}
    public static Set<Var> variables(Ty t) {var vs=new HashSet<Var>();gather(t,vs);return vs;}
    private static void gather(Ty t,Set<Var> vs) {t=prune(t);if(t instanceof Var v)vs.add(v);else ((Con)t).args.forEach(a->gather(a,vs));}
    public record Scheme(Set<Var> quantified,Ty body) {
        public Ty instantiate() {
            var replacements=new HashMap<Var,Ty>();for(Var v:variables(body))replacements.put(v,quantified.contains(v)?new Var():v);
            return copy(body,replacements);
        }
    }
}
