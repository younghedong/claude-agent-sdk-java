package com.anthropic.claude.sdk.client;

import com.anthropic.claude.sdk.errors.CLIJSONDecodeException;
import com.anthropic.claude.sdk.errors.ClaudeSDKException;
import com.anthropic.claude.sdk.errors.MessageParseException;
import com.anthropic.claude.sdk.types.ClaudeAgentOptions;
import com.anthropic.claude.sdk.types.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Main client for interacting with Claude Code SDK.
 * Provides bidirectional communication with Claude for interactive conversations.
 */
public class ClaudeSDKClient implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ClaudeSDKClient.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ClaudeAgentOptions options;
    private final Transport transport;
    private final BlockingQueue<Message> messageQueue;
    private volatile boolean connected;
    private Thread readerThread;

    /**
     * Creates a new Claude SDK client.
     *
     * @param options Configuration options
     * @param transport Custom transport (null to use default process transport)
     */
    public ClaudeSDKClient(@Nullable ClaudeAgentOptions options, @Nullable Transport transport) {
        this.options = options != null ? options : ClaudeAgentOptions.builder().build();
        this.transport = transport != null ? transport : new ProcessTransport(this.options);
        this.messageQueue = new LinkedBlockingQueue<>();
        this.connected = false;
    }

    /**
     * Creates a new Claude SDK client with default options.
     */
    public ClaudeSDKClient() {
        this(null, null);
    }

    /**
     * Connects to Claude Code and optionally sends an initial prompt.
     *
     * @param prompt Initial prompt to send (can be null)
     * @throws ClaudeSDKException if connection fails
     */
    public void connect(@Nullable String prompt) throws ClaudeSDKException {
        if (connected) {
            throw new IllegalStateException("Already connected");
        }

        transport.start();
        connected = true;

        // Start reader thread
        startReaderThread();

        // Send initial query if provided
        if (prompt != null) {
            query(prompt, "default");
        }
    }

    /**
     * Sends a query to Claude.
     *
     * @param prompt The prompt to send
     * @param sessionId Session identifier
     */
    public void query(String prompt, String sessionId) {
        if (!connected) {
            throw new IllegalStateException("Not connected");
        }

        try {
            Map<String, Object> message = new HashMap<>();
            message.put("type", "query");
            message.put("prompt", prompt);
            message.put("sessionId", sessionId);
            message.put("options", serializeOptions());

            sendMessage(message);
        } catch (IOException e) {
            logger.error("Failed to send query", e);
        }
    }

    /**
     * Receives messages from Claude as an iterator.
     *
     * @return An iterator of messages
     */
    public Iterator<Message> receiveMessages() {
        return new Iterator<Message>() {
            @Override
            public boolean hasNext() {
                return connected || !messageQueue.isEmpty();
            }

            @Override
            public Message next() {
                try {
                    return messageQueue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        };
    }

    /**
     * Sends an interrupt signal to Claude.
     */
    public void interrupt() {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("type", "control");
            message.put("request", "interrupt");
            sendMessage(message);
        } catch (IOException e) {
            logger.error("Failed to send interrupt", e);
        }
    }

    /**
     * Sets the permission mode.
     *
     * @param mode The permission mode to set
     */
    public void setPermissionMode(String mode) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("type", "control");
            message.put("request", "set_permission_mode");
            message.put("mode", mode);
            sendMessage(message);
        } catch (IOException e) {
            logger.error("Failed to set permission mode", e);
        }
    }

    private void startReaderThread() {
        readerThread = new Thread(() -> {
            try {
                String line;
                while (connected && (line = transport.getReader().readLine()) != null) {
                    processLine(line);
                }
            } catch (IOException e) {
                if (connected) {
                    logger.error("Error reading from transport", e);
                }
            }
        });
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void processLine(String line) {
        try {
            Map<String, Object> data = objectMapper.readValue(line, Map.class);
            Message message = parseMessage(data);
            if (message != null) {
                messageQueue.offer(message);
            }
        } catch (Exception e) {
            logger.error("Failed to process line: {}", line, e);
        }
    }

    @Nullable
    private Message parseMessage(Map<String, Object> data) throws MessageParseException {
        try {
            String json = objectMapper.writeValueAsString(data);
            return objectMapper.readValue(json, Message.class);
        } catch (Exception e) {
            throw new MessageParseException("Failed to parse message", data, e);
        }
    }

    private void sendMessage(Map<String, Object> message) throws IOException {
        String json = objectMapper.writeValueAsString(message);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(transport.getOutputStream()));
        writer.write(json);
        writer.newLine();
        writer.flush();
    }

    private Map<String, Object> serializeOptions() {
        Map<String, Object> opts = new HashMap<>();

        // Core configuration
        if (options.getAllowedTools() != null) {
            opts.put("allowedTools", options.getAllowedTools());
        }
        if (options.getDisallowedTools() != null) {
            opts.put("disallowedTools", options.getDisallowedTools());
        }
        if (options.getSystemPrompt() != null) {
            opts.put("systemPrompt", options.getSystemPrompt());
        }
        if (options.getPermissionMode() != null) {
            opts.put("permissionMode", options.getPermissionMode().getValue());
        }
        if (options.getMaxTurns() != null) {
            opts.put("maxTurns", options.getMaxTurns());
        }
        if (options.getMaxBudgetUsd() != null) {
            opts.put("maxBudgetUsd", options.getMaxBudgetUsd());
        }
        if (options.getCwd() != null) {
            opts.put("cwd", options.getCwd().toString());
        }
        if (options.getModel() != null) {
            opts.put("model", options.getModel());
        }
        if (options.getMaxThinkingTokens() != null) {
            opts.put("maxThinkingTokens", options.getMaxThinkingTokens());
        }

        // Agent definitions
        if (options.getAgents() != null) {
            opts.put("agents", options.getAgents());
        }

        // MCP servers
        if (options.getMcpServers() != null) {
            opts.put("mcpServers", options.getMcpServers());
        }

        // Session management
        opts.put("continueConversation", options.isContinueConversation());
        if (options.getResume() != null) {
            opts.put("resume", options.getResume());
        }
        opts.put("forkSession", options.isForkSession());

        // Additional directories
        if (options.getAddDirs() != null) {
            List<String> dirPaths = options.getAddDirs().stream()
                    .map(Path::toString)
                    .collect(java.util.stream.Collectors.toList());
            opts.put("addDirs", dirPaths);
        }

        // User and messaging
        if (options.getUser() != null) {
            opts.put("user", options.getUser());
        }
        opts.put("includePartialMessages", options.isIncludePartialMessages());

        return opts;
    }

    @Override
    public void close() {
        connected = false;
        if (readerThread != null) {
            readerThread.interrupt();
        }
        transport.close();
    }
}
