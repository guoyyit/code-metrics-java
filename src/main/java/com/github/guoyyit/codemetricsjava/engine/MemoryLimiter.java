package com.github.guoyyit.codemetricsjava.engine;

import java.util.concurrent.Callable;

/**
 * 并行分析的内存闸：按估算占用字节数发放许可，超过内存上限的任务排队等待。
 */
public class MemoryLimiter {

    private final long memoryLimitBytes;
    private final Object lock = new Object();
    private long usedBytes;

    public MemoryLimiter(long memoryLimitBytes) {
        this.memoryLimitBytes = Math.max(1L, memoryLimitBytes);
    }

    public <T> T runWithPermit(long estimatedBytes, Callable<T> task) throws Exception {
        long permit = Math.min(Math.max(1L, estimatedBytes), memoryLimitBytes);
        acquire(permit);
        try {
            return task.call();
        } finally {
            release(permit);
        }
    }

    private void acquire(long bytes) throws InterruptedException {
        synchronized (lock) {
            while (usedBytes + bytes > memoryLimitBytes) {
                lock.wait();
            }
            usedBytes += bytes;
        }
    }

    private void release(long bytes) {
        synchronized (lock) {
            usedBytes -= bytes;
            lock.notifyAll();
        }
    }
}
