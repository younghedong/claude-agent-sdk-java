package com.anthropic.claude.sdk.internal;

import com.anthropic.claude.sdk.exceptions.CLIConnectionException;
import com.anthropic.claude.sdk.exceptions.MessageParseException;
import com.anthropic.claude.sdk.protocol.MessageParser;
import com.anthropic.claude.sdk.transport.Transport;
import com.anthropic.claude.sdk.types.hooks.Hook;
import com.anthropic.claude.sdk.types.hooks.HookContext;
import com.anthropic.claude.sdk.types.hooks.HookMatcher;
import com.anthropic.claude.sdk.types.messages.Message;
import com.anthropic.claude.sdk.types.permissions.PermissionContext;
import com.anthropic.claude.sdk.types.permissions.PermissionResult;
import com.anthropic.claude.sdk.types.permissions.PermissionUpdate;
import com.anthropic.claude.sdk.types.permissions.ToolPermissionCallback;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Streaming query runner that mirrors the Python SDK control protocol.
 */
public final class StreamingQuery implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(StreamingQuery.class);
    private final Transport transport;
    private final MessageParser parser;
    private final ToolPermissionCallback canUseTool;
    private final Map<String, List<HookMatcher>> hooks;
    private final ObjectMapper mapper;
    private final BlockingQueue<Message> messageQueue;
    private final ExecutorService readerExecutor;
    private final AtomicBoolean reading;
    private final Map<String, CompletableFuture<JsonNode>> pendingControlResponses;
    private final Map<String, Hook> hookCallbacks;
    private final AtomicInteger nextCallbackId;
    private final AtomicInteger nextRequestId;

    public StreamingQuery(
            Transport transport,
            MessageParser parser,
            ToolPermissionCallback canUseTool,
            Map<String, List<HookMatcher>> hooks
    ) {
        this.transport = transport;
        this.parser = parser;
        this.canUseTool = canUseTool;
        this.hooks = hooks != null ? hooks : Collections.emptyMap();
        this.mapper = new ObjectMapper();
        this.messageQueue = new LinkedBlockingQueue<>();
        this.readerExecutor = Executors.newSingleThreadExecutor();
        this.reading = new AtomicBoolean(false);
        this.pendingControlResponses = new ConcurrentHashMap<>();
        this.hookCallbacks = new ConcurrentHashMap<>();
        this.nextCallbackId = new AtomicInteger();
        this.nextRequestId = new AtomicInteger();
    }

    /**
     * Start reading messages from the transport.
     */
    public void start() {
        if (reading.compareAndSet(false, true)) {
            readerExecutor.submit(this::readLoop);
        }
    }

    /**
     * Initialize hooks configuration with the CLI.
     */
    public CompletableFuture<JsonNode> initialize() {
        ObjectNode request = mapper.createObjectNode();
        request.put("subtype", "initialize");
        ObjectNode hooksNode = buildHooksConfig();
        if (hooksNode != null && hooksNode.size() > 0) {
            request.set("hooks", hooksNode);
        } else {
            request.putNull("hooks");
        }
        return sendControlRequest(request);
    }

    /**
     * Send a user message (already structured as JSON) to the CLI.
     */
    public CompletableFuture<Void> sendMessage(Map<String, Object> message) {
        try {
            String payload = mapper.writeValueAsString(message);
            return transport.write(payload);
        } catch (JsonProcessingException e) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    /**
     * Stream a collection of prompt messages to the CLI.
     */
    public CompletableFuture<Void> streamPrompt(Iterable<Map<String, Object>> prompts) {
        Objects.requireNonNull(prompts, "prompts");
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            for (Map<String, Object> message : prompts) {
                if (message == null) {
                    continue;
                }
                sendMessage(message).join();
            }
            result.complete(null);
        } catch (Exception e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    /**
     * Stream parsed messages returned by the CLI.
     */
    public Stream<Message> streamMessages() {
        Spliterator<Message> spliterator = new Spliterators.AbstractSpliterator<Message>(
                Long.MAX_VALUE,
                Spliterator.ORDERED | Spliterator.NONNULL
        ) {
            @Override
            public boolean tryAdvance(Consumer<? super Message> action) {
                while (true) {
                    if (!reading.get() && messageQueue.isEmpty()) {
                        return false;
                    }
                    try {
                        Message message = messageQueue.poll(250, TimeUnit.MILLISECONDS);
                        if (message != null) {
                            action.accept(message);
                            return true;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        };
        return StreamSupport.stream(spliterator, false);
    }

    private void readLoop() {
        try {
            Iterator<String> iterator = transport.readLines().iterator();
            while (!Thread.currentThread().isInterrupted() && iterator.hasNext()) {
                String line = iterator.next();
                if (line == null || line.trim().isEmpty()) {
                    continue;
                }
                handleLine(line);
            }
        } catch (Exception e) {
            logger.error("Fatal error while reading CLI output", e);
        } finally {
            reading.set(false);
            pendingControlResponses.values().forEach(future ->
                    future.completeExceptionally(
                            new CLIConnectionException("Connection closed before response")
                    )
            );
            pendingControlResponses.clear();
        }
    }

    private void handleLine(String line) {
        JsonNode root;

        try {
            root = mapper.readTree(line);
        } catch (IOException e) {
            logger.warn("Failed to decode JSON line: {}", line, e);
            return;
        }

        String type = Optional.ofNullable(root.get("type"))
                .map(JsonNode::asText)
                .orElse("");

        switch (type) {
            case "control_request":
                handleControlRequest(root);
                break;
            case "control_response":
                handleControlResponse(root);
                break;
            case "control_cancel_request":
                logger.debug("Received control cancel request: {}", line);
                break;
            default:
                try {
                    Message message = parser.parse(line);
                    messageQueue.offer(message);
                } catch (MessageParseException e) {
                    logger.warn("Failed to parse message: {}", line, e);
                }
                break;
        }
    }

    private void handleControlResponse(JsonNode node) {
        JsonNode responseNode = node.get("response");
        if (responseNode == null) {
            return;
        }
        String requestId = Optional.ofNullable(responseNode.get("request_id"))
                .map(JsonNode::asText)
                .orElse(null);
        if (requestId == null) {
            return;
        }

        CompletableFuture<JsonNode> pending = pendingControlResponses.remove(requestId);
        if (pending == null) {
            return;
        }

        String subtype = Optional.ofNullable(responseNode.get("subtype"))
                .map(JsonNode::asText)
                .orElse("");
        if ("error".equals(subtype)) {
            String errorMessage = Optional.ofNullable(responseNode.get("error"))
                    .map(JsonNode::asText)
                    .orElse("Unknown control error");
            pending.completeExceptionally(new CLIConnectionException(errorMessage));
        } else {
            pending.complete(responseNode);
        }
    }

    private void handleControlRequest(JsonNode node) {
        JsonNode requestNode = node.get("request");
        if (requestNode == null) {
            return;
        }
        String requestId = Optional.ofNullable(node.get("request_id"))
                .map(JsonNode::asText)
                .orElse(UUID.randomUUID().toString());
        String subtype = Optional.ofNullable(requestNode.get("subtype"))
                .map(JsonNode::asText)
                .orElse("");

        try {
            switch (subtype) {
                case "can_use_tool":
                    handleToolPermissionRequest(requestId, requestNode);
                    break;
                case "hook_callback":
                    handleHookCallback(requestId, requestNode);
                    break;
                case "mcp_message":
                    sendControlError(requestId, "MCP SDK servers are not supported in Java yet.");
                    break;
                default:
                    sendControlError(requestId, "Unsupported control request: " + subtype);
                    break;
            }
        } catch (Exception e) {
            logger.error("Failed to handle control request", e);
            sendControlError(requestId, e.getMessage());
        }
    }

    private void handleToolPermissionRequest(String requestId, JsonNode requestNode) {
        if (canUseTool == null) {
            sendControlError(requestId, "Tool permission callback not configured");
            return;
        }

        String toolName = Optional.ofNullable(requestNode.get("tool_name"))
                .map(JsonNode::asText)
                .orElse("");
        JsonNode inputNode = requestNode.get("input");
        Map<String, Object> toolInput = mapper.convertValue(
                inputNode,
                mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)
        );

        List<Map<String, Object>> suggestions = extractSuggestions(
                requestNode.get("permission_suggestions")
        );
        PermissionContext context = new PermissionContext(null, suggestions);

        PermissionResult result;
        try {
            result = canUseTool.canUseTool(toolName, toolInput, context).join();
        } catch (CompletionException ex) {
            throw new CLIConnectionException("Permission callback failed", ex.getCause());
        }

        if (result instanceof PermissionResult.Allow) {
            PermissionResult.Allow allow = (PermissionResult.Allow) result;
            Map<String, Object> response = new ConcurrentHashMap<>();
            response.put("behavior", "allow");
            Map<String, Object> updatedInput = allow.getUpdatedInput();
            response.put("updatedInput", updatedInput != null ? updatedInput : toolInput);

            List<PermissionUpdate> updates = allow.getUpdatedPermissions();
            if (updates != null && !updates.isEmpty()) {
                List<Map<String, Object>> updatePayload = new ArrayList<>();
                for (PermissionUpdate update : updates) {
                    updatePayload.add(update.toMap());
                }
                response.put("updatedPermissions", updatePayload);
            }
            sendControlSuccess(requestId, response);
        } else if (result instanceof PermissionResult.Deny) {
            PermissionResult.Deny deny = (PermissionResult.Deny) result;
            Map<String, Object> response = new ConcurrentHashMap<>();
            response.put("behavior", "deny");
            response.put("message", deny.getMessage());
            if (Boolean.TRUE.equals(deny.getInterrupt())) {
                response.put("interrupt", true);
            }
            sendControlSuccess(requestId, response);
        } else {
            throw new IllegalStateException(
                    "Permission callback must return PermissionResult.Allow or PermissionResult.Deny"
            );
        }
    }

    private void handleHookCallback(String requestId, JsonNode requestNode) {
        String callbackId = Optional.ofNullable(requestNode.get("callback_id"))
                .map(JsonNode::asText)
                .orElse("");
        Hook hook = hookCallbacks.get(callbackId);
        if (hook == null) {
            sendControlError(requestId, "Unknown hook callback: " + callbackId);
            return;
        }

        Map<String, Object> input = mapper.convertValue(
                requestNode.get("input"),
                mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)
        );
        String toolUseId = Optional.ofNullable(requestNode.get("tool_use_id"))
                .map(JsonNode::asText)
                .orElse(null);

        Map<String, Object> hookOutput;
        try {
            hookOutput = hook.execute(input, toolUseId, new HookContext(null)).join();
        } catch (CompletionException ex) {
            throw new CLIConnectionException("Hook callback failed", ex.getCause());
        }
        sendControlSuccess(requestId, normalizeHookOutput(hookOutput));
    }

    private List<Map<String, Object>> extractSuggestions(JsonNode suggestionsNode) {
        if (suggestionsNode == null || !suggestionsNode.isArray()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> suggestions = new ArrayList<>();
        for (JsonNode node : suggestionsNode) {
            Map<String, Object> suggestion = mapper.convertValue(
                    node,
                    mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)
            );
            suggestions.add(suggestion);
        }
        return suggestions;
    }

    private ObjectNode buildHooksConfig() {
        if (hooks.isEmpty()) {
            return null;
        }

        ObjectNode hooksNode = mapper.createObjectNode();
        for (Map.Entry<String, List<HookMatcher>> entry : hooks.entrySet()) {
            ArrayNode matchers = mapper.createArrayNode();
            for (HookMatcher matcher : entry.getValue()) {
                if (matcher == null || matcher.getHooks() == null || matcher.getHooks().isEmpty()) {
                    continue;
                }
                ObjectNode matcherNode = mapper.createObjectNode();
                matcherNode.put("matcher", matcher.getMatcher());
                ArrayNode callbackIds = mapper.createArrayNode();
                for (Hook hook : matcher.getHooks()) {
                    if (hook == null) {
                        continue;
                    }
                    String callbackId = "hook_" + nextCallbackId.getAndIncrement();
                    hookCallbacks.put(callbackId, hook);
                    callbackIds.add(callbackId);
                }
                matcherNode.set("hookCallbackIds", callbackIds);
                matchers.add(matcherNode);
            }
            hooksNode.set(entry.getKey(), matchers);
        }
        return hooksNode;
    }

    private CompletableFuture<JsonNode> sendControlRequest(ObjectNode request) {
        String requestId = "req_" + nextRequestId.getAndIncrement() + "_" + UUID.randomUUID();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingControlResponses.put(requestId, future);

        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("type", "control_request");
        envelope.put("request_id", requestId);
        envelope.set("request", request);

        try {
            transport.write(mapper.writeValueAsString(envelope)).join();
        } catch (JsonProcessingException e) {
            future.completeExceptionally(e);
            pendingControlResponses.remove(requestId);
            return future;
        }

        return future;
    }

    private void sendControlSuccess(String requestId, Map<String, Object> payload) {
        ObjectNode response = mapper.createObjectNode();
        response.put("type", "control_response");
        ObjectNode inner = mapper.createObjectNode();
        inner.put("subtype", "success");
        inner.put("request_id", requestId);
        inner.set("response", mapper.valueToTree(payload));
        response.set("response", inner);

        transport.write(response.toString()).join();
    }

    private void sendControlError(String requestId, String error) {
        ObjectNode response = mapper.createObjectNode();
        response.put("type", "control_response");
        ObjectNode inner = mapper.createObjectNode();
        inner.put("subtype", "error");
        inner.put("request_id", requestId);
        inner.put("error", Objects.requireNonNullElse(error, "Unknown error"));
        response.set("response", inner);

        transport.write(response.toString()).join();
    }

    private Map<String, Object> normalizeHookOutput(Map<String, Object> hookOutput) {
        if (hookOutput == null || hookOutput.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> normalized = new HashMap<>();
        for (Map.Entry<String, Object> entry : hookOutput.entrySet()) {
            String key = entry.getKey();
            if ("async_".equals(key)) {
                normalized.put("async", entry.getValue());
            } else if ("continue_".equals(key)) {
                normalized.put("continue", entry.getValue());
            } else {
                normalized.put(key, entry.getValue());
            }
        }
        return normalized;
    }

    @Override
    public void close() {
        reading.set(false);
        readerExecutor.shutdownNow();
        transport.close();
        messageQueue.clear();
    }
}
