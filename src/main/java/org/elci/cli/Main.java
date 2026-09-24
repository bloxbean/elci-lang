package org.elci.cli;

import org.elci.driver.ElciCompiler;
import org.elci.syntax.Diagnostic;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.backend.BackendException;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.vm.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class Main {
    public static void main(String[] args) { System.exit(run(args)); }
    public static int run(String...args) {
        String source="<project>";
        try {
            if(args.length==0||args[0].equals("--help")||args[0].equals("help")) {
                System.out.println("Elci "+org.elci.driver.BuildInfo.VERSION+" — Elm-syntax compiler powered by Julc\n"
                        +"Usage: elci <check|build> [project-directory] [--validator <name>]...\n"
                        +"       elci eval [project-directory]\n"
                        +"       elci init <project-directory> [--purpose spend|mint|function]\n"
                        +"       elci --version\n"
                        +"An [entrypoint] build writes build/validator.flat, validator.plutus, validator.plutus.json (Julc format) and abi.json (Elci schema).\n"
                        +"A named-validator build writes the same files under build/validators/<name>/ for each [validators.<name>] group.");return 0;
            }
            if(args[0].equals("--version")){System.out.println("elci "+org.elci.driver.BuildInfo.VERSION+" / julc "+ElciCompiler.JULC_VERSION);return 0;}
            if(args[0].equals("init"))return ProjectInitializer.run(Arrays.copyOfRange(args,1,args.length));
            if(!Set.of("check","build","eval").contains(args[0]))throw new IllegalArgumentException("Expected init, check, build or eval; see --help");
            String directory=null;var selected=new LinkedHashSet<String>();
            for(int i=1;i<args.length;i++) {
                if(args[i].equals("--validator")) {
                    if(args[0].equals("eval"))throw new IllegalArgumentException("ELCI4001: eval does not select validators; it runs [entrypoint] purpose = \"function\" projects");
                    if(++i==args.length)throw new IllegalArgumentException("ELCI4001: --validator requires a validator name");
                    if(!selected.add(args[i]))throw new IllegalArgumentException("ELCI4001: validator "+args[i]+" is selected more than once");
                } else if(args[i].startsWith("--"))throw new IllegalArgumentException("Unknown option: "+args[i]);
                else if(directory==null)directory=args[i];
                else throw new IllegalArgumentException("Expected at most one project directory; see --help");
            }
            var project=Project.read(Path.of(directory==null?".":directory));
            if(project.named())return named(args[0],project,selected);
            if(!selected.isEmpty())throw new IllegalArgumentException("ELCI4001: --validator requires [validators.<name>] configuration; this project uses [entrypoint]");
            source=project.source().toString();
            var result=new ElciCompiler().compileProject(project.sources(),project.module(),project.function(),project.purpose(),project.javaModules(),new CompilerOptions(),project.librarySources());
            if(!result.module().equals(project.module()))throw new IllegalArgumentException("Module header does not match configured source path");
            String manifest=org.elci.abi.Abi.manifest(result,project.purpose());
            if(project.expectedAbi()!=null&&!Files.readString(project.root().resolve(project.expectedAbi())).equals(manifest))throw new IllegalArgumentException("ELCI4001: ABI drift from "+project.expectedAbi()+"; compare constructor tags, field order, and entrypoint roles");
            byte[] flat=UplcFlatEncoder.encodeProgram(result.program());
            if(args[0].equals("eval")) {
                if(project.purpose()!=org.julclang.compiler.backend.FrontendProgram.Purpose.FUNCTION)throw new IllegalArgumentException("eval requires purpose = \"function\"; validators need ledger context fixtures");
                var evaluation=JulcVm.create().evaluate(result.program(),result.target().ledgerTarget());System.out.println(evaluation);return evaluation instanceof EvalResult.Success?0:1;
            }
            if(args[0].equals("build")) {
                var output=project.root().resolve("build");Files.createDirectories(output);
                Files.write(output.resolve("validator.flat"),flat);
                var script=org.julclang.clientlib.JulcScriptAdapter.fromProgram(result.program());
                String hex=script.getCborHex();
                if(project.purpose()!=org.julclang.compiler.backend.FrontendProgram.Purpose.FUNCTION) {
                    String purpose=project.purpose()==org.julclang.compiler.backend.FrontendProgram.Purpose.SPEND?"spending":"minting";
                    var artifact=new org.julclang.clientlib.ValidatorOutput("PlutusScriptV3",purpose,
                            result.module()+"."+result.function(),hex,
                            org.julclang.clientlib.JulcScriptAdapter.scriptHash(result.program()),"",flat.length);
                    Files.writeString(output.resolve("validator.plutus.json"),artifact.toJson());
                } else {
                    // A former validator build must not survive switching this project to a function.
                    Files.deleteIfExists(output.resolve("validator.plutus.json"));
                }
                Files.writeString(output.resolve("validator.plutus"),"{\n  \"type\": \"PlutusScriptV3\",\n  \"description\": \"Elci validator\",\n  \"cborHex\": \""+hex+"\"\n}\n");
                Files.writeString(output.resolve("abi.json"),manifest);
            }
            System.out.println(result.module()+"."+result.function()+": checked; "+flat.length+" FLAT bytes; "+result.target().profileId());return 0;
        } catch(Diagnostic e) {System.err.println(e.render(source));return 1;}
        catch(Exception e){System.err.println("elci: "+e.getMessage());return 1;}
    }

    /**
     * Check or build named validators. Every selected group is compiled and compared with its
     * expected ABI before any output is written, so a failure publishes nothing.
     */
    private static int named(String command,Project project,Set<String> selected) throws Exception {
        if(command.equals("eval"))throw new IllegalArgumentException("ELCI4001: eval requires an [entrypoint] project with purpose = \"function\"; named validators need ledger context fixtures");
        var configured=new TreeMap<String,Project.Validator>();project.validators().forEach(v->configured.put(v.name(),v));
        for(String name:selected)if(!configured.containsKey(name))throw new IllegalArgumentException("ELCI4001: unknown validator '"+name+"'; configured: "+String.join(", ",configured.keySet()));
        var groups=configured.values().stream().filter(v->selected.isEmpty()||selected.contains(v.name())).toList();
        var sources=project.sources();var librarySources=project.librarySources();
        var rendered=new ArrayList<ValidatorOutputs.Rendered>();var lines=new ArrayList<String>();
        for(var group:groups) {
            ElciCompiler.ValidatorBuild build;
            try {
                build=new ElciCompiler().compileValidator(sources,group.spec(),project.javaModules(),new CompilerOptions(),librarySources);
            } catch(Diagnostic e) {
                throw new IllegalArgumentException(where(project,group)+"\n"+e.render(sourceFor(project,group,e)),e);
            } catch(BackendException e) {
                throw new IllegalArgumentException(where(project,group)+": backend rejected the validator: "+e.getMessage(),e);
            } catch(IllegalArgumentException e) {
                throw new IllegalArgumentException(where(project,group)+": "+e.getMessage(),e);
            }
            var output=ValidatorOutputs.render(build);
            if(group.expectedAbi()!=null) {
                String expected=Files.readString(project.root().resolve(group.expectedAbi()));
                if(!expected.equals(new String(output.files().get("abi.json"),StandardCharsets.UTF_8)))
                    throw new IllegalArgumentException("ELCI4001: "+where(project,group)+": ABI drift from "+group.expectedAbi()+"; compare constructor tags, field order, handler roles and parameters");
            }
            rendered.add(output);lines.add(ValidatorOutputs.summary(build,output.size()));
        }
        if(command.equals("build"))ValidatorOutputs.publish(project.root().resolve("build"),rendered,selected.isEmpty());
        lines.forEach(System.out::println);
        return 0;
    }

    private static String where(Project project,Project.Validator group) {
        return project.root().resolve("elci.toml")+":"+group.line()+": [validators."+group.name()+"]";
    }

    /** The handler module a diagnostic most likely belongs to: the only one, or the first. */
    private static String sourceFor(Project project,Project.Validator group,Diagnostic diagnostic) {
        var modules=new LinkedHashSet<String>();group.handlers().forEach(h->modules.add(h.module()));
        try {return project.source(modules.iterator().next()).toString();}
        catch(RuntimeException e){return "<validator "+group.name()+">";}
    }
}
