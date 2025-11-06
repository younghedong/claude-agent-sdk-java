package com.anthropic.claude.sdk.client;

import com.anthropic.claude.sdk.errors.CLIJSONDecodeException;
import com.anthropic.claude.sdk.errors.ClaudeSDKException;
import com.anthropic.claude.sdk.errors.MessageParseException;
import com.anthropic.claude.sdk.hooks.HookCallback;
import com.anthropic.claude.sdk.hooks.HookMatcher;
import com.anthropic.claude.sdk.mcp.SdkMcpServer;
import com.anthropic.claude.sdk.mcp.SdkMcpTool;
import com.anthropic.claude.sdk.types.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main client for interacting with Claude Code SDK.
 * Provides bidirectional communication with Claude for interactive conversations.
 */
public class ClaudeSDKClient implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ClaudeSDKClient.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final SecureRandom secureRandom = new SecureRandom();

    private final ClaudeAgentOptions options;
    private final Transport transport;
    private final BlockingQueue<Message> messageQueue;
    private volatile boolean connected;
    private Thread readerThread;

    // Control protocol infrastructure
    private final Map<String, HookCallback> hookCallbacks = new ConcurrentHashMap<>();
    private final AtomicInteger nextCallbackId = new AtomicInteger(0);
    private final AtomicInteger requestCounter = new AtomicInteger(0);
    private final Map<String, CompletableFuture<Map<String, Object>>> pendingControlRequests = new ConcurrentHashMap<>();
    private final Map<String, SdkMcpServer> sdkMcpServers = new ConcurrentHashMap<>();
    private final ExecutorService controlProtocolExecutor = Executors.newCachedThreadPool();
    private volatile Map<String, Object> initializationResult = null;

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

        // Extract SDK MCP servers from options
        if (this.options.getMcpServers() != null && this.options.getMcpServers() instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mcpServers = (Map<String, Object>) this.options.getMcpServers();
            for (Map.Entry<String, Object> entry : mcpServers.entrySet()) {
                if (entry.getValue() instanceof SdkMcpServer) {
                    sdkMcpServers.put(entry.getKey(), (SdkMcpServer) entry.getValue());
                }
            }
        }
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

        // Send initialize request with hooks configuration
        sendInitializeRequest();

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
     * Receives messages from Claude until a ResultMessage is received.
     * This iterator terminates after receiving a ResultMessage, unlike
     * receiveMessages() which continues indefinitely.
     *
     * @return An iterator of messages that stops after a ResultMessage
     */
    public Iterator<Message> receiveResponse() {
        return new Iterator<Message>() {
            private Message nextMessage;
            private boolean done = false;

            @Override
            public boolean hasNext() {
                if (done) {
                    return false;
                }

                try {
                    nextMessage = messageQueue.take();
                    if (nextMessage instanceof ResultMessage) {
                        done = true;
                    }
                    return true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    done = true;
                    return false;
                }
            }

            @Override
            public Message next() {
                return nextMessage;
            }
        };
    }

    /**
     * Sends an interrupt signal to Claude.
     *
     * @return A CompletableFuture that completes when the interrupt is acknowledged
     */
    public CompletableFuture<Map<String, Object>> interrupt() {
        Map<String, Object> request = new HashMap<>();
        request.put("subtype", "interrupt");
        return sendControlRequest(request);
    }

    /**
     * Sets the permission mode.
     *
     * @param mode The permission mode to set
     * @return A CompletableFuture that completes when the mode is set
     */
    public CompletableFuture<Map<String, Object>> setPermissionMode(String mode) {
        Map<String, Object> request = new HashMap<>();
        request.put("subtype", "set_permission_mode");
        request.put("mode", mode);
        return sendControlRequest(request);
    }

    /**
     * Dynamically changes the AI model during the conversation.
     *
     * @param model The model to switch to (e.g., "sonnet", "opus", "haiku")
     * @return A CompletableFuture that completes when the model is changed
     */
    public CompletableFuture<Map<String, Object>> setModel(@Nullable String model) {
        Map<String, Object> request = new HashMap<>();
        request.put("subtype", "set_model");
        request.put("model", model);
        return sendControlRequest(request);
    }

    /**
     * Retrieves server capabilities and available commands.
     *
     * @return A CompletableFuture containing server information
     */
    public CompletableFuture<Map<String, Object>> getServerInfo() {
        Map<String, Object> request = new HashMap<>();
        request.put("subtype", "server_info");
        return sendControlRequest(request);
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

    /**
     * Routes incoming messages to appropriate handlers.
     * Distinguishes between control protocol messages and regular SDK messages.
     */
    private void processLine(String line) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {});
            String messageType = (String) data.get("type");

            if ("control_response".equals(messageType)) {
                // Response to our control request
                handleControlResponse(data);
            } else if ("control_request".equals(messageType)) {
                // Incoming control request from CLI
                handleControlRequest(data);
            } else {
                // Regular SDK message - parse and queue
                Message message = parseMessage(data);
                if (message != null) {
                    messageQueue.offer(message);
                }
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

        // Hooks
        if (options.getHooks() != null) {
            opts.put("hooks", convertHooksToInternalFormat(options.getHooks()));
        }

        return opts;
    }

    /**
     * Converts HookMatcher objects into internal Query format.
     * This transforms the hooks map from Java objects to the format expected by the CLI.
     *
     * @param hooks the hooks map to convert
     * @return converted hooks in internal format
     */
    private Map<String, List<Map<String, Object>>> convertHooksToInternalFormat(
            Map<String, List<HookMatcher>> hooks) {
        Map<String, List<Map<String, Object>>> converted = new HashMap<>();

        for (Map.Entry<String, List<HookMatcher>> entry : hooks.entrySet()) {
            String hookEvent = entry.getKey();
            List<HookMatcher> matchers = entry.getValue();

            List<Map<String, Object>> convertedMatchers = new ArrayList<>();
            for (HookMatcher matcher : matchers) {
                Map<String, Object> matcherMap = new HashMap<>();

                // Add matcher pattern if present
                if (matcher.getMatcher() != null) {
                    matcherMap.put("matcher", matcher.getMatcher());
                }

                // Note: In the internal format, hooks callbacks are registered with IDs
                // The actual callback execution is handled through the SDK control protocol
                // For now, we just mark that hooks are present
                matcherMap.put("hasCallbacks", !matcher.getHooks().isEmpty());
                matcherMap.put("callbackCount", matcher.getHooks().size());

                convertedMatchers.add(matcherMap);
            }

            converted.put(hookEvent, convertedMatchers);
        }

        return converted;
    }

    // ==================== Control Protocol Implementation ====================

    /**
     * Sends initialize control request with hooks configuration.
     */
    private void sendInitializeRequest() {
        Map<String, Object> hooksConfig = new HashMap<>();

        if (options.getHooks() != null && !options.getHooks().isEmpty()) {
            for (Map.Entry<String, List<HookMatcher>> entry : options.getHooks().entrySet()) {
                String hookEvent = entry.getKey();
                List<Map<String, Object>> matchers = new ArrayList<>();

                for (HookMatcher matcher : entry.getValue()) {
                    List<String> callbackIds = new ArrayList<>();

                    // Register each hook callback with unique ID
                    for (HookCallback callback : matcher.getHooks()) {
                        String callbackId = "hook_" + nextCallbackId.getAndIncrement();
                        hookCallbacks.put(callbackId, callback);
                        callbackIds.add(callbackId);
                    }

                    Map<String, Object> matcherMap = new HashMap<>();
                    if (matcher.getMatcher() != null) {
                        matcherMap.put("matcher", matcher.getMatcher());
                    }
                    matcherMap.put("hookCallbackIds", callbackIds);
                    matchers.add(matcherMap);
                }

                hooksConfig.put(hookEvent, matchers);
            }
        }

        Map<String, Object> request = new HashMap<>();
        request.put("subtype", "initialize");
        if (!hooksConfig.isEmpty()) {
            request.put("hooks", hooksConfig);
        }

        // Send initialize request and store result
        sendControlRequest(request).thenAccept(response -> {
            initializationResult = response;
            logger.info("Initialized successfully");
        }).exceptionally(e -> {
            logger.error("Failed to initialize", e);
            return null;
        });
    }

    /**
     * Handles control response from CLI.
     */
    private void handleControlResponse(Map<String, Object> message) {
        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) message.get("response");
        if (response == null) {
            logger.warn("Control response missing 'response' field");
            return;
        }

        String requestId = (String) response.get("request_id");
        if (requestId == null) {
            logger.warn("Control response missing 'request_id'");
            return;
        }

        CompletableFuture<Map<String, Object>> future = pendingControlRequests.remove(requestId);
        if (future == null) {
            logger.warn("No pending request found for ID: {}", requestId);
            return;
        }

        String subtype = (String) response.get("subtype");
        if ("error".equals(subtype)) {
            String error = (String) response.get("error");
            future.completeExceptionally(new ClaudeSDKException("Control request failed: " + error));
        } else {
            @SuppressWarnings("unchecked")
            Map<String, Object> responseData = (Map<String, Object>) response.get("response");
            future.complete(responseData != null ? responseData : new HashMap<>());
        }
    }

    /**
     * Handles incoming control request from CLI.
     */
    private void handleControlRequest(Map<String, Object> message) {
        String requestId = (String) message.get("request_id");
        @SuppressWarnings("unchecked")
        Map<String, Object> requestData = (Map<String, Object>) message.get("request");
        String subtype = (String) requestData.get("subtype");

        // Process request asynchronously
        controlProtocolExecutor.submit(() -> {
            try {
                Map<String, Object> responseData;

                if ("can_use_tool".equals(subtype)) {
                    responseData = handlePermissionRequest(requestData);
                } else if ("hook_callback".equals(subtype)) {
                    responseData = handleHookCallback(requestData);
                } else if ("mcp_message".equals(subtype)) {
                    responseData = handleMcpMessage(requestData);
                } else {
                    throw new IllegalArgumentException("Unknown subtype: " + subtype);
                }

                sendControlResponse(requestId, "success", responseData);
            } catch (Exception e) {
                logger.error("Error handling control request", e);
                sendControlError(requestId, e.getMessage());
            }
        });
    }

    /**
     * Sends a control request to CLI and waits for response.
     */
    private CompletableFuture<Map<String, Object>> sendControlRequest(Map<String, Object> request) {
        // Generate unique request ID
        int counter = requestCounter.incrementAndGet();
        byte[] randomBytes = new byte[4];
        secureRandom.nextBytes(randomBytes);
        String requestId = String.format("req_%d_%s", counter, bytesToHex(randomBytes));

        // Create future for response
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pendingControlRequests.put(requestId, future);

        // Build and send request
        Map<String, Object> controlRequest = new HashMap<>();
        controlRequest.put("type", "control_request");
        controlRequest.put("request_id", requestId);
        controlRequest.put("request", request);

        try {
            sendMessage(controlRequest);
        } catch (IOException e) {
            pendingControlRequests.remove(requestId);
            future.completeExceptionally(e);
        }

        // Set timeout (60 seconds)
        future.orTimeout(60, TimeUnit.SECONDS)
            .exceptionally(e -> {
                pendingControlRequests.remove(requestId);
                return null;
            });

        return future;
    }

    /**
     * Sends a successful control response.
     */
    private void sendControlResponse(String requestId, String subtype, Map<String, Object> responseData) {
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("type", "control_response");

            Map<String, Object> responseContent = new HashMap<>();
            responseContent.put("subtype", subtype);
            responseContent.put("request_id", requestId);
            responseContent.put("response", responseData);

            response.put("response", responseContent);
            sendMessage(response);
        } catch (IOException e) {
            logger.error("Failed to send control response", e);
        }
    }

    /**
     * Sends an error control response.
     */
    private void sendControlError(String requestId, String errorMessage) {
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("type", "control_response");

            Map<String, Object> responseContent = new HashMap<>();
            responseContent.put("subtype", "error");
            responseContent.put("request_id", requestId);
            responseContent.put("error", errorMessage);

            response.put("response", responseContent);
            sendMessage(response);
        } catch (IOException e) {
            logger.error("Failed to send control error", e);
        }
    }

    /**
     * Converts bytes to hex string.
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Handles permission callback request (can_use_tool).
     */
    private Map<String, Object> handlePermissionRequest(Map<String, Object> requestData) throws Exception {
        if (options.getCanUseTool() == null) {
            throw new IllegalStateException("canUseTool callback is not provided");
        }

        String toolName = (String) requestData.get("tool_name");
        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) requestData.get("input");
        @SuppressWarnings("unchecked")
        List<String> suggestions = (List<String>) requestData.get("permission_suggestions");

        ToolPermissionContext context = ToolPermissionContext.builder()
                .suggestions(suggestions != null ? suggestions : new ArrayList<>())
                .build();

        // Invoke user's permission callback
        CompletableFuture<PermissionResult> futureResult = options.getCanUseTool().apply(toolName, input, context);
        PermissionResult result = futureResult.get(); // Wait for result

        Map<String, Object> responseData = new HashMap<>();

        if (result instanceof PermissionResultAllow) {
            PermissionResultAllow allow = (PermissionResultAllow) result;
            responseData.put("behavior", "allow");
            responseData.put("updatedInput", allow.getUpdatedInput() != null ? allow.getUpdatedInput() : input);

            if (allow.getUpdatedPermissions() != null && !allow.getUpdatedPermissions().isEmpty()) {
                List<Map<String, Object>> permissions = new ArrayList<>();
                for (PermissionUpdate perm : allow.getUpdatedPermissions()) {
                    permissions.add(perm.toDict());
                }
                responseData.put("updatedPermissions", permissions);
            }
        } else if (result instanceof PermissionResultDeny) {
            PermissionResultDeny deny = (PermissionResultDeny) result;
            responseData.put("behavior", "deny");
            responseData.put("message", deny.getMessage());
            if (deny.isInterrupt()) {
                responseData.put("interrupt", true);
            }
        }

        return responseData;
    }

    /**
     * Handles hook callback execution.
     */
    private Map<String, Object> handleHookCallback(Map<String, Object> requestData) throws Exception {
        String callbackId = (String) requestData.get("callback_id");
        HookCallback callback = hookCallbacks.get(callbackId);

        if (callback == null) {
            throw new IllegalStateException("No hook callback found for ID: " + callbackId);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) requestData.get("input");
        String toolUseId = (String) requestData.get("tool_use_id");

        Map<String, Object> context = new HashMap<>();
        context.put("signal", null); // TODO: Add abort signal support

        // Invoke user's hook callback
        CompletableFuture<Map<String, Object>> futureResult = callback.apply(input, toolUseId, context);
        Map<String, Object> hookOutput = futureResult.get(); // Wait for result

        // Convert Java-safe field names to CLI-expected names if needed
        return convertHookOutputForCli(hookOutput);
    }

    /**
     * Converts hook output field names for CLI compatibility.
     */
    private Map<String, Object> convertHookOutputForCli(Map<String, Object> hookOutput) {
        Map<String, Object> converted = new HashMap<>();
        for (Map.Entry<String, Object> entry : hookOutput.entrySet()) {
            String key = entry.getKey();
            // Handle any special conversions if needed (e.g., async_ -> async)
            converted.put(key, entry.getValue());
        }
        return converted;
    }

    /**
     * Handles MCP message routing to SDK servers.
     */
    private Map<String, Object> handleMcpMessage(Map<String, Object> requestData) {
        String serverName = (String) requestData.get("server_name");
        @SuppressWarnings("unchecked")
        Map<String, Object> mcpMessage = (Map<String, Object>) requestData.get("message");

        if (serverName == null || mcpMessage == null) {
            return createJsonRpcError(-32600, "Invalid request: missing server_name or message");
        }

        SdkMcpServer server = sdkMcpServers.get(serverName);
        if (server == null) {
            return createJsonRpcError(-32601, "Server '" + serverName + "' not found");
        }

        String method = (String) mcpMessage.get("method");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) mcpMessage.get("params");

        try {
            Map<String, Object> mcpResponse;

            if ("initialize".equals(method)) {
                mcpResponse = handleMcpInitialize(server, mcpMessage);
            } else if ("tools/list".equals(method)) {
                mcpResponse = handleMcpToolsList(server, mcpMessage);
            } else if ("tools/call".equals(method)) {
                mcpResponse = handleMcpToolsCall(server, params, mcpMessage);
            } else if ("notifications/initialized".equals(method)) {
                // Acknowledge initialized notification
                mcpResponse = createJsonRpcSuccess(mcpMessage.get("id"), new HashMap<>());
            } else {
                mcpResponse = createJsonRpcError(-32601, "Method '" + method + "' not found");
            }

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("mcp_response", mcpResponse);
            return responseData;

        } catch (Exception e) {
            logger.error("Error handling MCP message", e);
            Map<String, Object> errorResponse = createJsonRpcError(-32603, e.getMessage());
            Map<String, Object> responseData = new HashMap<>();
            responseData.put("mcp_response", errorResponse);
            return responseData;
        }
    }

    /**
     * Handles MCP initialize request.
     */
    private Map<String, Object> handleMcpInitialize(SdkMcpServer server, Map<String, Object> mcpMessage) {
        Map<String, Object> serverInfo = new HashMap<>();
        serverInfo.put("name", server.getName());
        serverInfo.put("version", server.getVersion());

        Map<String, Object> capabilities = new HashMap<>();
        Map<String, Object> toolsCapability = new HashMap<>();
        capabilities.put("tools", toolsCapability);

        Map<String, Object> result = new HashMap<>();
        result.put("protocolVersion", "2024-11-05");
        result.put("capabilities", capabilities);
        result.put("serverInfo", serverInfo);

        return createJsonRpcSuccess(mcpMessage.get("id"), result);
    }

    /**
     * Handles MCP tools/list request.
     */
    private Map<String, Object> handleMcpToolsList(SdkMcpServer server, Map<String, Object> mcpMessage) {
        List<Map<String, Object>> toolsList = new ArrayList<>();

        for (SdkMcpTool<?> tool : server.getTools()) {
            Map<String, Object> toolDef = new HashMap<>();
            toolDef.put("name", tool.getName());
            toolDef.put("description", tool.getDescription());

            if (tool.getInputSchema() != null) {
                toolDef.put("inputSchema", tool.getInputSchema());
            } else {
                // Provide default schema
                Map<String, Object> schema = new HashMap<>();
                schema.put("type", "object");
                schema.put("properties", new HashMap<>());
                toolDef.put("inputSchema", schema);
            }

            toolsList.add(toolDef);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("tools", toolsList);

        return createJsonRpcSuccess(mcpMessage.get("id"), result);
    }

    /**
     * Handles MCP tools/call request.
     */
    private Map<String, Object> handleMcpToolsCall(SdkMcpServer server, Map<String, Object> params, Map<String, Object> mcpMessage) throws Exception {
        if (params == null) {
            return createJsonRpcError(-32602, "Invalid params: params is required");
        }

        String toolName = (String) params.get("name");
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = (Map<String, Object>) params.get("arguments");

        if (toolName == null) {
            return createJsonRpcError(-32602, "Invalid params: name is required");
        }

        // Find the tool
        SdkMcpTool<?> tool = null;
        for (SdkMcpTool<?> t : server.getTools()) {
            if (t.getName().equals(toolName)) {
                tool = t;
                break;
            }
        }

        if (tool == null) {
            return createJsonRpcError(-32601, "Tool not found: " + toolName);
        }

        try {
            // Invoke the tool handler
            Object input;
            if (tool.getInputClass() != null && arguments != null) {
                // Convert arguments map to the tool's input class
                input = objectMapper.convertValue(arguments, tool.getInputClass());
            } else {
                input = arguments != null ? arguments : new HashMap<>();
            }

            @SuppressWarnings("unchecked")
            Function<Object, CompletableFuture<Map<String, Object>>> handler =
                (Function<Object, CompletableFuture<Map<String, Object>>>) tool.getHandler();

            CompletableFuture<Map<String, Object>> futureResult = handler.apply(input);
            Map<String, Object> toolResult = futureResult.get(); // Wait for result

            // Wrap result in MCP content format
            List<Map<String, Object>> content = new ArrayList<>();
            Map<String, Object> contentBlock = new HashMap<>();
            contentBlock.put("type", "text");

            // Convert result to text (could be JSON string or plain text)
            String text;
            if (toolResult.containsKey("content")) {
                Object contentValue = toolResult.get("content");
                text = contentValue instanceof String ? (String) contentValue : objectMapper.writeValueAsString(contentValue);
            } else {
                text = objectMapper.writeValueAsString(toolResult);
            }

            contentBlock.put("text", text);
            content.add(contentBlock);

            Map<String, Object> result = new HashMap<>();
            result.put("content", content);

            // Add isError flag if present
            if (toolResult.containsKey("isError")) {
                result.put("isError", toolResult.get("isError"));
            }

            return createJsonRpcSuccess(mcpMessage.get("id"), result);

        } catch (Exception e) {
            logger.error("Error executing tool: " + toolName, e);
            return createJsonRpcError(-32603, "Tool execution failed: " + e.getMessage());
        }
    }

    /**
     * Creates a JSONRPC 2.0 success response.
     */
    private Map<String, Object> createJsonRpcSuccess(Object id, Object result) {
        Map<String, Object> response = new HashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    /**
     * Creates a JSONRPC 2.0 error response.
     */
    private Map<String, Object> createJsonRpcError(int code, String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("code", code);
        error.put("message", message);

        Map<String, Object> response = new HashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("error", error);
        return response;
    }

    /**
     * Converts hook output from Java-safe field names to CLI-expected names.
     */
    private Map<String, Object> convertHookOutputForCli(Map<String, Object> output) {
        // The output should already be in the correct format from the user's hook
        // Just return as-is, but ensure it has the expected structure
        return output != null ? output : new HashMap<>();
    }

    @Override
    public void close() {
        connected = false;
        if (readerThread != null) {
            readerThread.interrupt();
        }

        // Shutdown control protocol executor
        controlProtocolExecutor.shutdown();
        try {
            if (!controlProtocolExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                controlProtocolExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            controlProtocolExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        transport.close();
    }
}
