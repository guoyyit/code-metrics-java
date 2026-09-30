package com.github.guoyyit.codemetricsjava.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * Bounds the estimated in-flight memory used by parallel file analyses.
 * A permit represents one byte of budget; oversized files (larger than the
 * budget) still run serially instead of deadlocking. Acquisition is shared
 * between threads of one batch, while separate batches share only the
 * configured budget - they never share analysis state.
 */
@Component
public class MemoryLimiter {

    private final MetricsProperties properties;
    private Semaphore semaphore;

    public MemoryLimiter(MetricsProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        this.semaphore = new Semaphore(
                (int) Math.min(Integer.MAX_VALUE, properties.getMemoryBudgetBytes()), true);
    }

    public void acquire(long estimatedBytes) {
        int permits = (int) Math.max(1, Math.min(estimatedBytes,
                properties.getMemoryBudgetBytes()));
        try {
            semaphore.acquire(permits);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AnalysisInterruptedException(ex);
        }
    }

    public void release(long estimatedBytes) {
        int permits = (int) Math.max(1, Math.min(estimatedBytes,
                properties.getMemoryBudgetBytes()));
        semaphore.release(permits);
    }

    public int availablePermits() {
        return semaphore.availablePermits();
    }

    public static class AnalysisInterruptedException extends RuntimeException {
        public AnalysisInterruptedException(InterruptedException cause) {
            super(cause);
        }
    }
}
