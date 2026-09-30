package com.github.guoyyit.codemetricsjava.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Single Jackson configuration used for all durable JSON files.
 */
public final class JsonSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private JsonSupport() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static void writeAtomically(Path target, Object value) {
        try {
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            byte[] bytes = MAPPER.writeValueAsBytes(value);
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static <T> T read(Path source, Class<T> type) {
        try {
            return MAPPER.readValue(Files.readAllBytes(source), type);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
