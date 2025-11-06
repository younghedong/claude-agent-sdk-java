package com.anthropic.claude.sdk.hooks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Signal that can be used to abort long-running hook operations.
 * Similar to JavaScript's AbortSignal and Python's asyncio.Event.
 *
 * <p>Example usage:
 * <pre>{@code
 * HookCallback myHook = (input, toolUseId, context) -> {
 *     AbortSignal signal = (AbortSignal) context.get("signal");
 *
 *     // Check if already aborted
 *     if (signal != null && signal.isAborted()) {
 *         return CompletableFuture.completedFuture(
 *             Map.of("interrupt", true)
 *         );
 *     }
 *
 *     // Register abort listener
 *     if (signal != null) {
 *         signal.onAbort(() -> {
 *             // Clean up resources
 *             System.out.println("Hook operation aborted");
 *         });
 *     }
 *
 *     // Long-running operation...
 *     return performLongOperation();
 * };
 * }</pre>
 */
public class AbortSignal {

    private final AtomicBoolean aborted = new AtomicBoolean(false);
    private final List<Consumer<Void>> listeners = new ArrayList<>();
    private final CompletableFuture<Void> abortedFuture = new CompletableFuture<>();
    private volatile String reason = null;

    /**
     * Creates a new AbortSignal.
     */
    public AbortSignal() {
    }

    /**
     * Returns true if the signal has been aborted.
     *
     * @return true if aborted, false otherwise
     */
    public boolean isAborted() {
        return aborted.get();
    }

    /**
     * Gets the reason for the abort, if any.
     *
     * @return The abort reason, or null if not aborted or no reason provided
     */
    public String getReason() {
        return reason;
    }

    /**
     * Registers a callback to be invoked when the signal is aborted.
     * If already aborted, the callback is invoked immediately.
     *
     * @param listener The callback to invoke on abort
     */
    public synchronized void onAbort(Consumer<Void> listener) {
        if (aborted.get()) {
            // Already aborted, invoke immediately
            listener.accept(null);
        } else {
            listeners.add(listener);
        }
    }

    /**
     * Returns a CompletableFuture that completes when the signal is aborted.
     * Useful for async operations that want to wait for abort signal.
     *
     * @return A CompletableFuture that completes on abort
     */
    public CompletableFuture<Void> asCompletableFuture() {
        return abortedFuture;
    }

    /**
     * Aborts the signal, triggering all registered listeners.
     *
     * @param reason Optional reason for the abort
     */
    public synchronized void abort(String reason) {
        if (aborted.compareAndSet(false, true)) {
            this.reason = reason;

            // Notify all listeners
            for (Consumer<Void> listener : listeners) {
                try {
                    listener.accept(null);
                } catch (Exception e) {
                    // Ignore listener exceptions
                }
            }

            // Complete the future
            abortedFuture.complete(null);
        }
    }

    /**
     * Aborts the signal without a specific reason.
     */
    public void abort() {
        abort(null);
    }

    /**
     * Creates an already-aborted signal.
     *
     * @param reason The abort reason
     * @return A new AbortSignal that is already aborted
     */
    public static AbortSignal aborted(String reason) {
        AbortSignal signal = new AbortSignal();
        signal.abort(reason);
        return signal;
    }

    /**
     * Throws an AbortException if the signal has been aborted.
     * Useful for checking abort status at various points in a long operation.
     *
     * @throws AbortException if the signal has been aborted
     */
    public void throwIfAborted() throws AbortException {
        if (aborted.get()) {
            throw new AbortException(reason != null ? reason : "Operation aborted");
        }
    }

    /**
     * Exception thrown when an operation is aborted.
     */
    public static class AbortException extends Exception {
        public AbortException(String message) {
            super(message);
        }
    }
}
