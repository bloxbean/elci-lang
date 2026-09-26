package org.elci.cli;

import org.elci.driver.ElciCompiler;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.backend.DatumProfile;
import org.julclang.compiler.backend.FrontendProgram;
import org.julclang.compiler.schema.ContractSchema;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Strict, documented project-configuration subset of TOML. Unknown keys fail closed. A project
 * configures either one {@code [entrypoint]} function (for {@code elci eval}) or one or more
 * {@code [validators.<name>]} groups, never both. Validators are always named (ADR-007).
 */
public record Project(Path root,List<String> sourceDirectories,Entrypoint entrypoint,List<Validator> validators,
                      Map<String,String> javaModules,String expectedAbi,List<String> libraryJars,List<String> testDirectories) {
    /** A plain function entrypoint, evaluated with {@code elci eval}. */
    public record Entrypoint(String module,String function,FrontendProgram.Purpose purpose) {}

    /**
     * A named validator group: one deployed script.
     *
     * @param handlers    handlers in ledger-tag order
     * @param expectedAbi optional path of the committed ABI to compare before publishing
     * @param line        the line of the section header, for diagnostics
     */
    public record Validator(String name,List<ElciCompiler.HandlerSpec> handlers,List<String> parameters,
                            DatumProfile spendDatum,String expectedAbi,int line) {
        public ElciCompiler.ValidatorSpec spec() {return new ElciCompiler.ValidatorSpec(name,handlers,parameters,spendDatum);}
    }

    /** Documented validator-name rule: a lowercase, filesystem-safe component. */
    public static final Pattern VALIDATOR_NAME=Pattern.compile("[a-z][a-z0-9_-]{0,63}");
    private static final Pattern PARAMETER_NAME=Pattern.compile("[a-z][A-Za-z0-9_]*");
    private static final Pattern HANDLER=Pattern.compile("([A-Z][A-Za-z0-9_]*(?:\\.[A-Z][A-Za-z0-9_]*)*)\\.([a-z][A-Za-z0-9_]*)");
    private static final Map<String,ContractSchema.Purpose> PURPOSES=purposes();

    public Project {
        sourceDirectories=List.copyOf(sourceDirectories);validators=List.copyOf(validators);
        javaModules=Map.copyOf(javaModules);libraryJars=List.copyOf(libraryJars);testDirectories=List.copyOf(testDirectories);
    }

    /** Accessors for an {@code [entrypoint]} function project. */
    public String module(){return legacy().module();}
    public String function(){return legacy().function();}
    public FrontendProgram.Purpose purpose(){return legacy().purpose();}
    public boolean named(){return entrypoint==null;}
    private Entrypoint legacy(){if(entrypoint==null)throw new IllegalArgumentException("ELCI4001: this project configures named validators, not an [entrypoint]");return entrypoint;}

    public static Project read(Path root) throws IOException {
        var sections=new LinkedHashMap<String,Map<String,String>>();var headers=new HashMap<String,Integer>();var lines=new HashMap<String,Integer>();
        String section="";int line=0;
        for(String raw:Files.readAllLines(root.resolve("elci.toml"))) {
            line++;String text=raw.strip();if(text.isEmpty()||text.startsWith("#"))continue;
            if(text.startsWith("[")&&text.endsWith("]")) {
                section=text.substring(1,text.length()-1).strip();
                if(!Set.of("project","entrypoint","java-modules","tests").contains(section)&&!section.startsWith("validators."))throw new IllegalArgumentException("elci.toml:"+line+": unknown configuration section: "+section);
                if(section.startsWith("validators.")) {
                    String name=section.substring("validators.".length());
                    if(!VALIDATOR_NAME.matcher(name).matches())throw new IllegalArgumentException("elci.toml:"+line+": invalid validator name '"+name+"'; use lowercase letters, digits, '-' or '_', starting with a letter (at most 64 characters)");
                }
                if(headers.putIfAbsent(section,line)!=null)throw new IllegalArgumentException("elci.toml:"+line+": duplicate section ["+section+"]");
                sections.computeIfAbsent(section,k->new LinkedHashMap<>());continue;
            }
            int equal=text.indexOf('=');if(equal<1)throw new IllegalArgumentException("elci.toml:"+line+": expected key = value");
            String key=text.substring(0,equal).strip();if(key.startsWith("\""))key=quoted(key);
            var values=sections.computeIfAbsent(section,k->new LinkedHashMap<>());
            if(values.putIfAbsent(key,text.substring(equal+1).strip())!=null)throw new IllegalArgumentException("elci.toml:"+line+": duplicate configuration key: "+key);
            lines.put(section+"\u0000"+key,line);
        }
        var project=sections.getOrDefault("project",Map.of());
        keys(project,Set.of("source-directories","target","julc-version","expected-abi","library-jars"),"project");
        if(!quoted(required(project,"target","project")).equals(CompilerTarget.PLUTUS_V3_PV11.profileId()))throw new IllegalArgumentException("Unsupported target; expected "+CompilerTarget.PLUTUS_V3_PV11.profileId());
        if(!quoted(required(project,"julc-version","project")).equals(ElciCompiler.JULC_VERSION))throw new IllegalArgumentException("Julc version does not match this compiler; expected julc-version = \""+ElciCompiler.JULC_VERSION+"\"");
        var sourceDirs=paths(required(project,"source-directories","project"),"source-directories");
        if(sourceDirs.isEmpty())throw new IllegalArgumentException("At least one source directory is required");
        var modules=new LinkedHashMap<String,String>();sections.getOrDefault("java-modules",Map.of()).forEach((k,v)->modules.put(k,quoted(v)));
        var validatorSections=sections.keySet().stream().filter(s->s.startsWith("validators.")).sorted().toList();
        boolean legacy=sections.containsKey("entrypoint");
        if(legacy&&!validatorSections.isEmpty())throw new IllegalArgumentException("ELCI4001: elci.toml mixes [entrypoint] with [validators.*]; use one form");
        if(!legacy&&validatorSections.isEmpty())throw new IllegalArgumentException("ELCI4001: elci.toml must configure at least one [validators.<name>], or an [entrypoint] function");
        String expected=project.containsKey("expected-abi")?quoted(project.get("expected-abi")):null;
        var jars=project.containsKey("library-jars")?paths(project.get("library-jars"),"library-jars"):List.<String>of();
        var tests=sections.getOrDefault("tests",Map.of());keys(tests,Set.of("source-directories"),"tests");
        var testDirs=tests.containsKey("source-directories")?paths(tests.get("source-directories"),"source-directories"):List.of("tests");
        if(legacy) {
            var entry=sections.get("entrypoint");
            keys(entry,Set.of("module","function","purpose","boundary"),"entrypoint");
            if(entry.containsKey("boundary")&&!quoted(entry.get("boundary")).equals("julc-strict-v1"))throw new IllegalArgumentException("Unsupported boundary policy");
            // [entrypoint] is a plain function for elci eval and tests; validators are named (ADR-007).
            String purpose=entry.containsKey("purpose")?quoted(entry.get("purpose")):"function";
            if(PURPOSES.containsKey(purpose))throw new IllegalArgumentException("ELCI4001: [entrypoint] purpose = \""+purpose+"\" was removed; configure validators as [validators.<name>] with "+purpose+" = \""+quoted(required(entry,"module","entrypoint"))+"."+quoted(required(entry,"function","entrypoint"))+"\"");
            if(!purpose.equals("function"))throw new IllegalArgumentException("Unsupported purpose: "+purpose+"; [entrypoint] only supports purpose = \"function\"");
            var p=FrontendProgram.Purpose.FUNCTION;
            return new Project(root.toAbsolutePath().normalize(),sourceDirs,new Entrypoint(quoted(required(entry,"module","entrypoint")),quoted(required(entry,"function","entrypoint")),p),List.of(),modules,expected,jars,testDirs);
        }
        if(expected!=null)throw new IllegalArgumentException("ELCI4001: [project] expected-abi applies to [entrypoint] projects; set expected-abi in each [validators.<name>] table");
        var validators=new ArrayList<Validator>();
        for(String name:validatorSections)validators.add(validator(name.substring("validators.".length()),sections.get(name),headers.get(name),lines));
        return new Project(root.toAbsolutePath().normalize(),sourceDirs,null,validators,modules,null,jars,testDirs);
    }

    private static Validator validator(String name,Map<String,String> values,int line,Map<String,Integer> lines) {
        String where="[validators."+name+"]";
        var allowed=new HashSet<>(PURPOSES.keySet());allowed.addAll(Set.of("boundary","parameters","spend-datum","expected-abi"));
        keys(values,allowed,"validators."+name);
        if(!quoted(required(values,"boundary","validators."+name)).equals("julc-strict-v1"))throw new IllegalArgumentException("elci.toml:"+line+": "+where+" unsupported boundary policy; expected \"julc-strict-v1\"");
        var handlers=new ArrayList<ElciCompiler.HandlerSpec>();
        for(var entry:PURPOSES.entrySet()) {
            if(!values.containsKey(entry.getKey()))continue;
            String reference=quoted(values.get(entry.getKey()));
            var match=HANDLER.matcher(reference);
            int at=lines.getOrDefault("validators."+name+"\u0000"+entry.getKey(),line);
            if(!match.matches())throw new IllegalArgumentException("elci.toml:"+at+": "+where+" "+entry.getKey()+" = \""+reference+"\" must name an exposed function as \"Module.function\"");
            handlers.add(new ElciCompiler.HandlerSpec(entry.getValue(),match.group(1),match.group(2)));
        }
        if(handlers.isEmpty())throw new IllegalArgumentException("elci.toml:"+line+": "+where+" configures no handler; add one of "+String.join(", ",PURPOSES.keySet()));
        var parameters=values.containsKey("parameters")?paths(values.get("parameters"),"parameters"):List.<String>of();
        var seen=new HashSet<String>();
        for(String parameter:parameters) {
            if(!PARAMETER_NAME.matcher(parameter).matches())throw new IllegalArgumentException("elci.toml:"+line+": "+where+" invalid parameter name '"+parameter+"'");
            if(!seen.add(parameter))throw new IllegalArgumentException("elci.toml:"+line+": "+where+" duplicate parameter '"+parameter+"'");
        }
        DatumProfile datum=DatumProfile.REQUIRED;
        if(values.containsKey("spend-datum")) {
            String profile=quoted(values.get("spend-datum"));
            datum=switch(profile){case "required"->DatumProfile.REQUIRED;case "optional"->DatumProfile.OPTIONAL;case "absent"->DatumProfile.ABSENT;
                default->throw new IllegalArgumentException("elci.toml:"+line+": "+where+" spend-datum must be \"required\", \"optional\" or \"absent\"");};
            if(!values.containsKey("spend"))throw new IllegalArgumentException("elci.toml:"+line+": "+where+" spend-datum requires a spend handler");
        }
        String expected=values.containsKey("expected-abi")?quoted(values.get("expected-abi")):null;
        return new Validator(name,handlers,parameters,datum,expected,line);
    }

    /** Configuration keys in ledger-tag order. */
    private static Map<String,ContractSchema.Purpose> purposes() {
        var result=new LinkedHashMap<String,ContractSchema.Purpose>();
        Arrays.stream(ContractSchema.Purpose.values()).sorted(Comparator.comparingInt(org.julclang.compiler.backend.BackendContract::ledgerTag))
                .forEach(p->result.put(ElciCompiler.purpose(p),p));
        return Collections.unmodifiableMap(result);
    }

    private static List<String> paths(String value,String key) {
        if(!value.startsWith("[")||!value.endsWith("]"))throw new IllegalArgumentException(key+" must be an array of quoted strings");
        return Arrays.stream(value.substring(1,value.length()-1).split(",")).map(String::strip).filter(s->!s.isEmpty()).map(Project::quoted).toList();
    }
    public Map<String,String> librarySources() throws IOException {
        return org.julclang.compiler.LibrarySources.readJars(libraryJars.stream().map(root::resolve).toList());
    }
    public Map<String,String> sources() throws IOException {
        return modules(sourceDirectories,true);
    }
    /** Test modules from the {@code [tests]} directories; a missing default directory holds none. */
    public Map<String,String> testSources() throws IOException {
        return modules(testDirectories,false);
    }
    private Map<String,String> modules(List<String> directories,boolean required) throws IOException {
        var result=new LinkedHashMap<String,String>();
        for(String directory:directories) {
            Path base=root.resolve(directory);
            if(!Files.isDirectory(base)){if(required)throw new IllegalArgumentException("Source directory not found: "+base);continue;}
            try(var files=Files.walk(base)) {
                for(Path path:files.filter(p->p.toString().endsWith(".elm")).sorted().toList()) {
                    String module=base.relativize(path).toString().replace(java.io.File.separatorChar,'.');module=module.substring(0,module.length()-4);
                    if(result.putIfAbsent(module,source(path))!=null)throw new IllegalArgumentException("Duplicate module ownership: "+module);
                }
            }
        }
        return result;
    }
    /** The source file of the entrypoint function's module. */
    public Path source() {return source(module());}
    /** The source file of {@code module}. */
    public Path source(String module) {
        var directories=new ArrayList<>(sourceDirectories);directories.addAll(testDirectories);
        var found=directories.stream().map(d->root.resolve(d).resolve(module.replace('.','/')+".elm")).filter(Files::isRegularFile).toList();
        if(found.size()!=1)throw new IllegalArgumentException("Expected exactly one source for "+module+", found "+found.size());return found.getFirst();
    }
    private static String required(Map<String,String> values,String key,String section){String v=values.get(key);if(v==null)throw new IllegalArgumentException("Missing configuration key: ["+section+"] "+key);return v;}
    private static void keys(Map<String,String> values,Set<String> allowed,String section){for(String key:values.keySet())if(!allowed.contains(key))throw new IllegalArgumentException("Unknown configuration key: ["+section+"] "+key);}
    private static String quoted(String value){if(value.length()<2||!value.startsWith("\"")||!value.endsWith("\"")||value.substring(1,value.length()-1).contains("\"")||value.contains("\\"))throw new IllegalArgumentException("Expected a quoted string without escapes: "+value);return value.substring(1,value.length()-1);}

    /** A source file's text; a file that is not UTF-8 is named rather than failing obscurely. */
    private static String source(Path path) throws java.io.IOException {
        try {return Files.readString(path);}
        catch(java.nio.charset.MalformedInputException e){throw new IllegalArgumentException("ELCI4001: "+path+" is not valid UTF-8 text; save it as UTF-8");}
    }
}
