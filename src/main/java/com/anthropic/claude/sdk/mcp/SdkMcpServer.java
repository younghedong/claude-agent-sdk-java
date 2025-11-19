package com.anthropic.claude.sdk.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * In-process MCP server implementation for Java SDK.
 */
@Getter
@Builder
public class SdkMcpServer {

    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final String name;
    @Builder.Default
    private final String version = "1.0.0";

    @Builder.Default
    private final Map<String, SdkMcpTool> tools = new LinkedHashMap<>();

    private final ObjectMapper mapper = new ObjectMapper();

    public Map<String, Object> toCliConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("type", "sdk");
        config.put("name", name);
        config.put("version", version);
        return config;
    }

    public Map<String, Object> handleMessage(JsonNode message) {
        String method = message.has("method") ? message.get("method").asText() : null;
        JsonNode idNode = message.get("id");
        Object id = idNode != null ? mapper.convertValue(idNode, Object.class) : null;

        try {
            Map<String, Object> response = new HashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", id);

            if ("initialize".equals(method)) {
                response.put("result", buildInitializeResult());
            } else if ("tools/list".equals(method)) {
                response.put("result", buildToolsList());
            } else if ("tools/call".equals(method)) {
                JsonNode params = message.get("params");
                response.put("result", handleCallTool(params));
            } else {
                response.put("error", Map.of(
                        "code", -32601,
                        "message", "Method not found: " + method
                ));
            }

            return response;
        } catch (Exception ex) {
            Map<String, Object> response = new HashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", id);
            response.put("error", Map.of(
                    "code", -32000,
                    "message", ex.getMessage() != null ? ex.getMessage() : "SDK MCP error"
            ));
            return response;
        }
    }

    private Map<String, Object> buildInitializeResult() {
        Map<String, Object> result = new HashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.put("capabilities", Collections.singletonMap("tools", Collections.emptyMap()));
        result.put("serverInfo", Map.of("name", name, "version", version));
        return result;
    }

    private Map<String, Object> buildToolsList() {
        List<Object> toolEntries = new ArrayList<>();
        for (SdkMcpTool tool : tools.values()) {
            toolEntries.add(Map.of(
                    "name", tool.getName(),
                    "description", tool.getDescription(),
                    "inputSchema", tool.toSchema()
            ));
        }
        return Map.of("tools", toolEntries);
    }

    private Map<String, Object> handleCallTool(JsonNode params) throws Exception {
        if (params == null) {
            throw new IllegalArgumentException("Missing params for tools/call");
        }

        String toolName = params.has("name") ? params.get("name").asText() : null;
        if (toolName == null || !tools.containsKey(toolName)) {
            throw new IllegalArgumentException("Tool not found: " + toolName);
        }

        Map<String, Object> arguments = params.has("arguments")
                ? mapper.convertValue(params.get("arguments"), Map.class)
                : Collections.emptyMap();

        SdkMcpTool tool = tools.get(toolName);
        CompletableFuture<Map<String, Object>> future = tool.getHandler().handle(arguments);
        Map<String, Object> result = future.join();

        if (result == null) {
            result = Collections.emptyMap();
        }

        return result;
    }
}
