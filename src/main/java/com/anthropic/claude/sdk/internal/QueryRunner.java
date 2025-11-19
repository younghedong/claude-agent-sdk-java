package com.anthropic.claude.sdk.internal;

import com.anthropic.claude.sdk.protocol.MessageParser;
import com.anthropic.claude.sdk.transport.SubprocessTransport;
import com.anthropic.claude.sdk.transport.Transport;
import com.anthropic.claude.sdk.types.messages.Message;
import com.anthropic.claude.sdk.types.options.ClaudeAgentOptions;
import com.anthropic.claude.sdk.types.permissions.ToolPermissionCallback;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Internal helper to run streaming queries similar to the Python SDK.
 */
public final class QueryRunner {

    public Stream<Message> streamPrompt(
            Iterable<Map<String, Object>> prompts,
            ClaudeAgentOptions options,
            Transport customTransport
    ) {
        Objects.requireNonNull(prompts, "prompts");

        ClaudeAgentOptions safeOptions = options != null
                ? options
                : ClaudeAgentOptions.builder().build();

        validateStreamingOptions(safeOptions);

        ClaudeAgentOptions effectiveOptions = prepareOptionsForStreaming(safeOptions);
        Transport transport = customTransport != null
                ? customTransport
                : new SubprocessTransport(effectiveOptions, true);

        transport.connect().join();

        StreamingQuery streamingQuery = new StreamingQuery(
                transport,
                new MessageParser(),
                safeOptions.getCanUseTool(),
                safeOptions.getHooks()
        );

        streamingQuery.start();
        streamingQuery.initialize().join();

        try {
            streamingQuery.streamPrompt(prompts).join();
            transport.endInput().join();
        } catch (Exception e) {
            streamingQuery.close();
            throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
        }

        Stream<Message> messageStream = streamingQuery.streamMessages();
        return messageStream.onClose(streamingQuery::close);
    }

    private void validateStreamingOptions(ClaudeAgentOptions options) {
        ToolPermissionCallback callback = options.getCanUseTool();
        if (callback != null && options.getPermissionPromptToolName() != null
                && !"stdio".equals(options.getPermissionPromptToolName())) {
            throw new IllegalArgumentException(
                    "canUseTool callback requires permission_prompt_tool_name=\"stdio\" in streaming mode."
            );
        }
    }

    private ClaudeAgentOptions prepareOptionsForStreaming(ClaudeAgentOptions options) {
        if (options.getCanUseTool() == null
                || "stdio".equals(options.getPermissionPromptToolName())) {
            return options;
        }

        return options.toBuilder()
                .permissionPromptToolName("stdio")
                .build();
    }
}
