package com.anthropic.claude.sdk.client;

import com.anthropic.claude.sdk.protocol.MessageParser;
import com.anthropic.claude.sdk.transport.SubprocessTransport;
import com.anthropic.claude.sdk.transport.Transport;
import com.anthropic.claude.sdk.types.messages.Message;
import com.anthropic.claude.sdk.types.options.ClaudeAgentOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Claude Agent SDK Client for bidirectional sessions.
 *
 * This client provides full interactive conversation capabilities with Claude Code,
 * supporting hooks, tool permissions, and MCP servers.
 *
 * Example:
 * <pre>{@code
 * ClaudeAgentOptions options = ClaudeAgentOptions.builder()
 *     .allowedTools("Read", "Write", "Bash")
 *     .permissionMode(PermissionMode.ACCEPT_EDITS)
 *     .build();
 *
 * try (ClaudeClient client = new ClaudeClient(options)) {
 *     client.query("What is 2 + 2?").join();
 *
 *     client.receiveMessages().forEach(message -> {
 *         log.info("{}", message);
 *     });
 * }
 * }</pre>
 */
public class ClaudeClient implements Closeable {

    private static final Logger logger = LoggerFactory.getLogger(ClaudeClient.class);

    private final ClaudeAgentOptions options;
    private final MessageParser parser;
    private Transport transport;

    /**
     * Create a new Claude client with default options.
     */
    public ClaudeClient() {
        this(ClaudeAgentOptions.builder().build());
    }

    /**
     * Create a new Claude client with custom options.
     */
    public ClaudeClient(ClaudeAgentOptions options) {
        this.options = options;
        this.parser = new MessageParser();
    }

    /**
     * Query Claude with a prompt.
     * For simple one-shot queries, consider using {@link com.anthropic.claude.sdk.ClaudeAgentSdk#query(String)}
     *
     * @param prompt The prompt to send
     * @return CompletableFuture that completes when the query is sent
     */
    public CompletableFuture<Stream<Message>> query(String prompt) {
        if (options.getCanUseTool() != null) {
            throw new IllegalArgumentException(
                "Tool permission callbacks require streaming mode. Use ClaudeSDKClient for interactive sessions."
            );
        }
        if (options.getHooks() != null && !options.getHooks().isEmpty()) {
            throw new IllegalArgumentException(
                "Hooks require streaming mode. Use ClaudeSDKClient for interactive sessions."
            );
        }

        return CompletableFuture.supplyAsync(() -> {
            // Create transport if not exists
            if (transport == null) {
                transport = new SubprocessTransport(prompt, options);
                transport.connect().join();
            }

            // Return message stream
            return receiveMessages();
        });
    }

    /**
     * Receive messages from Claude.
     * This returns a stream of parsed Message objects.
     *
     * @return Stream of messages from Claude
     */
    public Stream<Message> receiveMessages() {
        if (transport == null || !transport.isReady()) {
            throw new IllegalStateException("Client not connected. Call query() first.");
        }

        return transport.readLines()
            .map(line -> {
                try {
                    logger.debug("Received: {}", line);
                    return parser.parse(line);
                } catch (Exception e) {
                    logger.error("Failed to parse message: {}", line, e);
                    return null;
                }
            })
            .filter(msg -> msg != null);
    }

    @Override
    public void close() {
        if (transport != null) {
            transport.close();
        }
    }

    /**
     * Create a builder for ClaudeClient with custom options.
     */
    public static ClaudeAgentOptions.ClaudeAgentOptionsBuilder options() {
        return ClaudeAgentOptions.builder();
    }
}
