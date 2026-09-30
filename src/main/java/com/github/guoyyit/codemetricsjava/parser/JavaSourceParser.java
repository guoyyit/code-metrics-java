package com.github.guoyyit.codemetricsjava.parser;

import com.github.guoyyit.codemetricsjava.model.FileFailure;
import com.github.guoyyit.codemetricsjava.model.SourceFile;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import org.springframework.stereotype.Component;

/**
 * Parses a single source file in isolation. A parse failure is reported as
 * a {@link ParsedFile.Error} instead of being thrown, so one bad file never
 * interrupts the overall analysis.
 */
@Component
public class JavaSourceParser {

    public JavaSourceParser() {
    }

    private static JavaParser newParser() {
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        return new JavaParser(configuration);
    }

    /**
     * JavaParser instances keep parser-state during a parse call and are
     * not safe to share across threads; each worker thread gets its own.
     */
    private static final ThreadLocal<JavaParser> PARSER =
            ThreadLocal.withInitial(JavaSourceParser::newParser);

    public ParsedFile parse(SourceFile source) {
        try {
            ParseResult<CompilationUnit> result = PARSER.get().parse(source.content());
            if (result.isSuccessful() && result.getResult().isPresent()) {
                return new ParsedFile.Success(source, result.getResult().get());
            }
            String detail = result.getProblems().stream()
                    .map(this::describe)
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("unknown parse failure");
            return new ParsedFile.Error(source,
                    new FileFailure(source.path(), "PARSE_ERROR", detail));
        } catch (RuntimeException ex) {
            return new ParsedFile.Error(source,
                    new FileFailure(source.path(), "PARSE_EXCEPTION",
                            ex.getClass().getSimpleName() + ": " + ex.getMessage()));
        }
    }

    private String describe(com.github.javaparser.Problem problem) {
        return problem.getLocation()
                .map(tokens -> "[" + tokens.getBegin().getRange()
                        .map(range -> String.valueOf(range.begin.line)).orElse("?") + "] "
                        + problem.getMessage())
                .orElse(problem.getMessage());
    }

    public sealed interface ParsedFile permits ParsedFile.Success, ParsedFile.Error {

        SourceFile source();

        default boolean ok() {
            return this instanceof Success;
        }

        record Success(SourceFile source, CompilationUnit unit) implements ParsedFile {
        }

        record Error(SourceFile source, FileFailure failure) implements ParsedFile {
        }
    }
}
