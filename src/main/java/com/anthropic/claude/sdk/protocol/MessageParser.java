package com.anthropic.claude.sdk.protocol;

import com.anthropic.claude.sdk.exceptions.MessageParseException;
import com.anthropic.claude.sdk.types.content.*;
import com.anthropic.claude.sdk.types.messages.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser for CLI JSON messages.
 */
public class MessageParser {

    private static final Logger logger = LoggerFactory.getLogger(MessageParser.class);
    private final ObjectMapper objectMapper;

    public MessageParser() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Parse a JSON line into a Message object.
     */
    public Message parse(String jsonLine) {
        try {
            JsonNode root = objectMapper.readTree(jsonLine);
            String type = root.get("type").asText();

            switch (type) {
                case "user":
                    return parseUserMessage(root);
                case "assistant":
                    return parseAssistantMessage(root);
                case "system":
                    return parseSystemMessage(root);
                case "result":
                    return parseResultMessage(root);
                case "stream_event":
                    return parseStreamEvent(root);
                default:
                    throw new MessageParseException(
                        "Unknown message type: " + type,
                        jsonLine
                    );
            }

        } catch (Exception e) {
            throw new MessageParseException("Failed to parse message", jsonLine, e);
        }
    }

    private UserMessage parseUserMessage(JsonNode root) {
        JsonNode messageNode = root.get("message");
        JsonNode contentNode = messageNode.get("content");

        List<ContentBlock> content = new ArrayList<>();
        if (contentNode.isArray()) {
            for (JsonNode blockNode : contentNode) {
                content.add(parseContentBlock(blockNode));
            }
        }

        String parentToolUseId = root.has("parent_tool_use_id")
            ? root.get("parent_tool_use_id").asText()
            : null;

        return new UserMessage(content, parentToolUseId);
    }

    private AssistantMessage parseAssistantMessage(JsonNode root) {
        JsonNode messageNode = root.get("message");
        JsonNode contentNode = messageNode.get("content");

        List<ContentBlock> content = new ArrayList<>();
        for (JsonNode blockNode : contentNode) {
            content.add(parseContentBlock(blockNode));
        }

        String model = messageNode.get("model").asText();
        String parentToolUseId = root.has("parent_tool_use_id")
            ? root.get("parent_tool_use_id").asText()
            : null;

        return new AssistantMessage(content, model, parentToolUseId);
    }

    private SystemMessage parseSystemMessage(JsonNode root) {
        String subtype = root.get("subtype").asText();
        Map<String, Object> data = objectMapper.convertValue(
            root,
            objectMapper.getTypeFactory().constructMapType(
                HashMap.class,
                String.class,
                Object.class
            )
        );

        return new SystemMessage(subtype, data);
    }

    private ResultMessage parseResultMessage(JsonNode root) {
        long durationMs = root.get("duration_ms").asLong();
        long durationApiMs = root.get("duration_api_ms").asLong();
        boolean isError = root.get("is_error").asBoolean();
        int numTurns = root.get("num_turns").asInt();
        String sessionId = root.get("session_id").asText();

        Double totalCostUsd = root.has("total_cost_usd")
            ? root.get("total_cost_usd").asDouble()
            : null;

        Map<String, Object> usage = root.has("usage")
            ? objectMapper.convertValue(
                root.get("usage"),
                objectMapper.getTypeFactory().constructMapType(
                    HashMap.class,
                    String.class,
                    Object.class
                )
            )
            : null;

        Object result = root.has("result")
            ? objectMapper.convertValue(root.get("result"), Object.class)
            : null;

        String subtype = root.get("subtype").asText();

        return new ResultMessage(
            subtype,
            durationMs,
            durationApiMs,
            isError,
            numTurns,
            sessionId,
            totalCostUsd,
            usage,
            result
        );
    }

    private ContentBlock parseContentBlock(JsonNode blockNode) {
        String type = blockNode.get("type").asText();

        switch (type) {
            case "text":
                return new TextBlock(blockNode.get("text").asText());
            case "thinking":
                return new ThinkingBlock(
                    blockNode.get("thinking").asText(),
                    blockNode.get("signature").asText()
                );
            case "tool_use": {
                Map<String, Object> input = objectMapper.convertValue(
                    blockNode.get("input"),
                    objectMapper.getTypeFactory().constructMapType(
                        HashMap.class,
                        String.class,
                        Object.class
                    )
                );
                return new ToolUseBlock(
                    blockNode.get("id").asText(),
                    blockNode.get("name").asText(),
                    input
                );
            }
            case "tool_result": {
                List<Object> content = null;
                if (blockNode.has("content")) {
                    JsonNode contentNode = blockNode.get("content");
                    if (contentNode.isArray()) {
                        // Content is an array
                        content = objectMapper.convertValue(
                            contentNode,
                            objectMapper.getTypeFactory().constructCollectionType(
                                ArrayList.class,
                                Object.class
                            )
                        );
                    } else if (contentNode.isTextual()) {
                        // Content is a string - wrap it in a list
                        content = new ArrayList<>();
                        content.add(contentNode.asText());
                    }
                }
                Boolean isError = blockNode.has("is_error")
                    ? blockNode.get("is_error").asBoolean()
                    : null;
                return new ToolResultBlock(
                    blockNode.get("tool_use_id").asText(),
                    content,
                    isError
                );
            }
            default:
                throw new IllegalArgumentException("Unknown content block type: " + type);
        }
    }

    private StreamEvent parseStreamEvent(JsonNode root) {
        String uuid = root.get("uuid").asText();
        String sessionId = root.get("session_id").asText();

        Map<String, Object> event = root.has("event") && !root.get("event").isNull()
            ? objectMapper.convertValue(
                root.get("event"),
                objectMapper.getTypeFactory().constructMapType(
                    HashMap.class,
                    String.class,
                    Object.class
                )
            )
            : null;

        String parentToolUseId = root.has("parent_tool_use_id")
            ? root.get("parent_tool_use_id").asText()
            : null;

        return new StreamEvent(uuid, sessionId, event, parentToolUseId);
    }
}
