package com.github.guoyyit.codemetricsjava.tests;

import com.github.guoyyit.codemetricsjava.engine.JavaFileAnalyzer;
import com.github.guoyyit.codemetricsjava.engine.SourceFile;
import com.github.guoyyit.codemetricsjava.model.AnalysisStatus;
import com.github.guoyyit.codemetricsjava.model.FileAnalysisResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaFileAnalyzerTest {

    private final JavaFileAnalyzer analyzer = new JavaFileAnalyzer();

    private SourceFile source(String path, String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder builder = new StringBuilder();
        for (byte value : hash) {
            builder.append(String.format("%02x", value));
        }
        return new SourceFile(path, "/tmp/" + path, bytes.length, builder.toString(), content);
    }

    @Test
    void measuresMethodAndTypeMetrics() throws Exception {
        String content = "package demo;\n"
                + "public class Sample {\n"
                + "    private int value;\n"
                + "    public int calc(int a, int b, int c) {\n"
                + "        if (a > b) {\n"
                + "            return a + c;\n"
                + "        }\n"
                + "        for (int i = 0; i < c; i++) {\n"
                + "            value += i;\n"
                + "        }\n"
                + "        return b;\n"
                + "    }\n"
                + "}\n";
        FileAnalysisResult result = analyzer.analyze(
                source("src/main/java/demo/Sample.java", content));

        assertEquals(AnalysisStatus.OK, result.status);
        assertEquals(1, result.types.size());
        assertEquals("demo.Sample", result.types.get(0).qualifiedName);
        assertEquals(1, result.methods.size());
        assertEquals(3, result.methods.get(0).parameterCount);
        // 基础 1 + if 1 + for 1 = 3
        assertEquals(3, result.methods.get(0).complexity);
        // 外层类不含嵌套/lambda 的判定点
        assertEquals(3, result.types.get(0).wmc);
        assertTrue(result.metrics.loc >= 12);
    }

    @Test
    void lambdaBranchesDoNotInflateEnclosingComplexity() throws Exception {
        String content = "package demo;\n"
                + "import java.util.List;\n"
                + "public class LambdaSample {\n"
                + "    public int sum(List<Integer> values) {\n"
                + "        return values.stream().filter(v -> {\n"
                + "            if (v > 0) return true;\n"
                + "            return false;\n"
                + "        }).mapToInt(v -> v).sum();\n"
                + "    }\n"
                + "}\n";
        FileAnalysisResult result = analyzer.analyze(
                source("src/main/java/demo/LambdaSample.java", content));

        assertEquals(AnalysisStatus.OK, result.status);
        // 外层方法只有基础复杂度 1，lambda 内的 if 不计入
        assertEquals(1, result.methods.get(0).complexity);
    }

    @Test
    void parseFailureIsReportedAsDistinctError() throws Exception {
        String broken = "package demo;\npublic class Broken {\n    public void x( {\n}\n";
        FileAnalysisResult result = analyzer.analyze(
                source("src/main/java/demo/Broken.java", broken));

        assertEquals(AnalysisStatus.PARSE_ERROR, result.status);
        assertTrue(result.errorMessage.startsWith("PARSE_ERROR"));
        assertTrue(result.methods.isEmpty());
    }

    @Test
    void nestedTypesAreMeasuredIndependently() throws Exception {
        String content = "package demo;\n"
                + "public class Outer {\n"
                + "    public int simple() {\n"
                + "        return 1;\n"
                + "    }\n"
                + "    public static class Inner {\n"
                + "        public int branchy(int a) {\n"
                + "            if (a > 0) return 1;\n"
                + "            return 0;\n"
                + "        }\n"
                + "    }\n"
                + "}\n";
        FileAnalysisResult result = analyzer.analyze(
                source("src/main/java/demo/Outer.java", content));

        assertEquals(AnalysisStatus.OK, result.status);
        assertEquals(2, result.types.size());
        assertEquals("demo.Outer.Inner", result.types.get(1).qualifiedName);
        assertEquals(1, result.methods.get(0).complexity);
        assertEquals(2, result.methods.get(1).complexity);
    }

    @Test
    void fanOutCountsDistinctUserTypesOnly() throws Exception {
        String content = "package demo;\n"
                + "import java.util.List;\n"
                + "import demo.other.Widget;\n"
                + "public class Uses {\n"
                + "    private Widget widget;\n"
                + "    private List<Widget> widgets;\n"
                + "    public Gadget make(Gadget gadget) {\n"
                + "        return new Gadget(widget);\n"
                + "    }\n"
                + "}\n";
        FileAnalysisResult result = analyzer.analyze(
                source("src/main/java/demo/Uses.java", content));

        // Widget 与 Gadget 计为两个不同用户类型；List 为 JDK 类型不计入，重复 Widget 去重
        assertEquals(2, result.types.get(0).fanOut);
    }
}
