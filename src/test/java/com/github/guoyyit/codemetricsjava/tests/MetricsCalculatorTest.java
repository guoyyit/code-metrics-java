package com.github.guoyyit.codemetricsjava.tests;

import com.github.guoyyit.codemetricsjava.metrics.MetricsCalculator;
import com.github.guoyyit.codemetricsjava.model.FileMetrics;
import com.github.guoyyit.codemetricsjava.model.MethodMetrics;
import com.github.guoyyit.codemetricsjava.model.SourceFile;
import com.github.guoyyit.codemetricsjava.model.TypeMetrics;
import com.github.guoyyit.codemetricsjava.parser.JavaSourceParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsCalculatorTest {

    private final JavaSourceParser parser = new JavaSourceParser();
    private final MetricsCalculator calculator = new MetricsCalculator();

    @Test
    void computesMethodAndTypeMetrics() {
        String source = """
                package com.example;
                import java.util.ArrayList;
                import java.util.List;
                public class Demo {
                    public int score(int a, int b, int c) {
                        List<Integer> values = new ArrayList<>();
                        if (a > 0 && b > 0) {
                            return a + b + c;
                        }
                        if (c > 0) {
                            return c;
                        }
                        return 0;
                    }
                }
                """;
        FileMetrics metrics = parse("com/example/Demo.java", source);

        assertEquals(1, metrics.types().size());
        TypeMetrics type = metrics.types().get(0);
        assertEquals("com.example.Demo", type.qualifiedName());
        assertEquals("CLASS", type.kind());
        assertEquals(1, type.methodCount());

        MethodMetrics method = type.methods().get(0);
        assertEquals(3, method.parameterCount());
        assertEquals(4, method.cyclomaticComplexity(),
                "1 base + two if branches + one && operator = 4");
        assertTrue(method.maxNestingDepth() >= 1);
        assertTrue(method.linesOfCode() >= 8);
        assertEquals(type.weightedMethodComplexity(),
                type.methods().get(0).cyclomaticComplexity());
        assertTrue(type.fanOut() >= 1, "List and ArrayList are referenced types");
        assertTrue(metrics.summary().sourceLinesOfCode() >= 10);
    }

    @Test
    void countsNestingDepth() {
        String source = """
                package com.example;
                public class Nest {
                    public void deep() {
                        if (true) {
                            for (int i = 0; i < 1; i++) {
                                while (true) {
                                    System.out.println(i);
                                }
                            }
                        }
                    }
                }
                """;
        FileMetrics metrics = parse("com/example/Nest.java", source);
        MethodMetrics method = metrics.types().get(0).methods().get(0);
        assertEquals(3, method.maxNestingDepth());
    }

    @Test
    void isDeterministicAcrossRuns() {
        String source = "package x; public class A { int v(int a){return a;} }";
        FileMetrics first = parse("A.java", source);
        FileMetrics second = parse("A.java", source);
        assertEquals(first, second);
    }

    private FileMetrics parse(String path, String source) {
        SourceFile file = new SourceFile(path, "hash", source.length(), source);
        JavaSourceParser.ParsedFile parsed = parser.parse(file);
        assertTrue(parsed instanceof JavaSourceParser.ParsedFile.Success,
                "fixture must parse");
        assertNotNull(parsed);
        return calculator.calculate(file,
                ((JavaSourceParser.ParsedFile.Success) parsed).unit());
    }
}
