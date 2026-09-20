package org.elci.cli;

import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.backend.FrontendProgram;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Strict, documented project-configuration subset of TOML. Unknown keys fail closed. */
public record Project(Path root,List<String> sourceDirectories,String module,String function,
                      FrontendProgram.Purpose purpose,Map<String,String> javaModules,String expectedAbi,List<String> libraryJars) {
    public static Project read(Path root) throws IOException {
        var sections=new LinkedHashMap<String,Map<String,String>>();String section="";int line=0;
        for(String raw:Files.readAllLines(root.resolve("elci.toml"))) {
            line++;String text=raw.strip();if(text.isEmpty()||text.startsWith("#"))continue;
            if(text.startsWith("[")&&text.endsWith("]")){section=text.substring(1,text.length()-1);if(!Set.of("project","entrypoint","java-modules").contains(section))throw new IllegalArgumentException("Unknown configuration section: "+section);continue;}
            int equal=text.indexOf('=');if(equal<1)throw new IllegalArgumentException("elci.toml:"+line+": expected key = value");
            String key=text.substring(0,equal).strip();if(key.startsWith("\""))key=quoted(key);
            var values=sections.computeIfAbsent(section,k->new LinkedHashMap<>());
            if(values.putIfAbsent(key,text.substring(equal+1).strip())!=null)throw new IllegalArgumentException("Duplicate configuration key: "+key);
        }
        var project=sections.getOrDefault("project",Map.of());var entry=sections.getOrDefault("entrypoint",Map.of());
        keys(project,Set.of("source-directories","target","julc-version","expected-abi","library-jars"));keys(entry,Set.of("module","function","purpose","boundary"));
        if(!quoted(required(project,"target")).equals(CompilerTarget.PLUTUS_V3_PV11.profileId()))throw new IllegalArgumentException("Unsupported target; expected "+CompilerTarget.PLUTUS_V3_PV11.profileId());
        if(!quoted(required(project,"julc-version")).equals(org.elci.driver.ElciCompiler.JULC_VERSION))throw new IllegalArgumentException("Julc version does not match this compiler");
        if(!quoted(required(entry,"boundary")).equals("julc-strict-v1"))throw new IllegalArgumentException("Unsupported boundary policy");
        String dirs=required(project,"source-directories");if(!dirs.startsWith("[")||!dirs.endsWith("]"))throw new IllegalArgumentException("source-directories must be an array of quoted paths");
        var sourceDirs=Arrays.stream(dirs.substring(1,dirs.length()-1).split(",")).map(String::strip).filter(s->!s.isEmpty()).map(Project::quoted).toList();
        if(sourceDirs.isEmpty())throw new IllegalArgumentException("At least one source directory is required");
        var modules=new LinkedHashMap<String,String>();sections.getOrDefault("java-modules",Map.of()).forEach((k,v)->modules.put(k,quoted(v)));
        String purpose=quoted(required(entry,"purpose"));
        var p=switch(purpose){case "spend"->FrontendProgram.Purpose.SPEND;case "mint"->FrontendProgram.Purpose.MINT;case "function"->FrontendProgram.Purpose.FUNCTION;default->throw new IllegalArgumentException("Unsupported purpose: "+purpose);};
        return new Project(root.toAbsolutePath().normalize(),sourceDirs,quoted(required(entry,"module")),quoted(required(entry,"function")),p,Map.copyOf(modules),project.containsKey("expected-abi")?quoted(project.get("expected-abi")):null, project.containsKey("library-jars")?paths(project.get("library-jars")):List.of());
    }
    private static List<String> paths(String value) {
        if(!value.startsWith("[")||!value.endsWith("]"))throw new IllegalArgumentException("library-jars must be an array of quoted paths");
        return Arrays.stream(value.substring(1,value.length()-1).split(",")).map(String::strip).filter(s->!s.isEmpty()).map(Project::quoted).toList();
    }
    public Map<String,String> librarySources() throws IOException {
        return org.julclang.compiler.LibrarySources.readJars(libraryJars.stream().map(root::resolve).toList());
    }
    public Map<String,String> sources() throws IOException {
        var result=new LinkedHashMap<String,String>();
        for(String directory:sourceDirectories) {
            Path base=root.resolve(directory);
            if(!Files.isDirectory(base))throw new IllegalArgumentException("Source directory not found: "+base);
            try(var files=Files.walk(base)) {
                for(Path path:files.filter(p->p.toString().endsWith(".elm")).sorted().toList()) {
                    String module=base.relativize(path).toString().replace(java.io.File.separatorChar,'.');module=module.substring(0,module.length()-4);
                    if(result.putIfAbsent(module,Files.readString(path))!=null)throw new IllegalArgumentException("Duplicate module ownership: "+module);
                }
            }
        }
        return result;
    }
    public Path source() {
        var found=sourceDirectories.stream().map(d->root.resolve(d).resolve(module.replace('.','/')+".elm")).filter(Files::isRegularFile).toList();
        if(found.size()!=1)throw new IllegalArgumentException("Expected exactly one source for "+module+", found "+found.size());return found.getFirst();
    }
    private static String required(Map<String,String> values,String key){String v=values.get(key);if(v==null)throw new IllegalArgumentException("Missing configuration key: "+key);return v;}
    private static void keys(Map<String,String> values,Set<String> allowed){for(String key:values.keySet())if(!allowed.contains(key))throw new IllegalArgumentException("Unknown configuration key: "+key);}
    private static String quoted(String value){if(value.length()<2||!value.startsWith("\"")||!value.endsWith("\"")||value.substring(1,value.length()-1).contains("\"")||value.contains("\\"))throw new IllegalArgumentException("Expected a quoted string without escapes: "+value);return value.substring(1,value.length()-1);}
}
