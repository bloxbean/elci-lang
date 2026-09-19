package org.elci.syntax;

import java.math.BigInteger;
import java.util.*;
import static org.elci.syntax.Ast.*;

/** Recursive descent with token-column layout boundaries and Pratt operators. */
public final class Parser {
    private final List<Token> tokens;
    private int pos;
    private final List<Union> unions = new ArrayList<>();
    private final Map<String,Integer> typeArities = new HashMap<>(Map.of("List",1,"Maybe",1));
    private final Map<String,Type> aliases = new LinkedHashMap<>();
    private static final Map<String,Integer> PRECEDENCE = Map.ofEntries(
        Map.entry("|>",0),Map.entry("<|",0),Map.entry("||",2),Map.entry("&&",3),
        Map.entry("==",4),Map.entry("/=",4),Map.entry("<",4),Map.entry(">",4),Map.entry("<=",4),Map.entry(">=",4),
        Map.entry("::",5),Map.entry("++",5),Map.entry("+",6),Map.entry("-",6),
        Map.entry(">>",9),Map.entry("<<",9),Map.entry("*",7),Map.entry("//",7),Map.entry("/",7));
    private static final Set<String> RESERVED = Set.of("then","else","in","of","module","import","exposing","as","type","port");
    public Parser(String source) {
        tokens = new Lexer(source).lex();
        for(int i=0;i<tokens.size()-2;i++)if(tokens.get(i).text().equals("type")&&!tokens.get(i+1).text().equals("alias")) {
            int j=i+2;while(j<tokens.size() && tokens.get(j).kind()==Token.Kind.NAME && Character.isLowerCase(tokens.get(j).text().charAt(0)))j++;
            typeArities.put(tokens.get(i+1).text(),j-i-2);
        }
    }
    public Ast.Module parse() {
        expect("module"); String module=qualified(true); expect("exposing"); var exports=exposing();
        var imports=new ArrayList<Import>();
        while(take("import")) {
            Token t=previous(); String name=qualified(true); String alias=name;
            if(take("as")) alias=upper().text();
            var exposed=take("exposing")?exposing():List.<String>of();
            imports.add(new Import(name,alias,exposed,t));
        }
        var ds=declarations(1,"<eof>");
        expect("<eof>");
        return new Ast.Module(module,exports,List.copyOf(imports),ds,Map.copyOf(aliases),List.copyOf(unions));
    }
    private List<String> exposing() {
        expect("("); var names=new ArrayList<String>();
        if(take("..")) { names.add(".."); expect(")"); return names; }
        if(take(")")) return names;
        do {
            String n=name().text();
            if(take("(")){expect("..");expect(")");n+="(..)";}
            names.add(n);
        } while(take(","));
        expect(")"); return List.copyOf(names);
    }
    private List<Decl> declarations(int indent,String end) {
        var result=new ArrayList<Decl>(); var seen=new HashSet<String>();
        while(!is(end) && peek().kind()!=Token.Kind.EOF) {
            if(peek().column()!=indent) throw error("Declarations must align at column " + indent);
            if(take("type")) {
                if(take("alias")) {
                    String alias=upper().text();expect("=");Type target=type(indent);
                    if(unions.stream().anyMatch(u->u.name().equals(alias))||aliases.putIfAbsent(alias,target)!=null)throw error("Duplicate type alias "+alias);
                } else {
                    Token at=upper();String n=at.text();var parameters=new ArrayList<String>();
                    while(!is("="))parameters.add(lower().text());expect("=");var variants=new ArrayList<Variant>();
                    do {
                        String constructor=upper().text();var fields=new ArrayList<Type>();
                        while(!is("|")&&!boundary(indent)) {
                            if(!is("(")&&!is("{")&&peek().kind()!=Token.Kind.NAME)break;
                            fields.add(typeAtom(indent));
                        }
                        variants.add(new Variant(constructor,List.copyOf(fields)));
                    }while(take("|"));
                    if(unions.stream().anyMatch(u->u.name().equals(n))||aliases.containsKey(n))throw error("Duplicate type "+n);
                    unions.add(new Union(n,parameters,variants,at));
                }
                continue;
            }
            Token t=lower(); String n=t.text(); Type annotation=null;
            if(take(":")) {
                annotation=type(indent); Token definition=lower();
                if(definition.column()!=indent || !definition.text().equals(n)) throw error("A signature must precede its matching definition");
            }
            var params=new ArrayList<String>();
            while(!is("=")) { var param=lower(); if(!param.text().equals("_")&&params.contains(param.text()))throw error("Duplicate parameter " + param.text()); params.add(param.text()); }
            expect("=");
            var body=expression(0,indent);
            if(!seen.add(n)) throw new Diagnostic("ELCI1002",t,"Duplicate declaration " + n);
            result.add(new Decl(n,List.copyOf(params),annotation,body,t));
            if(!is(end) && peek().kind()!=Token.Kind.EOF && peek().column()!=indent) throw error("Unexpected token after declaration");
        }
        return List.copyOf(result);
    }
    private Type type(int floor) {
        Type left=typeAtom(floor);
        var arguments=new ArrayList<>(left.arguments());
        while(!boundary(floor) && (peek().kind()==Token.Kind.NAME&&!RESERVED.contains(peek().text()) || is("(") || is("{")))arguments.add(typeAtom(floor));
        left=new Type(left.name(),arguments);
        if(take("->")) return Type.fn(left,type(floor));
        return left;
    }
    private Type typeAtom(int floor) {
        if(take("(")) {
            if(take(")")) return new Type("Unit",List.of());
            var a=type(floor);
            if(take(",")) { var parts=new ArrayList<Type>();parts.add(a); do{parts.add(type(floor));}while(take(","));expect(")");return new Type("Tuple",parts); }
            expect(")"); return a;
        }
        if(take("{")) {
            var fields=new TreeMap<String,Type>();
            if(!is("}"))do {
                String field=lower().text();
                if(is("|"))throw error("Extensible records are unsupported; use a closed record");
                expect(":");if(fields.putIfAbsent(field,type(-1))!=null)throw error("Duplicate record field "+field);
            }while(take(","));
            expect("}");return new Type("Record:"+String.join(",",fields.keySet()),List.copyOf(fields.values()));
        }
        var t=name(); String n=t.text();
        if(Character.isUpperCase(n.charAt(0)))while(take("."))n+="."+upper().text();
        if(n.equals("Float")) throw new Diagnostic("ELCI2001",t,"Float is unsupported; use scaled Int");
        var args=new ArrayList<Type>();

        return new Type(n,List.copyOf(args));
    }
    private Expr expression(int min, int floor) {
        Expr left=prefix(floor);
        while(!boundary(floor)) {
            if(is(".") && peek().offset()==previous().offset()+previous().text().length()) {
                Token dot=next();left=new Field(left,lower().text(),dot);continue;
            }
            String op=peek().text(); Integer precedence=PRECEDENCE.get(op);
            if(precedence!=null && precedence>=min) {
                Token token=next();
                if(op.equals("/")) throw new Diagnostic("ELCI2001",token,"Float division is unsupported; use // for integer quotient");
                Expr right=expression(precedence+(Set.of("::","++","<|","&&","||","<<").contains(op)?0:1),floor);
                left=new Binary(op,left,right,token);
                if(precedence==4 && PRECEDENCE.getOrDefault(peek().text(),-1)==4)throw error("Comparison operators cannot be chained; use && or explicit parentheses");
            } else if(atomStart() && 10>=min) {
                Token token=peek(); left=new Apply(left,expression(11,floor),token);
            } else break;
        }
        return left;
    }
    private Expr prefix(int floor) {
        Token t=next();
        if(t.text().equals("if")) {
            Expr cond=expression(0,floor);expect("then");Expr yes=expression(0,floor);expect("else");return new If(cond,yes,expression(0,floor),t);
        }
        if(t.text().equals("\\")) {
            var ps=new ArrayList<String>();do{ps.add(lower().text());}while(!is("->"));expect("->");return new Lambda(ps,expression(0,floor),t);
        }
        if(t.text().equals("let")) {
            int indent=peek().column();var ds=declarations(indent,"in");expect("in");return new Let(ds,expression(0,floor),t);
        }
        if(t.text().equals("case")) {
            Expr value=expression(0,floor); expect("of"); int indent=peek().column();var branches=new ArrayList<Branch>();
            do { var p=pattern();expect("->");branches.add(new Branch(p,expression(0,indent))); }
            while(peek().column()==indent && !is("<eof>") && !is("in") && !is("else"));
            return new Case(value,branches,t);
        }
        if(t.text().equals("-")) return new Binary("-",new Literal(BigInteger.ZERO,t),expression(9,floor),t);
        if(t.kind()==Token.Kind.INT) return new Literal(new BigInteger(t.text()),t);
        if(t.kind()==Token.Kind.STRING) return new Literal(t.text(),t);
        if(t.text().equals("True") || t.text().equals("False")) return new Literal(t.text().equals("True"),t);
        if(t.text().equals("(")) {
            if(take(")")) return new Literal(null,t);
            Expr e=expression(0,-1);
            if(take(",")) { var es=new ArrayList<Expr>();es.add(e);do{es.add(expression(0,-1));}while(take(","));expect(")");if(es.size()>3)throw error("Tuples have two or three elements");return new Items(es,true,t); }
            expect(")");return e;
        }
        if(t.text().equals("{")) {
            Expr base=null;var fields=new LinkedHashMap<String,Expr>();
            if(take("}"))return new RecordValue(null,fields,t);
            Token first=lower();
            if(take("|")){base=new Name(first.text(),first);first=lower();}
            while(true) {
                expect("=");if(fields.putIfAbsent(first.text(),expression(0,-1))!=null)throw error("Duplicate record field "+first.text());
                if(!take(","))break;first=lower();
            }
            expect("}");return new RecordValue(base,Collections.unmodifiableMap(fields),t);
        }
        if(t.text().equals("[")) {
            var es=new ArrayList<Expr>();if(!is("]")){do{es.add(expression(0,-1));}while(take(","));}expect("]");return new Items(es,false,t);
        }
        if(t.kind()==Token.Kind.NAME && !RESERVED.contains(t.text())) {
            String n=t.text();
            if(Character.isUpperCase(n.charAt(0)))while(take("."))n+="."+name().text();
            return new Name(n,t);
        }
        throw new Diagnostic("ELCI1002",t,"Expected expression, found '"+t.text()+"'");
    }
    private Pattern pattern() {
        Token t=next(); Pattern p;
        if(t.text().equals("[")) { expect("]");p=new Pattern("[]",List.of(),t); }
        else if(t.text().equals("(")) {
            p=pattern();
            if(take(",")){var parts=new ArrayList<Pattern>();parts.add(p);do{parts.add(pattern());}while(take(","));if(parts.size()>3)throw error("Tuple patterns have two or three elements");p=new Pattern("Tuple",parts,t);}
            expect(")");
        }
        else if(t.kind()==Token.Kind.NAME || t.kind()==Token.Kind.INT) p=new Pattern(t.text(),List.of(),t);
        else throw new Diagnostic("ELCI1002",t,"Unsupported pattern");
        if(t.kind()==Token.Kind.NAME && Character.isUpperCase(t.text().charAt(0)) && !Set.of("True","False").contains(t.text())) {
            String constructor=p.name();while(take("."))constructor+="."+upper().text();
            var args=new ArrayList<Pattern>();
            while(!is("->")&&!is("::")&&!is(")")&&!is(",")) {
                if(take("(")){args.add(pattern());expect(")");}
                else {Token a=lower();args.add(new Pattern(a.text(),List.of(),a));}
            }
            p=new Pattern(constructor,args,t);
        }
        if(take("::")) return new Pattern("::",List.of(p,pattern()),t);
        return p;
    }
    private boolean boundary(int floor) {
        return peek().kind()==Token.Kind.EOF || (peek().column()<=floor && peek().line()>previous().line());
    }
    private boolean atomStart() {
        var t=peek();return (t.kind()==Token.Kind.NAME && !RESERVED.contains(t.text()) && !Set.of("if","let","case").contains(t.text())) || t.kind()==Token.Kind.INT || t.kind()==Token.Kind.STRING || is("(") || is("[") || is("{");
    }
    private String qualified(boolean capital) { String n=(capital?upper():name()).text();while(take("."))n+="."+(capital?upper():name()).text();return n; }
    private Token lower() { Token t=name();if(!Character.isLowerCase(t.text().charAt(0))&&!t.text().equals("_"))throw new Diagnostic("ELCI1002",t,"Expected lowercase name");return t; }
    private Token upper() { Token t=name();if(!Character.isUpperCase(t.text().charAt(0)))throw new Diagnostic("ELCI1002",t,"Expected capitalized module name");return t; }
    private Token name() {Token t=next();if(t.kind()!=Token.Kind.NAME||RESERVED.contains(t.text()))throw new Diagnostic("ELCI1002",t,"Expected name");return t;}
    private Token peek(){return tokens.get(pos);} private Token previous(){return tokens.get(Math.max(0,pos-1));}
    private Token next(){return tokens.get(pos++);} private boolean is(String s){return peek().text().equals(s);}
    private boolean take(String s){if(is(s)){pos++;return true;}return false;}
    private void expect(String s){if(!take(s))throw error("Expected '"+s+"', found '"+peek().text()+"'");}
    private Diagnostic error(String m){return new Diagnostic("ELCI1002",peek(),m);}
}
