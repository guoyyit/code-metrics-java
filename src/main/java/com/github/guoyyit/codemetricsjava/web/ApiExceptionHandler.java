package com.github.guoyyit.codemetricsjava.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Map;

/**
 * Translates expected failure modes into stable HTTP status codes.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(body("BAD_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body("ILLEGAL_STATE", ex.getMessage()));
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<Map<String, Object>> storage(UncheckedIOException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("STORAGE_ERROR", ex.getMessage()));
    }

    private Map<String, Object> body(String error, String message) {
        return Map.of(
                "error", error,
                "message", message == null ? "" : message,
                "at", Instant.now().toString());
    }
}
