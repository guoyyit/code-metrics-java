package com.github.guoyyit.codemetricsjava.engine;

import org.springframework.util.AntPathMatcher;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 扫描根目录下的 Java 源文件，计算 sha256 并读取内容。
 */
@Component
public class SourceScanner {

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "target", "build", ".idea", ".gradle", "node_modules");

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public List<SourceFile> scan(Path root, List<String> includes, List<String> excludes) {
        List<SourceFile> files = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && SKIP_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!file.toString().endsWith(".java")) {
                        return FileVisitResult.CONTINUE;
                    }
                    String relativePath = toPosix(root.relativize(file).toString());
                    if (scopeMatches(relativePath, includes, excludes)) {
                        files.add(toSourceFile(root, file, relativePath));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        files.sort((left, right) -> left.path.compareTo(right.path));
        return files;
    }

    private boolean scopeMatches(String relativePath, List<String> includes, List<String> excludes) {
        boolean included = includes == null || includes.isEmpty()
                || includes.stream().anyMatch(pattern -> pathMatcher.match(pattern, relativePath));
        if (!included) {
            return false;
        }
        return excludes == null
                || excludes.stream().noneMatch(pattern -> pathMatcher.match(pattern, relativePath));
    }

    private SourceFile toSourceFile(Path root, Path file, String relativePath) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            String sha256 = sha256(bytes);
            String content = new String(bytes, StandardCharsets.UTF_8);
            return new SourceFile(relativePath, file.toAbsolutePath().toString(),
                    bytes.length, sha256, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String toPosix(String path) {
        return path.replace('\\', '/');
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
