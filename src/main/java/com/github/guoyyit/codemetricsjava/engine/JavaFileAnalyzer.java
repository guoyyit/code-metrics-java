package com.github.guoyyit.codemetricsjava.engine;

import com.github.guoyyit.codemetricsjava.model.FileAnalysisResult;
import com.github.guoyyit.codemetricsjava.model.FileMetrics;
import com.github.guoyyit.codemetricsjava.model.MethodMetrics;
import com.github.guoyyit.codemetricsjava.model.TypeMetrics;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 基于 JavaParser 的单文件解析器：计算方法级与类型级度量，不做坏味道判定。
 */
@Component
public class JavaFileAnalyzer {

    private static final ParserConfiguration PARSER_CONFIGURATION =
            new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);

    private static final Set<String> JDK_JAVA_LANG_TYPES = Set.of(
            "Appendable", "AutoCloseable", "CharSequence", "Cloneable", "Comparable",
            "Iterable", "Readable", "Runnable", "Thread", "System", "Class", "ClassLoader",
            "Compiler", "InheritableThreadLocal", "Module", "ModuleLayer", "Package",
            "Process", "ProcessBuilder", "Runtime", "RuntimePermission", "SecurityManager",
            "StackTraceElement", "StackWalker", "StrictMath", "String", "StringBuffer",
            "StringBuilder", "ThreadGroup", "ThreadLocal", "Throwable", "Void",
            "Character", "Byte", "Short", "Integer", "Long", "Float", "Double", "Boolean",
            "Number", "Math", "Enum", "Record", "Object", "Exception", "RuntimeException",
            "Error", "IllegalArgumentException", "IllegalStateException",
            "NullPointerException", "IndexOutOfBoundsException", "UnsupportedOperationException",
            "StringIndexOutOfBoundsException", "ArithmeticException", "ClassCastException",
            "ArrayStoreException", "NegativeArraySizeException", "SecurityException",
            "ReflectiveOperationException", "ClassNotFoundException", "InstantiationException",
            "InterruptedException", "NoSuchFieldException", "NoSuchMethodException");

    private static final Set<String> JDK_OTHER_TYPES = Set.of(
            "List", "Map", "Set", "Collection", "ArrayList", "LinkedList", "HashMap",
            "LinkedHashMap", "TreeMap", "HashSet", "LinkedHashSet", "TreeSet",
            "Optional", "OptionalDouble", "OptionalInt", "OptionalLong",
            "Stream", "IntStream", "LongStream", "DoubleStream", "Collectors",
            "Path", "Paths", "Files", "URI", "URL", "File", "IOException",
            "UncheckedIOException", "InputStream", "OutputStream", "Reader", "Writer",
            "BufferedReader", "BufferedWriter", "PrintStream", "PrintWriter",
            "Charset", "StandardCharsets", "Instant", "LocalDate", "LocalDateTime",
            "LocalTime", "ZonedDateTime", "Duration", "Period", "ZoneId",
            "DateTimeFormatter", "Date", "Calendar", "UUID", "BigDecimal", "BigInteger",
            "Pattern", "Matcher", "Predicate", "Function", "Consumer", "Supplier",
            "BiFunction", "BiPredicate", "BiConsumer", "Callable", "Future",
            "CompletableFuture", "Executor", "ExecutorService", "Executors",
            "ThreadFactory", "TimeoutException", "ReentrantLock", "Lock",
            "Comparator", "Iterator", "ListIterator", "Spliterator",
            "StringJoiner", "Objects", "Arrays", "Collections", "Random",
            "ConcurrentHashMap", "ConcurrentMap", "AtomicInteger", "AtomicLong",
            "AtomicReference", "Deque", "ArrayDeque", "Queue", "PriorityQueue",
            "FileVisitResult", "FileVisitor", "SimpleFileVisitor", "BasicFileAttributes",
            "StandardCopyOption", "StandardOpenOption", "MessageDigest",
            "TypeReference", "ObjectMapper");

    public FileAnalysisResult analyze(SourceFile sourceFile) {
        try {
            com.github.javaparser.ParseResult<CompilationUnit> parsed =
                    new com.github.javaparser.JavaParser(PARSER_CONFIGURATION)
                            .parse(sourceFile.content);
            if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
                return FileAnalysisResult.error(sourceFile.path, sourceFile.sha256,
                        "PARSE_ERROR: " + parsed.getProblems());
            }
            CompilationUnit cu = parsed.getResult().get();
            FileAnalysisResult result = new FileAnalysisResult();
            result.path = sourceFile.path;
            result.sha256 = sourceFile.sha256;
            result.status = com.github.guoyyit.codemetricsjava.model.AnalysisStatus.OK;

            String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString())
                    .orElse("");
            java.util.Map<String, String> importsBySimpleName = new java.util.HashMap<>();
            for (ImportDeclaration importDeclaration : cu.getImports()) {
                if (!importDeclaration.isAsterisk() && !importDeclaration.isStatic()) {
                    String qualified = importDeclaration.getNameAsString();
                    importsBySimpleName.put(
                            qualified.substring(qualified.lastIndexOf('.') + 1), qualified);
                }
            }

            List<TypeMetrics> typeMetricsList = new ArrayList<>();
            List<MethodMetrics> methodMetricsList = new ArrayList<>();
            for (TypeDeclaration<?> root : cu.getTypes()) {
                for (TypeDeclaration<?> type : collectTypes(root)) {
                    TypeMetrics typeMetrics = analyzeType(type, packageName, importsBySimpleName);
                    typeMetricsList.add(typeMetrics);
                    for (MethodMetrics methodMetrics : analyzeMethods(type,
                            typeMetrics.qualifiedName, sourceFile.content)) {
                        methodMetricsList.add(methodMetrics);
                        typeMetrics.wmc += methodMetrics.complexity;
                    }
                }
            }
            result.types = typeMetricsList;
            result.methods = methodMetricsList;
            result.metrics = aggregate(sourceFile.path, sourceFile.content, typeMetricsList,
                    methodMetricsList);
            return result;
        } catch (RuntimeException e) {
            return FileAnalysisResult.error(sourceFile.path, sourceFile.sha256,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private TypeMetrics analyzeType(TypeDeclaration<?> type, String packageName,
                                    java.util.Map<String, String> importsBySimpleName) {
        TypeMetrics metrics = new TypeMetrics();
        metrics.kind = typeKind(type);
        metrics.name = nestedSimpleName(type);
        metrics.qualifiedName = type.getFullyQualifiedName()
                .orElse(packageName.isEmpty()
                        ? nestedDottedName(type)
                        : packageName + "." + nestedDottedName(type));
        metrics.beginLine = type.getBegin().map(p -> p.line).orElse(1);
        metrics.endLine = type.getEnd().map(p -> p.line).orElse(metrics.beginLine);
        metrics.loc = metrics.endLine - metrics.beginLine + 1;
        metrics.sloc = 0;

        Set<String> dependencies = new HashSet<>();

        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof FieldDeclaration field) {
                metrics.fieldCount += field.getVariables().size();
                field.getVariables().forEach(variable -> variable.getType()
                        .ifClassOrInterfaceType(classType -> addReferencedTypes(classType, dependencies)));
            } else if (member instanceof MethodDeclaration method) {
                metrics.methodCount++;
                method.getType().ifClassOrInterfaceType(classType -> addReferencedTypes(classType, dependencies));
                method.getParameters().forEach(parameter -> parameter.getType()
                        .ifClassOrInterfaceType(classType -> addReferencedTypes(classType, dependencies)));
                method.getThrownExceptions()
                        .forEach(thrown -> thrown.ifClassOrInterfaceType(
                                classType -> addReferencedTypes(classType, dependencies)));
                method.findAll(ClassOrInterfaceType.class)
                        .forEach(classType -> addReferencedTypes(classType, dependencies));
            } else if (member instanceof ConstructorDeclaration constructor) {
                metrics.constructorCount++;
                constructor.getParameters().forEach(parameter -> parameter.getType()
                        .ifClassOrInterfaceType(classType -> addReferencedTypes(classType, dependencies)));
                constructor.findAll(ClassOrInterfaceType.class)
                        .forEach(classType -> addReferencedTypes(classType, dependencies));
            }
        }

        if (type instanceof ClassOrInterfaceDeclaration classOrInterface) {
            metrics.dit = classOrInterface.getExtendedTypes().size();
            classOrInterface.getExtendedTypes()
                    .forEach(extended -> addReferencedTypes(extended, dependencies));
            for (ClassOrInterfaceType implemented : classOrInterface.getImplementedTypes()) {
                addReferencedTypes(implemented, dependencies);
            }
        }
        Set<String> responseSet = new HashSet<>();
        for (MethodDeclaration method : type.getMethods()) {
            responseSet.add(method.getNameAsString());
            method.findAll(MethodCallExpr.class)
                    .forEach(call -> responseSet.add(call.getNameAsString()));
            method.findAll(ObjectCreationExpr.class)
                    .forEach(creation -> addReferencedTypes(creation.getType(), dependencies));
        }
        for (ConstructorDeclaration constructor : type.getConstructors()) {
            responseSet.add(constructor.getNameAsString());
            constructor.findAll(MethodCallExpr.class)
                    .forEach(call -> responseSet.add(call.getNameAsString()));
            constructor.findAll(ObjectCreationExpr.class)
                    .forEach(creation -> addReferencedTypes(creation.getType(), dependencies));
        }
        metrics.rfc = responseSet.size();
        dependencies.remove(type.getNameAsString());
        metrics.fanOut = (int) dependencies.stream()
                .filter(name -> !isJdkType(name, importsBySimpleName))
                .count();

        return metrics;
    }

    private List<TypeDeclaration<?>> collectTypes(TypeDeclaration<?> root) {
        List<TypeDeclaration<?>> all = new ArrayList<>();
        all.add(root);
        root.getMembers().forEach(member -> {
            if (member instanceof TypeDeclaration<?> nested) {
                all.addAll(collectTypes(nested));
            }
        });
        return all;
    }

    private String nestedDottedName(TypeDeclaration<?> type) {
        if (type.getParentNode().isPresent()
                && type.getParentNode().get() instanceof TypeDeclaration<?> parent) {
            return nestedDottedName(parent) + "." + type.getNameAsString();
        }
        return type.getNameAsString();
    }

    private String nestedSimpleName(TypeDeclaration<?> type) {
        if (type.getParentNode().isPresent()
                && type.getParentNode().get() instanceof TypeDeclaration<?> parent) {
            return nestedSimpleName(parent) + "." + type.getNameAsString();
        }
        return type.getNameAsString();
    }

    private void addReferencedTypes(ClassOrInterfaceType referenced, Set<String> dependencies) {
        String name = referenced.getNameAsString();
        if (name != null && !name.isBlank()) {
            dependencies.add(name);
        }
        if (referenced.getTypeArguments().isPresent()) {
            for (com.github.javaparser.ast.type.Type argument
                    : referenced.getTypeArguments().get()) {
                if (argument.isClassOrInterfaceType()) {
                    addReferencedTypes(argument.asClassOrInterfaceType(), dependencies);
                }
            }
        }
    }

    private boolean isJdkType(String simpleName,
                             java.util.Map<String, String> importsBySimpleName) {
        String qualified = importsBySimpleName.get(simpleName);
        if (qualified != null) {
            return qualified.startsWith("java.") || qualified.startsWith("javax.")
                    || qualified.startsWith("jdk.") || qualified.startsWith("sun.");
        }
        return JDK_JAVA_LANG_TYPES.contains(simpleName)
                || JDK_OTHER_TYPES.contains(simpleName);
    }

    private String typeKind(TypeDeclaration<?> type) {
        if (type.isClassOrInterfaceDeclaration()) {
            return type.asClassOrInterfaceDeclaration().isInterface() ? "interface" : "class";
        }
        if (type.isEnumDeclaration()) {
            return "enum";
        }
        if (type.isAnnotationDeclaration()) {
            return "@interface";
        }
        if (type.isRecordDeclaration()) {
            return "record";
        }
        return "type";
    }

    private List<MethodMetrics> analyzeMethods(TypeDeclaration<?> type, String typeQualifiedName,
                                               String source) {
        List<MethodMetrics> methods = new ArrayList<>();
        List<CallableDeclaration<?>> callables = new ArrayList<>();
        callables.addAll(type.getMethods());
        callables.addAll(type.getConstructors());
        for (CallableDeclaration<?> callable : callables) {
            boolean hasBody = callable instanceof MethodDeclaration method
                    ? method.getBody().isPresent()
                    : ((ConstructorDeclaration) callable).getBody() != null;
            if (!hasBody) {
                continue;
            }
            MethodMetrics metrics = new MethodMetrics();
            metrics.name = callable.getNameAsString();
            metrics.signature = callable.getSignature().asString();
            metrics.qualifiedName = typeQualifiedName + "#" + metrics.signature;
            metrics.beginLine = callable.getBegin().map(p -> p.line).orElse(1);
            metrics.endLine = callable.getEnd().map(p -> p.line).orElse(metrics.beginLine);
            metrics.loc = metrics.endLine - metrics.beginLine + 1;
            metrics.parameterCount = callable.getParameters().size();
            metrics.complexity = cyclomaticComplexity(callable) + 1;
            methods.add(metrics);
        }
        return methods;
    }

    private int cyclomaticComplexity(CallableDeclaration<?> callable) {
        int[] count = {0};
        VoidVisitorAdapter<Void> visitor = new VoidVisitorAdapter<>() {
            @Override
            public void visit(IfStmt n, Void arg) {
                count[0]++;
                super.visit(n, arg);
            }

            @Override
            public void visit(ForStmt n, Void arg) {
                count[0]++;
                super.visit(n, arg);
            }

            @Override
            public void visit(ForEachStmt n, Void arg) {
                count[0]++;
                super.visit(n, arg);
            }

            @Override
            public void visit(WhileStmt n, Void arg) {
                count[0]++;
                super.visit(n, arg);
            }

            @Override
            public void visit(DoStmt n, Void arg) {
                count[0]++;
                super.visit(n, arg);
            }

            @Override
            public void visit(CatchClause n, Void arg) {
                count[0]++;
                super.visit(n, arg);
            }

            @Override
            public void visit(ConditionalExpr n, Void arg) {
                count[0]++;
                super.visit(n, arg);
            }

            @Override
            public void visit(SwitchExpr n, Void arg) {
                n.getEntries().stream().filter(entry -> entry.getLabels().isNonEmpty())
                        .forEach(entry -> count[0]++);
                super.visit(n, arg);
            }

            @Override
            public void visit(SwitchEntry n, Void arg) {
                if (n.getLabels().isNonEmpty()) {
                    count[0]++;
                }
                super.visit(n, arg);
            }

            @Override
            public void visit(LambdaExpr n, Void arg) {
                // lambda 形成独立控制流，其内部判定点不计入外层方法复杂度
            }

            @Override
            public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                // 嵌套类型有自己的方法，复杂度不并入外层方法
            }

            @Override
            public void visit(com.github.javaparser.ast.body.EnumDeclaration n, Void arg) {
            }
        };
        callable.accept(visitor, null);
        return count[0];
    }

    private FileMetrics aggregate(String path, String source, List<TypeMetrics> types,
                                  List<MethodMetrics> methods) {
        FileMetrics metrics = new FileMetrics();
        metrics.path = path;
        String[] lines = source.split("\n", -1);
        metrics.loc = lines.length;
        int sloc = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("//") && !trimmed.startsWith("*")
                    && !trimmed.startsWith("/*")) {
                sloc++;
            }
        }
        metrics.sloc = sloc;
        metrics.typeCount = types.size();
        metrics.methodCount = methods.size();
        metrics.totalComplexity = methods.stream().mapToInt(method -> method.complexity).sum();
        metrics.maxMethodComplexity = methods.stream()
                .mapToInt(method -> method.complexity).max().orElse(0);
        metrics.avgMethodComplexity = methods.isEmpty() ? 0.0
                : Math.round(metrics.totalComplexity * 100.0 / methods.size()) / 100.0;
        metrics.totalFanOut = types.stream().mapToInt(type -> type.fanOut).sum();
        metrics.maxFanOut = types.stream().mapToInt(type -> type.fanOut).max().orElse(0);
        for (TypeMetrics type : types) {
            type.sloc = countSourceLines(source, type.beginLine, type.endLine);
        }
        for (MethodMetrics method : methods) {
            method.sloc = countSourceLines(source, method.beginLine, method.endLine);
        }
        return metrics;
    }

    private int countSourceLines(String source, int beginLine, int endLine) {
        String[] lines = source.split("\n", -1);
        int count = 0;
        for (int index = Math.max(0, beginLine - 1);
             index < Math.min(lines.length, endLine); index++) {
            String trimmed = lines[index].trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("//") && !trimmed.startsWith("*")
                    && !trimmed.startsWith("/*")) {
                count++;
            }
        }
        return count;
    }
}
