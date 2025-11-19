package com.anthropic.claude.sdk.transport;

import com.anthropic.claude.sdk.exceptions.CLIConnectionException;
import com.anthropic.claude.sdk.exceptions.CLINotFoundException;
import com.anthropic.claude.sdk.exceptions.ProcessException;
import com.anthropic.claude.sdk.internal.CLIFinder;
import com.anthropic.claude.sdk.mcp.SdkMcpServer;
import com.anthropic.claude.sdk.types.options.AgentDefinition;
import com.anthropic.claude.sdk.types.options.ClaudeAgentOptions;
import com.anthropic.claude.sdk.types.options.SdkPluginConfig;
import com.anthropic.claude.sdk.types.options.SettingSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Subprocess-based transport implementation.
 * Manages Claude Code CLI process lifecycle and I/O streams.
 */
public class SubprocessTransport implements Transport {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessTransport.class);
    private static final String SDK_VERSION = "0.1.0";
    private static final int DEFAULT_BUFFER_SIZE = 1024 * 1024;

    private final String prompt;
    private final boolean streamingMode;
    private final ClaudeAgentOptions options;
    private final String cliPath;
    private final ObjectMapper objectMapper;
    private final int bufferSize;
    private final ExecutorService executor;

    private Process process;
    private BufferedReader stdoutReader;
    private BufferedWriter stdinWriter;
    private BufferedReader stderrReader;
    private volatile boolean ready;

    public SubprocessTransport(String prompt, ClaudeAgentOptions options) {
        this(prompt, options, false);
    }

    public SubprocessTransport(ClaudeAgentOptions options, boolean streamingMode) {
        this(null, options, streamingMode);
    }

    private SubprocessTransport(String prompt, ClaudeAgentOptions options, boolean streamingMode) {
        this.prompt = prompt;
        this.streamingMode = streamingMode;
        this.options = options;
        this.cliPath = options.getCliPath() != null
                ? options.getCliPath().toString()
                : CLIFinder.findCLI();
        this.objectMapper = new ObjectMapper();
        this.executor = Executors.newCachedThreadPool();
        this.ready = false;
        this.bufferSize = options.getMaxBufferSize() != null && options.getMaxBufferSize() > 0
                ? options.getMaxBufferSize()
                : DEFAULT_BUFFER_SIZE;
    }

    @Override
    public CompletableFuture<Void> connect() {
        return CompletableFuture.runAsync(() -> {
            try {
                List<String> command = buildCommand();
                ProcessBuilder pb = new ProcessBuilder(command);

                // Set environment variables
                Map<String, String> env = pb.environment();
                env.putAll(options.getEnv());
                env.put("CLAUDE_CODE_ENTRYPOINT", "sdk-java");
                env.put("CLAUDE_AGENT_SDK_VERSION", SDK_VERSION);

                // Set working directory
                if (options.getCwd() != null) {
                    pb.directory(options.getCwd().toFile());
                }

                // Redirect streams
                pb.redirectInput(ProcessBuilder.Redirect.PIPE);
                pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
                pb.redirectError(ProcessBuilder.Redirect.PIPE);

                // Start process
                logger.debug("Starting Claude Code CLI: {}", String.join(" ", command));
                process = pb.start();

                // Setup I/O streams
                stdoutReader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()),
                        bufferSize
                );

                stdinWriter = new BufferedWriter(
                        new OutputStreamWriter(process.getOutputStream()),
                        bufferSize
                );

                stderrReader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream()),
                        bufferSize
                );

                // Start stderr reader in background
                executor.submit(this::readStderr);

                // For non-streaming mode, close stdin immediately
                if (!streamingMode && stdinWriter != null) {
                    stdinWriter.close();
                    stdinWriter = null;
                }

                ready = true;
                logger.debug("Claude Code CLI started successfully");

            } catch (FileNotFoundException e) {
                throw new CLINotFoundException("CLI not found at: " + cliPath, e);
            } catch (IOException e) {
                throw new CLIConnectionException("Failed to start Claude Code CLI", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> write(String line) {
        return CompletableFuture.runAsync(() -> {
            if (!ready || stdinWriter == null) {
                throw new IllegalStateException("Transport not connected");
            }

            try {
                stdinWriter.write(line);
                stdinWriter.newLine();
                stdinWriter.flush();
            } catch (IOException e) {
                throw new CLIConnectionException("Failed to write to CLI stdin", e);
            }
        }, executor);
    }

    @Override
    public Stream<String> readLines() {
        if (!ready || stdoutReader == null) {
            throw new IllegalStateException("Transport not connected");
        }

        return stdoutReader.lines();
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public CompletableFuture<Void> endInput() {
        return CompletableFuture.runAsync(() -> {
            if (stdinWriter != null) {
                try {
                    stdinWriter.close();
                } catch (IOException e) {
                    logger.warn("Error closing stdin", e);
                } finally {
                    stdinWriter = null;
                }
            }
        }, executor);
    }

    @Override
    public void close() {
        ready = false;

        try {
            if (stdinWriter != null) {
                stdinWriter.close();
            }
            if (stdoutReader != null) {
                stdoutReader.close();
            }
            if (stderrReader != null) {
                stderrReader.close();
            }
        } catch (IOException e) {
            logger.warn("Error closing streams", e);
        }

        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }

        executor.shutdown();
    }

    /**
     * Build CLI command with all arguments.
     */
    private List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(cliPath);
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--verbose");

        // System prompt
        if (options.getSystemPrompt() == null) {
            cmd.add("--system-prompt");
            cmd.add("");
        } else {
            cmd.add("--system-prompt");
            cmd.add(options.getSystemPrompt());
        }

        // Allowed tools
        if (!options.getAllowedTools().isEmpty()) {
            cmd.add("--allowedTools");
            cmd.add(String.join(",", options.getAllowedTools()));
        }

        // Disallowed tools
        if (!options.getDisallowedTools().isEmpty()) {
            cmd.add("--disallowedTools");
            cmd.add(String.join(",", options.getDisallowedTools()));
        }

        // Max turns
        if (options.getMaxTurns() != null) {
            cmd.add("--max-turns");
            cmd.add(options.getMaxTurns().toString());
        }

        // Max budget
        if (options.getMaxBudgetUsd() != null) {
            cmd.add("--max-budget-usd");
            cmd.add(options.getMaxBudgetUsd().toString());
        }

        // Model
        if (options.getModel() != null) {
            cmd.add("--model");
            cmd.add(options.getModel());
        }
        if (options.getFallbackModel() != null) {
            cmd.add("--fallback-model");
            cmd.add(options.getFallbackModel());
        }

        // Permission mode
        if (options.getPermissionMode() != null) {
            cmd.add("--permission-mode");
            cmd.add(options.getPermissionMode().getValue());
        }

        // Permission prompt tool
        if (options.getPermissionPromptToolName() != null) {
            cmd.add("--permission-prompt-tool");
            cmd.add(options.getPermissionPromptToolName());
        }

        // Continue conversation
        if (options.isContinueConversation()) {
            cmd.add("--continue");
        }

        // Resume session
        if (options.getResume() != null) {
            cmd.add("--resume");
            cmd.add(options.getResume());
        }

        // Settings
        if (options.getSettings() != null) {
            cmd.add("--settings");
            cmd.add(options.getSettings());
        }

        if (options.getUser() != null) {
            cmd.add("--user");
            cmd.add(options.getUser());
        }

        // Setting sources
        if (!options.getSettingSources().isEmpty()) {
            cmd.add("--setting-sources");
            cmd.add(options.getSettingSources().stream()
                    .map(SettingSource::getValue)
                    .collect(Collectors.joining(",")));
        }

        // Max thinking tokens
        if (options.getMaxThinkingTokens() != null) {
            cmd.add("--max-thinking-tokens");
            cmd.add(options.getMaxThinkingTokens().toString());
        }

        // Additional directories
        for (Path dir : options.getAddDirs()) {
            cmd.add("--add-dir");
            cmd.add(dir.toString());
        }

        // MCP servers
        if (!options.getMcpServers().isEmpty()) {
            try {
                Map<String, Object> sanitized = sanitizeMcpServers(options.getMcpServers());
                if (!sanitized.isEmpty()) {
                    Map<String, Object> mcpConfig = new HashMap<>();
                    mcpConfig.put("mcpServers", sanitized);
                    String mcpJson = objectMapper.writeValueAsString(mcpConfig);
                    cmd.add("--mcp-config");
                    cmd.add(mcpJson);
                }
            } catch (Exception e) {
                logger.warn("Failed to serialize MCP config", e);
            }
        }

        // Include partial messages
        if (options.isIncludePartialMessages()) {
            cmd.add("--include-partial-messages");
        }

        // Fork session
        if (options.isForkSession()) {
            cmd.add("--fork-session");
        }

        // Agents
        if (!options.getAgents().isEmpty()) {
            try {
                Map<String, Object> payload = new HashMap<>();
                for (Map.Entry<String, AgentDefinition> entry : options.getAgents().entrySet()) {
                    AgentDefinition definition = entry.getValue();
                    payload.put(entry.getKey(), definition != null ? definition.toMap() : Collections.emptyMap());
                }
                cmd.add("--agents");
                cmd.add(objectMapper.writeValueAsString(payload));
            } catch (Exception e) {
                logger.warn("Failed to serialize agents", e);
            }
        }

        // Plugins
        if (!options.getPlugins().isEmpty()) {
            for (SdkPluginConfig plugin : options.getPlugins()) {
                if (plugin.getType() == SdkPluginConfig.PluginType.LOCAL && plugin.getPath() != null) {
                    cmd.add("--plugin-dir");
                    cmd.add(plugin.getPath().toString());
                } else {
                    logger.warn("Unsupported plugin configuration: {}", plugin.getType());
                }
            }
        }

        if (options.getOutputFormat() != null
                && "json_schema".equals(options.getOutputFormat().get("type"))
                && options.getOutputFormat().get("schema") != null) {
            try {
                String schemaJson = objectMapper.writeValueAsString(options.getOutputFormat().get("schema"));
                cmd.add("--json-schema");
                cmd.add(schemaJson);
            } catch (Exception e) {
                logger.warn("Failed to serialize structured output schema", e);
            }
        }

        // Extra args
        for (Map.Entry<String, String> entry : options.getExtraArgs().entrySet()) {
            cmd.add("--" + entry.getKey());
            if (entry.getValue() != null) {
                cmd.add(entry.getValue());
            }
        }

        // Prompt handling
        if (streamingMode) {
            cmd.add("--input-format");
            cmd.add("stream-json");
        } else {
            cmd.add("--print");
            cmd.add("--");
            if (prompt != null) {
                cmd.add(prompt);
            } else {
                cmd.add("");
            }
        }

        return cmd;
    }

    /**
     * Read stderr in background.
     */
    private void readStderr() {
        try {
            String line;
            while ((line = stderrReader.readLine()) != null) {
                logger.debug("CLI stderr: {}", line);
            }
        } catch (IOException e) {
            if (ready) {
                logger.error("Error reading stderr", e);
            }
        }
    }

    private Map<String, Object> sanitizeMcpServers(Map<String, Object> servers) {
        Map<String, Object> sanitized = new HashMap<>();
        for (Map.Entry<String, Object> entry : servers.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof SdkMcpServer) {
                sanitized.put(entry.getKey(), ((SdkMcpServer) value).toCliConfig());
            } else if (value instanceof Map<?, ?>) {
                Map<?, ?> rawMap = (Map<?, ?>) value;
                Map<String, Object> normalized = new HashMap<>();
                for (Map.Entry<?, ?> inner : rawMap.entrySet()) {
                    Object key = inner.getKey();
                    if (!(key instanceof String)) {
                        continue;
                    }
                    String keyStr = (String) key;
                    if ("instance".equals(keyStr)) {
                        continue;
                    }
                    normalized.put(keyStr, inner.getValue());
                }
                sanitized.put(entry.getKey(), normalized);
            } else if (value != null) {
                sanitized.put(entry.getKey(), value);
            }
        }
        return sanitized;
    }
}
