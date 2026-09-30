package com.github.guoyyit.codemetricsjava.metrics;

import com.github.guoyyit.codemetricsjava.model.FileMetrics;
import com.github.guoyyit.codemetricsjava.model.MethodMetrics;
import com.github.guoyyit.codemetricsjava.model.SourceFile;
import com.github.guoyyit.codemetricsjava.model.SourceLocation;
import com.github.guoyyit.codemetricsjava.model.TypeMetrics;
import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes structural metrics from a parsed compilation unit.
 * Exact definitions are documented in docs/metrics.md.
 */
@Component
public class MetricsCalculator {

    private static final Set<String> JAVA_LANG_TYPES = Set.of(
            "String", "Integer", "Long", "Double", "Float", "Boolean", "Character",
            "Byte", "Short", "Object", "Number", "CharSequence", "Comparable",
            "Iterable", "Runnable", "Throwable", "Exception", "RuntimeException",
            "Error", "Class", "System", "Math", "Void", "Enum", "Record",
            "Override", "Deprecated", "SuppressWarnings", "FunctionalInterface",
            "StringBuilder", "StringBuffer", "AutoCloseable");

    public FileMetrics calculate(SourceFile source, CompilationUnit unit) {
        String packageName = unit.getPackageDeclaration()
                .map(pd -> pd.getNameAsString()).orElse("");
        Map<String, String> imports = collectImports(unit);
        List<TypeMetrics> types = new ArrayList<>();
        for (TypeDeclaration<?> root : unit.getTypes()) {
            collectType(root, packageName, source.path(), imports, "", types);
        }
        types.sort((a, b) -> Integer.compare(a.location().startLine(), b.location().startLine()));
        return new FileMetrics(source.path(), source.contentHash(), packageName,
                types, summarize(source.content(), types));
    }

    private void collectType(TypeDeclaration<?> type, String packageName, String path,
                             Map<String, String> imports, String enclosingName,
                             List<TypeMetrics> output) {
        String qualifiedName = enclosingName.isEmpty()
                ? qualify(packageName, type.getNameAsString())
                : enclosingName + "." + type.getNameAsString();
        output.add(calculateType(type, qualifiedName, packageName, path, imports));
        for (BodyMember member : BodyMember.of(type)) {
            collectType(member.declaration(), packageName, path, imports,
                    qualifiedName, output);
        }
    }

    private TypeMetrics calculateType(TypeDeclaration<?> type, String qualifiedName,
                                      String packageName, String path,
                                      Map<String, String> imports) {
        List<MethodMetrics> methods = new ArrayList<>();
        int complexitySum = 0;
        for (MethodDeclaration method : type.getMethods()) {
            MethodMetrics metrics = methodMetrics(method, path, qualifiedName);
            methods.add(metrics);
            complexitySum += metrics.cyclomaticComplexity();
        }
        for (ConstructorDeclaration constructor : type.getConstructors()) {
            MethodMetrics metrics = constructorMetrics(constructor, path, qualifiedName);
            methods.add(metrics);
            complexitySum += metrics.cyclomaticComplexity();
        }
        methods.sort((a, b) -> {
            int byLine = Integer.compare(a.location().startLine(), b.location().startLine());
            return byLine != 0 ? byLine : a.id().compareTo(b.id());
        });
        int fieldCount = type.getFields().stream()
                .mapToInt(field -> field.getVariables().size()).sum();
        int start = type.getBegin().map(p -> p.line).orElse(1);
        int end = type.getEnd().map(p -> p.line).orElse(start);
        return new TypeMetrics(qualifiedName, typeKind(type),
                new SourceLocation(path, qualifiedName, null, start, end),
                end - start + 1, methods.size(), fieldCount, complexitySum,
                fanOut(type, qualifiedName, packageName, imports), methods);
    }

    private MethodMetrics methodMetrics(MethodDeclaration method, String path, String typeName) {
        int start = method.getBegin().map(p -> p.line).orElse(1);
        int end = method.getEnd().map(p -> p.line).orElse(start);
        String signature = method.getSignature().asString();
        SourceLocation location = new SourceLocation(path, typeName, signature, start, end);
        return new MethodMetrics(typeName + "#" + signature, method.getNameAsString(),
                location, end - start + 1, StatementCounter.count(method),
                ComplexityVisitor.of(method), NestingVisitor.of(method),
                method.getParameters().size());
    }

    private MethodMetrics constructorMetrics(ConstructorDeclaration constructor,
                                             String path, String typeName) {
        int start = constructor.getBegin().map(p -> p.line).orElse(1);
        int end = constructor.getEnd().map(p -> p.line).orElse(start);
        String signature = constructor.getSignature().asString();
        SourceLocation location = new SourceLocation(path, typeName, signature, start, end);
        String simpleName = typeName.substring(typeName.lastIndexOf('.') + 1);
        return new MethodMetrics(typeName + "#" + signature, simpleName, location,
                end - start + 1, StatementCounter.count(constructor),
                ComplexityVisitor.of(constructor), NestingVisitor.of(constructor),
                constructor.getParameters().size());
    }

    private Map<String, String> collectImports(CompilationUnit unit) {
        Map<String, String> simpleToFq = new LinkedHashMap<>();
        unit.getImports().forEach(imp -> {
            String name = imp.getNameAsString();
            if (!imp.isAsterisk()) {
                simpleToFq.put(name.substring(name.lastIndexOf('.') + 1), name);
            }
        });
        return simpleToFq;
    }

    private int fanOut(TypeDeclaration<?> type, String selfQualifiedName,
                       String packageName, Map<String, String> imports) {
        Set<String> referenced = new LinkedHashSet<>();
        Set<String> ownTypes = new LinkedHashSet<>();
        type.walk(TypeDeclaration.class,
                t -> ownTypes.add(t.getNameAsString()));
        type.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(ClassOrInterfaceType node, Void arg) {
                addReference(node.getNameWithScope());
                super.visit(node, arg);
            }

            @Override
            public void visit(ObjectCreationExpr node, Void arg) {
                addReference(node.getType().getNameWithScope());
                super.visit(node, arg);
            }

            @Override
            public void visit(InstanceOfExpr node, Void arg) {
                if (node.getType().isClassOrInterfaceType()) {
                    addReference(node.getType().asClassOrInterfaceType().getNameWithScope());
                }
                super.visit(node, arg);
            }

            private void addReference(String rawName) {
                String name = rawName;
                int generics = name.indexOf('<');
                if (generics >= 0) {
                    name = name.substring(0, generics);
                }
                String resolved = resolveReference(name, packageName, imports);
                if (resolved != null && !resolved.equals(selfQualifiedName)
                        && !ownTypes.contains(resolved)) {
                    referenced.add(resolved);
                }
            }
        }, null);
        return referenced.size();
    }

    private String resolveReference(String name, String packageName,
                                    Map<String, String> imports) {
        if (name.isBlank()) {
            return null;
        }
        String simple = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        if (JAVA_LANG_TYPES.contains(simple) || !Character.isUpperCase(simple.charAt(0))) {
            return null;
        }
        if (name.contains(".")) {
            return name;
        }
        String imported = imports.get(name);
        return imported != null ? imported : qualify(packageName, name);
    }

    private String qualify(String packageName, String simple) {
        return packageName.isEmpty() ? simple : packageName + "." + simple;
    }

    private String typeKind(TypeDeclaration<?> type) {
        if (type instanceof com.github.javaparser.ast.body.EnumDeclaration) {
            return "ENUM";
        }
        if (type instanceof com.github.javaparser.ast.body.AnnotationDeclaration) {
            return "ANNOTATION";
        }
        if (type instanceof com.github.javaparser.ast.body.RecordDeclaration) {
            return "RECORD";
        }
        if (type instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration classType) {
            if (classType.isInterface()) {
                return "INTERFACE";
            }
            return classType.getModifiers().stream()
                    .anyMatch(modifier -> modifier.getKeyword()
                            == com.github.javaparser.ast.Modifier.Keyword.ABSTRACT)
                    ? "ABSTRACT_CLASS" : "CLASS";
        }
        return "CLASS";
    }

    private FileMetrics.Summary summarize(String content, List<TypeMetrics> types) {
        String[] lines = content.isEmpty() ? new String[0] : content.split("\n", -1);
        int sloc = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("//")
                    && !trimmed.startsWith("/*") && !trimmed.startsWith("*")) {
                sloc++;
            }
        }
        int totalMethods = 0;
        int totalComplexity = 0;
        int maxComplexity = 0;
        int totalParameters = 0;
        int totalFanOut = 0;
        for (TypeMetrics type : types) {
            totalMethods += type.methodCount();
            totalFanOut += type.fanOut();
            for (MethodMetrics method : type.methods()) {
                totalComplexity += method.cyclomaticComplexity();
                totalParameters += method.parameterCount();
                maxComplexity = Math.max(maxComplexity, method.cyclomaticComplexity());
            }
        }
        return new FileMetrics.Summary(lines.length, sloc, totalMethods,
                totalComplexity, maxComplexity, totalParameters, totalFanOut);
    }
}
