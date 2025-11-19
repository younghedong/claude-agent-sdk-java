package com.anthropic.claude.sdk.client;

import com.anthropic.claude.sdk.exceptions.CLIConnectionException;
import com.anthropic.claude.sdk.internal.StreamingQuery;
import com.anthropic.claude.sdk.protocol.MessageParser;
import com.anthropic.claude.sdk.transport.SubprocessTransport;
import com.anthropic.claude.sdk.transport.Transport;
import com.anthropic.claude.sdk.types.messages.Message;
import com.anthropic.claude.sdk.types.options.ClaudeAgentOptions;
import com.anthropic.claude.sdk.types.permissions.ToolPermissionCallback;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Interactive client that mirrors the Python ClaudeSDKClient behavior.
 */
public final class ClaudeSDKClient implements AutoCloseable {

    private final ClaudeAgentOptions originalOptions;
    private final Transport customTransport;
    private ClaudeAgentOptions effectiveOptions;
    private final MessageParser parser;

    private Transport transport;
    private StreamingQuery query;
    private final AtomicBoolean connected;

    public ClaudeSDKClient() {
        this(ClaudeAgentOptions.builder().build(), null);
    }

    public ClaudeSDKClient(ClaudeAgentOptions options) {
        this(options, null);
    }

    public ClaudeSDKClient(ClaudeAgentOptions options, Transport transport) {
        this.originalOptions = Objects.requireNonNull(options, "options");
        this.customTransport = transport;
        this.parser = new MessageParser();
        this.connected = new AtomicBoolean(false);
    }

    /**
     * Connect to Claude without sending an initial prompt.
     */
    public CompletableFuture<Void> connect() {
        if (connected.get()) {
            return CompletableFuture.completedFuture(null);
        }

        effectiveOptions = prepareOptions(originalOptions);
        if (customTransport != null) {
            transport = customTransport;
        } else {
            transport = new SubprocessTransport(effectiveOptions, true);
        }

        return transport.connect()
                .thenCompose(ignored -> {
                    query = new StreamingQuery(
                            transport,
                            parser,
                            originalOptions.getCanUseTool(),
                            originalOptions.getHooks()
                    );
                    query.start();
                    return query.initialize();
                })
                .thenAccept(response -> connected.set(true));
    }

    /**
     * Connect and immediately send an initial prompt.
     */
    public CompletableFuture<Void> connect(String prompt) {
        return connect().thenCompose(ignored -> query(prompt));
    }

    /**
     * Send a user message in the default session.
     */
    public CompletableFuture<Void> query(String prompt) {
        return query(prompt, "default");
    }

    /**
     * Send a user message with a custom session id.
     */
    public CompletableFuture<Void> query(String prompt, String sessionId) {
        ensureConnected();
        Map<String, Object> data = new HashMap<>();
        data.put("type", "user");

        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);

        data.put("message", message);
        data.put("parent_tool_use_id", null);
        data.put("session_id", sessionId);
        return query.sendMessage(data);
    }

    /**
     * Send a fully-structured event to the CLI (advanced use).
     */
    public CompletableFuture<Void> send(Map<String, Object> event) {
        ensureConnected();
        return query.sendMessage(event);
    }

    /**
     * Stream responses from Claude.
     */
    public Stream<Message> receiveMessages() {
        ensureConnected();
        return query.streamMessages();
    }

    private ClaudeAgentOptions prepareOptions(ClaudeAgentOptions source) {
        ToolPermissionCallback callback = source.getCanUseTool();
        if (callback == null) {
            return source;
        }

        if (source.getPermissionPromptToolName() != null
                && !"stdio".equals(source.getPermissionPromptToolName())) {
            throw new IllegalArgumentException(
                    "canUseTool requires permission_prompt_tool_name=\"stdio\" when using streaming mode."
            );
        }

        return source.toBuilder()
                .permissionPromptToolName("stdio")
                .build();
    }

    private void ensureConnected() {
        if (!connected.get() || transport == null || query == null) {
            throw new CLIConnectionException("Not connected. Call connect() first.");
        }
    }

    @Override
    public void close() {
        connected.set(false);
        if (query != null) {
            query.close();
            query = null;
        }
        if (transport != null) {
            transport.close();
            transport = null;
        }
    }
}
