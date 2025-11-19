package com.anthropic.claude.sdk.transport;

import java.io.Closeable;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Transport layer for communication with Claude Code CLI.
 */
public interface Transport extends Closeable {

    /**
     * Connect to the CLI process.
     */
    CompletableFuture<Void> connect();

    /**
     * Write a message to the CLI stdin.
     *
     * @param line Line to write (usually JSON)
     */
    CompletableFuture<Void> write(String line);

    /**
     * Read messages from the CLI stdout.
     *
     * @return Stream of raw message lines
     */
    Stream<String> readLines();

    /**
     * Close the stdin/input stream without shutting down the transport.
     */
    CompletableFuture<Void> endInput();

    /**
     * Check if the transport is ready.
     */
    boolean isReady();

    /**
     * Close the transport and cleanup resources.
     */
    @Override
    void close();
}
