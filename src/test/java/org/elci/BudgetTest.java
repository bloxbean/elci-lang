package org.elci;

import org.elci.driver.ElciCompiler;
import org.julclang.compiler.backend.FrontendProgram;
import org.julclang.core.*;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.vm.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

class BudgetTest {
    @Test void traversalMeasurements() throws Exception {
        var csv=new StringBuilder("elements,flat_bytes,cpu,memory\n");long previous=-1;
        for(int n:new int[]{0,10,50,100}) {
            String list=IntStream.rangeClosed(1,n).mapToObj(Integer::toString).collect(Collectors.joining(", "));
            String source="module Budget exposing (main)\nmain : Int\nmain = List.foldl (\\x total -> x + total) 0 ["+list+"]\n";
            var result=new ElciCompiler().compile(source,"main",FrontendProgram.Purpose.FUNCTION);
            var evaluated=assertInstanceOf(EvalResult.Success.class,JulcVm.create().evaluate(result.program(),result.target().ledgerTarget(),new ExBudget(1_000_000_000,10_000_000)));
            assertEquals(new Term.Const(Constant.integer((long)n*(n+1)/2)),evaluated.resultTerm());
            assertTrue(evaluated.consumed().cpuSteps()>previous);previous=evaluated.consumed().cpuSteps();
            csv.append(n).append(',').append(UplcFlatEncoder.encodeProgram(result.program()).length).append(',').append(previous).append(',').append(evaluated.consumed().memoryUnits()).append('\n');
        }
        Path report=Path.of("build/reports/elci/budgets.csv");Files.createDirectories(report.getParent());Files.writeString(report,csv);
    }
    @Test void unusedDivergenceIsNotEliminated() {
        String source="module Divergence exposing (main)\nloop : Int -> Int\nloop x = loop x\nmain : Int\nmain =\n    let\n        divergent = loop 0\n    in\n    7\n";
        var result=new ElciCompiler().compile(source,"main",FrontendProgram.Purpose.FUNCTION);
        assertInstanceOf(EvalResult.BudgetExhausted.class,JulcVm.create().evaluate(result.program(),result.target().ledgerTarget(),new ExBudget(1_000_000,100_000)));
    }
}
