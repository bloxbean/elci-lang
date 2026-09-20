package org.elci.cli;

import org.elci.driver.ElciCompiler;
import org.elci.syntax.Diagnostic;
import org.julclang.compiler.CompilerOptions;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.vm.*;
import java.nio.file.*;
import java.util.*;

public final class Main {
    public static void main(String[] args) { System.exit(run(args)); }
    public static int run(String...args) {
        String source="<project>";
        try {
            if(args.length==0||args[0].equals("--help")||args[0].equals("help")) {
                System.out.println("Elci "+org.elci.driver.BuildInfo.VERSION+" — Elm-syntax compiler powered by Julc\nUsage: elci <check|build|eval> [project-directory]\n       elci init <project-directory> [--purpose spend|mint|function]\n       elci --version\nBuild writes build/validator.flat, validator.plutus, validator.plutus.json (Julc format) and abi.json (Elci schema).");return 0;
            }
            if(args[0].equals("--version")){System.out.println("elci "+org.elci.driver.BuildInfo.VERSION+" / julc "+ElciCompiler.JULC_VERSION);return 0;}
            if(args[0].equals("init"))return ProjectInitializer.run(Arrays.copyOfRange(args,1,args.length));
            if(args.length>2||!Set.of("check","build","eval").contains(args[0]))throw new IllegalArgumentException("Expected init, check, build or eval; see --help");
            var project=Project.read(Path.of(args.length==2?args[1]:"."));source=project.source().toString();
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
}
