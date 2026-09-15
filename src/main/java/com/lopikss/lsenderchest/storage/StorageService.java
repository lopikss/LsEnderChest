package com.lopikss.lsenderchest.storage;

import com.lopikss.lsenderchest.overflow.OverflowRecord;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class StorageService {

    private final StorageProvider provider;
    private final ExecutorService executor;

    public StorageService(StorageProvider provider) {
        this.provider = provider;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "LsEnderChest-Storage");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void initialize(Duration timeout) throws Exception {
        try {
            submit(() -> {
                provider.init();
                return null;
            }).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            throw new RuntimeException(cause);
        } catch (TimeoutException exception) {
            throw new IllegalStateException("Timed out while initializing storage.", exception);
        }
    }

    public CompletableFuture<String> load(UUID playerUuid) {
        return submit(() -> provider.load(playerUuid));
    }

    public CompletableFuture<Void> save(UUID playerUuid, String playerName, String contents) {
        return retrying(() -> {
            provider.save(playerUuid, playerName, contents);
            return null;
        });
    }

    public CompletableFuture<OverflowRecord> loadOverflow(UUID overflowId) {
        return submit(() -> provider.loadOverflow(overflowId));
    }

    public CompletableFuture<Void> saveOverflow(OverflowRecord record) {
        return retrying(() -> {
            provider.saveOverflow(record);
            return null;
        });
    }

    public CompletableFuture<Boolean> deleteOverflow(UUID overflowId) {
        return retrying(() -> provider.deleteOverflow(overflowId));
    }

    public void shutdownAndFlush(Duration timeout) {
        try {
            submit(() -> {
                provider.close();
                return null;
            }).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // Call sites log operation failures; shutdown must continue regardless.
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }

    private <T> CompletableFuture<T> retrying(ThrowingSupplier<T> supplier) {
        return submit(() -> {
            Exception lastFailure = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    return supplier.get();
                } catch (Exception exception) {
                    lastFailure = exception;
                    if (attempt < 3) {
                        Thread.sleep(100L * attempt);
                    }
                }
            }
            throw lastFailure == null ? new IllegalStateException("Unknown storage failure.") : lastFailure;
        });
    }

    private <T> CompletableFuture<T> submit(ThrowingSupplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
        }
        return future;
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
