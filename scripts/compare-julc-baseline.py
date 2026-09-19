#!/usr/bin/env python3
"""Compare original and extracted Java pipelines without modifying either checkout."""
from pathlib import Path
import subprocess, os
root=Path(__file__).resolve().parents[1]
if 'JULC_WORKTREE' not in os.environ or 'JAVA_HOME' not in os.environ:
    raise SystemExit('Set JULC_WORKTREE and JAVA_HOME before running this developer check.')
julc=Path(os.environ['JULC_WORKTREE'])
java=Path(os.environ['JAVA_HOME'])
work=root/'tmp'/'baseline';work.mkdir(parents=True,exist_ok=True)
cp=(root/'build/runtime-classpath.txt').read_text()
original=subprocess.check_output(['git','show','b93fe4ad:julc-compiler/src/main/java/org/julclang/compiler/JulcCompiler.java'],cwd=julc,text=True)
source=work/'src/org/julclang/compiler/JulcCompiler.java';source.parent.mkdir(parents=True,exist_ok=True);source.write_text(original)
classes=work/'original';classes.mkdir(exist_ok=True)
subprocess.run([str(java/'bin/javac'),'-cp',cp,'-d',str(classes),str(source)],check=True)
runner=work/'Compare.java'
runner.write_text('''
import org.julclang.compiler.*;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.core.flat.UplcFlatEncoder;
import java.security.MessageDigest;
import java.util.HexFormat;
public class Compare {
  public static void main(String[] args) throws Exception {
    String imports="import java.math.BigInteger; import org.julclang.ledger.*; import org.julclang.core.PlutusData; import org.julclang.stdlib.annotation.*; ";
    String[] fixtures={
      "@SpendingValidator public class T { @Entrypoint static boolean validate(BigInteger d, BigInteger r, ScriptContext c) { return r.compareTo(d)>=0; } }",
      "@SpendingValidator public class T { record D(BigInteger a, boolean b) {} @Entrypoint static boolean validate(D d, BigInteger r, ScriptContext c) { return d.b() && r.equals(d.a()); } }",
      "@MintingValidator public class T { @Param static BigInteger minimum; @Entrypoint static boolean validate(BigInteger r, ScriptContext c) { return r.compareTo(minimum)>=0; } }",
      "@SpendingValidator public class T { static BigInteger fact(BigInteger n) { if(n.equals(BigInteger.ZERO)) return BigInteger.ONE; return n.multiply(fact(n.subtract(BigInteger.ONE))); } @Entrypoint static boolean validate(BigInteger d, BigInteger r, ScriptContext c) { return fact(d).equals(r); } }",
      "@MultiValidator public class T { @Entrypoint(purpose=Purpose.MINT) static boolean mint(BigInteger r, ScriptContext c) { return r.signum()>0; } @Entrypoint(purpose=Purpose.SPEND) static boolean spend(BigInteger r, ScriptContext c) { return r.signum()>=0; } }"
    };
    for(var level:new OptimizationLevel[]{OptimizationLevel.NONE,OptimizationLevel.BASELINE,OptimizationLevel.PV11_SAFE}) for(boolean debug:new boolean[]{false,true}) for(int i=0;i<fixtures.length;i++) {
      var options=new CompilerOptions().setOptimizationLevel(level).setSourceMapEnabled(debug);
      var result=new JulcCompiler(StdlibRegistry.defaultRegistry(),options).compileWithDetails(imports+fixtures[i]);
      var bytes=UplcFlatEncoder.encodeProgram(result.program());
      var hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      System.out.println(i+" "+level+" "+debug+" "+hash+" "+result.params()+" "+(result.sourceMap()==null?0:result.sourceMap().size()));
    }
  }
}
''')
subprocess.run([str(java/'bin/javac'),'-cp',cp,'-d',str(work),str(runner)],check=True)
outputs=[]
for name,prefix in [('before',str(classes)+os.pathsep),('after','')]:
    output=subprocess.check_output([str(java/'bin/java'),'-cp',prefix+str(work)+os.pathsep+cp,'Compare'],text=True)
    (work/(name+'.txt')).write_text(output);outputs.append(output)
if outputs[0]!=outputs[1]:
    raise SystemExit('REGRESSION: baseline and extracted compiler artifacts/metadata differ; inspect tmp/baseline')
print('PASS: 30 Java compilations have identical FLAT SHA-256, parameter metadata, and source-map entry counts.')
