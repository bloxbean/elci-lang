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
        String source="<project>";java.util.function.Function<String,String> files=module->null;
        // `--report=json` prints a failure as one JSON object on stderr, for editors and agents (ADR-011).
        boolean json=Arrays.asList(args).contains("--report=json");
        if(json)args=Arrays.stream(args).filter(a->!a.equals("--report=json")).toArray(String[]::new);
        // Colour for a terminal, unless NO_COLOR is set; --color=always|never overrides (ADR-012).
        String color="auto";
        for(String a:args)if(a.startsWith("--color="))color=a.substring("--color=".length());
        args=Arrays.stream(args).filter(a->!a.startsWith("--color=")).toArray(String[]::new);
        if(!Set.of("auto","always","never").contains(color)){System.err.println("elci: ELCI4001: --color takes auto, always or never");return 1;}
        colour=!json&&(color.equals("always")||color.equals("auto")&&System.getenv("NO_COLOR")==null
                &&System.console()!=null&&System.console().isTerminal());
        try {
            // `elci --help`, `elci help` and `elci <command> --help` all print the usage.
            if(args.length==0||args[0].equals("help")||Arrays.asList(args).contains("--help")) {
                System.out.println("Elci "+org.elci.driver.BuildInfo.VERSION+" — Elm-syntax compiler powered by Julc\n"
                        +"Usage: elci <check|build> [project-directory] [--validator <name>]...\n"
                        +"       elci eval [project-directory] [--builtins]\n"
                        +"       elci test [project-directory] [--match <substring>] [--seed <n>] [--fuzz <runs>]\n"
                        +"       elci apply [project-directory] --validator <name> --param <name>=<plutus-data-json|@file> [--output <file>]\n"
                        +"       elci init <project-directory> [--validator <name>] [--purpose spend|mint|withdraw|certify|vote|propose|function]\n"
                        +"       elci explain [code]\n"
                        +"       elci --version\n"
                        +"check, build, eval and test take --report=json to print each compile error as a JSON object on its own line,\n"
                        +"and --color=auto|always|never for coloured errors (auto: on a terminal, unless NO_COLOR is set)\n"
                        +"A build writes build/validators/<name>/validator.flat, validator.plutus, validator.plutus.json (Julc format) and abi.json (Elci schema)\n"
                        +"for each [validators.<name>] group; an [entrypoint] function project writes build/validator.flat, validator.plutus and abi.json.");return 0;
            }
            if(args[0].equals("--version")){System.out.println("elci "+org.elci.driver.BuildInfo.VERSION+" / julc "+ElciCompiler.JULC_VERSION);return 0;}
            if(json&&!Set.of("check","build","eval","test").contains(args[0]))throw new IllegalArgumentException("ELCI4001: --report=json applies to check, build, eval and test");
            if(args[0].equals("init"))return ProjectInitializer.run(Arrays.copyOfRange(args,1,args.length));
            if(args[0].equals("explain"))return Explain.run(Arrays.copyOfRange(args,1,args.length),colour);
            if(args[0].equals("test"))return test(json,Arrays.copyOfRange(args,1,args.length));
            if(!Set.of("check","build","eval","apply").contains(args[0]))throw new IllegalArgumentException("ELCI4001: Expected init, check, build, eval, test, apply or explain; see --help");
            String directory=null;var selected=new LinkedHashSet<String>();var parameters=new LinkedHashMap<String,String>();Path appliedOutput=null;boolean builtins=false;
            for(int i=1;i<args.length;i++) {
                if(args[0].equals("apply")&&args[i].equals("--param")) {
                    if(++i==args.length)throw new IllegalArgumentException("ELCI4001: --param requires <name>=<plutus-data-json>");
                    int equal=args[i].indexOf('=');
                    if(equal<1)throw new IllegalArgumentException("ELCI4001: --param requires <name>=<plutus-data-json>");
                    if(parameters.putIfAbsent(args[i].substring(0,equal),args[i].substring(equal+1))!=null)throw new IllegalArgumentException("ELCI4001: parameter "+args[i].substring(0,equal)+" is given more than once");
                } else if(args[0].equals("apply")&&args[i].equals("--output")) {
                    if(++i==args.length||appliedOutput!=null)throw new IllegalArgumentException("ELCI4001: --output requires one file path");
                    appliedOutput=Path.of(args[i]);
                } else if(args[0].equals("eval")&&args[i].equals("--builtins")) {
                    builtins=true;
                } else if(args[i].equals("--validator")) {
                    if(args[0].equals("eval"))throw new IllegalArgumentException("ELCI4001: eval does not select validators; it runs [entrypoint] purpose = \"function\" projects");
                    if(++i==args.length)throw new IllegalArgumentException("ELCI4001: --validator requires a validator name");
                    if(!selected.add(args[i]))throw new IllegalArgumentException("ELCI4001: validator "+args[i]+" is selected more than once");
                } else if(args[i].startsWith("--"))throw new IllegalArgumentException("ELCI4001: Unknown option: "+args[i]);
                else if(directory==null)directory=args[i];
                else throw new IllegalArgumentException("ELCI4001: Expected at most one project directory; see --help");
            }
            var project=Project.read(Path.of(directory==null?".":directory));
            files=module->{try{return project.source(module).toString();}catch(RuntimeException e){return null;}};
            if(args[0].equals("apply")) {
                if(selected.size()>1)throw new IllegalArgumentException("ELCI4001: apply takes exactly one --validator");
                return ParameterApplication.run(project,selected.isEmpty()?null:selected.iterator().next(),parameters,appliedOutput);
            }
            if(project.named())return named(args[0],project,selected);
            if(!selected.isEmpty())throw new IllegalArgumentException("ELCI4001: --validator requires [validators.<name>] configuration; this project has an [entrypoint] function");
            source=project.source().toString();
            var result=new ElciCompiler().compileProject(project.sources(),project.module(),project.function(),project.purpose(),project.javaModules(),new CompilerOptions(),project.librarySources());
            if(!result.module().equals(project.module()))throw new IllegalArgumentException("ELCI4001: the [entrypoint] module header does not match its configured source path");
            String manifest=org.elci.abi.Abi.manifest(result);
            if(project.expectedAbi()!=null&&!Files.readString(project.root().resolve(project.expectedAbi())).equals(manifest))throw new IllegalArgumentException("ELCI4001: ABI drift from "+project.expectedAbi()+"; compare constructor tags, field order, and entrypoint roles");
            byte[] flat=UplcFlatEncoder.encodeProgram(result.program());
            if(args[0].equals("eval"))return evaluate(result,builtins);
            if(args[0].equals("build")) {
                var output=project.root().resolve("build");Files.createDirectories(output);
                Files.write(output.resolve("validator.flat"),flat);
                String hex=org.julclang.clientlib.JulcScriptAdapter.fromProgram(result.program()).getCborHex();
                // A function is not a validator; no Julc validator artifact is written.
                Files.writeString(output.resolve("validator.plutus"),"{\n  \"type\": \"PlutusScriptV3\",\n  \"description\": \"Elci function\",\n  \"cborHex\": \""+hex+"\"\n}\n");
                Files.writeString(output.resolve("abi.json"),manifest);
            }
            System.out.println(result.module()+"."+result.function()+": checked; "+flat.length+" FLAT bytes; "+result.target().profileId());return 0;
        } catch(Diagnostic e) {report(e,files,source,json,null);return 1;}
        // The diagnostic's own line comes first, as for every diagnostic; the validator group follows.
        catch(ValidatorDiagnostic e) {report(e.diagnostic,e.files,e.fallback,json,e.where);return 1;}
        catch(Exception e){System.err.println(json?failure(e.getMessage()):"elci: "+e.getMessage());return 1;}
    }

    /** Whether diagnostics are printed in colour: a terminal, or --color=always. */
    private static boolean colour;

    /**
     * Print a run's diagnostics in source order, at most {@link Diagnostic#LIMIT}: each with its
     * excerpt and hints, then the validator group they arose in, then a count when there are
     * several, and last where to read more about their codes. With {@code --report=json}, one
     * JSON object per line (ADR-012).
     */
    private static void report(Diagnostic diagnostic,java.util.function.Function<String,String> files,String fallback,boolean json,String context) {
        var all=diagnostic.all();
        int shown=Math.min(all.size(),Diagnostic.LIMIT);
        for(int i=0;i<shown;i++) {
            if(json){System.err.println(all.get(i).json(files,fallback,context));continue;}
            if(i>0)System.err.println();
            System.err.println(all.get(i).explain(files,fallback,colour));
        }
        if(json)return;
        if(context!=null)System.err.println("\nIn "+context);
        if(all.size()>shown)System.err.println("\n... and "+(all.size()-shown)+" more");
        if(all.size()>1)System.err.println("\n"+all.size()+" errors");
        String footer=Explain.footer(all.subList(0,shown).stream().map(Diagnostic::code).toList());
        if(footer!=null)System.err.println("\n"+footer);
    }

    /** A diagnostic in one named validator group, with where to render it. */
    private static final class ValidatorDiagnostic extends RuntimeException {
        final String where;final Diagnostic diagnostic;final java.util.function.Function<String,String> files;final String fallback;
        ValidatorDiagnostic(String where,Diagnostic diagnostic,java.util.function.Function<String,String> files,String fallback) {
            super(where+"\n"+diagnostic.render(files,fallback),diagnostic);
            this.where=where;this.diagnostic=diagnostic;this.files=files;this.fallback=fallback;
        }
    }

    /** A failure without a source location, in the JSON report's shape. */
    private static String failure(String message) {
        String text=message==null?"":message;
        var coded=java.util.regex.Pattern.compile("^(ELCI\\d{4}): ").matcher(text);
        String code=coded.find()?Diagnostic.quote(coded.group(1)):"null";
        if(!code.equals("null"))text=text.substring(coded.end());
        return "{\"code\":"+code+",\"path\":null,\"line\":null,\"column\":null,\"message\":"+Diagnostic.quote(text)+",\"hints\":[],\"excerpt\":null,\"context\":null}";
    }

    /**
     * Check or build named validators. Every selected group is compiled and compared with its
     * expected ABI before any output is written, so a failure publishes nothing.
     */
    private static int named(String command,Project project,Set<String> selected) throws Exception {
        if(command.equals("eval"))throw new IllegalArgumentException("ELCI4001: eval runs an [entrypoint] function; test validators with elci test");
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
                java.util.function.Function<String,String> files=module->{try{return project.source(module).toString();}catch(RuntimeException x){return null;}};
                throw new ValidatorDiagnostic(where(project,group),e,files,sourceFor(project,group,e));
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

    /**
     * {@code elci test}: run source-level tests (ADR-006) and report each with its budget.
     * Properties run {@code --fuzz} times (100) from {@code --seed} (random, and printed).
     */
    private static int test(boolean json,String... args) throws Exception {
        String directory=null,match=null;
        Long seed=null;Integer runs=null;
        for(int i=0;i<args.length;i++) {
            if(args[i].equals("--match")){if(++i==args.length||match!=null)throw new IllegalArgumentException("ELCI4001: --match requires one substring");match=args[i];}
            else if(args[i].equals("--seed")){if(++i==args.length||seed!=null)throw new IllegalArgumentException("ELCI4001: --seed requires one integer");seed=number(args[i],"--seed");}
            else if(args[i].equals("--fuzz")){if(++i==args.length||runs!=null)throw new IllegalArgumentException("ELCI4001: --fuzz requires one positive number of runs");runs=(int)number(args[i],"--fuzz");if(runs<1)throw new IllegalArgumentException("ELCI4001: --fuzz requires a positive number of runs");}
            else if(args[i].startsWith("--"))throw new IllegalArgumentException("ELCI4001: Unknown option: "+args[i]);
            else if(directory==null)directory=args[i];
            else throw new IllegalArgumentException("ELCI4001: Expected at most one project directory; see --help");
        }
        var project=Project.read(Path.of(directory==null?".":directory));
        var settings=seed==null?org.julclang.testkit.property.PropertyCheck.Settings.defaults():org.julclang.testkit.property.PropertyCheck.Settings.seeded(seed);
        if(runs!=null)settings=settings.withTries(runs);
        java.util.function.Function<String,String> files=module->{try{return project.source(module).toString();}catch(RuntimeException x){return null;}};
        var runner=new org.elci.test.TestRunner(project,settings);
        java.util.List<org.elci.test.TestRunner.Outcome> outcomes;
        try {outcomes=runner.run(match);}
        catch(Diagnostic e){report(e,files,"<tests>",json,null);return 1;}
        int failed=0;
        boolean properties=false;
        for(var outcome:outcomes) {
            properties|=outcome.runs()>0;
            var details=new java.util.ArrayList<String>();
            if(outcome.runs()>0)details.add(outcome.runs()+(outcome.runs()==1?" run":" runs"));
            if(outcome.budget()!=null)details.add((outcome.runs()>0&&outcome.passed()?"max cpu ":"cpu ")+outcome.budget().cpuSteps()+", mem "+outcome.budget().memoryUnits());
            System.out.println((outcome.passed()?"PASS ":"FAIL ")+outcome.name()+(details.isEmpty()?"":" ("+String.join(", ",details)+")"));
            if(!outcome.passed()) {
                failed++;
                for(String line:outcome.detail().split("\n"))System.out.println("    "+line);
                for(String trace:outcome.traces())System.out.println("    trace: "+trace);
            }
        }
        if(outcomes.isEmpty()) {
            System.out.println(match==null?"No tests found in "+String.join(", ",project.testDirectories()):"No tests match '"+match+"'");
            return 1;
        }
        System.out.println((outcomes.size()-failed)+" passed, "+failed+" failed"+(properties?" (seed "+runner.seed()+")":""));
        return failed==0?0:1;
    }

    private static long number(String text,String option) {
        try {return Long.parseLong(text);}
        catch(NumberFormatException e){throw new IllegalArgumentException("ELCI4001: "+option+" expects an integer, not '"+text+"'");}
    }

    /**
     * {@code elci eval}: run the entrypoint on Julc's VM and print its value in Elci syntax, the
     * budget and any traces; {@code --builtins} also lists every builtin call.
     */
    private static int evaluate(ElciCompiler.Result result,boolean builtins) {
        var evaluation=JulcVm.create().evaluate(result.program(),result.target().ledgerTarget());
        if(evaluation instanceof EvalResult.Success success)System.out.println(org.elci.driver.ElciValues.show(success.resultTerm(),result.pirType(),result.namedTypes(),result.type()));
        else System.out.println("evaluation failed: "+(evaluation instanceof EvalResult.Failure failure?failure.error():"budget exhausted"));
        var budget=evaluation.budgetConsumed();
        System.out.println("cpu "+budget.cpuSteps()+", mem "+budget.memoryUnits());
        for(String trace:evaluation.traces())System.out.println("trace: "+trace);
        if(builtins)for(var call:evaluation.builtinTrace())System.out.println("builtin: "+call);
        return evaluation instanceof EvalResult.Success?0:1;
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
