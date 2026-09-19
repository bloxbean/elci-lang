package org.elci.syntax;

import java.util.List;
import java.util.Map;

public final class Ast {
    private Ast() {}
    public record Module(String name, List<String> exposing, List<Import> imports, List<Decl> declarations, Map<String,Type> aliases, List<Union> unions) {}
    public record Union(String name, List<String> parameters, List<Variant> variants, Token token) {}
    public record Variant(String name, List<Type> fields) {}
    public record Import(String module, String alias, List<String> exposing, Token token) {}
    public record Decl(String name, List<String> parameters, Type annotation, Expr body, Token token) {}
    public record Type(String name, List<Type> arguments) {
        public static Type fn(Type a, Type b) { return new Type("->",List.of(a,b)); }
    }
    public sealed interface Expr {
        Token token();
    }
    public record Field(Expr record, String name, Token token) implements Expr {}
    public record RecordValue(Expr base, Map<String,Expr> fields, Token token) implements Expr {}
    public record Name(String name, Token token) implements Expr {}
    public record Literal(Object value, Token token) implements Expr {}
    public record Apply(Expr function, Expr argument, Token token) implements Expr {}
    public record Lambda(List<String> parameters, Expr body, Token token) implements Expr {}
    public record If(Expr condition, Expr yes, Expr no, Token token) implements Expr {}
    public record Binary(String op, Expr left, Expr right, Token token) implements Expr {}
    public record Let(List<Decl> declarations, Expr body, Token token) implements Expr {}
    public record Items(List<Expr> elements, boolean tuple, Token token) implements Expr {}
    public record Case(Expr scrutinee, List<Branch> branches, Token token) implements Expr {}
    public record Branch(Pattern pattern, Expr body) {}
    public record Pattern(String name, List<Pattern> arguments, Token token) {}
}
