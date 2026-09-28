package org.elci;

import org.elci.driver.ElciCompiler;
import org.elci.driver.ElciCompiler.HandlerSpec;
import org.elci.driver.ElciCompiler.ValidatorSpec;
import org.elci.syntax.Parser;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.backend.DatumProfile;
import org.julclang.compiler.schema.ContractSchema.Purpose;

import java.util.List;
import java.util.Map;

/** One-source named validators for tests: the single handler is {@code module.function}. */
final class Scripts {
    static final Map<String, String> MATH = Map.of("Julc.Math", "org.julclang.stdlib.lib.MathLib");

    private Scripts() {}

    static ElciCompiler.ValidatorBuild validator(String source, String function, Purpose purpose,
                                                 Map<String, String> javaModules) {
        String module = new Parser(source).parse().name();
        return new ElciCompiler().compileValidator(Map.of(module, source),
                new ValidatorSpec("test", List.of(new HandlerSpec(purpose, module, function)), List.of(), DatumProfile.REQUIRED),
                javaModules, new CompilerOptions(), Map.of());
    }

    static ElciCompiler.ValidatorBuild spend(String source, String function) {
        return validator(source, function, Purpose.SPEND, MATH);
    }
}
